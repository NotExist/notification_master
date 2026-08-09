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
 * 一次只有一個活躍提醒（不同 key 的新觸發覆蓋舊的；同 key 只更新內容不重啟鈴聲）。
 *
 * 管線各階段結果記錄至 [AlertDiagnostics]（logcat tag `NMAlert` + prefs 環形緩衝）。
 */
class PersistentAlertService : Service() {

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    /** 是否已成功 startForeground（決定 startForeground 失敗時能否沿用舊通知續播） */
    private var isForegroundActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val reason = intent.getStringExtra(EXTRA_STOP_REASON) ?: "unknown"
            stopAlertAndSelf(reason)
            return START_NOT_STICKY
        }

        val incoming = takePendingAlertData()
        val active = currentAlertData
        val data = incoming ?: active

        if (data == null) {
            // 交接資料與現存提醒皆空（理論上僅殘留的過期 start 指令會走到這）。
            // 經 startForegroundService 啟動就必須進前景一次再停，
            // 避免 "did not then call startForeground" 例外
            AlertDiagnostics.log(
                this, "service", AlertDiagnostics.OUTCOME_SKIP,
                "startId=$startId 交接資料與現存提醒皆為 null，無可播放內容，結束服務"
            )
            enterForeground(buildIdleNotification(), key = null)
            stopAlertAndSelf("no_data")
            return START_NOT_STICKY
        }

        val isPlaybackActive = ringtone != null || vibrator != null
        val resumedFromRace = incoming == null // active != null：交接被先前指令取走
        val sameKeyUpdate = incoming != null && active != null && isPlaybackActive &&
                incoming.notificationKey == active.notificationKey

        currentAlertData = data

        val foregroundOk = enterForeground(buildNotification(data), data.notificationKey)
        if (!foregroundOk && !isPlaybackActive) {
            // 尚無播放中的提醒且通知發不出來 = 無停止按鈕 → 不啟動（避免無法停止的鈴聲）
            stopAlertAndSelf("foreground_failed")
            return START_NOT_STICKY
        }
        // foreground 失敗但已有播放中的提醒：先前的前景通知還在，沿用舊通知繼續

        if (resumedFromRace) {
            AlertDiagnostics.log(
                this, "service", AlertDiagnostics.OUTCOME_OK,
                "startId=$startId 交接資料已被先前指令取走，沿用現存提醒續播 " +
                    "key=${data.notificationKey}"
            )
            return START_NOT_STICKY
        }
        if (sameKeyUpdate) {
            AlertDiagnostics.log(
                this, "service", AlertDiagnostics.OUTCOME_OK,
                "startId=$startId 節流：同 key 提醒已在響，僅更新通知內容不重啟鈴聲 " +
                    "key=${data.notificationKey} event=${data.eventType}"
            )
            return START_NOT_STICKY
        }

        // 覆蓋前先停掉舊的播放實例，避免產生失去參照、無法停止的孤兒 Ringtone/Vibrator
        if (isPlaybackActive) {
            AlertDiagnostics.log(
                this, "service", AlertDiagnostics.OUTCOME_OK,
                "startId=$startId 覆蓋：停止舊提醒 key=${active?.notificationKey} → " +
                    "啟動新提醒 key=${data.notificationKey}"
            )
        }
        stopPlayback()

        startRingtone(data)
        if (data.vibrate) startVibration(data)

        Log.i(
            AlertDiagnostics.TAG,
            "[service] alert started — startId=$startId key=${data.notificationKey} " +
                "event=${data.eventType} pkg=${data.packageName} vibrate=${data.vibrate} " +
                "sound=${data.soundUri ?: "(系統預設鬧鐘)"} stream=${data.audioStream}"
        )
        return START_NOT_STICKY
    }

    /** startForeground 包裝：成功回傳 true 並標記 [isForegroundActive]，失敗記錄診斷 */
    private fun enterForeground(notification: Notification, key: String?): Boolean {
        return try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                else 0
            )
            isForegroundActive = true
            true
        } catch (e: Exception) {
            AlertDiagnostics.log(
                this, "foreground", AlertDiagnostics.OUTCOME_FAIL,
                "startForeground 失敗 key=$key alreadyForeground=$isForegroundActive", e
            )
            false
        }
    }

    /**
     * 鈴聲播放：指定 soundUri 無法播放時 fallback 至系統預設鬧鐘鈴聲。
     */
    private fun startRingtone(data: AlertData) {
        val usage = if (data.audioStream == "alarm")
            android.media.AudioAttributes.USAGE_ALARM
        else
            android.media.AudioAttributes.USAGE_NOTIFICATION

        val requested = data.soundUri?.let { Uri.parse(it) }
        if (requested != null) {
            if (playRingtone(requested, usage, data.notificationKey)) return
            AlertDiagnostics.log(
                this, "ringtone", AlertDiagnostics.OUTCOME_SKIP,
                "指定鈴聲無法播放，fallback 至系統預設鬧鐘 uri=$requested " +
                    "key=${data.notificationKey}"
            )
        }
        val fallback = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        if (!playRingtone(fallback, usage, data.notificationKey)) {
            AlertDiagnostics.log(
                this, "ringtone", AlertDiagnostics.OUTCOME_FAIL,
                "系統預設鬧鐘也無法播放，本次提醒僅剩振動與通知 key=${data.notificationKey}"
            )
        }
    }

    private fun playRingtone(uri: Uri, usage: Int, key: String): Boolean {
        return try {
            val tone = RingtoneManager.getRingtone(this, uri) ?: return false
            tone.audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) tone.isLooping = true
            tone.play()
            ringtone = tone
            AlertDiagnostics.log(
                this, "ringtone", AlertDiagnostics.OUTCOME_OK,
                "播放中 uri=$uri key=$key"
            )
            true
        } catch (e: Exception) {
            AlertDiagnostics.log(
                this, "ringtone", AlertDiagnostics.OUTCOME_FAIL,
                "播放失敗 uri=$uri key=$key", e
            )
            false
        }
    }

    private fun startVibration(data: AlertData) {
        try {
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
        } catch (e: Exception) {
            AlertDiagnostics.log(
                this, "vibrate", AlertDiagnostics.OUTCOME_FAIL,
                "振動啟動失敗 key=${data.notificationKey}", e
            )
        }
    }

    private fun buildNotification(data: AlertData): Notification {
        // 停止按鈕與滑掉通知走不同 requestCode 的 PendingIntent，診斷可區分停止來源
        val stopPi = PendingIntent.getService(
            this, RC_STOP_BUTTON, stopIntent(this, STOP_REASON_BUTTON),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val deletePi = PendingIntent.getService(
            this, RC_DELETE, stopIntent(this, STOP_REASON_DISMISSED),
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
            // Android 14 起使用者可滑掉 FGS 通知：滑掉即停止提醒，避免無停止入口的鈴聲
            .setDeleteIntent(deletePi)

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

    /** 無資料可播時的最小前景通知（隨即停止，僅滿足 startForegroundService 契約） */
    private fun buildIdleNotification(): Notification =
        NotificationCompat.Builder(this, PersistentAlertManager.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.alert_channel_name))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /** 停止播放實例並清空參照（不動前景狀態）。stop/cancel 失敗個別記錄，不中斷清理 */
    private fun stopPlayback() {
        try {
            ringtone?.stop()
        } catch (e: Exception) {
            AlertDiagnostics.log(
                this, "stop", AlertDiagnostics.OUTCOME_FAIL, "Ringtone.stop() 失敗", e
            )
        }
        ringtone = null
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            AlertDiagnostics.log(
                this, "stop", AlertDiagnostics.OUTCOME_FAIL, "Vibrator.cancel() 失敗", e
            )
        }
        vibrator = null
    }

    private fun stopAlertAndSelf(reason: String) {
        AlertDiagnostics.log(
            this, "stop", AlertDiagnostics.OUTCOME_OK,
            "reason=$reason key=${currentAlertData?.notificationKey}"
        )
        stopPlayback()
        currentAlertData = null
        // 廣播停止事件，讓全螢幕 Activity 同步關閉（按通知停止鈕時 Activity 不會自己收到）
        sendBroadcast(Intent(ACTION_STOP).setPackage(packageName))
        isForegroundActive = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (ringtone != null || vibrator != null) {
            AlertDiagnostics.log(
                this, "stop", AlertDiagnostics.OUTCOME_OK,
                "onDestroy 清理未經 stopAlertAndSelf 的播放實例（非正常停止路徑）" +
                    " key=${currentAlertData?.notificationKey}"
            )
        }
        stopPlayback()
        currentAlertData = null
        isForegroundActive = false
    }

    companion object {
        const val NOTIFICATION_ID = 900_000
        const val ACTION_STOP = "com.notificationmaster.action.STOP_ALERT_SERVICE"
        const val EXTRA_STOP_REASON = "stop_reason"

        const val STOP_REASON_BUTTON = "notification_button"
        const val STOP_REASON_DISMISSED = "notification_dismissed"
        const val STOP_REASON_ACTIVITY = "activity"
        const val STOP_REASON_NLS_DESTROY = "nls_destroy"

        private const val RC_STOP_BUTTON = 1
        private const val RC_DELETE = 2

        /** 啟動前暫存，onStartCommand 以 [takePendingAlertData] 原子取走 */
        private var pendingAlertData: AlertData? = null

        /** 供 PersistentAlertActivity 讀取當前提醒資料 */
        @Volatile
        var currentAlertData: AlertData? = null
            private set

        private fun takePendingAlertData(): AlertData? = synchronized(this) {
            pendingAlertData?.also { pendingAlertData = null }
        }

        private fun stopIntent(context: Context, reason: String): Intent =
            Intent(context, PersistentAlertService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_STOP_REASON, reason)
            }

        fun start(context: Context, data: AlertData) {
            synchronized(this) { pendingAlertData = data }
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, PersistentAlertService::class.java)
                )
                Log.i(
                    AlertDiagnostics.TAG,
                    "[fgs_start] ok — key=${data.notificationKey} event=${data.eventType} " +
                        "pkg=${data.packageName}"
                )
            } catch (e: Exception) {
                synchronized(this) {
                    if (pendingAlertData === data) pendingAlertData = null
                }
                AlertDiagnostics.log(
                    context, "fgs_start", AlertDiagnostics.OUTCOME_FAIL,
                    "startForegroundService 被拒（常見原因：App 遭強制停止／OEM 背景限制）" +
                        " key=${data.notificationKey} event=${data.eventType} " +
                        "pkg=${data.packageName}", e
                )
            }
        }

        fun stop(context: Context, reason: String) {
            try {
                context.startService(stopIntent(context, reason))
            } catch (e: Exception) {
                // Service 未運行且 App 在背景時 startService 會被拒；此時本就無提醒可停
                AlertDiagnostics.log(
                    context, "stop", AlertDiagnostics.OUTCOME_SKIP,
                    "停止指令未送達（service 可能未運行）reason=$reason", e
                )
            }
        }

        /**
         * 強制重置（Debug 緊急脫困）：不走 ACTION_STOP 訊息路徑，直接清空一切相關狀態。
         * 清除交接/當前資料 → stopService（onDestroy 兜底停播放）→ 取消通知殘留 →
         * 廣播關閉全螢幕 Activity。孤兒播放實例（失去參照的 Ringtone）process 內無法
         * 保證清除，需要 100% 歸零時由呼叫端搭配終止 process。
         */
        fun forceReset(context: Context) {
            AlertDiagnostics.log(
                context, "stop", AlertDiagnostics.OUTCOME_OK,
                "force_reset：清空交接資料 + stopService + 取消通知 + 關閉 Activity" +
                    "（pending=${pendingAlertData?.notificationKey} " +
                    "current=${currentAlertData?.notificationKey}）"
            )
            synchronized(this) { pendingAlertData = null }
            currentAlertData = null
            try {
                context.stopService(Intent(context, PersistentAlertService::class.java))
            } catch (e: Exception) {
                AlertDiagnostics.log(
                    context, "stop", AlertDiagnostics.OUTCOME_FAIL,
                    "force_reset：stopService 失敗", e
                )
            }
            try {
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(NOTIFICATION_ID)
            } catch (e: Exception) {
                AlertDiagnostics.log(
                    context, "stop", AlertDiagnostics.OUTCOME_FAIL,
                    "force_reset：取消通知失敗", e
                )
            }
            context.sendBroadcast(Intent(ACTION_STOP).setPackage(context.packageName))
        }
    }
}
