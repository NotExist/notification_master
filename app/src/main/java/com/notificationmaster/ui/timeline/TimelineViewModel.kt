package com.notificationmaster.ui.timeline

import android.app.Application
import android.os.Parcelable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.core.filter.Rule
import com.notificationmaster.core.filter.RuleEngine
import com.notificationmaster.core.filter.RuleRepository
import com.notificationmaster.data.db.dao.count
import com.notificationmaster.data.db.dao.query
import com.notificationmaster.data.filter.EventFilterSpec
import com.notificationmaster.data.filter.coreFilterSpecOf
import com.notificationmaster.data.filter.toFilterSpec
import com.notificationmaster.ui.common.NotificationDisplay
import com.notificationmaster.ui.common.NotificationEnricher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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

    private val _coreSpec = MutableStateFlow(EventFilterSpec.Deduplicated)
    val coreSpec: StateFlow<EventFilterSpec> = _coreSpec.asStateFlow()

    // === 載入狀態 ===

    private val _todayNotifications = MutableStateFlow<List<NotificationDisplay>>(emptyList())
    val todayNotifications: StateFlow<List<NotificationDisplay>> = _todayNotifications.asStateFlow()

    private val _historicalDays =
        MutableStateFlow<List<Pair<Long, List<NotificationDisplay>>>>(emptyList())
    val historicalDays: StateFlow<List<Pair<Long, List<NotificationDisplay>>>> =
        _historicalDays.asStateFlow()

    private val _allNotifications = MutableStateFlow<List<NotificationDisplay>>(emptyList())
    val allNotifications: StateFlow<List<NotificationDisplay>> = _allNotifications.asStateFlow()

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
        rebuildSpec()
        loadNotifications()
    }

    /** 依當前 activeRuleId / dedupChecked 重新計算 coreSpec */
    private fun rebuildSpec() {
        val ruleId = _activeRuleId.value
        if (ruleId != null) {
            val rule = RuleEngine.getRule(ruleId)
            if (rule != null) {
                _coreSpec.value = rule.toFilterSpec()
                return
            }
        }
        _coreSpec.value = coreFilterSpecOf(deduplicate = _dedupChecked.value)
    }

    fun setDedupChecked(checked: Boolean) {
        if (_dedupChecked.value == checked) return
        _dedupChecked.value = checked
        savedState[KEY_DEDUP_CHECKED] = checked
        rebuildSpec()
        loadNotifications()
    }

    fun applyRule(rule: Rule) {
        // 第一次進 rule 模式時記下 user dedup，切換 rule 之間不重複記
        if (_activeRuleId.value == null) {
            userDedupBeforeRule = _dedupChecked.value
            savedState[KEY_USER_DEDUP_BEFORE_RULE] = userDedupBeforeRule
        }
        _activeRuleId.value = rule.id
        savedState[KEY_ACTIVE_RULE_ID] = rule.id
        val ruleSpec = rule.toFilterSpec()
        _dedupChecked.value = ruleSpec.deduplicate
        savedState[KEY_DEDUP_CHECKED] = ruleSpec.deduplicate
        _coreSpec.value = ruleSpec
        loadNotifications()
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
        rebuildSpec()
        loadNotifications()
    }

    fun setFilterText(text: String) {
        val normalized = text.trim()
        if (_filterText.value == normalized) return
        _filterText.value = normalized
        savedState[KEY_FILTER_TEXT] = normalized
    }

    /** 是否走天分頁漸進載入：純去重 / 全部 spec 才用，其餘走全域 spec 查詢 */
    fun usesDayPaging(): Boolean {
        val spec = _coreSpec.value
        return spec.matchers.isEmpty() && spec.timeFrom == null && spec.timeTo == null &&
            spec.limit == null
    }

    fun loadNotifications(origin: LoadOrigin = LoadOrigin.SYSTEM) {
        loadJob?.cancel()

        _loadOrigin.value = origin
        _awaitingInitialData.value = true
        _todayNotifications.value = emptyList()
        _historicalDays.value = emptyList()
        _allNotifications.value = emptyList()
        _isLoadingMore.value = false
        _hasReachedEnd.value = false
        _removedIds.value = emptySet()
        _totalCount.value = 0

        val specSnapshot = _coreSpec.value

        loadJob = viewModelScope.launch {
            launch {
                eventDao.count(specSnapshot).collectLatest { total ->
                    _totalCount.value = total
                }
            }

            val showRemovedOverlay = specSnapshot.isRemoved != true
            if (showRemovedOverlay) {
                launch {
                    eventDao.getRemovedNotificationKeysFlow().collectLatest { ids ->
                        _removedIds.value = ids.toSet()
                    }
                }
            }

            if (usesDayPagingForSpec(specSnapshot)) {
                val todayStart = startOfDay(System.currentTimeMillis())
                val yesterdayStart = todayStart - ONE_DAY_MS
                nextDayToLoad = yesterdayStart - ONE_DAY_MS

                earliestPostTime = withContext(Dispatchers.IO) { eventDao.getEarliestPostTime() }

                val yesterdaySpec =
                    specSnapshot.copy(timeFrom = yesterdayStart, timeTo = todayStart)
                val yesterdayData = withContext(Dispatchers.IO) {
                    enrichAndMap(eventDao.query(yesterdaySpec).first())
                }
                if (yesterdayData.isNotEmpty()) {
                    insertHistoricalDay(yesterdayStart, yesterdayData)
                }
                refreshReachedEnd()

                val todaySpec =
                    specSnapshot.copy(timeFrom = todayStart, timeTo = Long.MAX_VALUE)
                // Phase 14 Q3：map 工作從 collectLatest 內部移到 Flow upstream + conflate。
                // 原本 collectLatest 在連續 Room invalidation 下會反覆 cancel map 工作，
                // 導致 list 渲染落後 totalCount counter。改用 .map { } + flowOn(IO) + .conflate()：
                // - map 在 IO 上游完成，不被下游 cancel 中斷已完成的計算
                // - conflate 對連續 emit 只保留最新，下游 collect 處理中不被打斷
                // Phase 14 Q2-A/B：enrichAndMap 內 batch 預載 channel importance + ranking observation，
                // 注入 NotificationDisplay 對應 chip 顯示用欄位
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
            } else {
                eventDao.query(specSnapshot)
                    .map { items -> enrichAndMap(items) }
                    .flowOn(Dispatchers.IO)
                    .conflate()
                    .collect { displays ->
                        _allNotifications.value = displays
                        _awaitingInitialData.value = false
                    }
            }
        }
    }

    fun loadNextDay() {
        // 阻擋條件：載入中 / 已到底 / 非天分頁模式 / 初次資料尚未抵達（避免 init yesterday 載入未完
        // 就觸發 loadNextDay 導致 historicalDays append 順序錯亂）
        if (_isLoadingMore.value || _hasReachedEnd.value || !usesDayPaging() ||
            _awaitingInitialData.value) return
        _isLoadingMore.value = true
        val specSnapshot = _coreSpec.value

        viewModelScope.launch {
            val dayStart = nextDayToLoad
            val dayEnd = dayStart + ONE_DAY_MS
            val spec = specSnapshot.copy(timeFrom = dayStart, timeTo = dayEnd)

            val data = withContext(Dispatchers.IO) {
                enrichAndMap(eventDao.query(spec).first())
            }

            if (data.isNotEmpty()) {
                insertHistoricalDay(dayStart, data)
            }

            nextDayToLoad = dayStart - ONE_DAY_MS
            refreshReachedEnd()
            _isLoadingMore.value = false
            recombineAll()
        }
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

    private fun usesDayPagingForSpec(spec: EventFilterSpec): Boolean =
        spec.matchers.isEmpty() && spec.timeFrom == null && spec.timeTo == null &&
            spec.limit == null

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
        const val ONE_DAY_MS = 24 * 60 * 60 * 1000L
        const val KEY_DEDUP_CHECKED = "timeline.dedupChecked"
        const val KEY_ACTIVE_RULE_ID = "timeline.activeRuleId"
        const val KEY_FILTER_TEXT = "timeline.filterText"
        const val KEY_USER_DEDUP_BEFORE_RULE = "timeline.userDedupBeforeRule"
        const val KEY_SCROLL_STATE = "timeline.scrollState"
    }
}
