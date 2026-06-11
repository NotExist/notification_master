package com.notificationmaster.ui.archive

import android.os.Parcelable
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Archive Detail 列表頁 ViewModel
 *
 * 純 in-memory scrollState（view 重建後還原；從 Notification Detail 返回時回原位）。
 * navArgs 不同會綁不同的 Fragment instance，因此一個 packageName/channelId 對應一個 ViewModel。
 */
class ArchiveDetailViewModel : ViewModel() {
    var scrollState: Parcelable? = null

    /**
     * W23b：漸進載入 page size。修前 spec 無 limit — 單一 package 上萬 events 時
     * 一次撈全部（含 raw_json）+ enrich 萬筆 + main thread diff 萬筆，畫面長時間空白。
     * 放 ViewModel 讓「進 Notification Detail 返回」時保留已擴張的載入量。
     */
    val pageSize = MutableStateFlow(INITIAL_PAGE_SIZE)

    companion object {
        const val INITIAL_PAGE_SIZE = 200
        const val PAGE_INCREMENT = 200
    }
}
