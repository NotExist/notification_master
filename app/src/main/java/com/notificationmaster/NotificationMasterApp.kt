package com.notificationmaster

import android.app.Application
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.notificationmaster.data.db.NotificationDatabase
import com.notificationmaster.ui.main.MainActivity

/**
 * Application 類別
 * 負責初始化全域資源
 */
class NotificationMasterApp : Application() {

    /** 資料庫實例 (lazy 初始化) */
    val database: NotificationDatabase by lazy {
        NotificationDatabase.getInstance(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        setupShortcuts()
    }

    /**
     * 註冊動態 Shortcut
     * 使用 ShortcutManagerCompat 繞開部分 OEM launcher 對 static shortcut 的相容性問題
     */
    private fun setupShortcuts() {
        val shortcut = ShortcutInfoCompat.Builder(this, SHORTCUT_ID_AUDIBLE)
            .setShortLabel(getString(R.string.shortcut_audible_short))
            .setLongLabel(getString(R.string.shortcut_audible_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_shortcut_audible))
            .setIntent(Intent(MainActivity.ACTION_SHOW_AUDIBLE).apply {
                setClass(this@NotificationMasterApp, MainActivity::class.java)
            })
            .build()
        ShortcutManagerCompat.setDynamicShortcuts(this, listOf(shortcut))
    }

    companion object {
        private const val SHORTCUT_ID_AUDIBLE = "recent_audible"

        @Volatile
        private var instance: NotificationMasterApp? = null

        fun getInstance(): NotificationMasterApp {
            return instance ?: throw IllegalStateException(
                "NotificationMasterApp not initialized"
            )
        }
    }
}
