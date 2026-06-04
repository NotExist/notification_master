package com.notificationmaster.ui.timeline

import android.app.Application
import android.os.Parcelable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.core.debug.ProfileLogger
import com.notificationmaster.core.filter.MatchContext
import com.notificationmaster.core.filter.Rule
import com.notificationmaster.core.filter.RuleEngine
import com.notificationmaster.core.filter.RuleRepository
import com.notificationmaster.core.prefs.AppPreferences
import com.notificationmaster.data.db.dao.count
import com.notificationmaster.data.db.dao.query
import com.notificationmaster.data.db.entity.NotificationEventEntity
import com.notificationmaster.data.filter.EventFilterSpec
import com.notificationmaster.data.filter.RemovalFilter
import com.notificationmaster.data.filter.toFilterSpec
import com.notificationmaster.ui.common.NotificationDisplay
import com.notificationmaster.ui.common.NotificationEnricher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Timeline 載入頂層狀態（Phase 26）。
 *
 * 把多個 StateFlow（awaitingInitialData / isLoadingMore / hasReachedEnd / totalCount）
 * 的時序協調收斂成單一 sealed source。Fragment 用一次 `when` 就映射到所有 indicator，
 * 杜絕「多 Flow 翻一半」造成的競態與重複顯示。
 *
 * 派生公式（見 [TimelineViewModel.state]）：
 * ```
 * err != null            → Error(err)
 * total == null          → InitialLoading       (count 還沒 emit)
 * total == 0             → EmptyDb              (確認 DB 空)
 * items.isEmpty()        → InitialLoading       (count>0 但 enrich 未完)
 * loading                → LoadingMore
 * pageSize >= total      → EndReached
 * else                   → Ready(canLoadMore=true)
 * ```
 */
/**
 * Plan 2 W20：載入階段（純資料層）。
 * 跟 lazyload / footer / error 各維度正交，由 [TimelineLoadState] product type 組合。
 */
sealed interface LoadPhase {
    /** Cold start，count Flow / items 尚未 emit */
    object Initial : LoadPhase

    /** 有資料可顯示（items / displays / total 任一就緒）*/
    object Loaded : LoadPhase

    /** DB 真為空（totalRaw == 0）*/
    object Empty : LoadPhase
}

/**
 * Plan 2 W20：頂層載入狀態改 **product type**，各維度 (phase / isLazyloading / canLoadMore /
 * error) 顯式為 field。各 UI 投影 derive 自己關心的 field，消除舊 sealed enum
 * 用 when ordering 短路造成的「為 footer 改 state ordering 順帶影響 SwipeRefresh」副作用。
 *
 * Plan 1 W8 / W16 / W19 對應到此結構：
 * - W8 sticky loading：footer derive 用 `isLazyloading`（debug delay 內化進 lazyload 期間）
 * - W16 raw-exhausted：邏輯下放到 `canLoadMore` field 計算
 * - W19 condition：保留在 loadNextDay 等待真實 items 變化的判定
 *
 * W22f：footerCooldown 欄位移除 — debug 用的「LoadingMore 最少可見時間」純粹延後
 * `_isLoadingMore = false` 的執行時機（cooldown delay 在 lazyload coroutine 內、set false
 * 之前 inline），不再暴露為獨立 state factor。
 */
data class TimelineLoadState(
    val phase: LoadPhase,
    val isLazyloading: Boolean = false,
    val canLoadMore: Boolean = false,
    val error: Throwable? = null
) {
    companion object {
        val InitialLoading = TimelineLoadState(phase = LoadPhase.Initial)
    }
}

/**
 * Plan 2 W21：footer 視覺狀態獨立。
 * 從 [TimelineLoadState] derive 為純函數，所有 footer 觸發 / 調整邏輯集中於此。
 */
sealed interface FooterState {
    object None : FooterState         // 無 footer（cold start / empty / error）
    object Loading : FooterState      // 載入中…（spinner）
    object Pending : FooterState      // ↓ 繼續滾動載入更多
    object EndReached : FooterState   // 已無更多記錄
}

/**
 * Timeline 狀態 ViewModel
 *
 * Plan 2 Phase 26（單一狀態源 + 錯誤通道 + 確定性 lazyload 結束判斷）：
 *
 * 重構動機：phase 22-25 的「多 StateFlow 協調」對 user 出現 5 個實機問題（A-E）：
 * 載入時序競態 / indicator 重複 / lazyload 無實質追加 / 卷到底反覆觸發 / crash silent fail。
 * 本 phase 把載入相關狀態收斂為 sealed class，並補錯誤觀察通道。
 *
 * **狀態模型**：[TimelineLoadState]（sealed）為單一頂層狀態，由 combine 衍生。
 * **資料源**：[allNotifications]（不去重 raw events，Eagerly stateIn）+
 *           [displayedNotifications]（client-side chip overlay）。
 *
 * **保留**（phase 22-25）：
 * - Phase 22 client-side chip overlay
 * - Phase 23 _pageSize + flatMapLatest 模型
 * - Phase 24 ChipState atomic / rerenderToolbarCounter
 * - Phase 25 INITIAL_PAGE_SIZE=100 + PAGE_INCREMENT=100
 *
 * **移除**（phase 26）：
 * - `_awaitingInitialData` MutableStateFlow（改為 derived from [state]）
 * - `_hasReachedEnd` 獨立 StateFlow（併入 sealed state）
 * - `LoadOrigin` enum（state 已表達）
 * - `withTimeoutOrNull(3000)` 超時保險絲（改用 first 確定條件）
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TimelineViewModel(
    application: Application,
    private val savedState: SavedStateHandle
) : AndroidViewModel(application) {

    private val database = NotificationMasterApp.getInstance().database
    private val eventDao = database.notificationEventDao()
    private val channelDao = database.channelDao()
    private val rankingObsDao = database.rankingObservationDao()
    private val rankingSnapDao = database.rankingSnapshotDao()

    /**
     * Phase 24：chip 狀態原子化封裝。
     * 兩個 field 同時改的情境用單一 .copy(...) emit，避免 combine 看到中間態。
     *
     * Plan 1-zippy-thunder W6：`activeRuleId` 改 `activeRuleIds: Set<String>`，為未來多
     * filter 串聯（取交集）預留。UI 仍維持單選（chip click 取代為單元素 set）。
     */
    private data class ChipState(
        val dedupChecked: Boolean,
        val activeRuleIds: Set<String>,
        /** Plan 2 W1.c：「已移除」view-level filter（與 dedup 並列）*/
        val removalFilter: RemovalFilter
    )

    // === chip state（單一 source）===

    private val _chipState = MutableStateFlow(
        ChipState(
            dedupChecked = savedState[KEY_DEDUP_CHECKED] ?: true,
            activeRuleIds = (savedState.get<ArrayList<String>>(KEY_ACTIVE_RULE_IDS))
                ?.toSet().orEmpty(),
            removalFilter = (savedState.get<String>(KEY_REMOVAL_FILTER))
                ?.let { runCatching { RemovalFilter.valueOf(it) }.getOrNull() }
                ?: RemovalFilter.None
        )
    )

    val dedupChecked: StateFlow<Boolean> = _chipState
        .map { it.dedupChecked }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SHARING_STOP_TIMEOUT_MS), _chipState.value.dedupChecked)

    val removalFilter: StateFlow<RemovalFilter> = _chipState
        .map { it.removalFilter }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SHARING_STOP_TIMEOUT_MS), _chipState.value.removalFilter)

    val activeRuleIds: StateFlow<Set<String>> = _chipState
        .map { it.activeRuleIds }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SHARING_STOP_TIMEOUT_MS), _chipState.value.activeRuleIds)

    /** 套 rule 前 user 自選的去重狀態，rule 取消後還原 */
    private var userDedupBeforeRule: Boolean? = savedState[KEY_USER_DEDUP_BEFORE_RULE]

    private val _filterText = MutableStateFlow(savedState[KEY_FILTER_TEXT] ?: "")
    val filterText: StateFlow<String> = _filterText.asStateFlow()

    /**
     * 邏輯 spec：給 fragment 用於渲染決策（dedup 是否啟用、是否有 rule 篩選等）。
     * 由 chip state 衍生而成，**不參與 DB query**。
     *
     * Phase 31j：similarCount 已脫離 deduplicate（永遠顯示「+N 同內容」）。
     * W6：多 ruleIds 場景把所有 rule.matchers concat（AND），單 rule 行為與舊版等價。
     */
    val coreSpec: StateFlow<EventFilterSpec> = _chipState.map { chip ->
        combineRulesToSpec(chip.activeRuleIds, chip.dedupChecked)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(SHARING_STOP_TIMEOUT_MS),
        EventFilterSpec(deduplicate = _chipState.value.dedupChecked)
    )

    /**
     * W6：把多個 rule.matchers concat AND，list filter 控制欄位用合理 reduce
     * （limit 取最小、timeFrom 取最大、timeTo 取最小、orderBy / deduplicate 取第一個 rule）。
     * 0 rule → 純 dedup spec；1 rule → 等價 [Rule.toFilterSpec]。
     */
    private fun combineRulesToSpec(ruleIds: Set<String>, dedupChecked: Boolean): EventFilterSpec {
        val rules = ruleIds.mapNotNull { RuleEngine.getRule(it) }
        if (rules.isEmpty()) return EventFilterSpec(deduplicate = dedupChecked)
        if (rules.size == 1) return rules[0].toFilterSpec()
        val listFilters = rules.mapNotNull { it.action as? com.notificationmaster.core.filter.RuleAction.ListFilter }
        val first = listFilters.firstOrNull()
        return EventFilterSpec(
            matchers = rules.flatMap { it.matchers },
            orderBy = first?.orderBy ?: com.notificationmaster.core.filter.OrderBy.PostTimeDesc,
            limit = listFilters.mapNotNull { it.limit }.minOrNull(),
            deduplicate = first?.deduplicate ?: dedupChecked,
            timeFrom = listFilters.mapNotNull { it.timeFrom }.maxOrNull(),
            timeTo = listFilters.mapNotNull { it.timeTo }.minOrNull()
        )
    }

    // === pageSize 載入控制 ===

    /**
     * Phase 25：base list 載入量上限。Lazyload 時 += [PAGE_INCREMENT] 擴張。
     *
     * Phase 27：**不持久化**。
     * - Fragment 重建（detail 返回 / tab 切換 / config change）→ ViewModel 仍活，_pageSize 保留
     * - Process restart（冷啟 / force-stop）→ ViewModel 重建，_pageSize 回到 INITIAL_PAGE_SIZE
     * 與 user 直覺一致（「重開 App 從頭開始」），避免 phase 25 持久化造成的重 enrich OOM。
     */
    // W22-debug：cold start 初始頁面大小改 runtime — user 可在 Settings debug 區塊調整
    // （預設仍 30，調大如 80 可避免 cold start auto-fill 連發）。INITIAL_PAGE_SIZE 常數
    // 作為 fallback / 文件用，實際初始值由 AppPreferences 提供。
    private val _pageSize = MutableStateFlow(AppPreferences.getLazyloadInitialPageSize(application))

    // === 錯誤通道（Phase 26）===

    /**
     * Phase 26：enrich / DAO / parse 例外的觀察通道。Fragment 觀察 [state] 為 [TimelineLoadState.Error]
     * 時顯示 Snackbar，並保留現有 list（不 crash 不空白）。
     */
    private val _errorCh = MutableStateFlow<Throwable?>(null)

    // === DB 訂閱（base）===

    /**
     * Phase 25 + 26：base list — `EventFilterSpec.All.copy(limit = _pageSize)`。
     *
     * - Service 寫入任何 event → Room invalidation → Flow re-emit
     * - Lazyload → _pageSize 擴張 → flatMapLatest 重新訂閱
     * - `.catch` 攔截 DAO / enrich 例外，emit 空 list + 推 errorCh（不傳到 collectLatest 導致 crash）
     */
    val allNotifications: StateFlow<List<NotificationDisplay>> = _pageSize
        .flatMapLatest { size ->
            val tStart = System.currentTimeMillis()
            ProfileLogger.append("Timeline", "flatMapLatest start limit=$size")
            eventDao.query(EventFilterSpec.All.copy(limit = size))
                .onEach { items ->
                    ProfileLogger.append(
                        "Timeline",
                        "query emit limit=$size size=${items.size} since-start=${System.currentTimeMillis() - tStart}ms"
                    )
                }
                .map { items -> enrichAndMap(items) }
                .flowOn(Dispatchers.IO)
                .conflate()
        }
        .catch { e ->
            _errorCh.value = e
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SHARING_STOP_TIMEOUT_MS), emptyList())

    /**
     * Phase 22：UI 實際顯示的 list（base + client-side overlay）。
     */
    val displayedNotifications: StateFlow<List<NotificationDisplay>> = combine(
        allNotifications, _chipState
    ) { base, chip ->
        val result = applyClientSideOverlay(base, chip.dedupChecked, chip.activeRuleIds, chip.removalFilter)
        // Phase 31ak：log overlay compute — base/chip/result 對齊
        ProfileLogger.append(
            "Displays",
            "overlay base=${base.size} dedup=${chip.dedupChecked} " +
                "ruleIds=${chip.activeRuleIds.joinToString(",").ifEmpty { "none" }} " +
                "result=${result.size}"
        )
        result
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SHARING_STOP_TIMEOUT_MS), emptyList())

    /**
     * Phase 31h：DB raw events 數（無 dedup），Eagerly 訂閱作為 [totalCount] 的 base。
     *
     * 之前是 dialog-only flow 用 WhileSubscribed → 沒人 collect 時 stateIn 維持 null →
     * dialog 開啟讀 .value 永遠 null。現在改成 chip 切換的 base，Eagerly 全程訂閱。
     */
    val totalRawCount: StateFlow<Int?> = eventDao.count(EventFilterSpec.All)
        .map<Int, Int?> { it }
        .catch { e ->
            _errorCh.value = e
            emit(null)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Phase 31h：DB unique notification_key 數，Eagerly 訂閱作為 [totalCount] 的 base。
     */
    val totalUniqueCount: StateFlow<Int?> = eventDao.count(EventFilterSpec.All.copy(deduplicate = true))
        .map<Int, Int?> { it }
        .catch { e ->
            _errorCh.value = e
            emit(null)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Plan 1-zippy-thunder W2.a：chip-aware 全 DB 符合篩選的數量。
     *
     * 取代之前的「dedup ? unique : raw」derive — 改用 coreSpec.flatMapLatest 直接
     * query count，spec 同步包含 chip + rule + dedup，與 displayedNotifications.size
     * 同視角，讓 state.EndReached 公式 `displays.size >= total` 在 chip-filtered 場景
     * 也能正確觸發（修前 total 永遠是全表 unique 數 → chip ON 時 displays < total →
     * 永不 EndReached → footer 永遠不顯示「沒有更多」）。
     *
     * Dialog 仍可讀 [totalRawCount] / [totalUniqueCount] 取得「不套 chip 的整體數量」。
     */
    val totalCount: StateFlow<Int?> = coreSpec
        .flatMapLatest { spec -> eventDao.count(spec).map<Int, Int?> { it } }
        .catch { e ->
            _errorCh.value = e
            emit(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SHARING_STOP_TIMEOUT_MS), null)

    // Plan 2 W1.c：_removedIds + removedIds Flow 移除 — row.isRemoved 廣義語意由 enrichment
    // 計算寫入 NotificationDisplay.isRemoved，UI 直接讀屬性（廢除 record-level Flow 訂閱）

    private val _isLoadingMore = MutableStateFlow(false)

    /**
     * Plan 2 W20：頂層載入狀態 product type 重寫。
     *
     * 修前（sealed enum + when ordering 短路）：為 footer 顯示時長改 ordering（W8 / W16）會
     * 順帶影響 SwipeRefresh / counter 等其他 UI。
     *
     * 修後：各維度（phase / isLazyloading / canLoadMore / error）顯式為 field，
     * UI 投影各自 derive 關心欄位，互不干擾。
     *
     * W22f：原 footerCooldown source 移除。debug 用的「LoadingMore 最少可見時間」純粹
     * 內化為 lazyload coroutine 內、`_isLoadingMore=false` 之前的 inline delay；不再
     * 作為並列 StateFlow source 影響 footer derive — 消除「cooldown=false 跟 loading=false
     * 兩個獨立 emit 必然產生『都 false 中間態』」造成的 footer flicker。
     */
    val loadState: StateFlow<TimelineLoadState> = combine(
        displayedNotifications,
        totalCount,
        _isLoadingMore,
        combine(allNotifications, totalRawCount) { items, totalRaw ->
            items to totalRaw
        },
        _errorCh
    ) { displays, total, loading, bundle, err ->
        val items = bundle.first
        val totalRaw = bundle.second

        val phase: LoadPhase = when {
            err != null -> LoadPhase.Initial  // Error 仍標 Initial，UI 主要看 error field
            totalRaw == 0 -> LoadPhase.Empty
            total == null -> LoadPhase.Initial
            items.isEmpty() -> LoadPhase.Initial
            else -> LoadPhase.Loaded
        }

        // canLoadMore 各維度合算：raw 未載完 + chip-aware total 未顯示完 + 未達 MAX_PAGE_SIZE
        val canLoadMore = when {
            err != null -> false
            phase != LoadPhase.Loaded -> false
            total == 0 -> false  // chip 篩 0
            totalRaw != null && items.size >= totalRaw -> false  // W16: raw 全載完
            total != null && displays.size >= total -> false     // W2.a: chip-aware 全顯示
            _pageSize.value >= MAX_PAGE_SIZE -> false
            else -> true
        }

        TimelineLoadState(
            phase = phase,
            isLazyloading = loading,
            canLoadMore = canLoadMore,
            error = err
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SHARING_STOP_TIMEOUT_MS), TimelineLoadState.InitialLoading)

    /**
     * Plan 2 W21：FooterState 獨立 derive。
     *
     * W22f：footer 純依 [isLazyloading] / [canLoadMore] derive；debug cooldown delay
     * 期間 isLazyloading 仍 true，footer 自然維持 Loading，不需要額外的 cooldown flag。
     */
    val footerState: StateFlow<FooterState> = loadState.map { s ->
        when {
            s.phase == LoadPhase.Empty -> FooterState.None
            s.phase == LoadPhase.Initial -> FooterState.None
            s.error != null -> FooterState.None
            s.isLazyloading -> FooterState.Loading
            s.canLoadMore -> FooterState.Pending
            else -> FooterState.EndReached
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SHARING_STOP_TIMEOUT_MS), FooterState.None)

    /**
     * @Deprecated 舊 [state] 引用保留為 alias 給 SearchFragment / ArchiveFragment 等暫未遷移處；
     * 新 code 直接讀 [loadState] / [footerState]。本 plan 範圍只動 Timeline，其他 fragment 後續再清。
     */
    val state: StateFlow<TimelineLoadState> = loadState

    init {
        viewModelScope.launch {
            // 把舊 state 公式 log 對齊新結構，方便 log 觀察各維度變化
            loadState.collect { s ->
                ProfileLogger.append(
                    "State",
                    "emit phase=${s.phase::class.simpleName} loading=${s.isLazyloading} " +
                        "canLoadMore=${s.canLoadMore} err=${s.error != null}"
                )
            }
        }
    }

    /**
     * RecyclerView LayoutManager.onSaveInstanceState() 的結果。
     *
     * Phase 27：從 SavedStateHandle 改為 ViewModel-local var，生命週期跟 ViewModel 一致：
     * - Fragment 重建（detail 返回）→ ViewModel 仍活，scrollState 保留 → 回原位置
     * - Process restart → ViewModel 重建，scrollState=null → 從頂部開始
     * 與 [_pageSize] 同生死，避免 list 大小跟 scroll 位置語意不一致。
     */
    var scrollState: Parcelable? = null

    init {
        // RuleEngine 內容可能還沒載入；冪等呼叫保證 activeRuleIds 對應的 rule 能讀到
        RuleRepository.load(application)
        // 若持久化的 ruleIds 在 RuleEngine 已不存在，清掉避免後續找不到
        val ids = _chipState.value.activeRuleIds
        val surviving = ids.filter { RuleEngine.getRule(it) != null }.toSet()
        if (surviving.size != ids.size) {
            _chipState.value = _chipState.value.copy(activeRuleIds = surviving)
            savedState[KEY_ACTIVE_RULE_IDS] = ArrayList(surviving)
            if (surviving.isEmpty()) {
                userDedupBeforeRule = null
                savedState[KEY_USER_DEDUP_BEFORE_RULE] = null
            }
        }

        // Phase 28：totalCount 改為 stateIn Room Flow 直接 derive（見 [totalCount] 宣告），
        // 不在 init 內 launch — 確保 cold start 時 fragment 來 collect 才啟動 Room Flow，
        // state 從 InitialLoading 開始 emit。

        // Plan 2 W1.c：removedIds Flow 訂閱移除 — row.isRemoved 由 NotificationEnricher 廣義
        // 計算寫進 NotificationDisplay.isRemoved，UI 直接讀屬性
    }

    fun setDedupChecked(checked: Boolean) {
        if (_chipState.value.dedupChecked == checked) return
        _chipState.value = _chipState.value.copy(dedupChecked = checked)
        savedState[KEY_DEDUP_CHECKED] = checked
        ProfileLogger.append("Chip", "setDedupChecked=$checked")
    }

    /** Plan 2 W1.c：「已移除」view-level filter setter，與 dedup 並列正交。 */
    fun setRemovalFilter(filter: RemovalFilter) {
        if (_chipState.value.removalFilter == filter) return
        _chipState.value = _chipState.value.copy(removalFilter = filter)
        savedState[KEY_REMOVAL_FILTER] = filter.name
        ProfileLogger.append("Chip", "setRemovalFilter=$filter")
    }

    /**
     * W6：UI 仍維持單選 — applyRule 把 set 替換為 [rule.id]，視覺與舊版等價。
     * 未來改多選 UI 時新增 addRule / removeRule，不破壞此 API。
     */
    fun applyRule(rule: Rule) {
        val current = _chipState.value
        // 第一次進 rule 模式時記下 user dedup，切換 rule 之間不重複記
        if (current.activeRuleIds.isEmpty()) {
            userDedupBeforeRule = current.dedupChecked
            savedState[KEY_USER_DEDUP_BEFORE_RULE] = userDedupBeforeRule
        }
        val ruleSpec = rule.toFilterSpec()
        val newIds = setOf(rule.id)
        _chipState.value = current.copy(
            activeRuleIds = newIds,
            dedupChecked = ruleSpec.deduplicate
        )
        savedState[KEY_ACTIVE_RULE_IDS] = ArrayList(newIds)
        savedState[KEY_DEDUP_CHECKED] = ruleSpec.deduplicate
        ProfileLogger.append("Chip", "applyRule id=${rule.id} dedup=${ruleSpec.deduplicate}")
    }

    fun deactivateRule() {
        val current = _chipState.value
        if (current.activeRuleIds.isEmpty()) return
        val restore = userDedupBeforeRule ?: true
        userDedupBeforeRule = null
        savedState[KEY_USER_DEDUP_BEFORE_RULE] = null
        _chipState.value = current.copy(
            activeRuleIds = emptySet(),
            dedupChecked = restore
        )
        savedState[KEY_ACTIVE_RULE_IDS] = ArrayList<String>()
        savedState[KEY_DEDUP_CHECKED] = restore
        ProfileLogger.append("Chip", "deactivateRule restoreDedup=$restore")
    }

    fun setFilterText(text: String) {
        val normalized = text.trim()
        if (_filterText.value == normalized) return
        _filterText.value = normalized
        savedState[KEY_FILTER_TEXT] = normalized
    }

    /** Phase 23+：保留 API，Fragment scroll listener 用此判斷是否觸發 lazyload */
    fun usesDayPaging(): Boolean = true

    /**
     * Phase 22：在 base list 上套 client-side overlay（rule predicate + dedup）。
     * 維持與 SQL `EventFilterSqlBuilder` 一致語意。
     */
    private fun applyClientSideOverlay(
        base: List<NotificationDisplay>,
        dedup: Boolean,
        ruleIds: Set<String>,
        removalFilter: RemovalFilter
    ): List<NotificationDisplay> {
        var result = base
        val rules = ruleIds.mapNotNull { RuleEngine.getRule(it) }
        val listFilters = rules.mapNotNull { it.action as? com.notificationmaster.core.filter.RuleAction.ListFilter }

        if (rules.isNotEmpty()) {
            result = result.filter { display ->
                val ctx = matchContextOf(display)
                rules.all { it.matches(ctx) }
            }
            // W6：多 rule 取最嚴格時間窗（timeFrom 取最大、timeTo 取最小）
            listFilters.mapNotNull { it.timeFrom }.maxOrNull()
                ?.let { from -> result = result.filter { it.postTime >= from } }
            listFilters.mapNotNull { it.timeTo }.minOrNull()
                ?.let { to -> result = result.filter { it.postTime <= to } }
        }

        // Plan 2 W1.c：「已移除」view-level filter（dedup 之前套用，使 dedup 在「篩後集合」內取最新）
        when (removalFilter) {
            RemovalFilter.None -> { /* 不過濾 */ }
            RemovalFilter.OnlyRemoved -> result = result.filter { it.isRemoved }
            RemovalFilter.ExcludeRemoved -> result = result.filter { !it.isRemoved }
        }

        if (dedup) {
            // W1.c：dedup 規則 = 每 nkey 取「篩後集合」內最新 event_time。
            // SQL 已過濾 event_type != 'REMOVED'（W1.a），所以 base 不含 REMOVED row。
            // 加上 isRemoved chip 篩 client overlay 後，per-nkey 取 event_time 最新 = 該 nkey
            // 「最新可顯示 row」。
            result = result
                .groupBy { it.notificationKey }
                .values
                .map { events -> events.maxBy { it.event.eventTime } }
                .sortedByDescending { it.postTime }
        }

        // W6：多 rule limit 取最小（最嚴格）
        listFilters.mapNotNull { it.limit }.minOrNull()
            ?.let { lim -> result = result.take(lim) }
        return result
    }

    private fun matchContextOf(d: NotificationDisplay): MatchContext = MatchContext(
        packageName = d.packageName,
        channelId = d.channelId,
        eventType = d.event.eventType,
        title = d.title,
        text = d.text,
        bigText = d.bigText,
        subText = d.subText,
        channelImportance = d.importance.takeIf { it >= 0 },
        flags = d.flags,
        isAudible = d.isAudible,
        likelyHeadsup = d.likelyHeadsup,
        isRemoved = d.isRemoved
    )

    /**
     * Phase 26：保留 API 給 fragment 下拉手勢呼叫。
     * 重新訂閱不需要（base Flow 永遠 Eagerly），但可清除 error state（user 主動再試）。
     */
    fun refresh() {
        _errorCh.value = null
    }

    /**
     * Phase 26 + W2.b/e 重寫：lazyload 改為多層 guard + footer 防閃。
     *
     * Guard 順序（任何一條成立即 skip 不重複觸發無意義 +50）：
     * 1. state 非 Ready / canLoadMore=false
     * 2. _isLoadingMore=true（重入防護）
     * 3. displays.size >= totalCount（chip-filtered 已全顯示）
     * 4. **W2.b 新**：allNotifications.size < _pageSize（上次 query.size < limit
     *    表示 DB 內已沒更多 events 可載；ranking 未就緒時 chip 篩 0 的無限迴圈在
     *    此截斷）
     * 5. **W2.b 新**：_pageSize >= [MAX_PAGE_SIZE] hard cap，防意外失控
     *
     * Footer 防閃（**W2.e**）：condition_met 後 delay 200ms 才 reset
     * _isLoadingMore，確保 LoadingMore footer 至少可見 200ms 給 user 視覺回饋。
     */
    fun loadNextDay() {
        val st = loadState.value
        if (st.phase != LoadPhase.Loaded || !st.canLoadMore) return
        if (_isLoadingMore.value) return
        // canLoadMore 公式已涵蓋 displays>=total / raw exhausted / MAX cap guard
        // 此處純粹再讀 lastQuery snapshot 防 race（_pageSize 剛 set 但 flatMapLatest 未跑）
        val lastQuerySize = allNotifications.value.size
        if (lastQuerySize < _pageSize.value) {
            ProfileLogger.append(
                "Timeline",
                "loadNextDay skip: lastQuery=$lastQuerySize < pageSize=${_pageSize.value} (DB exhausted)"
            )
            return
        }

        val target = (_pageSize.value + PAGE_INCREMENT).coerceAtMost(MAX_PAGE_SIZE)
        val beforeDisplayedSize = displayedNotifications.value.size
        val beforeItemsSize = allNotifications.value.size
        ProfileLogger.append(
            "Timeline",
            "loadNextDay trigger target=$target beforeDisplayed=$beforeDisplayedSize " +
                "allItems=$beforeItemsSize"
        )
        _isLoadingMore.value = true
        _pageSize.value = target

        viewModelScope.launch {
            // W19：等真實 items 變化或 displays 增加才視為「query 回來」。
            val result = withTimeoutOrNull(10_000L) {
                combine(displayedNotifications, allNotifications) { displays, items ->
                    displays.size > beforeDisplayedSize || items.size > beforeItemsSize
                }.first { it }
            }
            ProfileLogger.append(
                "Timeline",
                "loadNextDay done reason=${if (result == null) "timeout" else "condition_met"} " +
                    "afterDisplayed=${displayedNotifications.value.size} afterItems=${allNotifications.value.size}"
            )
            // W22f：debug 用「LoadingMore 最少可見時間」純粹延後 `_isLoadingMore=false`
            // 的執行時機 — 整段 lazyload+delay 用單一 isLazyloading=true 表示，cooldown
            // 結束跟 lazyload 結束是同一個 set，combine 只 emit 一次 state change，
            // footer 從 Loading 直接 transition 到下一狀態（Pending / EndReached / 或被
            // Fragment trigger 下一輪 lazyload 蓋回 Loading），不經過「都 false」中間態。
            //
            // 預設 0 = delay(0) no-op = 生產行為跟沒這層邏輯一樣；debug 設大時 footer
            // LoadingMore 完整顯示 N ms + 阻擋下一輪 lazyload（_isLoadingMore guard 守住）。
            if (result != null) {
                delay(AppPreferences.getLazyloadFooterMinMs(getApplication()))
            }
            _isLoadingMore.value = false
        }
    }

    /**
     * Phase 26：enrichAndMap 加 per-event runCatching，壞 row 不整批失敗。
     * 仍有可能整批 throw（如 channelDao.getAllChannelsSync 拋 SQLiteException），由 [allNotifications].catch 接住。
     */
    private suspend fun enrichAndMap(events: List<NotificationEventEntity>): List<NotificationDisplay> =
        NotificationEnricher.enrich(events, channelDao, rankingObsDao, rankingSnapDao, eventDao)

    companion object {
        const val KEY_DEDUP_CHECKED = "timeline.dedupChecked"
        const val KEY_ACTIVE_RULE_IDS = "timeline.activeRuleIds"
        const val KEY_FILTER_TEXT = "timeline.filterText"
        const val KEY_USER_DEDUP_BEFORE_RULE = "timeline.userDedupBeforeRule"
        /** Plan 2 W1.c：「已移除」view-level filter 持久化 key（存 RemovalFilter enum name） */
        const val KEY_REMOVAL_FILTER = "timeline.removalFilter"
        // Phase 27 移除：KEY_SCROLL_STATE / KEY_PAGE_SIZE（不跨 process 持久化）

        /**
         * Phase 28：stateIn 的 SharingStarted.WhileSubscribed timeout。
         *
         * Eagerly → WhileSubscribed(5s) 後，upstream Flow 只在有 collector 時啟動。
         * Cold start 時 fragment 來 collect 才開始 query / enrich，state 從 InitialLoading
         * 開始 emit，user 能看到 spinner（Eagerly 模式下 stateIn 在 ViewModel.init 就啟動，
         * fragment 來 collect 時可能已經 transition 過 InitialLoading 到 Ready）。
         *
         * 5s timeout 給 fragment 重建（detail 返回 / config change）grace period 重用 cache。
         */
        const val SHARING_STOP_TIMEOUT_MS = 5_000L

        /**
         * Phase 25：base list 初始載入量。
         * Phase 29：100 → 30，cold start 先快速顯示 30 項，避免 user 等 30+ 秒空白。
         */
        const val INITIAL_PAGE_SIZE = 30

        /** Phase 25：每次 lazyload 擴張的 events 數量。Phase 29：100 → 50 配合 INITIAL 縮小。 */
        const val PAGE_INCREMENT = 50

        /**
         * W2.b：lazyload pageSize hard cap，防 chip 篩 0 + lastQuery 巧合等於 pageSize
         * 等邊界導致的 +50 失控。5000 對應約 1.7 年 events（按 8 events/day 估算）通常已遠
         * 超過實際使用，達上限後 user 仍可手動清資料或調大此值。
         */
        const val MAX_PAGE_SIZE = 5000

        /**
         * @Deprecated W18：改 runtime 從 [AppPreferences.getLazyloadFooterMinMs] 讀，
         * 保留 const 僅為向下相容 / 預設值參考。實際 delay 用 AppPreferences 值。
         */
        const val FOOTER_MIN_VISIBLE_MS_DEFAULT = 2000L
    }
}
