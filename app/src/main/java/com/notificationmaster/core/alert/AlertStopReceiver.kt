package com.notificationmaster.core.alert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.notificationmaster.service.NotificationCaptureService

/**
 * 接收「停止提醒」Action 的 BroadcastReceiver
 *
 * 由 PersistentAlertManager 發送的 heads-up 通知中的停止按鈕觸發。
 */
class AlertStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == PersistentAlertManager.ACTION_STOP_ALERT) {
            NotificationCaptureService.getInstance()?.stopPersistentAlert()
        }
    }
}
