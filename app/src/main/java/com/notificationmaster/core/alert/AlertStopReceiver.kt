package com.notificationmaster.core.alert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 接收「停止提醒」Action 的 BroadcastReceiver
 *
 * 保留向下相容（舊版通知的停止按鈕仍用 Broadcast）。
 * 新架構的停止按鈕直接走 Service Intent，不經此 Receiver。
 */
class AlertStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == PersistentAlertService.ACTION_STOP) {
            PersistentAlertService.stop(context)
        }
    }
}
