package com.notificationmaster.ui.archive

import android.os.Parcelable
import androidx.lifecycle.ViewModel

/**
 * Archive Detail 列表頁 ViewModel
 *
 * 純 in-memory scrollState（view 重建後還原；從 Notification Detail 返回時回原位）。
 * navArgs 不同會綁不同的 Fragment instance，因此一個 packageName/channelId 對應一個 ViewModel。
 */
class ArchiveDetailViewModel : ViewModel() {
    var scrollState: Parcelable? = null

    /**
     * W23z：append 式分頁的已載入量（OFFSET 游標）。
     *
     * W23b 的 pageSize MutableStateFlow「重查整窗」模型（每次擴頁 flatMapLatest 重查
     * + 重 enrich 整個視窗）在 9000+ 筆單一 app 深滑時線性劣化，改為 append：每批只查
     * offset 之後的 PAGE_SIZE 筆、enrich 該批、接到清單尾端。放 ViewModel 讓「進
     * Notification Detail 返回」時知道要一次載回多少（重建累積狀態）。
     */
    var loadedCount = 0

    companion object {
        const val PAGE_SIZE = 200
    }
}
