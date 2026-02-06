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
            try {
                val activeNotifications = activeNotifications ?: emptyArray()
                Log.i(TAG, "Processing ${activeNotifications.size} existing notifications")

                activeNotifications.forEach { sbn ->
                    processNotification(sbn, EventType.INITIAL, getCurrentRanking())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing existing notifications", e)
            }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "Listener disconnected")
        isConnected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        onNotificationPosted(sbn, getCurrentRanking())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        super.onNotificationPosted(sbn, rankingMap)
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

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        onNotificationRemoved(sbn, getCurrentRanking(), REASON_CANCEL)
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap?,
        reason: Int
    ) {
        super.onNotificationRemoved(sbn, rankingMap, reason)
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
        super.onNotificationRankingUpdate(rankingMap)
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
            updateChannel(sbn.packageName, entity.channelId, captureTime)
        }

        Log.d(TAG, "Saved notification: $notificationId, event: $eventType")
    }

    /**
     * 處理通知移除
     */
    private suspend fun processRemoval(
        sbn: StatusBarNotification,
        reason: Int,
        rankingMap: RankingMap?
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
     */
    private suspend fun updateChannel(packageName: String, channelId: String, captureTime: Long) {
        if (Build.VERSION.SDK_INT < 26) return

        val existing = database.channelDao().getByPackageAndChannelId(packageName, channelId)

        if (existing != null) {
            // 增加計數
            database.channelDao().incrementNotificationCount(packageName, channelId, captureTime)
        } else {
            // 取得 Channel 資訊
            val nm = getSystemService(android.app.NotificationManager::class.java)
            val channels = nm?.getNotificationChannels() ?: emptyList()
            val channel = channels.find { it.id == channelId }

            // 取得或建立 AppSource
            val appSource = database.appSourceDao().getByPackageName(packageName)
                ?: return

            val channelEntity = ChannelEntity(
                appSourceId = appSource.id,
                packageName = packageName,
                channelId = channelId,
                channelName = channel?.name?.toString(),
                description = channel?.description,
                importance = channel?.importance ?: android.app.NotificationManager.IMPORTANCE_DEFAULT,
                groupId = channel?.group,
                showBadge = channel?.canShowBadge() ?: true,
                canBubble = if (Build.VERSION.SDK_INT >= 29) channel?.canBubble() ?: false else false,
                soundUri = channel?.sound?.toString(),
                vibratePattern = channel?.vibrationPattern?.let {
                    org.json.JSONArray(it.toList()).toString()
                },
                lightColor = channel?.lightColor ?: 0,
                lockScreenVisibility = channel?.lockscreenVisibility
                    ?: android.app.Notification.VISIBILITY_PRIVATE,
                isBlocked = channel?.importance == android.app.NotificationManager.IMPORTANCE_NONE,
                firstSeen = captureTime,
                lastUpdated = captureTime,
                notificationCount = 1
            )
            database.channelDao().insert(channelEntity)
        }
    }
}
