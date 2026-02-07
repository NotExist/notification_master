package com.notificationmaster.core.compat

import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * API 版本相容性輔助類別
 */
object ApiVersionHelper {

    // === 版本常數 ===

    /** NotificationListenerService 首次引入 */
    const val API_NOTIFICATION_LISTENER = 18

    /** getKey() 方法引入 */
    const val API_NOTIFICATION_KEY = 21

    /** Heads-up 通知引入 */
    const val API_HEADS_UP = 21

    /** Icon 類別引入 */
    const val API_ICON_CLASS = 23

    /** Direct Reply 引入 */
    const val API_DIRECT_REPLY = 24

    /** MessagingStyle 引入 */
    const val API_MESSAGING_STYLE = 24

    /** NotificationChannel 引入 */
    const val API_NOTIFICATION_CHANNEL = 26

    /** 語意動作引入 */
    const val API_SEMANTIC_ACTION = 28

    /** Bubbles 引入 */
    const val API_BUBBLES = 29

    /** Authentication Required 引入 */
    const val API_AUTH_REQUIRED = 31

    /** POST_NOTIFICATIONS 權限引入 */
    const val API_POST_NOTIFICATIONS = 33

    // === 裝置狀態 API 常數 ===

    /** PowerManager.isInteractive() 引入 */
    const val API_IS_INTERACTIVE = 20

    /** BatteryManager.getIntProperty() 引入 */
    const val API_BATTERY_PROPERTY = 21

    /** NetworkCapabilities 引入 (替代已棄用的 getActiveNetworkInfo) */
    const val API_NETWORK_CAPABILITIES = 29

    // === 功能檢查 ===

    fun supportsNotificationKey(): Boolean = Build.VERSION.SDK_INT >= API_NOTIFICATION_KEY
    fun supportsHeadsUp(): Boolean = Build.VERSION.SDK_INT >= API_HEADS_UP
    fun supportsIconClass(): Boolean = Build.VERSION.SDK_INT >= API_ICON_CLASS
    fun supportsDirectReply(): Boolean = Build.VERSION.SDK_INT >= API_DIRECT_REPLY
    fun supportsMessagingStyle(): Boolean = Build.VERSION.SDK_INT >= API_MESSAGING_STYLE
    fun supportsNotificationChannel(): Boolean = Build.VERSION.SDK_INT >= API_NOTIFICATION_CHANNEL
    fun supportsSemanticAction(): Boolean = Build.VERSION.SDK_INT >= API_SEMANTIC_ACTION
    fun supportsBubbles(): Boolean = Build.VERSION.SDK_INT >= API_BUBBLES
    fun supportsAuthRequired(): Boolean = Build.VERSION.SDK_INT >= API_AUTH_REQUIRED
    fun supportsPostNotificationsPermission(): Boolean = Build.VERSION.SDK_INT >= API_POST_NOTIFICATIONS

    // === Key 產生 ===

    /**
     * 取得通知 Key
     * API 21+ 使用 sbn.key
     * API 18-20 手動組裝
     */
    fun getNotificationKey(sbn: StatusBarNotification): String {
        return if (Build.VERSION.SDK_INT >= API_NOTIFICATION_KEY) {
            sbn.key
        } else {
            "${sbn.packageName}|${sbn.id}|${sbn.tag ?: ""}"
        }
    }

    // === 移除原因分類 ===

    /**
     * 分類移除原因
     */
    fun categorizeRemovalReason(reason: Int): String {
        return when (reason) {
            NotificationListenerService.REASON_CLICK -> "USER_CLICK"
            NotificationListenerService.REASON_SNOOZED -> "USER_SNOOZE"
            NotificationListenerService.REASON_APP_CANCEL,
            NotificationListenerService.REASON_CANCEL -> "APP_CANCEL"
            NotificationListenerService.REASON_APP_CANCEL_ALL,
            NotificationListenerService.REASON_CANCEL_ALL -> "APP_CANCEL_ALL"
            NotificationListenerService.REASON_LISTENER_CANCEL -> "LISTENER_OR_SWIPE"
            NotificationListenerService.REASON_TIMEOUT -> "TIMEOUT"
            NotificationListenerService.REASON_CHANNEL_BANNED -> "CHANNEL_BANNED"
            else -> "OTHER"
        }
    }

    /**
     * 移除原因的人類可讀描述
     */
    fun getRemovalReasonDescription(reason: Int): String {
        return when (reason) {
            NotificationListenerService.REASON_CLICK -> "使用者點擊"
            NotificationListenerService.REASON_SNOOZED -> "使用者暫停"
            NotificationListenerService.REASON_APP_CANCEL -> "App 取消"
            NotificationListenerService.REASON_CANCEL -> "取消"
            NotificationListenerService.REASON_APP_CANCEL_ALL -> "App 取消全部"
            NotificationListenerService.REASON_CANCEL_ALL -> "取消全部"
            NotificationListenerService.REASON_LISTENER_CANCEL -> "監聽器取消/滑動"
            NotificationListenerService.REASON_TIMEOUT -> "超時"
            NotificationListenerService.REASON_CHANNEL_BANNED -> "Channel 被禁用"
            NotificationListenerService.REASON_ERROR -> "錯誤"
            NotificationListenerService.REASON_GROUP_OPTIMIZATION -> "群組最佳化"
            NotificationListenerService.REASON_GROUP_SUMMARY_CANCELED -> "群組摘要取消"
            else -> "未知 ($reason)"
        }
    }

    // === Heads-up 推斷 ===

    /**
     * 推斷通知是否可能以 Heads-up 方式顯示
     */
    @Suppress("DEPRECATION")
    fun isLikelyHeadsUp(notification: Notification, channelImportance: Int?): Boolean {
        return if (Build.VERSION.SDK_INT >= API_NOTIFICATION_CHANNEL && channelImportance != null) {
            // API 26+: Channel importance 為 HIGH 或 MAX
            channelImportance >= android.app.NotificationManager.IMPORTANCE_HIGH
        } else {
            // API 21-25: PRIORITY_HIGH 或 MAX + 有聲音或震動
            @Suppress("DEPRECATION")
            val isHighPriority = notification.priority >= Notification.PRIORITY_HIGH
            val hasSound = notification.sound != null
            val hasVibrate = notification.vibrate != null
            isHighPriority && (hasSound || hasVibrate)
        }
    }

    // === Flags 解析 ===

    fun isOngoing(flags: Int): Boolean = (flags and Notification.FLAG_ONGOING_EVENT) != 0
    fun isForegroundService(flags: Int): Boolean = (flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
    fun isAutoCancel(flags: Int): Boolean = (flags and Notification.FLAG_AUTO_CANCEL) != 0
    fun isNoClear(flags: Int): Boolean = (flags and Notification.FLAG_NO_CLEAR) != 0
    @Suppress("DEPRECATION")
    fun isHighPriority(flags: Int): Boolean = (flags and Notification.FLAG_HIGH_PRIORITY) != 0
    fun isLocalOnly(flags: Int): Boolean = (flags and Notification.FLAG_LOCAL_ONLY) != 0
    fun isGroupSummary(flags: Int): Boolean = (flags and Notification.FLAG_GROUP_SUMMARY) != 0
}
