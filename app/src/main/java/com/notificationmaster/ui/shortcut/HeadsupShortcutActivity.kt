package com.notificationmaster.ui.shortcut

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Heads-up Shortcut Activity — 透明背景承載 BottomSheet
 * BottomSheet dismiss 時自動 finish
 */
class HeadsupShortcutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 不設 contentView，直接顯示 BottomSheet
        if (savedInstanceState == null) {
            HeadsupBottomSheetFragment().show(supportFragmentManager, TAG_BOTTOM_SHEET)
        }
    }

    fun finishFromBottomSheet() {
        finish()
    }

    companion object {
        const val ACTION_SHORTCUT_HEADSUP = "com.notificationmaster.action.SHORTCUT_HEADSUP"
        private const val TAG_BOTTOM_SHEET = "HeadsupBottomSheet"
    }
}
