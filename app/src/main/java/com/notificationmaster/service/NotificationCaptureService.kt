package com.notificationmaster.service

import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.core.NotificationExtractor
import com.notificationmaster.core.compat.ApiVersionHelper
import com.notificationmaster.data.db.NotificationDatabase
import com.notificationmaster.data.db.entity.AppSourceEntity
import com.notificationmaster.data.db.entity.ChannelEntity
import com.notificationmaster.data.db.entity.EventType
import com.notificationmaster.data.db.entity.NotificationEventEntity
import com.notificationmaster.core.capture.DeviceStateCapture
import com.notificationmaster.core.media.MediaExtractor
import com.notificationmaster.debug.DebugDumper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 通知擷取服務
 * 繼承 NotificationListenerService，攔截所有系統通知
 */
class NotificationCaptureService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var database: NotificationDatabase
    private lateinit var extractor: NotificationExtractor
    private lateinit var debugDumper: DebugDumper
    private lateinit var deviceStateCapture: DeviceStateCapture
    private lateinit var mediaExtractor: MediaExtractor

    companion object {
        private const val TAG = "NotificationCapture"

        @Volatile
        var isConnected = false
            private set

        // 用於 UI 層查詢服務狀態
        private var instance: NotificationCaptureService? = null

        fun getInstance(): NotificationCaptureService? = instance
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        database = NotificationMasterApp.getInstance().database
        extractor = NotificationExtractor(this)
        debugDumper = DebugDumper(this)
        deviceStateCapture = DeviceStateCapture(this)
        mediaExtractor = MediaExtractor(this)
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        instance = null
        isConnected = false
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "Service bound")
        return super.onBind(intent)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Listener connected")
        isConnected = true

        // 擷取所有現有通知（標記為 INITIAL）
        serviceScope.launch {
            val activeNotifications = try {
                activeNotifications ?: emptyArray()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get active notifications", e)
                emptyArray()
            }
            Log.i(TAG, "Processing ${activeNotifications.size} existing notifications")

            val rankingMap = try {
                getCurrentRanking()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get current ranking", e)
                null
            }

            var successCount = 0
            var failCount = 0
            for (sbn in activeNotifications) {
                try {
                    processNotification(sbn, EventType.INITIAL, rankingMap)
                    successCount++
                } catch (e: Exception) {
                    failCount++
                    Log.e(TAG, "Error processing notification: ${sbn.packageName} key=${ApiVersionHelper.getNotificationKey(sbn)}", e)
                }
            }
            Log.i(TAG, "Initial capture complete: $successCount success, $failCount failed")
        }
    }

    /**
     * 手動擷取目前所有活躍通知
     * 供 UI 層在下拉刷新時呼叫，避免 onListenerConnected 未觸發時無資料
     * 會跳過已存在的通知（以 notificationKey 判斷）
     */
    fun captureActiveNotifications() {
        if (!isConnected) {
            Log.w(TAG, "captureActiveNotifications: service not connected, skip")
            return
        }
        serviceScope.launch {
            val notifications = try {
                activeNotifications ?: emptyArray()
            } catch (e: Exception) {
                Log.e(TAG, "captureActiveNotifications: failed to get active notifications", e)
                emptyArray()
            }
            Log.i(TAG, "captureActiveNotifications: ${notifications.size} active notifications")
            if (notifications.isEmpty()) return@launch

            val rankingMap = try {
                getCurrentRanking()
            } catch (e: Exception) { null }

            var newCount = 0
            var skipCount = 0
            for (sbn in notifications) {
                try {
                    val key = ApiVersionHelper.getNotificationKey(sbn)
                    if (database.notificationDao().existsByKey(key)) {
                        skipCount++
                    } else {
                        processNotification(sbn, EventType.INITIAL, rankingMap)
                        newCount++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "captureActiveNotifications: error", e)
                }
            }
            Log.i(TAG, "captureActiveNotifications complete: $newCount new, $skipCount skipped")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "Listener disconnected")
        isConnected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        Log.d(TAG, "Notification posted: ${sbn.packageName} - ${ApiVersionHelper.getNotificationKey(sbn)}")

        serviceScope.launch {
            try {
                // 檢查是否為更新
                val key = ApiVersionHelper.getNotificationKey(sbn)
                val isUpdate = database.notificationDao().existsByKey(key)
                val eventType = if (isUpdate) EventType.UPDATED else EventType.POSTED

                processNotification(sbn, eventType, rankingMap)

                // Debug dump
                debugDumper.dumpNotification(sbn, eventType.name)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing posted notification", e)
            }
        }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap?,
        reason: Int
    ) {
        Log.d(TAG, "Notification removed: ${sbn.packageName} - reason: $reason (${ApiVersionHelper.categorizeRemovalReason(reason)})")

        serviceScope.launch {
            try {
                processRemoval(sbn, reason, rankingMap)

                // Debug dump
                debugDumper.dumpRemoval(sbn, reason)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing removed notification", e)
            }
        }
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap) {
        Log.d(TAG, "Ranking update received")

        serviceScope.launch {
            try {
                processRankingUpdate(rankingMap)

                // Debug dump
                debugDumper.dumpRankingUpdate(rankingMap)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing ranking update", e)
            }
        }
    }

    // === 內部處理方法 ===

    /**
     * 處理通知（INITIAL/POSTED/UPDATED）
     */
    private suspend fun processNotification(
        sbn: StatusBarNotification,
        eventType: EventType,
        rankingMap: RankingMap?
    ) {
        val captureTime = System.currentTimeMillis()

        // 1. 提取通知資料
        val entity = extractor.extractNotification(sbn, rankingMap, captureTime)

        // 2. 儲存通知記錄
        val notificationId = database.notificationDao().insert(entity)

        // 3. 提取並儲存 Actions
        val actions = extractor.extractActions(sbn.notification, notificationId, captureTime)
        if (actions.isNotEmpty()) {
            database.actionDao().insertAll(actions)
        }

        // 3.5 提取並儲存媒體附件
        try {
            val mediaAttachments = mediaExtractor.extractMedia(sbn.notification, notificationId, captureTime)
            if (mediaAttachments.isNotEmpty()) {
                database.mediaAttachmentDao().insertAll(mediaAttachments)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract media", e)
        }

        // 3.6 記錄裝置狀態快照
        try {
            val deviceState = deviceStateCapture.capture(notificationId, captureTime)
            database.deviceStateDao().insert(deviceState)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to capture device state", e)
        }

        // 4. 記錄事件
        val event = NotificationEventEntity(
            notificationId = notificationId,
            notificationKey = entity.notificationKey,
            eventType = eventType,
            eventTime = captureTime,
            removalReason = null,
            removalReasonCategory = null,
            rankingRank = entity.rankingRank.takeIf { it >= 0 },
            rankingImportance = entity.importance.takeIf { it >= 0 },
            isAmbient = entity.isAmbient,
            isSuspended = entity.isSuspended,
            suppressedVisualEffects = entity.suppressedVisualEffects,
            contentSnapshot = extractor.generateContentSnapshot(sbn.notification)
        )
        database.notificationEventDao().insert(event)

        // 5. 更新 App 來源
        updateAppSource(sbn.packageName, captureTime)

        // 6. 更新 Channel (API 26+)
        if (Build.VERSION.SDK_INT >= 26 && entity.channelId != null) {
            // API 28+: 從 Ranking 取得完整 NotificationChannel（正確途徑）
            val notificationChannel: android.app.NotificationChannel? =
                if (Build.VERSION.SDK_INT >= 28) {
                    val ranking = Ranking()
                    val key = ApiVersionHelper.getNotificationKey(sbn)
                    if (rankingMap?.getRanking(key, ranking) == true) {
                        ranking.channel
                    } else null
                } else null
            updateChannel(sbn.packageName, entity.channelId, captureTime, notificationChannel)
        }

        Log.d(TAG, "Saved notification: $notificationId, event: $eventType")
    }

    /**
     * 處理通知移除
     */
    private suspend fun processRemoval(
        sbn: StatusBarNotification,
        reason: Int,
        @Suppress("UNUSED_PARAMETER") rankingMap: RankingMap?
    ) {
        val captureTime = System.currentTimeMillis()
        val key = ApiVersionHelper.getNotificationKey(sbn)

        // 取得最新的通知記錄
        val latestNotification = database.notificationDao().getLatestByKey(key)

        if (latestNotification != null) {
            // 記錄移除事件
            val event = NotificationEventEntity(
                notificationId = latestNotification.id,
                notificationKey = key,
                eventType = EventType.REMOVED,
                eventTime = captureTime,
                removalReason = reason,
                removalReasonCategory = ApiVersionHelper.categorizeRemovalReason(reason),
                rankingRank = null,
                rankingImportance = null,
                isAmbient = null,
                isSuspended = null,
                suppressedVisualEffects = null,
                contentSnapshot = null
            )
            database.notificationEventDao().insert(event)

            Log.d(TAG, "Recorded removal event for: $key, reason: $reason")
        } else {
            Log.w(TAG, "Removal event for unknown notification: $key")
        }
    }

    /**
     * 處理 Ranking 更新
     */
    private suspend fun processRankingUpdate(rankingMap: RankingMap) {
        // Ranking 詳細資訊（rank, importance, isAmbient）需要 API 24+
        if (Build.VERSION.SDK_INT < 24) return

        val captureTime = System.currentTimeMillis()

        // 對每個在 ranking 中的通知記錄 RANKING 事件
        val keys = rankingMap.orderedKeys
        for (key in keys) {
            val ranking = Ranking()
            if (rankingMap.getRanking(key, ranking)) {
                val latestNotification = database.notificationDao().getLatestByKey(key)

                if (latestNotification != null) {
                    // 只在有變化時記錄
                    val hasChanges = latestNotification.rankingRank != ranking.rank ||
                            latestNotification.importance != ranking.importance ||
                            latestNotification.isAmbient != ranking.isAmbient

                    if (hasChanges) {
                        val event = NotificationEventEntity(
                            notificationId = latestNotification.id,
                            notificationKey = key,
                            eventType = EventType.RANKING,
                            eventTime = captureTime,
                            removalReason = null,
                            removalReasonCategory = null,
                            rankingRank = ranking.rank,
                            rankingImportance = ranking.importance,
                            isAmbient = ranking.isAmbient,
                            isSuspended = if (Build.VERSION.SDK_INT >= 28) ranking.isSuspended else false,
                            suppressedVisualEffects = if (Build.VERSION.SDK_INT >= 24) {
                                ranking.suppressedVisualEffects
                            } else 0,
                            contentSnapshot = null
                        )
                        database.notificationEventDao().insert(event)
                    }
                }
            }
        }
    }

    /**
     * 更新 App 來源記錄
     */
    private suspend fun updateAppSource(packageName: String, captureTime: Long) {
        val existing = database.appSourceDao().getByPackageName(packageName)

        if (existing != null) {
            // 增加計數
            database.appSourceDao().incrementNotificationCount(packageName, captureTime)
        } else {
            // 新增 App 來源
            val appInfo = try {
                packageManager.getApplicationInfo(packageName, 0)
            } catch (e: Exception) {
                null
            }

            val packageInfo = try {
                packageManager.getPackageInfo(packageName, 0)
            } catch (e: Exception) {
                null
            }

            val appSource = AppSourceEntity(
                packageName = packageName,
                appName = appInfo?.let { packageManager.getApplicationLabel(it).toString() },
                versionName = packageInfo?.versionName,
                versionCode = if (Build.VERSION.SDK_INT >= 28) {
                    packageInfo?.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo?.versionCode?.toLong()
                },
                iconPath = null, // TODO: 儲存圖示
                firstSeen = captureTime,
                lastUpdated = captureTime,
                notificationCount = 1,
                isSystemApp = appInfo?.let {
                    (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                } ?: false,
                isDisabled = false,
                isUninstalled = false
            )
            database.appSourceDao().insert(appSource)
        }
    }

    /**
     * 更新 Channel 記錄 (API 26+)
     *
     * @param notificationChannel 從 Ranking.getChannel() 取得的 NotificationChannel（API 28+），
     *                            API 26-27 為 null，僅能儲存 channelId 和 importance。
     */
    private suspend fun updateChannel(
        packageName: String,
        channelId: String,
        captureTime: Long,
        notificationChannel: android.app.NotificationChannel? = null
    ) {
        if (Build.VERSION.SDK_INT < 26) return

        val existing = database.channelDao().getByPackageAndChannelId(packageName, channelId)

        if (existing != null) {
            if (notificationChannel != null) {
                // API 28+: 有完整 channel 資訊，同時更新 metadata 和計數
                database.channelDao().updateChannelInfoAndIncrement(
                    packageName = packageName,
                    channelId = channelId,
                    channelName = notificationChannel.name?.toString(),
                    description = notificationChannel.description,
                    importance = notificationChannel.importance,
                    groupId = notificationChannel.group,
                    showBadge = notificationChannel.canShowBadge(),
                    canBubble = if (Build.VERSION.SDK_INT >= 29) notificationChannel.canBubble() else false,
                    soundUri = notificationChannel.sound?.toString(),
                    vibratePattern = notificationChannel.vibrationPattern?.let {
                        org.json.JSONArray(it.toList()).toString()
                    },
                    lightColor = notificationChannel.lightColor,
                    lockScreenVisibility = notificationChannel.lockscreenVisibility,
                    isBlocked = notificationChannel.importance == android.app.NotificationManager.IMPORTANCE_NONE,
                    updateTime = captureTime
                )
            } else {
                // API 26-27: 僅遞增計數
                database.channelDao().incrementNotificationCount(packageName, channelId, captureTime)
            }
        } else {
            // 新增 Channel 記錄
            val appSource = database.appSourceDao().getByPackageName(packageName) ?: return

            val channelEntity = ChannelEntity(
                appSourceId = appSource.id,
                packageName = packageName,
                channelId = channelId,
                channelName = notificationChannel?.name?.toString(),
                description = notificationChannel?.description,
                importance = notificationChannel?.importance
                    ?: android.app.NotificationManager.IMPORTANCE_DEFAULT,
                groupId = notificationChannel?.group,
                showBadge = notificationChannel?.canShowBadge() ?: true,
                canBubble = if (Build.VERSION.SDK_INT >= 29) {
                    notificationChannel?.canBubble() ?: false
                } else false,
                soundUri = notificationChannel?.sound?.toString(),
                vibratePattern = notificationChannel?.vibrationPattern?.let {
                    org.json.JSONArray(it.toList()).toString()
                },
                lightColor = notificationChannel?.lightColor ?: 0,
                lockScreenVisibility = notificationChannel?.lockscreenVisibility
                    ?: android.app.Notification.VISIBILITY_PRIVATE,
                isBlocked = notificationChannel?.importance == android.app.NotificationManager.IMPORTANCE_NONE,
                firstSeen = captureTime,
                lastUpdated = captureTime,
                notificationCount = 1
            )
            database.channelDao().insert(channelEntity)
        }
    }
}
