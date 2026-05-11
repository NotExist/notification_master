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
}
