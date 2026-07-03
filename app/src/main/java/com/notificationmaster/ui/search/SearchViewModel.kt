package com.notificationmaster.ui.search

import android.os.Parcelable
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.core.debug.ProfileLogger
import com.notificationmaster.data.db.entity.NotificationEventEntity
import com.notificationmaster.ui.common.NotificationDisplay
import com.notificationmaster.ui.common.NotificationEnricher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Search ViewModel
 *
 * W24a：時間切片漸進搜尋（user 裁決取代 FTS 方案）。
 * - 每批只掃 [ROW_SLICE] 列的時間視窗（新→舊），命中逐批 append 到結果 —
 *   「資料從新到舊逐漸出現」，批間為保證取消點
 * - raw_json 搜尋開關（[rawSearchEnabled]）預設關：關閉只比對 title/text/媒體檔名
 *   （小欄位，快）；開啟才掃 raw_json（進階/除錯，可等待）
 * - 掃到累積 [RESULTS_PAGE] 筆暫停（footer Pending），滑近底部 [loadMore] 續掃 —
 *   不為使用者不會看的結果白掃白 enrich
 * - 跑在 viewModelScope：進出 Detail 不中斷；離開 fragment 由 [cancelOngoingSearch]
 *   中斷（Fragment 端以 navigatingToDetail 旗標區分），已出現的結果保留、可續掃
 * - 舊版 GROUP BY notification_key（每 key 取最新命中）語意由 [seenKeys] 等價保留
 */
class SearchViewModel : ViewModel() {

    private val _query = MutableLiveData<String>("")
    val query: LiveData<String> = _query

    private val _results = MutableLiveData<List<NotificationDisplay>>(emptyList())
    val results: LiveData<List<NotificationDisplay>> = _results

    /** 掃描進行中（UI 顯示光條 + footer Loading） */
    private val _isSearching = MutableLiveData(false)
    val isSearching: LiveData<Boolean> = _isSearching

    /** 已掃完整個表（footer End）；false + 非掃描中 = 暫停（footer Pending 可續掃） */
    private val _endReached = MutableLiveData(true)
    val endReached: LiveData<Boolean> = _endReached

    /** W24a：raw_json 搜尋開關（預設關；in-memory，離開 search 隨 ViewModel 重置） */
    private val _rawSearchEnabled = MutableLiveData(false)
    val rawSearchEnabled: LiveData<Boolean> = _rawSearchEnabled

    /**
     * RecyclerView LayoutManager.onSaveInstanceState() 結果；view 重建（含從 Detail 返回）後還原。
     * 純 in-memory，process death 不保留（搜尋結果本身也不保留，重啟後行為一致）。
     */
    var scrollState: Parcelable? = null

    private var searchJob: Job? = null

    // === 單一 search run 的掃描狀態（restart 時重置；loadMore/resume 沿用） ===

    /** 已累積的結果（掃描新→舊 append） */
    private val accumulated = mutableListOf<NotificationDisplay>()

    /** 已納入的 event id（cursor 用 <=，切片邊界列會重覆出現 — 以 id 去重） */
    private val seenEventIds = HashSet<Long>()

    /** 已出現的 notification_key（保留舊版「每 key 只取最新命中」語意） */
    private val seenKeys = HashSet<String>()

    /** 掃描游標：下一批的範圍上界（event_time，含） */
    private var cursorTime = Long.MAX_VALUE

    /** 本輪掃描的累積目標，達標即暫停（loadMore 時 +RESULTS_PAGE 再續） */
    private var targetCount = RESULTS_PAGE

    fun setRawSearchEnabled(enabled: Boolean) {
        if (_rawSearchEnabled.value == enabled) return
        _rawSearchEnabled.value = enabled
        // 開關切換 = 搜尋範圍改變，當前 query 重掃
        val q = _query.value
        if (!q.isNullOrBlank()) restart(q, debounce = false)
    }

    fun search(query: String) {
        _query.value = query
        if (query.isBlank()) {
            searchJob?.cancel()
            resetRun()
            _results.value = emptyList()
            _endReached.value = true
            _isSearching.value = false
            return
        }
        restart(query, debounce = true)
    }

    /** 滑近底部：提高目標再續掃（不重置游標與已累積結果） */
    fun loadMore() {
        val q = _query.value ?: return
        if (q.isBlank() || _isSearching.value == true || _endReached.value == true) return
        targetCount = accumulated.size + RESULTS_PAGE
        resume(q, debounce = false)
    }

    /**
     * W24a：離開 search fragment 時中斷掃描（Fragment.onDestroyView 呼叫；進 Detail
     * 不呼叫）。已出現的結果保留，endReached 維持 false → footer Pending，返回後
     * 滑動可續掃。
     */
    fun cancelOngoingSearch() {
        if (_isSearching.value == true) {
            searchJob?.cancel()
            _isSearching.value = false
        }
    }

    private fun resetRun() {
        accumulated.clear()
        seenEventIds.clear()
        seenKeys.clear()
        cursorTime = Long.MAX_VALUE
        targetCount = RESULTS_PAGE
    }

    private fun restart(query: String, debounce: Boolean) {
        searchJob?.cancel()
        resetRun()
        _results.value = emptyList()
        resume(query, debounce)
    }

    private fun resume(query: String, debounce: Boolean) {
        searchJob?.cancel()
        _isSearching.value = true
        _endReached.value = false
        val raw = _rawSearchEnabled.value == true
        searchJob = viewModelScope.launch {
            if (debounce) delay(300)
            val database = NotificationMasterApp.getInstance().database
            val dao = database.notificationEventDao()
            val tStart = System.currentTimeMillis()

            while (isActive && accumulated.size < targetCount) {
                // 1. 時間界標：cursor 往下第 ROW_SLICE 列的 event_time（index-only，快）。
                //    null = 剩餘不足一個切片 → 本批掃到表尾
                val boundary = withContext(Dispatchers.IO) {
                    dao.getEventTimeAtOffsetSync(cursorTime, ROW_SLICE)
                }
                val afterTime = boundary ?: Long.MIN_VALUE

                // 2. 本切片內搜尋（LIMIT 防單片命中爆量一次 enrich 過多）
                val batch = withContext(Dispatchers.IO) {
                    if (raw) dao.searchEventsRawBatch(query, cursorTime, afterTime, MATCH_CAP)
                    else dao.searchEventsLightBatch(query, cursorTime, afterTime, MATCH_CAP)
                }

                // 3. 去重：邊界重覆列（id）+ 每 key 只留最新命中（新→舊首見即最新）
                val fresh = batch.filter { e ->
                    val newId = seenEventIds.add(e.id)
                    newId && seenKeys.add(e.notificationKey)
                }

                // 4. 命中批次 enrich + 漸進 emit
                if (fresh.isNotEmpty()) {
                    val displays = withContext(Dispatchers.IO) { enrich(fresh) }
                    accumulated += displays
                    _results.value = accumulated.toList()
                }
                ProfileLogger.append(
                    "Search",
                    "slice raw=$raw cursor=$cursorTime batch=${batch.size} fresh=${fresh.size} " +
                        "acc=${accumulated.size}/$targetCount since-start=${System.currentTimeMillis() - tStart}ms"
                )

                // 5. 游標推進
                if (batch.size >= MATCH_CAP) {
                    // 切片內滿載（LIMIT 截斷）→ 從本批最後一筆時間續掃同片
                    // （<= 含邊界，重覆列由 seenEventIds 濾；整批同 ms 且無新列時 -1 防呆
                    //   跳出 — 僅單一毫秒內事件數 > MATCH_CAP 才會發生，實務不出現）
                    val last = batch.last().eventTime
                    cursorTime = if (last == cursorTime && fresh.isEmpty()) last - 1 else last
                } else if (boundary == null) {
                    // 掃到表尾
                    _endReached.value = true
                    break
                } else {
                    cursorTime = boundary
                }
            }
            _isSearching.value = false
        }
    }

    private suspend fun enrich(events: List<NotificationEventEntity>): List<NotificationDisplay> {
        val database = NotificationMasterApp.getInstance().database
        return NotificationEnricher.enrich(
            events,
            database.channelDao(),
            database.rankingObservationDao(),
            database.rankingSnapshotDao(),
            database.notificationEventDao()
        )
    }

    companion object {
        /** 每批掃描的時間視窗列數（界標 query 依 event_time index 取，快） */
        const val ROW_SLICE = 2000

        /** 單批命中上限（防單片爆量一次 enrich 過多） */
        const val MATCH_CAP = 100

        /** 每「頁」累積目標；達標暫停，滑近底部 +100 續掃 */
        const val RESULTS_PAGE = 100
    }
}
