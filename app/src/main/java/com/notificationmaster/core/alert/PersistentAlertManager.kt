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
import android.annotation.SuppressLint
import androidx.core.app.NotificationCompat
import com.notificationmaster.R

/**
 * 持續提醒管理器
 *
 * 負責 Ringtone 循環播放、Vibrator 循環振動、發送 heads-up 通知。
 * 一次只有一個活躍提醒（新觸發覆蓋舊的）。
 */
class PersistentAlertManager(private val context: Context) {

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

    fun startAlert(
        notificationKey: String,
        title: String,
        text: String?,
        soundUri: String?,
        vibrate: Boolean
    ) {
        stopAlert()  // 一次一個

        // 鈴聲（循環）
        val uri = if (soundUri != null) Uri.parse(soundUri)
                  else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
            play()
        }

        // 振動（循環）
        if (vibrate) {
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

        // Heads-up 通知
        postAlertNotification(notificationKey, title, text)
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

    @SuppressLint("NotificationPermission")
    private fun postAlertNotification(notificationKey: String, title: String, text: String?) {
        val stopIntent = Intent(ACTION_STOP_ALERT).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_NOTIFICATION_KEY, notificationKey)
        }
        val stopPi = PendingIntent.getBroadcast(
            context, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setOngoing(true)
            .addAction(0, context.getString(R.string.alert_stop), stopPi)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification)
    }
}
