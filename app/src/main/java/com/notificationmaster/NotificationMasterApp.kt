package com.notificationmaster

import android.app.Application
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.notificationmaster.core.alert.PersistentAlertManager
import com.notificationmaster.core.debug.DebugCrashHandler
import com.notificationmaster.core.debug.DebugPaths
import com.notificationmaster.core.debug.MainThreadWatchdog
import com.notificationmaster.core.debug.ProfileLogger
import com.notificationmaster.service.NlsKeepaliveService
import com.notificationmaster.data.db.NotificationDatabase
import com.notificationmaster.ui.shortcut.AudibleShortcutActivity
import com.notificationmaster.ui.shortcut.DismissedShortcutActivity
import com.notificationmaster.ui.shortcut.HeadsupShortcutActivity

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
        ProfileLogger.init(this)
        // Plan 1-zippy-thunder W7：debug mode 開啟時掛 main thread watchdog + uncaught crash log
        // 用於診斷啟動卡死 / ANR / FC 等不易從 logcat 回追的問題
        if (DebugPaths.isEnabled(this)) {
            DebugCrashHandler.install()
            MainThreadWatchdog.start()
            ProfileLogger.append("App", "onCreate diagnostics installed (watchdog + crash handler)")
        }
        PersistentAlertManager.createNotificationChannel(this)
        NlsKeepaliveService.createNotificationChannel(this)
        setupShortcuts()
    }

    /**
     * 註冊動態 Shortcut
     * 使用 ShortcutManagerCompat 繞開部分 OEM launcher 對 static shortcut 的相容性問題
     */
    private fun setupShortcuts() {
        val audibleShortcut = ShortcutInfoCompat.Builder(this, SHORTCUT_ID_AUDIBLE)
            .setShortLabel(getString(R.string.shortcut_audible_short))
            .setLongLabel(getString(R.string.shortcut_audible_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_shortcut_audible))
            .setIntent(Intent(AudibleShortcutActivity.ACTION_SHORTCUT_AUDIBLE).apply {
                setClass(this@NotificationMasterApp, AudibleShortcutActivity::class.java)
            })
            .build()

        val headsupShortcut = ShortcutInfoCompat.Builder(this, SHORTCUT_ID_HEADSUP)
            .setShortLabel(getString(R.string.shortcut_headsup_short))
            .setLongLabel(getString(R.string.shortcut_headsup_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_shortcut_headsup))
            .setIntent(Intent(HeadsupShortcutActivity.ACTION_SHORTCUT_HEADSUP).apply {
                setClass(this@NotificationMasterApp, HeadsupShortcutActivity::class.java)
            })
            .build()

        val dismissedShortcut = ShortcutInfoCompat.Builder(this, SHORTCUT_ID_DISMISSED)
            .setShortLabel(getString(R.string.shortcut_dismissed_short))
            .setLongLabel(getString(R.string.shortcut_dismissed_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_shortcut_dismissed))
            .setIntent(Intent(DismissedShortcutActivity.ACTION_SHORTCUT_DISMISSED).apply {
                setClass(this@NotificationMasterApp, DismissedShortcutActivity::class.java)
            })
            .build()

        ShortcutManagerCompat.setDynamicShortcuts(this, listOf(audibleShortcut, headsupShortcut, dismissedShortcut))
    }

    companion object {
        private const val SHORTCUT_ID_AUDIBLE = "shortcut_audible"
        private const val SHORTCUT_ID_HEADSUP = "shortcut_headsup"
        private const val SHORTCUT_ID_DISMISSED = "shortcut_dismissed"

        @Volatile
        private var instance: NotificationMasterApp? = null

        fun getInstance(): NotificationMasterApp {
            return instance ?: throw IllegalStateException(
                "NotificationMasterApp not initialized"
            )
        }
    }
}
