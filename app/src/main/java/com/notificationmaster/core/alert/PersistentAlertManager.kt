package com.notificationmaster.core.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import com.notificationmaster.R

/**
 * 持續提醒管理器（薄門面）
 *
 * 委派給 [PersistentAlertService] 前景服務處理實際的鈴聲、振動、通知。
 * NLS 透過此類啟動/停止提醒，不需要直接接觸 Service。
 */
class PersistentAlertManager(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "persistent_alert"

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.alert_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.settings_persistent_alert_summary)
                    setSound(null, null)    // 鈴聲由 App 自行控制
                    enableVibration(false)  // 振動由 App 自行控制
                }
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(channel)
            }
        }
    }

    fun startAlert(data: AlertData) {
        // API 33+: 若無 POST_NOTIFICATIONS 權限則跳過（前景服務通知無法顯示 = 無停止按鈕）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context, "android.permission.POST_NOTIFICATIONS"
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                AlertDiagnostics.log(
                    context, "guard", AlertDiagnostics.OUTCOME_SKIP,
                    "POST_NOTIFICATIONS 未授權，跳過本次提醒（無通知＝無停止按鈕）" +
                        " key=${data.notificationKey} pkg=${data.packageName}"
                )
                return
            }
        }

        PersistentAlertService.start(context, data)
    }

    fun stopAlert(reason: String) {
        PersistentAlertService.stop(context, reason)
    }
}
