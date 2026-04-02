package com.notificationmaster.ui.search

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.data.db.entity.NotificationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchViewModel : ViewModel() {

    private val _query = MutableLiveData<String>("")
    val query: LiveData<String> = _query

    private val _results = MutableLiveData<List<NotificationEntity>>(emptyList())
    val results: LiveData<List<NotificationEntity>> = _results

    private var searchJob: Job? = null

    fun search(query: String) {
        searchJob?.cancel()
        _query.value = query

        if (query.isBlank()) {
            _results.value = emptyList()
            return
        }

        searchJob = viewModelScope.launch {
            delay(300)
            val database = NotificationMasterApp.getInstance().database
            val results = withContext(Dispatchers.IO) {
                database.notificationDao().searchNotifications(query, 100)
            }
            _results.value = results
        }
    }
}
