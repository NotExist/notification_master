package com.notificationmaster.core.alert

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.notificationmaster.R
import com.notificationmaster.ui.alert.PersistentAlertActivity

/**
 * 持續提醒前景服務
 *
 * 負責 Ringtone 循環播放、Vibrator 循環振動、發送 heads-up / fullScreenIntent 通知。
 * 生命週期獨立於 NLS，確保提醒行為不因 NLS 狀態變化而中斷。
 * 一次只有一個活躍提醒（新觸發覆蓋舊的）。
 */
class PersistentAlertService : Service() {

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAlertAndSelf()
                return START_NOT_STICKY
            }
        }

        val data = pendingAlertData
        if (data == null) {
            Log.w(TAG, "No AlertData available, stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        pendingAlertData = null

        // 存給 Activity 讀取
        currentAlertData = data

        // 先發前景通知（必須在 startForeground 5 秒內呼叫）
        val notification = buildNotification(data)
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                else 0
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to startForeground", e)
            stopSelf()
            return START_NOT_STICKY
        }

        // 鈴聲（循環）
        val uri = if (data.soundUri != null) Uri.parse(data.soundUri)
                  else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
            play()
        }

        // 振動（循環）
        if (data.vibrate) {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                    .defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
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

        Log.d(TAG, "Alert started: key=${data.notificationKey}, vibrate=${data.vibrate}")
        return START_NOT_STICKY
    }

    private fun buildNotification(data: AlertData): Notification {
        val stopIntent = Intent(this, PersistentAlertService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, PersistentAlertManager.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(data.title)
            .setContentText(data.text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, getString(R.string.alert_stop), stopPi)

        // fullScreenIntent
        val activityIntent = PersistentAlertActivity.createIntent(this)
        val fullScreenPi = PendingIntent.getActivity(
            this, NOTIFICATION_ID, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.canUseFullScreenIntent()) {
                builder.setFullScreenIntent(fullScreenPi, true)
            }
        } else {
            builder.setFullScreenIntent(fullScreenPi, true)
        }

        return builder.build()
    }

    private fun stopAlertAndSelf() {
        Log.d(TAG, "Stopping alert")
        ringtone?.stop()
        ringtone = null
        vibrator?.cancel()
        vibrator = null
        currentAlertData = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        ringtone?.stop()
        ringtone = null
        vibrator?.cancel()
        vibrator = null
        currentAlertData = null
    }

    companion object {
        private const val TAG = "PersistentAlertService"
        const val NOTIFICATION_ID = 900_000
        const val ACTION_STOP = "com.notificationmaster.action.STOP_ALERT_SERVICE"

        /** 啟動前暫存，Service.onStartCommand 取走後清空 */
        @Volatile
        var pendingAlertData: AlertData? = null
            private set

        /** 供 PersistentAlertActivity 讀取當前提醒資料 */
        @Volatile
        var currentAlertData: AlertData? = null
            private set

        fun start(context: Context, data: AlertData) {
            pendingAlertData = data
            val intent = Intent(context, PersistentAlertService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, PersistentAlertService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
