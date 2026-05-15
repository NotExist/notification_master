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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar

/**
 * Timeline 狀態 ViewModel
 *
 * Plan 2 Phase 24（time-window + atomic chip state）：
 *
 * **base 模型 — time-window**：
 * - base list 訂閱 `EventFilterSpec.All.copy(timeFrom = _windowStart)`
 * - 初始 `_windowStart = yesterdayStart`（涵蓋「今天 + 昨天」兩天內）
 * - Lazyload → DAO 找 `_windowStart` 之前最近的 post_time，跳到該天 start
 *   （自動跨過空白天，無需 retry batch）
 * - Service 寫入任何 event（含未來的「兩天內」新 events）→ Room invalidation → Flow re-emit
 *
 * **chip overlay**（Phase 22 + atomic state）：
 * - chip 狀態合成 [ChipState] 由單一 MutableStateFlow 管理 → `applyRule` /
 *   `deactivateRule` 一次 emit，combine 不再 emit 中間態
 * - dedup / rule chip 純 client-side filter，不重查 DB
 *
 * **被移除（vs 原 phase 23）**：
 * - `_pageSize`（改回 time-window）
 * - 連續兩次 set StateFlow 導致閃動的問題（合成 ChipState）
 *
 * **保留**：
 * - 單一 Flow 模型（base 仍是一條 flatMapLatest）
 * - INITIAL 動態載入：service 寫 DB → Flow 自動 emit
 * - 跨日邊界無 bug：base spec 不分 today/historical
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
     * 「rule 啟用 → 取代 dedup 設定為 rule.dedup」、「rule 取消 → 還原 dedup」這類
     * 兩個 field 同時改的情境，用 `_chipState.value = ...copy(...)` 一次 emit，
     * 避免 combine 看到中間態（rule cleared 但 dedup 還是 rule 的值）導致 list 閃動。
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

    // === time-window 載入控制 ===

    /**
     * Phase 24：base list 訂閱範圍的時間下界（post_time >= _windowStart）。
     * 初始值 = `yesterdayStart`（兩天內）。Lazyload 把它推到「下一個非空天 start」。
     */
    private val _windowStart = MutableStateFlow(
        savedState[KEY_WINDOW_START] ?: defaultInitialWindowStart()
    )

    // === DB 訂閱（base）===

    /**
     * Phase 24：base list — `EventFilterSpec.All.copy(timeFrom = _windowStart)`。
     *
     * - Service 寫入任何 post_time >= _windowStart 的 event → Room invalidation → Flow re-emit
     * - Lazyload → _windowStart 往前推 → flatMapLatest 重新訂閱涵蓋更早
     * - chip 切換不影響此 Flow（client-side overlay 在 displayedNotifications 處理）
     */
    val allNotifications: StateFlow<List<NotificationDisplay>> = _windowStart
        .flatMapLatest { start ->
            eventDao.query(EventFilterSpec.All.copy(timeFrom = start))
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
     */
    val displayedNotifications: StateFlow<List<NotificationDisplay>> = combine(
        allNotifications, _chipState
    ) { base, chip ->
        applyClientSideOverlay(base, chip.dedupChecked, chip.activeRuleId)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _totalCount = MutableStateFlow(0)
    val totalCount: StateFlow<Int> = _totalCount.asStateFlow()

    private val _removedIds = MutableStateFlow<Set<String>>(emptySet())
    val removedIds: StateFlow<Set<String>> = _removedIds.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    /**
     * Phase 24：是否已載完 DB 全部 events。
     * `lazyload` 後沒找到更早 post_time → 設 true。
     * 重置時機：DB 有新 events 寫入但 `_windowStart` 已涵蓋全部時自動為 false
     * （由 base list size vs totalCount 衡量）。
     */
    private val _hasReachedEnd = MutableStateFlow(false)
    val hasReachedEnd: StateFlow<Boolean> = _hasReachedEnd.asStateFlow()

    private val _awaitingInitialData = MutableStateFlow(true)
    val awaitingInitialData: StateFlow<Boolean> = _awaitingInitialData.asStateFlow()

    /**
     * 標記當前的 `loadNotifications` 觸發來源。Fragment 觀察此值決定 UI 載入指示
     * （Phase 24 後實際無分支，base Flow 永遠常駐；保留 API 給呼叫端標記）。
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
        val ruleId = _chipState.value.activeRuleId
        if (ruleId != null && RuleEngine.getRule(ruleId) == null) {
            _chipState.value = _chipState.value.copy(activeRuleId = null)
            savedState[KEY_ACTIVE_RULE_ID] = null
            userDedupBeforeRule = null
            savedState[KEY_USER_DEDUP_BEFORE_RULE] = null
        }

        // totalCount Flow（DB 全 events 數）
        viewModelScope.launch {
            eventDao.count(EventFilterSpec.All).collectLatest { total ->
                _totalCount.value = total
            }
        }

        // removed overlay 訂閱
        viewModelScope.launch {
            eventDao.getRemovedNotificationKeysFlow().collectLatest { ids ->
                _removedIds.value = ids.toSet()
            }
        }

        // awaitingInitialData：第一筆 base list emit 後（或 DB 已空）關閉
        viewModelScope.launch {
            allNotifications.collect { items ->
                if (items.isNotEmpty() || _totalCount.value == 0) {
                    _awaitingInitialData.value = false
                }
                // 載入後 base list size 已達 totalCount → reset hasReachedEnd（避免黏在 true）
                if (_totalCount.value > 0 && items.size >= _totalCount.value) {
                    _hasReachedEnd.value = true
                }
            }
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
        // Phase 24：atomic 更新 activeRuleId + dedupChecked，避免 combine 看到中間態
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
        // Phase 24：atomic 更新，single emit
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
     * Phase 23+：保留 API 給 fragment 下拉刷新呼叫。base Flow 永遠 Eagerly 訂閱、
     * service 寫入會自動觸發 emit，所以此函式不再「重 launch loadJob」，只更新
     * loadOrigin 給 UI indicator 用。
     */
    fun loadNotifications(origin: LoadOrigin = LoadOrigin.SYSTEM) {
        _loadOrigin.value = origin
        if (origin == LoadOrigin.USER_REFRESH && allNotifications.value.isEmpty()) {
            _awaitingInitialData.value = true
        }
    }

    /**
     * Phase 24：lazyload 改為「找下一個非空天」。
     *
     * - DAO `getLatestPostTimeBefore(_windowStart)` 直接拿 `_windowStart` 之前最近的 event post_time
     * - 跳到該天 start（自動跨過空白天範圍，無需 retry batch）
     * - 沒有更早事件 → 設 hasReachedEnd = true
     * - 等 flatMapLatest 重新 emit 才放開 isLoadingMore（最多 3 秒 timeout 防卡）
     */
    fun loadNextDay() {
        if (_isLoadingMore.value || _hasReachedEnd.value || _awaitingInitialData.value) return
        _isLoadingMore.value = true

        viewModelScope.launch {
            val latestBefore = withContext(Dispatchers.IO) {
                eventDao.getLatestPostTimeBefore(_windowStart.value)
            }
            if (latestBefore == null) {
                _hasReachedEnd.value = true
                _isLoadingMore.value = false
                return@launch
            }

            val before = allNotifications.value.size
            val newStart = startOfDay(latestBefore)
            _windowStart.value = newStart
            savedState[KEY_WINDOW_START] = newStart

            // 等 base Flow emit 反映新範圍（最多 3 秒 timeout）
            withTimeoutOrNull(3000L) {
                allNotifications.first { it.size > before }
            }
            _isLoadingMore.value = false
        }
    }

    private suspend fun enrichAndMap(events: List<NotificationEventEntity>): List<NotificationDisplay> =
        NotificationEnricher.enrich(events, channelDao, rankingObsDao, rankingSnapDao)

    private fun startOfDay(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun defaultInitialWindowStart(): Long {
        // Phase 24：預設涵蓋「今天 + 昨天」兩天 → 起點 = yesterdayStart
        return startOfDay(System.currentTimeMillis()) - ONE_DAY_MS
    }

    private companion object {
        const val ONE_DAY_MS = 24 * 60 * 60 * 1000L
        const val KEY_DEDUP_CHECKED = "timeline.dedupChecked"
        const val KEY_ACTIVE_RULE_ID = "timeline.activeRuleId"
        const val KEY_FILTER_TEXT = "timeline.filterText"
        const val KEY_USER_DEDUP_BEFORE_RULE = "timeline.userDedupBeforeRule"
        const val KEY_SCROLL_STATE = "timeline.scrollState"
        const val KEY_WINDOW_START = "timeline.windowStart"
    }
}
