package com.notificationmaster.ui.archive

import android.os.Parcelable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

/**
 * Archive 列表頁 ViewModel
 *
 * Plan 2 §J 抖動修復：
 * - currentTab 走 SavedStateHandle（process death 後也保留，取代原本 onSaveInstanceState）
 * - 兩個 tab 各自的 scrollState 純 in-memory，view 重建後（含從 Detail 返回）還原
 */
class ArchiveViewModel(private val savedState: SavedStateHandle) : ViewModel() {

    var currentTab: ArchiveFragment.Tab
        get() = ArchiveFragment.Tab.values()[savedState[KEY_CURRENT_TAB] ?: 0]
        set(value) { savedState[KEY_CURRENT_TAB] = value.ordinal }

    var appTabScrollState: Parcelable? = null
    var channelTabScrollState: Parcelable? = null

    private companion object {
        const val KEY_CURRENT_TAB = "archive.currentTab"
    }
}
