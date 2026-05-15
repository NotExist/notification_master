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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Timeline 狀態 ViewModel
 *
 * Plan 2 Phase 23（單一 Flow 模型）：
 *
 * **架構**：
 * - 一條 base Flow 訂閱 `EventFilterSpec.All`（不去重、無 matcher）+ 動態 `_pageSize`
 * - Service 寫入任何 event（INITIAL / POSTED / UPDATED / REMOVED，任何 postTime）
 *   → Room invalidation → Flow re-emit → list 動態更新
 * - Lazyload = `_pageSize += PAGE_INCREMENT`，flatMapLatest 自動重新訂閱
 * - 沒有「today vs historical」二分；service 不需要通知 ViewModel
 *
 * **chip overlay**（Phase 22）：
 * - `displayedNotifications` 由 `allNotifications` + chip state combine
 * - dedup / rule chip 純 client-side filter，不重查 DB；取消 chip 瞬間 restore
 *
 * **持久化**（SavedStateHandle）：
 * - chip 狀態 / pageSize / 篩選文字 / scroll position
 *
 * **被移除的東西**（Phase 23）：
 * - today Flow + historicalDays（合併為單一 allNotifications）
 * - earliestPostTime / nextDayToLoad / nextNonEmptyHistoricalDay / insertHistoricalDay /
 *   refreshReachedEnd / recombineAll：靠 pageSize vs totalCount 判斷邊界
 * - INIT_PRELOAD_DAY_COUNT / MAX_CONSECUTIVE_EMPTY_DAYS_LAZY：自然消失
 * - loadNotifications 的「重 launch loadJob」邏輯：base Flow 永遠 Eagerly 訂閱
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

    // === 篩選 chip state（Phase 22）===

    /** 當前 LIST_FILTER rule id，null 表示走核心 chip */
    private val _activeRuleId = MutableStateFlow<String?>(savedState[KEY_ACTIVE_RULE_ID])
    val activeRuleId: StateFlow<String?> = _activeRuleId.asStateFlow()

    /** 「去重」chip 勾選狀態（rule 模式下會被 rule 的 dedup 設定覆蓋並 disable） */
    private val _dedupChecked = MutableStateFlow(savedState[KEY_DEDUP_CHECKED] ?: true)
    val dedupChecked: StateFlow<Boolean> = _dedupChecked.asStateFlow()

    /** 套 rule 前 user 自選的去重狀態，rule 取消後還原 */
    private var userDedupBeforeRule: Boolean? = savedState[KEY_USER_DEDUP_BEFORE_RULE]

    private val _filterText = MutableStateFlow(savedState[KEY_FILTER_TEXT] ?: "")
    val filterText: StateFlow<String> = _filterText.asStateFlow()

    /**
     * 邏輯 spec：給 fragment 用於渲染決策（如 buildTimelineItems 是否顯示 similarCount）。
     * 由 chip state 衍生而成，**不參與 DB query**。
     */
    val coreSpec: StateFlow<EventFilterSpec> = combine(
        _dedupChecked, _activeRuleId
    ) { dedup, ruleId ->
        val rule = ruleId?.let { RuleEngine.getRule(it) }
        rule?.toFilterSpec() ?: EventFilterSpec(deduplicate = dedup)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        EventFilterSpec(deduplicate = savedState[KEY_DEDUP_CHECKED] ?: true)
    )

    // === 動態載入控制 ===

    /**
     * Phase 23：base list 載入量上限。flatMapLatest 訂閱會跟著這個值變動，
     * lazyload 時 += [PAGE_INCREMENT] 即可載入更多。
     */
    private val _pageSize = MutableStateFlow(
        savedState[KEY_PAGE_SIZE] ?: INITIAL_PAGE_SIZE
    )

    // === DB 訂閱（base）===

    /**
     * Phase 23：base list — 訂閱 `EventFilterSpec.All` 加動態 limit。
     *
     * - Service 寫入新 event（任何 postTime）→ Room invalidation → Flow re-emit
     * - User 卷到底 → [loadNextDay] 增加 _pageSize → flatMapLatest 重新訂閱
     * - 不再切「今天 vs 歷史」兩段，跨日事件由 Flow 自然涵蓋
     *
     * 排序為 postTime DESC（base spec 預設 OrderBy.PostTimeDesc）。
     */
    val allNotifications: StateFlow<List<NotificationDisplay>> = _pageSize
        .flatMapLatest { size ->
            eventDao.query(EventFilterSpec.All.copy(limit = size))
                .map { items -> enrichAndMap(items) }
                .flowOn(Dispatchers.IO)
                .conflate()
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Phase 22：UI 實際顯示的 list（base + client-side overlay）。
     *
     * 套用順序：
     * 1. rule chip 啟用 → 用 [Rule.matches] 過 base，再依 rule 自帶 time/limit 過濾
     * 2. dedup chip 啟用 → per `notificationKey` 取 `event_time` 最大者（對齊 SQL builder 行為）
     * 3. 排序維持 base 的 postTime DESC
     *
     * chip 變動時這個 flow 立即 re-emit，無 DB query / 無空窗。
     */
    val displayedNotifications: StateFlow<List<NotificationDisplay>> = combine(
        allNotifications, _dedupChecked, _activeRuleId
    ) { base, dedup, ruleId ->
        applyClientSideOverlay(base, dedup, ruleId)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _totalCount = MutableStateFlow(0)
    val totalCount: StateFlow<Int> = _totalCount.asStateFlow()

    private val _removedIds = MutableStateFlow<Set<String>>(emptySet())
    val removedIds: StateFlow<Set<String>> = _removedIds.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    /**
     * Phase 23：是否已載完 DB 全部 events。`_pageSize >= _totalCount` 就算盡頭。
     * `_totalCount == 0` 時保持 false 避免 init 期間誤判（DB 空 / count Flow 尚未 emit）。
     */
    val hasReachedEnd: StateFlow<Boolean> = combine(_pageSize, _totalCount) { size, total ->
        total > 0 && size >= total
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _awaitingInitialData = MutableStateFlow(true)
    val awaitingInitialData: StateFlow<Boolean> = _awaitingInitialData.asStateFlow()

    /**
     * 標記當前的 `loadNotifications` 觸發來源。Phase 23 後保留給呼叫端區分
     * USER_REFRESH（下拉手勢）vs SYSTEM（init / 權限變動）— 雖然 base Flow 永遠常駐、
     * 兩者實際行為相同，但 fragment 仍可能用此值控制其他 indicator。
     */
    enum class LoadOrigin { SYSTEM, USER_REFRESH }

    private val _loadOrigin = MutableStateFlow(LoadOrigin.SYSTEM)
    val loadOrigin: StateFlow<LoadOrigin> = _loadOrigin.asStateFlow()

    /** RecyclerView LayoutManager.onSaveInstanceState() 的結果；SavedStateHandle 自動序列化 Parcelable。 */
    var scrollState: Parcelable?
        get() = savedState[KEY_SCROLL_STATE]
        set(value) { savedState[KEY_SCROLL_STATE] = value }

    init {
        // RuleEngine 內容可能還沒載入；冪等呼叫保證 activeRuleId 對應的 rule 能讀到
        RuleRepository.load(application)
        // 若持久化的 ruleId 在 RuleEngine 已不存在（user 刪掉），清掉避免後續找不到
        val ruleId = _activeRuleId.value
        if (ruleId != null && RuleEngine.getRule(ruleId) == null) {
            _activeRuleId.value = null
            savedState[KEY_ACTIVE_RULE_ID] = null
            userDedupBeforeRule = null
            savedState[KEY_USER_DEDUP_BEFORE_RULE] = null
        }

        // totalCount Flow（DB 全 events 數，給 hasReachedEnd 用）
        viewModelScope.launch {
            eventDao.count(EventFilterSpec.All).collectLatest { total ->
                _totalCount.value = total
            }
        }

        // base 包含所有事件（含 REMOVED），removed overlay 仍需持續訂閱供 chip 渲染參考
        viewModelScope.launch {
            eventDao.getRemovedNotificationKeysFlow().collectLatest { ids ->
                _removedIds.value = ids.toSet()
            }
        }

        // awaitingInitialData：第一筆 base list emit 後永遠 false
        viewModelScope.launch {
            allNotifications.collect { items ->
                if (items.isNotEmpty() || _totalCount.value == 0) {
                    _awaitingInitialData.value = false
                }
            }
        }
    }

    fun setDedupChecked(checked: Boolean) {
        if (_dedupChecked.value == checked) return
        _dedupChecked.value = checked
        savedState[KEY_DEDUP_CHECKED] = checked
        // Phase 22：純 UI overlay 切換，base list 不變、不重查 DB；displayedNotifications 自動 re-emit
    }

    fun applyRule(rule: Rule) {
        // 第一次進 rule 模式時記下 user dedup，切換 rule 之間不重複記
        if (_activeRuleId.value == null) {
            userDedupBeforeRule = _dedupChecked.value
            savedState[KEY_USER_DEDUP_BEFORE_RULE] = userDedupBeforeRule
        }
        _activeRuleId.value = rule.id
        savedState[KEY_ACTIVE_RULE_ID] = rule.id
        // rule 自帶的 deduplicate 屬性同步到 chip 狀態（dedup chip 在 rule 模式下被 disable）
        val ruleSpec = rule.toFilterSpec()
        _dedupChecked.value = ruleSpec.deduplicate
        savedState[KEY_DEDUP_CHECKED] = ruleSpec.deduplicate
        // Phase 22：純 UI overlay 切換，不重查 DB
    }

    fun deactivateRule() {
        if (_activeRuleId.value == null) return
        _activeRuleId.value = null
        savedState[KEY_ACTIVE_RULE_ID] = null
        val restore = userDedupBeforeRule ?: true
        userDedupBeforeRule = null
        savedState[KEY_USER_DEDUP_BEFORE_RULE] = null
        _dedupChecked.value = restore
        savedState[KEY_DEDUP_CHECKED] = restore
        // Phase 22：純 UI overlay 切換，不重查 DB
    }

    fun setFilterText(text: String) {
        val normalized = text.trim()
        if (_filterText.value == normalized) return
        _filterText.value = normalized
        savedState[KEY_FILTER_TEXT] = normalized
    }

    /**
     * Phase 23：保留 API 兼容（usesDayPaging 永遠 true，沒有「不分天」分支）。
     * Fragment 內 scroll listener 仍用此判斷是否要觸發 loadNextDay。
     */
    fun usesDayPaging(): Boolean = true

    /**
     * Phase 22：在 base list 上套 client-side overlay（rule predicate + dedup）。
     *
     * 維持與 SQL [com.notificationmaster.data.filter.EventFilterSqlBuilder] 一致語意：
     * - rule.matchers AND 組合（沿用 [Rule.matches]）
     * - rule.timeFrom/timeTo 套在 postTime 上
     * - dedup = per `notificationKey` 取 `event_time` 最大者
     * - 排序維持 postTime DESC
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
            // SQL 端 dedup 是 per key 取 MAX(event_time)，這裡完全對齊
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
        // channelGroupId 未進 NotificationDisplay；group 條件 rule 在 client-side 視為不符合
        flags = d.flags,
        isAudible = d.isAudible,
        likelyHeadsup = d.likelyHeadsup,
        isRemoved = d.isRemoved
    )

    /**
     * Phase 23：保留 API 給 fragment 下拉刷新呼叫。base Flow 永遠 Eagerly 訂閱、
     * service 寫入會自動觸發 emit，所以此函式不再「重 launch loadJob」，只更新
     * loadOrigin 和短暫 awaiting state 給 UI indicator 用。
     */
    fun loadNotifications(origin: LoadOrigin = LoadOrigin.SYSTEM) {
        _loadOrigin.value = origin
        // 下拉刷新時 user 期待短暫 spinner；base Flow 會在下一次 emit 後 reset awaiting
        // 但若 DB 已有資料，allNotifications.value 非空 → 不主動把 awaiting 設 true 避免閃爍
        if (origin == LoadOrigin.USER_REFRESH && allNotifications.value.isEmpty()) {
            _awaitingInitialData.value = true
        }
    }

    /**
     * Phase 23：lazyload 改為 pageSize 漸進擴張。
     * - flatMapLatest 自動重新訂閱 DAO，更新後的 base list 涵蓋更多 events
     * - 「沒有更多」由 [hasReachedEnd]（pageSize vs totalCount）判斷，不再依賴 earliestPostTime
     * - 等下次 emit 後 reset isLoadingMore
     */
    fun loadNextDay() {
        if (_isLoadingMore.value || hasReachedEnd.value || _awaitingInitialData.value) return
        _isLoadingMore.value = true
        val target = _pageSize.value + PAGE_INCREMENT
        _pageSize.value = target
        savedState[KEY_PAGE_SIZE] = target

        viewModelScope.launch {
            // 等到 list 變大（DB 確實有更多資料）或已抵達盡頭再放開 isLoadingMore
            allNotifications.first { items ->
                items.size >= target || hasReachedEnd.value
            }
            _isLoadingMore.value = false
        }
    }

    private suspend fun enrichAndMap(events: List<NotificationEventEntity>): List<NotificationDisplay> =
        NotificationEnricher.enrich(events, channelDao, rankingObsDao, rankingSnapDao)

    private companion object {
        const val KEY_DEDUP_CHECKED = "timeline.dedupChecked"
        const val KEY_ACTIVE_RULE_ID = "timeline.activeRuleId"
        const val KEY_FILTER_TEXT = "timeline.filterText"
        const val KEY_USER_DEDUP_BEFORE_RULE = "timeline.userDedupBeforeRule"
        const val KEY_SCROLL_STATE = "timeline.scrollState"
        const val KEY_PAGE_SIZE = "timeline.pageSize"

        /** Phase 23：base list 初始載入量。涵蓋多數 user 的「今天 + 昨天」資料量。 */
        const val INITIAL_PAGE_SIZE = 300

        /** Phase 23：每次 lazyload 擴張的 events 數量。 */
        const val PAGE_INCREMENT = 300
    }
}
