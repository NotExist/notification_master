package com.notificationmaster.ui.search

import android.os.Parcelable
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.ui.common.NotificationDisplay
import com.notificationmaster.ui.common.NotificationEnricher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Search ViewModel
 *
 * Plan 2 Phase 9：搜尋接 notification_events 表（每個 key 取最新 event），呈現用
 * [NotificationDisplay] 攤平 snapshot 後傳給 adapter。
 */
class SearchViewModel : ViewModel() {

    private val _query = MutableLiveData<String>("")
    val query: LiveData<String> = _query

    private val _results = MutableLiveData<List<NotificationDisplay>>(emptyList())
    val results: LiveData<List<NotificationDisplay>> = _results

    /** W23l：搜尋進行中（searchEvents + enrich 大資料時達秒級，期間 UI 顯示光條） */
    private val _isSearching = MutableLiveData(false)
    val isSearching: LiveData<Boolean> = _isSearching

    /** W23p：當前 limit 下結果已撈完（events.size < limit）— footer End/Pending 判定用 */
    private val _endReached = MutableLiveData(true)
    val endReached: LiveData<Boolean> = _endReached

    private var currentLimit = INITIAL_LIMIT

    /**
     * RecyclerView LayoutManager.onSaveInstanceState() 結果；view 重建（含從 Detail 返回）後還原。
     * 純 in-memory，process death 不保留（搜尋結果本身也不保留，重啟後行為一致）。
     */
    var scrollState: Parcelable? = null

    private var searchJob: Job? = null

    fun search(query: String) {
        _query.value = query
        currentLimit = INITIAL_LIMIT

        if (query.isBlank()) {
            searchJob?.cancel()
            _results.value = emptyList()
            _endReached.value = true
            _isSearching.value = false
            return
        }

        runSearch(query, debounce = true)
    }

    /**
     * W23p：lazyload — 滑近底部時擴增 limit 重查（重查整批，與 searchEvents 的
     * LIMIT 模型一致；endReached 後不再觸發）。
     */
    fun loadMore() {
        val query = _query.value ?: return
        if (query.isBlank() || _isSearching.value == true || _endReached.value == true) return
        currentLimit += PAGE_INCREMENT
        runSearch(query, debounce = false)
    }

    private fun runSearch(query: String, debounce: Boolean) {
        searchJob?.cancel()
        _isSearching.value = true
        val limit = currentLimit
        searchJob = viewModelScope.launch {
            if (debounce) delay(300)
            val database = NotificationMasterApp.getInstance().database
            val (results, rawCount) = withContext(Dispatchers.IO) {
                val events = database.notificationEventDao().searchEvents(query, limit)
                NotificationEnricher.enrich(
                    events,
                    database.channelDao(),
                    database.rankingObservationDao(),
                    database.rankingSnapshotDao(),
                    database.notificationEventDao()
                ) to events.size
            }
            _results.value = results
            _endReached.value = rawCount < limit
            _isSearching.value = false
        }
    }

    companion object {
        const val INITIAL_LIMIT = 100
        const val PAGE_INCREMENT = 100
    }
}
