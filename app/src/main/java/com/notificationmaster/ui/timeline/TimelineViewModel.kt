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
import com.notificationmaster.data.filter.EventFilterSpec
import com.notificationmaster.data.filter.toFilterSpec
import com.notificationmaster.ui.common.NotificationDisplay
import com.notificationmaster.ui.common.NotificationEnricher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Timeline 狀態 ViewModel
 *
 * 抖動修復（Plan 2 §J）：把 chip / 篩選 / 載入結果搬到 ViewModel，跨 view 重建保留，
 * 避免從 Detail 返回時 Flow 重新訂閱 + counter 從 0 重畫的中間態。
 *
 * 持久化（SavedStateHandle）：
 * - `KEY_DEDUP_CHECKED`、`KEY_ACTIVE_RULE_ID`、`KEY_FILTER_TEXT`、`KEY_USER_DEDUP_BEFORE_RULE`、
 *   `KEY_SCROLL_STATE`，process death 後也能還原。
 *
 * scroll position 還原：Fragment 在 onPause 把 LayoutManager 的 Parcelable state 存到 [scrollState]，
 * 重建後 onViewCreated 取出，等 adapter 完成 submitList 後 restore。從 Detail 返回時等同回到進入前位置
 * （不再採 Plan 2 §I 原方案 §I 的 lastViewedKey + Snackbar 銜接）。
 *
 * Plan 2 Phase 9：列表資料切到 [com.notificationmaster.data.db.entity.NotificationEventEntity]，
 * 渲染前一次性 transform 為 [NotificationDisplay]（snapshot 解析在 IO thread 集中完成）。
 *
 * Phase 22（filter chip overlay）：
 * - **base spec 永遠 [EventFilterSpec.All]**（不去重、無 matcher）：DB 訂閱 + day paging 載入都用這份。
 * - **chip 純 client-side overlay**：dedupChecked / activeRuleId 變動只改 UI state，不重查 DB。
 * - [displayedNotifications] 由 [allNotifications] + chip state combine 出來，套上 rule predicate +
 *   dedup（per-key 取最新 event_time）後輸出。chip 取消 = 瞬間 restore 為 base list。
 */
class TimelineViewModel(
    application: Application,
    private val savedState: SavedStateHandle
) : AndroidViewModel(application) {

    private val database = NotificationMasterApp.getInstance().database
    private val eventDao = database.notificationEventDao()
    private val channelDao = database.channelDao()
    private val rankingObsDao = database.rankingObservationDao()
    private val rankingSnapDao = database.rankingSnapshotDao()

    // === 篩選狀態 ===

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
     * Phase 22：base spec 永遠是 `EventFilterSpec.All`（不去重、無 matcher）。
     * 給 fragment 用於渲染決策（如 buildTimelineItems 是否要算 similarCount 等）的「邏輯 spec」
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

    // === 載入狀態 ===

    private val _todayNotifications = MutableStateFlow<List<NotificationDisplay>>(emptyList())
    val todayNotifications: StateFlow<List<NotificationDisplay>> = _todayNotifications.asStateFlow()

    private val _historicalDays =
        MutableStateFlow<List<Pair<Long, List<NotificationDisplay>>>>(emptyList())
    val historicalDays: StateFlow<List<Pair<Long, List<NotificationDisplay>>>> =
        _historicalDays.asStateFlow()

    /**
     * Phase 22：base list — 由 base spec ([EventFilterSpec.All]) 訂閱出的不去重 raw events，
     * 不受 chip 影響。chip 切換 = 在這份 list 上重套 client-side overlay。
     */
    private val _allNotifications = MutableStateFlow<List<NotificationDisplay>>(emptyList())
    val allNotifications: StateFlow<List<NotificationDisplay>> = _allNotifications.asStateFlow()

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
        _allNotifications, _dedupChecked, _activeRuleId
    ) { base, dedup, ruleId ->
        applyClientSideOverlay(base, dedup, ruleId)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _totalCount = MutableStateFlow(0)
    val totalCount: StateFlow<Int> = _totalCount.asStateFlow()

    private val _removedIds = MutableStateFlow<Set<String>>(emptySet())
    val removedIds: StateFlow<Set<String>> = _removedIds.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _hasReachedEnd = MutableStateFlow(false)
    val hasReachedEnd: StateFlow<Boolean> = _hasReachedEnd.asStateFlow()

    private val _awaitingInitialData = MutableStateFlow(false)
    val awaitingInitialData: StateFlow<Boolean> = _awaitingInitialData.asStateFlow()

    /**
     * 標記當前的 `loadNotifications` 觸發來源。Fragment 觀察此值決定 UI 載入指示：
     * - USER_REFRESH：使用者下拉 → SwipeRefresh spinner（中央 progressLoading 不顯示）
     * - SYSTEM：初次載入、spec/rule 切換等 system-initiated → 中央 progressLoading 圓圈
     */
    enum class LoadOrigin { SYSTEM, USER_REFRESH }

    private val _loadOrigin = MutableStateFlow(LoadOrigin.SYSTEM)
    val loadOrigin: StateFlow<LoadOrigin> = _loadOrigin.asStateFlow()

    private var earliestPostTime: Long? = null
    private var nextDayToLoad: Long = 0L
    private var loadJob: Job? = null

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
        loadNotifications()
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
     * Phase 22：base spec 永遠是 [EventFilterSpec.All] → 永遠走天分頁。
     * 保留 API 給 Fragment 的 scroll listener 判斷是否要觸發 loadNextDay。
     */
    fun usesDayPaging(): Boolean = true

    /**
     * Phase 22：在 base list 上套 client-side overlay（rule predicate + dedup）。
     *
     * 維持與 SQL [EventFilterSqlBuilder] 一致語意：
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
        // （fail-closed，與 in-memory MatchContext 預設一致；常用 rule 罕用 group 篩選）
        flags = d.flags,
        isAudible = d.isAudible,
        likelyHeadsup = d.likelyHeadsup,
        isRemoved = d.isRemoved
    )

    fun loadNotifications(origin: LoadOrigin = LoadOrigin.SYSTEM) {
        loadJob?.cancel()

        _loadOrigin.value = origin
        _awaitingInitialData.value = true
        // Phase 15：不清空 list / counter / removed state。保留舊資料直到新 Flow 第一筆 emit 覆寫。
        // 這是 0.1.0 順暢體驗的關鍵：切換 chip / rule 時畫面不消失，新結果到才替換。
        // 載入控制 flag 仍 reset（舊狀態對新查詢無效）。
        _isLoadingMore.value = false
        _hasReachedEnd.value = false

        // Phase 22：base spec 永遠 All，不再隨 chip 變動
        val specSnapshot = BASE_SPEC

        loadJob = viewModelScope.launch {
            launch {
                eventDao.count(specSnapshot).collectLatest { total ->
                    _totalCount.value = total
                }
            }

            // base 包含所有事件（含 REMOVED），removed overlay 仍需持續訂閱供 chip 渲染參考
            launch {
                eventDao.getRemovedNotificationKeysFlow().collectLatest { ids ->
                    _removedIds.value = ids.toSet()
                }
            }

            val todayStart = startOfDay(System.currentTimeMillis())
            val yesterdayStart = todayStart - ONE_DAY_MS

            earliestPostTime = withContext(Dispatchers.IO) { eventDao.getEarliestPostTime() }

            // Phase 18：預載「最近兩個有資料的歷史天」而非「昨天」這一天。
            nextDayToLoad = yesterdayStart
            var preloadedDays = 0
            while (preloadedDays < INIT_PRELOAD_DAY_COUNT) {
                val result = nextNonEmptyHistoricalDay(nextDayToLoad, specSnapshot)
                if (result == null) {
                    _hasReachedEnd.value = true
                    break
                }
                val (foundDayStart, displays) = result
                insertHistoricalDay(foundDayStart, displays)
                nextDayToLoad = foundDayStart - ONE_DAY_MS
                preloadedDays++
            }
            refreshReachedEnd()

            val todaySpec = specSnapshot.copy(timeFrom = todayStart, timeTo = Long.MAX_VALUE)
            // Phase 14 Q3：map 工作從 collectLatest 內部移到 Flow upstream + conflate。
            // Phase 14 Q2-A/B：enrichAndMap 內 batch 預載 channel importance + ranking observation。
            eventDao.query(todaySpec)
                .map { items -> enrichAndMap(items) }
                .flowOn(Dispatchers.IO)
                .conflate()
                .collect { displays ->
                    _todayNotifications.value = displays
                    // 注意：必須在 _todayNotifications 寫完後 recombineAll，才能 set awaiting=false；
                    // 後者放行 loadNextDay 進入「init 已完成」狀態。
                    recombineAll()
                    _awaitingInitialData.value = false
                }
        }
    }

    fun loadNextDay() {
        // 阻擋條件：載入中 / 已到底 / 初次資料尚未抵達（避免 init yesterday 載入未完
        // 就觸發 loadNextDay 導致 historicalDays append 順序錯亂）
        if (_isLoadingMore.value || _hasReachedEnd.value || _awaitingInitialData.value) return
        _isLoadingMore.value = true

        viewModelScope.launch {
            // Phase 18：用「找到下一個非空歷史天」邏輯取代「一次查一天」
            // 連續空白天自動跳過，避免 lazyload 卡死
            val result = nextNonEmptyHistoricalDay(nextDayToLoad, BASE_SPEC)
            if (result != null) {
                val (foundDayStart, displays) = result
                insertHistoricalDay(foundDayStart, displays)
                nextDayToLoad = foundDayStart - ONE_DAY_MS
                refreshReachedEnd()
            } else {
                _hasReachedEnd.value = true
            }
            _isLoadingMore.value = false
            recombineAll()
        }
    }

    /**
     * Phase 18：從 [fromDayStart] 開始往前找最近一個「該天 spec 篩選後非空」的歷史天。
     *
     * - 連續空白天自動跨過（最多 [MAX_CONSECUTIVE_EMPTY_DAYS_LAZY] 嘗試後放棄）
     * - 到達 earliestPostTime 之前停止
     * - 找到 → 回傳 (dayStart, displays)；找不到 → null（呼叫端設 hasReachedEnd=true）
     */
    private suspend fun nextNonEmptyHistoricalDay(
        fromDayStart: Long,
        specSnapshot: EventFilterSpec
    ): Pair<Long, List<NotificationDisplay>>? {
        val earliest = earliestPostTime ?: return null
        var dayStart = fromDayStart
        var emptyAttempts = 0
        while (dayStart + ONE_DAY_MS > earliest) {
            val data = withContext(Dispatchers.IO) {
                enrichAndMap(
                    eventDao.query(
                        specSnapshot.copy(timeFrom = dayStart, timeTo = dayStart + ONE_DAY_MS)
                    ).first()
                )
            }
            if (data.isNotEmpty()) return dayStart to data
            emptyAttempts++
            if (emptyAttempts >= MAX_CONSECUTIVE_EMPTY_DAYS_LAZY) return null
            dayStart -= ONE_DAY_MS
        }
        return null
    }

    /** 插入歷史天資料，保持 dayStart 降序（新到舊）。同 dayStart 已存在則覆蓋（避免 race 重複）。 */
    private fun insertHistoricalDay(dayStart: Long, data: List<NotificationDisplay>) {
        val current = _historicalDays.value
        val withoutSameDay = current.filterNot { it.first == dayStart }
        val merged = (withoutSameDay + (dayStart to data)).sortedByDescending { it.first }
        _historicalDays.value = merged
    }

    private fun recombineAll() {
        _allNotifications.value =
            _todayNotifications.value + _historicalDays.value.flatMap { it.second }
    }

    private fun refreshReachedEnd() {
        val earliest = earliestPostTime ?: run {
            _hasReachedEnd.value = true
            return
        }
        if (nextDayToLoad + ONE_DAY_MS <= earliest) {
            _hasReachedEnd.value = true
        }
    }

    private suspend fun enrichAndMap(events: List<com.notificationmaster.data.db.entity.NotificationEventEntity>): List<NotificationDisplay> =
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

    private companion object {
        /** Phase 22：固定 base spec（不去重、無 matcher）。所有 DB 訂閱 / count / lazyload 都用這份。 */
        val BASE_SPEC = EventFilterSpec.All

        const val ONE_DAY_MS = 24 * 60 * 60 * 1000L
        const val KEY_DEDUP_CHECKED = "timeline.dedupChecked"
        const val KEY_ACTIVE_RULE_ID = "timeline.activeRuleId"
        const val KEY_FILTER_TEXT = "timeline.filterText"
        const val KEY_USER_DEDUP_BEFORE_RULE = "timeline.userDedupBeforeRule"
        const val KEY_SCROLL_STATE = "timeline.scrollState"

        /** Phase 18：init 預載非空歷史天的數量 — 跨日午夜後仍能看到 N 天有資料的歷史 */
        const val INIT_PRELOAD_DAY_COUNT = 2

        /**
         * Phase 18：loadNextDay 連續嘗試空白天的上限。
         * 連續 N 天無資料就放棄（設 hasReachedEnd=true），避免阻塞 UI 無限往前查。
         * 30 天約一個月空窗，超過此值通常表示真的到達歷史底部。
         */
        const val MAX_CONSECUTIVE_EMPTY_DAYS_LAZY = 30
    }
}
