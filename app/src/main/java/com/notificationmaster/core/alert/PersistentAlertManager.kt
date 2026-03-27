package com.notificationmaster.core.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.notificationmaster.R
import com.notificationmaster.ui.alert.PersistentAlertActivity

/**
 * 持續提醒管理器
 *
 * 負責 Ringtone 循環播放、Vibrator 循環振動、發送 heads-up 通知。
 * 一次只有一個活躍提醒（新觸發覆蓋舊的）。
 */
class PersistentAlertManager(private val context: Context) {

    private val TAG = "PersistentAlertManager"
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var isAlerting = false

    companion object {
        const val CHANNEL_ID = "persistent_alert"
        const val NOTIFICATION_ID = 900_000
        const val ACTION_STOP_ALERT = "com.notificationmaster.action.STOP_ALERT"
        const val EXTRA_NOTIFICATION_KEY = "notification_key"

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
        // API 33+: 若無 POST_NOTIFICATIONS 權限則跳過（無法顯示停止按鈕，提醒將無法被停止）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context, "android.permission.POST_NOTIFICATIONS"
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "POST_NOTIFICATIONS not granted, skipping persistent alert")
                return
            }
        }

        stopAlert()  // 一次一個

        // 鈴聲（循環）
        val uri = if (data.soundUri != null) Uri.parse(data.soundUri)
                  else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
            play()
        }

        // 振動（循環）
        if (data.vibrate) {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                    .defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator = vib.also {
                val pattern = longArrayOf(0, 500, 500)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(pattern, 0)
                }
            }
        }

        // Heads-up 通知（含 fullScreenIntent）
        postAlertNotification(data)
        isAlerting = true
    }

    fun stopAlert() {
        ringtone?.stop()
        ringtone = null
        vibrator?.cancel()
        vibrator = null
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NOTIFICATION_ID)
        isAlerting = false
    }

    private fun postAlertNotification(data: AlertData) {
        val stopIntent = Intent(ACTION_STOP_ALERT).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_NOTIFICATION_KEY, data.notificationKey)
        }
        val stopPi = PendingIntent.getBroadcast(
            context, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(data.title)
            .setContentText(data.text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setOngoing(true)
            .addAction(0, context.getString(R.string.alert_stop), stopPi)

        // fullScreenIntent: API 34+ 需檢查權限，低版本直接設定
        val activityIntent = PersistentAlertActivity.createIntent(context, data)
        val fullScreenPi = PendingIntent.getActivity(
            context, NOTIFICATION_ID, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.canUseFullScreenIntent()) {
                builder.setFullScreenIntent(fullScreenPi, true)
            }
            // 無權限時 graceful degradation 為現有 heads-up 行為
        } else {
            builder.setFullScreenIntent(fullScreenPi, true)
        }

        val notification = builder.build()

        // 權限已在 startAlert() 入口檢查；此處為 lint 滿足條件的雙重保護
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, "android.permission.POST_NOTIFICATIONS"
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted, cannot post alert notification")
            return
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification)
    }
}
