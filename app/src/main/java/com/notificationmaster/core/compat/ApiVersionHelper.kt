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

    /** lastAudiblyAlertedMillis 引入 */
    const val API_AUDIBLE_ALERTED = 29

    /** Authentication Required 引入 */
    const val API_AUTH_REQUIRED = 31

    /** Person API 引入（與 SemanticAction 同級） */
    const val API_PERSON = 28

    /** Bubble 自動展開/抑制 */
    const val API_BUBBLE_AUTO_EXPAND = 30

    /** POST_NOTIFICATIONS 權限引入 */
    const val API_POST_NOTIFICATIONS = 33

    /** PendingIntent 型別屬性 */
    const val API_PENDING_INTENT_TYPE = 34

    // === 裝置狀態 API 常數 ===

    /** PowerManager.isInteractive() 引入 */
    const val API_IS_INTERACTIVE = 20

    /** BatteryManager.getIntProperty() 引入 */
    const val API_BATTERY_PROPERTY = 21

    /** NetworkCapabilities 引入 (替代已棄用的 getActiveNetworkInfo) */
    const val API_NETWORK_CAPABILITIES = 29

    // === 非公開或高版本 API 的移除原因常數 ===

    /** REASON_PACKAGE_CHANGED (@SystemApi, 非公開) */
    const val REASON_PACKAGE_CHANGED_INT = 5

    /** REASON_CHANNEL_REMOVED (API 30+) */
    const val REASON_CHANNEL_REMOVED_INT = 20

    /** REASON_CLEAR_DATA (API 30+) */
    const val REASON_CLEAR_DATA_INT = 21

    /** REASON_ASSISTANT_CANCEL (API 33+) */
    const val REASON_ASSISTANT_CANCEL_INT = 22

    // === 功能檢查 ===

    fun supportsNotificationKey(): Boolean = true  // minSdk 21 >= API_NOTIFICATION_KEY (21)
    fun supportsHeadsUp(): Boolean = true  // minSdk 21 >= API_HEADS_UP (21)
    fun supportsIconClass(): Boolean = Build.VERSION.SDK_INT >= API_ICON_CLASS
    fun supportsDirectReply(): Boolean = Build.VERSION.SDK_INT >= API_DIRECT_REPLY
    fun supportsMessagingStyle(): Boolean = Build.VERSION.SDK_INT >= API_MESSAGING_STYLE
    fun supportsNotificationChannel(): Boolean = Build.VERSION.SDK_INT >= API_NOTIFICATION_CHANNEL
    fun supportsSemanticAction(): Boolean = Build.VERSION.SDK_INT >= API_SEMANTIC_ACTION
    fun supportsBubbles(): Boolean = Build.VERSION.SDK_INT >= API_BUBBLES
    fun supportsAuthRequired(): Boolean = Build.VERSION.SDK_INT >= API_AUTH_REQUIRED
    fun supportsPerson(): Boolean = Build.VERSION.SDK_INT >= API_PERSON
    fun supportsBubbleAutoExpand(): Boolean = Build.VERSION.SDK_INT >= API_BUBBLE_AUTO_EXPAND
    fun supportsPostNotificationsPermission(): Boolean = Build.VERSION.SDK_INT >= API_POST_NOTIFICATIONS
    fun supportsLastAudiblyAlerted(): Boolean = Build.VERSION.SDK_INT >= API_AUDIBLE_ALERTED
    fun supportsNetworkCapabilities(): Boolean = Build.VERSION.SDK_INT >= API_NETWORK_CAPABILITIES

    // === Key 產生 ===

    /**
     * 取得通知 Key
     * API 21+ 使用 sbn.key
     * API 18-20 手動組裝
     */
    fun getNotificationKey(sbn: StatusBarNotification): String = sbn.key  // minSdk 21 >= API 21

    // === 移除原因分類 ===

    /**
     * 分類移除原因
     *
     * 涵蓋 Android SDK 定義的所有 REASON_* 常數（API 26–33）。
     * 參考：NotificationListenerService.onNotificationRemoved()
     */
    fun categorizeRemovalReason(reason: Int): String {
        return when (reason) {
            // 使用者操作
            NotificationListenerService.REASON_CLICK -> "USER_CLICK"              // 1
            NotificationListenerService.REASON_CANCEL -> "USER_DISMISS"           // 2
            NotificationListenerService.REASON_CANCEL_ALL -> "USER_CLEAR_ALL"     // 3
            NotificationListenerService.REASON_USER_STOPPED -> "USER_STOPPED"     // 6
            NotificationListenerService.REASON_SNOOZED -> "USER_SNOOZE"           // 18
            REASON_CLEAR_DATA_INT -> "CLEAR_DATA"                                 // 21

            // App 操作
            NotificationListenerService.REASON_APP_CANCEL -> "APP_CANCEL"         // 8
            NotificationListenerService.REASON_APP_CANCEL_ALL -> "APP_CANCEL_ALL" // 9

            // 監聽器操作
            NotificationListenerService.REASON_LISTENER_CANCEL -> "LISTENER_CANCEL"         // 10
            NotificationListenerService.REASON_LISTENER_CANCEL_ALL -> "LISTENER_CANCEL_ALL" // 11
            REASON_ASSISTANT_CANCEL_INT -> "ASSISTANT_CANCEL"                                // 22

            // 系統操作
            NotificationListenerService.REASON_ERROR -> "ERROR"                              // 4
            REASON_PACKAGE_CHANGED_INT -> "PACKAGE_CHANGED"                                  // 5
            NotificationListenerService.REASON_PACKAGE_BANNED -> "PACKAGE_BANNED"            // 7
            NotificationListenerService.REASON_GROUP_SUMMARY_CANCELED -> "GROUP_SUMMARY_CANCELED" // 12
            NotificationListenerService.REASON_GROUP_OPTIMIZATION -> "GROUP_OPTIMIZATION"    // 13
            NotificationListenerService.REASON_PACKAGE_SUSPENDED -> "PACKAGE_SUSPENDED"      // 14
            NotificationListenerService.REASON_PROFILE_TURNED_OFF -> "PROFILE_TURNED_OFF"   // 15
            NotificationListenerService.REASON_UNINSTALLED -> "UNINSTALLED"                  // 16
            NotificationListenerService.REASON_CHANNEL_BANNED -> "CHANNEL_BANNED"            // 17
            NotificationListenerService.REASON_TIMEOUT -> "TIMEOUT"                          // 19
            REASON_CHANNEL_REMOVED_INT -> "CHANNEL_REMOVED"                                  // 20

            else -> "OTHER"
        }
    }

    /**
     * 移除原因的人類可讀描述（用於 debug log）
     */
    fun getRemovalReasonDescription(reason: Int): String {
        return when (reason) {
            NotificationListenerService.REASON_CLICK -> "使用者點擊通知"
            NotificationListenerService.REASON_CANCEL -> "使用者滑動清除"
            NotificationListenerService.REASON_CANCEL_ALL -> "使用者全部清除"
            NotificationListenerService.REASON_ERROR -> "系統錯誤"
            REASON_PACKAGE_CHANGED_INT -> "App 更新"
            NotificationListenerService.REASON_USER_STOPPED -> "使用者強制停止"
            NotificationListenerService.REASON_PACKAGE_BANNED -> "App 通知被封鎖"
            NotificationListenerService.REASON_APP_CANCEL -> "App 程式取消"
            NotificationListenerService.REASON_APP_CANCEL_ALL -> "App 程式取消全部"
            NotificationListenerService.REASON_LISTENER_CANCEL -> "監聽器取消"
            NotificationListenerService.REASON_LISTENER_CANCEL_ALL -> "監聽器取消全部"
            NotificationListenerService.REASON_GROUP_SUMMARY_CANCELED -> "群組摘要取消"
            NotificationListenerService.REASON_GROUP_OPTIMIZATION -> "群組最佳化"
            NotificationListenerService.REASON_PACKAGE_SUSPENDED -> "App 被暫停"
            NotificationListenerService.REASON_PROFILE_TURNED_OFF -> "工作設定檔關閉"
            NotificationListenerService.REASON_UNINSTALLED -> "App 已解除安裝"
            NotificationListenerService.REASON_CHANNEL_BANNED -> "Channel 被禁用"
            NotificationListenerService.REASON_SNOOZED -> "使用者暫停通知"
            NotificationListenerService.REASON_TIMEOUT -> "超時自動移除"
            REASON_CHANNEL_REMOVED_INT -> "Channel 已移除"
            REASON_CLEAR_DATA_INT -> "使用者清除 App 資料"
            REASON_ASSISTANT_CANCEL_INT -> "數位助理取消"
            else -> "未知 (#$reason)"
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

    // === Audible 推斷 ===

    /**
     * 推斷通知是否產生聲響
     * - API 29+：lastAudiblyAlertedMillis 與 captureTime 差距 ≤ 5 秒 → 確認
     * - API 26-28：importance >= DEFAULT 且非 FLAG_ONLY_ALERT_ONCE 的 UPDATED → 推斷
     * - Pre-26：soundUri 非 null → 推斷
     */
    fun isLikelyAudible(
        lastAudiblyAlertedMillis: Long,
        captureTime: Long,
        importance: Int,
        flags: Int,
        soundUri: String?,
        isUpdate: Boolean
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= API_AUDIBLE_ALERTED) { // API 29+
            lastAudiblyAlertedMillis > 0 && (captureTime - lastAudiblyAlertedMillis) <= 5000
        } else if (Build.VERSION.SDK_INT >= API_NOTIFICATION_CHANNEL) { // API 26-28
            val isDefaultOrHigher = importance >= android.app.NotificationManager.IMPORTANCE_DEFAULT
            val isOnlyAlertOnce = (flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0
            isDefaultOrHigher && !(isUpdate && isOnlyAlertOnce)
        } else { // Pre-26
            soundUri != null
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
