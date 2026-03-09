package com.notificationmaster.ui.shortcut

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Dismissed Shortcut Activity — 透明背景承載 BottomSheet
 * BottomSheet dismiss 時自動 finish
 */
class DismissedShortcutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            DismissedBottomSheetFragment().show(supportFragmentManager, TAG_BOTTOM_SHEET)
        }
    }

    fun finishFromBottomSheet() {
        finish()
    }

    companion object {
        const val ACTION_SHORTCUT_DISMISSED = "com.notificationmaster.action.SHORTCUT_DISMISSED"
        private const val TAG_BOTTOM_SHEET = "DismissedBottomSheet"
    }
}
