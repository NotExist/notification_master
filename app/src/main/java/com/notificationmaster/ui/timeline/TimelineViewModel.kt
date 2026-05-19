package com.notificationmaster.ui.timeline

import android.app.Application
import android.os.Parcelable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.core.filter.MatchContext
import com.notificationmaster.core.filter.Rule
import com.notificationmaster.core.filter.RuleEngine
import com.notificationmaster.core.filter.RuleRepository
import com.notificationmaster.data.db.dao.count
import com.notificationmaster.data.db.dao.query
import com.notificationmaster.data.db.entity.NotificationEventEntity
import com.notificationmaster.data.filter.EventFilterSpec
import com.notificationmaster.data.filter.toFilterSpec
import com.notificationmaster.ui.common.NotificationDisplay
import com.notificationmaster.ui.common.NotificationEnricher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
sealed interface TimelineLoadState {
    /** App 開啟到第一筆 emit 之間；冷啟反饋階段 */
    object InitialLoading : TimelineLoadState

    /** 有資料、無動作；可繼續 lazyload */
    data class Ready(val canLoadMore: Boolean) : TimelineLoadState

    /** Lazyload 進行中（pageSize 擴張，等待 DAO emit）*/
    object LoadingMore : TimelineLoadState

    /** pageSize >= totalCount，DB 全部 events 已載入 */
    object EndReached : TimelineLoadState

    /** count Flow 確認 DB 為空（與 InitialLoading 區分）*/
    object EmptyDb : TimelineLoadState

    /** enrich / parse / DAO 例外。list 仍可用快取資料渲染，不 crash */
    data class Error(val cause: Throwable) : TimelineLoadState
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
     */
    private data class ChipState(
        val dedupChecked: Boolean,
        val activeRuleId: String?
    )

    // === chip state（單一 source）===

    private val _chipState = MutableStateFlow(
        ChipState(
            dedupChecked = savedState[KEY_DEDUP_CHECKED] ?: true,
            activeRuleId = savedState[KEY_ACTIVE_RULE_ID]
        )
    )

    val dedupChecked: StateFlow<Boolean> = _chipState
        .map { it.dedupChecked }
        .stateIn(viewModelScope, SharingStarted.Eagerly, _chipState.value.dedupChecked)

    val activeRuleId: StateFlow<String?> = _chipState
        .map { it.activeRuleId }
        .stateIn(viewModelScope, SharingStarted.Eagerly, _chipState.value.activeRuleId)

    /** 套 rule 前 user 自選的去重狀態，rule 取消後還原 */
    private var userDedupBeforeRule: Boolean? = savedState[KEY_USER_DEDUP_BEFORE_RULE]

    private val _filterText = MutableStateFlow(savedState[KEY_FILTER_TEXT] ?: "")
    val filterText: StateFlow<String> = _filterText.asStateFlow()

    /**
     * 邏輯 spec：給 fragment 用於渲染決策（如 buildTimelineItems 是否顯示 similarCount）。
     * 由 chip state 衍生而成，**不參與 DB query**。
     */
    val coreSpec: StateFlow<EventFilterSpec> = _chipState.map { chip ->
        val rule = chip.activeRuleId?.let { RuleEngine.getRule(it) }
        rule?.toFilterSpec() ?: EventFilterSpec(deduplicate = chip.dedupChecked)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        EventFilterSpec(deduplicate = _chipState.value.dedupChecked)
    )

    // === pageSize 載入控制 ===

    /**
     * Phase 25：base list 載入量上限。Lazyload 時 += [PAGE_INCREMENT] 擴張。
     *
     * Phase 27：**不持久化**。
     * - Fragment 重建（detail 返回 / tab 切換 / config change）→ ViewModel 仍活，_pageSize 保留
     * - Process restart（冷啟 / force-stop）→ ViewModel 重建，_pageSize 回到 INITIAL_PAGE_SIZE
     * 與 user 直覺一致（「重開 App 從頭開始」），避免 phase 25 持久化造成的重 enrich OOM。
     */
    private val _pageSize = MutableStateFlow(INITIAL_PAGE_SIZE)

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
            eventDao.query(EventFilterSpec.All.copy(limit = size))
                .map { items -> enrichAndMap(items) }
                .flowOn(Dispatchers.IO)
                .conflate()
        }
        .catch { e ->
            _errorCh.value = e
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Phase 22：UI 實際顯示的 list（base + client-side overlay）。
     */
    val displayedNotifications: StateFlow<List<NotificationDisplay>> = combine(
        allNotifications, _chipState
    ) { base, chip ->
        applyClientSideOverlay(base, chip.dedupChecked, chip.activeRuleId)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Phase 26：DB 全 events 數。
     * **`null` = count Flow 尚未首次 emit**（init 期間 → InitialLoading state）
     * **`0` = 確認 DB 真空**（EmptyDb state）
     * 不再用 0 代表「未知」，避免 init 競態。
     */
    private val _totalCount = MutableStateFlow<Int?>(null)
    val totalCount: StateFlow<Int?> = _totalCount.asStateFlow()

    private val _removedIds = MutableStateFlow<Set<String>>(emptySet())
    val removedIds: StateFlow<Set<String>> = _removedIds.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)

    /**
     * Phase 26：頂層載入狀態。Fragment 觀察此值用單一 `when` 映射所有 indicator
     * （SwipeRefresh 圓圈 / progress 光條 / footer / emptyState / Snackbar）。
     *
     * 派生時序：所有 source（allNotifications / _totalCount / _pageSize / _isLoadingMore /
     * _errorCh）任一變動觸發重算。combine 對 Eagerly StateFlow 通常合併同 dispatch frame。
     */
    val state: StateFlow<TimelineLoadState> = combine(
        allNotifications,
        _totalCount,
        _pageSize,
        _isLoadingMore,
        _errorCh
    ) { items, total, size, loading, err ->
        when {
            err != null              -> TimelineLoadState.Error(err)
            total == null            -> TimelineLoadState.InitialLoading
            total == 0               -> TimelineLoadState.EmptyDb
            items.isEmpty()          -> TimelineLoadState.InitialLoading
            loading                  -> TimelineLoadState.LoadingMore
            size >= total            -> TimelineLoadState.EndReached
            else                     -> TimelineLoadState.Ready(canLoadMore = true)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TimelineLoadState.InitialLoading)

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
        // RuleEngine 內容可能還沒載入；冪等呼叫保證 activeRuleId 對應的 rule 能讀到
        RuleRepository.load(application)
        // 若持久化的 ruleId 在 RuleEngine 已不存在，清掉避免後續找不到
        val ruleId = _chipState.value.activeRuleId
        if (ruleId != null && RuleEngine.getRule(ruleId) == null) {
            _chipState.value = _chipState.value.copy(activeRuleId = null)
            savedState[KEY_ACTIVE_RULE_ID] = null
            userDedupBeforeRule = null
            savedState[KEY_USER_DEDUP_BEFORE_RULE] = null
        }

        // totalCount Flow（DB 全 events 數）— 首次 emit 時把 null 轉為實際數字
        viewModelScope.launch {
            eventDao.count(EventFilterSpec.All)
                .catch { e -> _errorCh.value = e }
                .collectLatest { total -> _totalCount.value = total }
        }

        // removed overlay 訂閱
        viewModelScope.launch {
            eventDao.getRemovedNotificationKeysFlow()
                .catch { /* removed overlay 非關鍵，例外吞掉避免影響 state */ }
                .collectLatest { ids -> _removedIds.value = ids.toSet() }
        }
    }

    fun setDedupChecked(checked: Boolean) {
        if (_chipState.value.dedupChecked == checked) return
        _chipState.value = _chipState.value.copy(dedupChecked = checked)
        savedState[KEY_DEDUP_CHECKED] = checked
    }

    fun applyRule(rule: Rule) {
        val current = _chipState.value
        // 第一次進 rule 模式時記下 user dedup，切換 rule 之間不重複記
        if (current.activeRuleId == null) {
            userDedupBeforeRule = current.dedupChecked
            savedState[KEY_USER_DEDUP_BEFORE_RULE] = userDedupBeforeRule
        }
        val ruleSpec = rule.toFilterSpec()
        _chipState.value = current.copy(
            activeRuleId = rule.id,
            dedupChecked = ruleSpec.deduplicate
        )
        savedState[KEY_ACTIVE_RULE_ID] = rule.id
        savedState[KEY_DEDUP_CHECKED] = ruleSpec.deduplicate
    }

    fun deactivateRule() {
        val current = _chipState.value
        if (current.activeRuleId == null) return
        val restore = userDedupBeforeRule ?: true
        userDedupBeforeRule = null
        savedState[KEY_USER_DEDUP_BEFORE_RULE] = null
        _chipState.value = current.copy(
            activeRuleId = null,
            dedupChecked = restore
        )
        savedState[KEY_ACTIVE_RULE_ID] = null
        savedState[KEY_DEDUP_CHECKED] = restore
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
        ruleId: String?
    ): List<NotificationDisplay> {
        var result = base
        val rule = ruleId?.let { RuleEngine.getRule(it) }
        val ruleSpec = rule?.toFilterSpec()

        if (rule != null) {
            result = result.filter { display ->
                rule.matches(matchContextOf(display))
            }
            ruleSpec?.timeFrom?.let { from -> result = result.filter { it.postTime >= from } }
            ruleSpec?.timeTo?.let { to -> result = result.filter { it.postTime <= to } }
        }

        if (dedup) {
            result = result
                .groupBy { it.notificationKey }
                .values
                .map { events -> events.maxBy { it.event.eventTime } }
                .sortedByDescending { it.postTime }
        }

        ruleSpec?.limit?.let { lim -> result = result.take(lim) }
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
     * Phase 26：lazyload 改為 sealed state guard + first 確定條件。
     *
     * - guard 直接讀 state.value，state 已是單一 source of truth
     * - 完成判斷：「items.size 達 target」OR「total 確認 size==total」 — 確定數字無 timing race
     * - **不再有** `withTimeoutOrNull(3000)` 強制放行（C/D 根因）
     */
    fun loadNextDay() {
        val st = state.value
        if (st !is TimelineLoadState.Ready || !st.canLoadMore) return
        if (_isLoadingMore.value) return

        val target = _pageSize.value + PAGE_INCREMENT
        _isLoadingMore.value = true
        _pageSize.value = target
        // Phase 27：pageSize 不持久化（避免重 enrich 大量 events 造成 OOM）

        viewModelScope.launch {
            // 等到「items 達 target 量」或「DB 已全載入」確定條件，再 reset isLoadingMore
            // Phase 27：加 10s timeout safety net 防止極端例外（如 OOM）導致 _isLoadingMore 永久 true
            withTimeoutOrNull(10_000L) {
                combine(allNotifications, _totalCount) { items, total ->
                    items.size >= target || (total != null && items.size >= total)
                }.first { it }
            }
            _isLoadingMore.value = false
        }
    }

    /**
     * Phase 26：enrichAndMap 加 per-event runCatching，壞 row 不整批失敗。
     * 仍有可能整批 throw（如 channelDao.getAllChannelsSync 拋 SQLiteException），由 [allNotifications].catch 接住。
     */
    private suspend fun enrichAndMap(events: List<NotificationEventEntity>): List<NotificationDisplay> =
        NotificationEnricher.enrich(events, channelDao, rankingObsDao, rankingSnapDao)

    private companion object {
        const val KEY_DEDUP_CHECKED = "timeline.dedupChecked"
        const val KEY_ACTIVE_RULE_ID = "timeline.activeRuleId"
        const val KEY_FILTER_TEXT = "timeline.filterText"
        const val KEY_USER_DEDUP_BEFORE_RULE = "timeline.userDedupBeforeRule"
        // Phase 27 移除：KEY_SCROLL_STATE / KEY_PAGE_SIZE（不跨 process 持久化）

        /**
         * Phase 25：base list 初始載入量。
         * 主要 enrich 成本是 per-event JSON parse（~1-5ms/筆），小 preload 換初次載入快。
         */
        const val INITIAL_PAGE_SIZE = 100

        /** Phase 25：每次 lazyload 擴張的 events 數量。 */
        const val PAGE_INCREMENT = 100
    }
}
