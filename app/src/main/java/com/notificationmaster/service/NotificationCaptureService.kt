package com.notificationmaster.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.room.withTransaction
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.core.NotificationExtractor
import com.notificationmaster.core.cache.PendingIntentCache
import com.notificationmaster.core.alert.PersistentAlertManager
import com.notificationmaster.core.action.ClipboardCopyHelper
import com.notificationmaster.core.content.ExportDetailLevel
import com.notificationmaster.core.content.NotificationContentHelper
import com.notificationmaster.core.filter.ActionType
import com.notificationmaster.core.filter.MatchContext
import com.notificationmaster.core.filter.Matcher
import com.notificationmaster.core.filter.RuleAction
import com.notificationmaster.core.filter.RuleEngine
import com.notificationmaster.core.compat.ApiVersionHelper
import com.notificationmaster.data.db.NotificationDatabase
import com.notificationmaster.data.db.entity.AppSourceEntity
import com.notificationmaster.data.db.entity.ChannelEntity
import com.notificationmaster.data.db.entity.EventType
import com.notificationmaster.data.db.entity.NotificationEntity
import com.notificationmaster.data.db.entity.NotificationEventEntity
import com.notificationmaster.core.capture.DeviceStateCapture
import com.notificationmaster.core.media.MediaExtractor
import com.notificationmaster.core.prefs.AppPreferences
import com.notificationmaster.debug.DebugDumper
import com.notificationmaster.export.calendar.CalendarExporter
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

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
    private lateinit var calendarExporter: CalendarExporter
    private lateinit var alertManager: PersistentAlertManager

    /** 追蹤延遲清除的排程任務，key = notification key */
    private val pendingDismissJobs = ConcurrentHashMap<String, Job>()

    /** 追蹤即時日曆匯出的事件 ID，key = notification key */
    private val calendarExportMap = ConcurrentHashMap<String, Long>()

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
        calendarExporter = CalendarExporter(this)
        val accName = AppPreferences.getRealtimeCalendarAccountName(this)
        val accType = AppPreferences.getRealtimeCalendarAccountType(this)
        if (accName != null && accType != null) {
            calendarExporter.setTargetAccount(accName, accType)
        }
        alertManager = PersistentAlertManager(this)
        RuleEngine.load(this)
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        alertManager.stopAlert()
        pendingDismissJobs.values.forEach { it.cancel() }
        pendingDismissJobs.clear()
        calendarExportMap.clear()
        PendingIntentCache.clear()
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
            } catch (e: Exception) {
                Log.w(TAG, "captureActiveNotifications: failed to get current ranking", e)
                null
            }

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
        PendingIntentCache.clear()
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

        // 取消待處理的延遲清除排程（通知已被移除，無需再清除）
        val dismissKey = ApiVersionHelper.getNotificationKey(sbn)
        pendingDismissJobs.remove(dismissKey)?.cancel()

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

        // 0. 過濾檢查（在 extraction 之前，避免不必要的 IO）
        val filterChannelId = if (ApiVersionHelper.supportsNotificationChannel()) sbn.notification.channelId else null
        val extras = sbn.notification.extras
        val (rankImportance, rankGroupId) = getRankingInfo(ApiVersionHelper.getNotificationKey(sbn), rankingMap)
        val matchCtx = MatchContext(
            packageName = sbn.packageName,
            channelId = filterChannelId,
            eventType = eventType,
            title = extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString(),
            text = extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString(),
            bigText = extras?.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString(),
            subText = extras?.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString(),
            channelImportance = rankImportance,
            channelGroupId = rankGroupId
        )
        if (RuleEngine.matches(ActionType.SKIP_RECORD, matchCtx)) {
            Log.d(TAG, "Filtered: ${sbn.packageName}/$filterChannelId event=$eventType")
            return
        }

        // 1. 提取通知資料
        val entity = extractor.extractNotification(sbn, rankingMap, captureTime)

        // 1.5 UPDATED 事件時，取得前一版本以計算差異
        val contentDiff = if (eventType == EventType.UPDATED) {
            val previous = database.notificationDao().getLatestByKey(entity.notificationKey)
            previous?.let { generateContentDiff(it, entity) }
        } else null

        // 2. 提取 Actions 和媒體（在 transaction 外準備資料）
        val actions = extractor.extractActions(sbn.notification, 0, captureTime)
        val mediaAttachments = try {
            mediaExtractor.extractMedia(sbn.notification, 0, captureTime, sbn.packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract media", e)
            emptyList()
        }
        val deviceState = try {
            deviceStateCapture.capture(0, captureTime)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to capture device state", e)
            null
        }

        // 3. 以 Transaction 寫入核心資料
        val notificationId = database.withTransaction {
            val nId = database.notificationDao().insert(entity)

            if (actions.isNotEmpty()) {
                database.actionDao().insertAll(actions.map { it.copy(notificationId = nId) })
            }
            if (mediaAttachments.isNotEmpty()) {
                database.mediaAttachmentDao().insertAll(mediaAttachments.map { it.copy(notificationId = nId) })
            }
            if (deviceState != null) {
                database.deviceStateDao().insert(deviceState.copy(notificationId = nId))
            }

            val event = NotificationEventEntity(
                notificationId = nId,
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
                contentDiff = contentDiff
            )
            database.notificationEventDao().insert(event)

            nId
        }

        // 3.1 快取 PendingIntent 參照（不需 transaction）
        cachePendingIntents(sbn)

        // 5. 更新 App 來源
        updateAppSource(sbn.packageName, captureTime)

        // 6. 更新 Channel (API 26+)
        if (ApiVersionHelper.supportsNotificationChannel() && entity.channelId != null) {
            // API 28+: 從 Ranking 取得完整 NotificationChannel（正確途徑）
            val notificationChannel: android.app.NotificationChannel? =
                if (ApiVersionHelper.supportsPerson()) {
                    val ranking = Ranking()
                    val key = ApiVersionHelper.getNotificationKey(sbn)
                    if (rankingMap?.getRanking(key, ranking) == true) {
                        ranking.channel
                    } else null
                } else null
            updateChannel(sbn.packageName, entity.channelId, captureTime, notificationChannel)
        }

        // 7. 各 ActionType 獨立判斷 eventType 是否在允許範圍
        if (eventType in ActionType.AUTO_DISMISS.allowedEventTypes) {
            checkAutoDismiss(sbn, rankingMap, eventType)
        }
        if (eventType in ActionType.CALENDAR_EXPORT.allowedEventTypes) {
            checkRealtimeCalendarExport(entity, matchCtx)
        }
        if (eventType in ActionType.PERSISTENT_ALERT.allowedEventTypes) {
            checkPersistentAlert(entity, matchCtx)
        }
        if (eventType in ActionType.CLIPBOARD_COPY.allowedEventTypes) {
            checkClipboardCopy(entity, matchCtx)
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

        // 過濾檢查（被過濾的通知仍需清理 PendingIntentCache）
        val filterChannelId: String? = if (ApiVersionHelper.supportsNotificationChannel()) sbn.notification.channelId else null
        val removalExtras = sbn.notification.extras

        // 從 DB 的 ChannelEntity 取得 channel 屬性（removal 時 rankingMap 中該 key 可能已被移除）
        val channelEntity = if (filterChannelId != null) {
            database.channelDao().getByPackageAndChannelId(sbn.packageName, filterChannelId)
        } else null

        val removalMatchCtx = MatchContext(
            packageName = sbn.packageName,
            channelId = filterChannelId,
            eventType = EventType.REMOVED,
            title = removalExtras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString(),
            text = removalExtras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString(),
            bigText = removalExtras?.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString(),
            subText = removalExtras?.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString(),
            channelImportance = channelEntity?.importance,
            channelGroupId = channelEntity?.groupId
        )
        if (RuleEngine.matches(ActionType.SKIP_RECORD, removalMatchCtx)) {
            PendingIntentCache.remove(key)
            return
        }

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
                contentDiff = null
            )
            database.notificationEventDao().insert(event)

            Log.d(TAG, "Recorded removal event for: $key, reason: $reason")
        } else {
            Log.w(TAG, "Removal event for unknown notification: $key")
        }

        // 即時日曆匯出 — 更新結束時間
        checkRealtimeCalendarRemoval(
            key, captureTime, sbn.packageName,
            if (ApiVersionHelper.supportsNotificationChannel()) sbn.notification.channelId else null
        )

        PendingIntentCache.remove(key)
    }

    /**
     * 處理 Ranking 更新
     */
    private suspend fun processRankingUpdate(rankingMap: RankingMap) {
        // Ranking 詳細資訊（rank, importance, isAmbient）需要 API 24+
        if (!ApiVersionHelper.supportsDirectReply()) return  // Ranking 需要 API 24+

        val captureTime = System.currentTimeMillis()

        // 對每個在 ranking 中的通知記錄 RANKING 事件
        val keys = rankingMap.orderedKeys
        for (key in keys) {
            val ranking = Ranking()
            if (rankingMap.getRanking(key, ranking)) {
                val latestNotification = database.notificationDao().getLatestByKey(key)

                if (latestNotification != null) {
                    // 過濾檢查
                    val channelEntity = latestNotification.channelId?.let { chId ->
                        database.channelDao().getByPackageAndChannelId(latestNotification.packageName, chId)
                    }
                    if (RuleEngine.matches(
                            ActionType.SKIP_RECORD,
                            MatchContext(
                                packageName = latestNotification.packageName,
                                channelId = latestNotification.channelId,
                                eventType = EventType.RANKING,
                                title = latestNotification.title,
                                text = latestNotification.text,
                                bigText = latestNotification.bigText,
                                subText = latestNotification.subText,
                                channelImportance = channelEntity?.importance,
                                channelGroupId = channelEntity?.groupId
                            )
                        )) continue

                    val newRank = ranking.rank
                    val newImportance = ranking.importance
                    val newIsAmbient = ranking.isAmbient
                    val newIsSuspended = if (ApiVersionHelper.supportsPerson()) ranking.isSuspended else false
                    val newSuppressedVisualEffects = ranking.suppressedVisualEffects

                    // 計算 ranking diff
                    val diff = JSONObject()
                    if (latestNotification.rankingRank != newRank) {
                        diff.put("rankingRank", JSONObject().put("old", latestNotification.rankingRank).put("new", newRank))
                    }
                    if (latestNotification.importance != newImportance) {
                        diff.put("importance", JSONObject().put("old", latestNotification.importance).put("new", newImportance))
                    }
                    if (latestNotification.isAmbient != newIsAmbient) {
                        diff.put("isAmbient", JSONObject().put("old", latestNotification.isAmbient).put("new", newIsAmbient))
                    }
                    if (latestNotification.isSuspended != newIsSuspended) {
                        diff.put("isSuspended", JSONObject().put("old", latestNotification.isSuspended).put("new", newIsSuspended))
                    }
                    if (latestNotification.suppressedVisualEffects != newSuppressedVisualEffects) {
                        diff.put("suppressedVisualEffects", JSONObject().put("old", latestNotification.suppressedVisualEffects).put("new", newSuppressedVisualEffects))
                    }

                    // 只在有變化時記錄
                    if (diff.length() > 0) {
                        val event = NotificationEventEntity(
                            notificationId = latestNotification.id,
                            notificationKey = key,
                            eventType = EventType.RANKING,
                            eventTime = captureTime,
                            removalReason = null,
                            removalReasonCategory = null,
                            rankingRank = newRank,
                            rankingImportance = newImportance,
                            isAmbient = newIsAmbient,
                            isSuspended = newIsSuspended,
                            suppressedVisualEffects = newSuppressedVisualEffects,
                            contentDiff = diff.toString()
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
                versionCode = if (ApiVersionHelper.supportsPerson()) {  // longVersionCode API 28+
                    packageInfo?.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo?.versionCode?.toLong()
                },
                iconPath = saveAppIcon(packageName),
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
     * 儲存 App 圖示到媒體目錄
     *
     * 以 Bitmap hash 去重：相同圖示不重複寫入。
     * @return 相對路徑（media/xxx.png）或 null
     */
    private fun saveAppIcon(packageName: String): String? {
        return try {
            val drawable = packageManager.getApplicationIcon(packageName)
            val width = drawable.intrinsicWidth.coerceAtLeast(48)
            val height = drawable.intrinsicHeight.coerceAtLeast(48)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)

            // Hash 去重
            val bytes = bitmap.rowBytes * bitmap.height
            val buffer = java.nio.ByteBuffer.allocate(bytes)
            bitmap.copyPixelsToBuffer(buffer)
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(buffer.array())
                .joinToString("") { "%02x".format(it) }
                .substring(0, 16)

            val fileName = "${packageName}_APP_ICON_${hash}.png"
            val mediaDir = File(MediaExtractor.getMediaBaseDir(this), "media").apply { mkdirs() }
            val file = File(mediaDir, fileName)
            val relativePath = "media/$fileName"

            if (!file.exists()) {
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
            bitmap.recycle()

            relativePath
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save app icon for $packageName", e)
            null
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
        if (!ApiVersionHelper.supportsNotificationChannel()) return

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
                    canBubble = if (ApiVersionHelper.supportsBubbles()) notificationChannel.canBubble() else false,
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
                canBubble = if (ApiVersionHelper.supportsBubbles()) {
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

    /**
     * 快取通知中的 PendingIntent 參照
     */
    private fun cachePendingIntents(sbn: StatusBarNotification) {
        val notification = sbn.notification
        val key = ApiVersionHelper.getNotificationKey(sbn)

        val actionIntents = mutableMapOf<Int, android.app.PendingIntent>()
        notification.actions?.forEachIndexed { index, action ->
            action.actionIntent?.let { actionIntents[index] = it }
        }

        PendingIntentCache.put(key, PendingIntentCache.IntentSet(
            contentIntent = notification.contentIntent,
            deleteIntent = notification.deleteIntent,
            fullScreenIntent = notification.fullScreenIntent,
            actionIntents = actionIntents
        ))
    }

    /**
     * 比對兩個 NotificationEntity，產生變動內容 JSON
     * 僅記錄有變化的欄位，格式：{"欄位": {"old": 舊值, "new": 新值}}
     * 無變動時回傳 null
     */
    private fun generateContentDiff(
        old: NotificationEntity,
        new: NotificationEntity
    ): String? {
        val diff = JSONObject()

        fun diffString(key: String, oldVal: String?, newVal: String?) {
            if (oldVal != newVal) {
                diff.put(key, JSONObject().put("old", oldVal ?: JSONObject.NULL).put("new", newVal ?: JSONObject.NULL))
            }
        }

        fun diffInt(key: String, oldVal: Int, newVal: Int) {
            if (oldVal != newVal) {
                diff.put(key, JSONObject().put("old", oldVal).put("new", newVal))
            }
        }

        fun diffLong(key: String, oldVal: Long, newVal: Long) {
            if (oldVal != newVal) {
                diff.put(key, JSONObject().put("old", oldVal).put("new", newVal))
            }
        }

        fun diffBool(key: String, oldVal: Boolean, newVal: Boolean) {
            if (oldVal != newVal) {
                diff.put(key, JSONObject().put("old", oldVal).put("new", newVal))
            }
        }

        // 基本內容
        diffString("title", old.title, new.title)
        diffString("text", old.text, new.text)
        diffString("bigText", old.bigText, new.bigText)
        diffString("bigTitle", old.bigTitle, new.bigTitle)
        diffString("subText", old.subText, new.subText)
        diffString("infoText", old.infoText, new.infoText)
        diffString("summaryText", old.summaryText, new.summaryText)
        diffString("tickerText", old.tickerText, new.tickerText)

        // 進度
        diffInt("progress", old.progress, new.progress)
        diffInt("progressMax", old.progressMax, new.progressMax)
        diffBool("progressIndeterminate", old.progressIndeterminate, new.progressIndeterminate)

        // MessagingStyle
        diffString("conversationTitle", old.conversationTitle, new.conversationTitle)
        diffBool("isGroupConversation", old.isGroupConversation, new.isGroupConversation)

        // 樣式模板
        diffString("template", old.template, new.template)

        // 通知屬性
        diffInt("flags", old.flags, new.flags)
        diffInt("priority", old.priority, new.priority)
        diffInt("visibility", old.visibility, new.visibility)
        diffString("category", old.category, new.category)
        diffInt("color", old.color, new.color)
        diffString("groupKey", old.groupKey, new.groupKey)
        diffString("sortKey", old.sortKey, new.sortKey)
        diffLong("whenTime", old.whenTime, new.whenTime)

        // Channel
        diffString("channelId", old.channelId, new.channelId)

        // 動作數量
        diffInt("actionCount", old.actionCount, new.actionCount)

        // 自訂 View
        diffBool("hasCustomContentView", old.hasCustomContentView, new.hasCustomContentView)
        diffBool("hasCustomBigContentView", old.hasCustomBigContentView, new.hasCustomBigContentView)

        return if (diff.length() > 0) diff.toString() else null
    }

    /**
     * 從 RankingMap 取得指定通知的 importance 和 channel groupId
     */
    private fun getRankingInfo(key: String, rankingMap: RankingMap?): Pair<Int?, String?> {
        if (rankingMap == null) return null to null
        val ranking = Ranking()
        if (!rankingMap.getRanking(key, ranking)) return null to null
        // Ranking.importance requires API 24+, Ranking.channel requires API 28+
        val importance = if (ApiVersionHelper.supportsDirectReply()) ranking.importance else null
        val groupId = if (ApiVersionHelper.supportsPerson()) ranking.channel?.group else null
        return importance to groupId
    }

    /**
     * 回找已匯出的日曆事件 ID（記憶體映射 → CalendarContract 查詢）
     * @param remove 是否從映射中移除（REMOVED 時為 true）
     */
    private fun resolveCalendarEventId(notificationKey: String, remove: Boolean): Long {
        val eventId = if (remove) calendarExportMap.remove(notificationKey) else calendarExportMap[notificationKey]
        if (eventId != null) return eventId

        val calendarId = AppPreferences.getRealtimeCalendarId(this)
        if (calendarId < 0) return -1L

        val recovered = calendarExporter.findEventByNotificationKey(calendarId, notificationKey)
        if (recovered > 0 && !remove) {
            calendarExportMap[notificationKey] = recovered
        }
        return recovered
    }

    /**
     * 檢查是否需要即時匯出到日曆
     * 條件：即時匯出已啟用 + CALENDAR_EXPORT 白名單匹配
     * UPDATED 時更新既有事件內容，不建立重複事件
     */
    private fun checkRealtimeCalendarExport(
        entity: NotificationEntity,
        matchCtx: MatchContext
    ) {
        if (!AppPreferences.isRealtimeCalendarEnabled(this)) return

        val calendarId = AppPreferences.getRealtimeCalendarId(this)
        if (calendarId < 0) return

        // 只匯出白名單匹配的通知
        val whitelistRules = RuleEngine.getRules(ActionType.CALENDAR_EXPORT)
        if (whitelistRules.isEmpty()) return  // 無白名單 → 不匯出（避免大量灌入）

        if (!RuleEngine.matches(ActionType.CALENDAR_EXPORT, matchCtx)) return

        val key = entity.notificationKey
        val existingEventId = resolveCalendarEventId(key, remove = false)

        if (existingEventId > 0) {
            // UPDATED: 更新既有事件內容
            calendarExporter.updateCalendarEventContent(existingEventId, entity, ExportDetailLevel.FULL)
        } else {
            // 首次 POSTED: 插入新事件並記錄映射
            val eventId = calendarExporter.exportSingleNotification(entity, calendarId, ExportDetailLevel.FULL)
            if (eventId > 0) {
                calendarExportMap[key] = eventId
                Log.d(TAG, "Realtime calendar export: ${entity.packageName}")
            }
        }
    }

    /**
     * 通知移除時更新對應日曆事件的結束時間
     * 輕量級檢查：packageName/channelId 匹配 + EventTypes 包含 REMOVED
     */
    private fun checkRealtimeCalendarRemoval(
        notificationKey: String,
        removalTime: Long,
        packageName: String,
        channelId: String?
    ) {
        if (!AppPreferences.isRealtimeCalendarEnabled(this)) return

        val eventId = resolveCalendarEventId(notificationKey, remove = true)
        if (eventId < 0) return

        // 輕量級規則檢查：只比對 package/channel + EventTypes
        val shouldUpdate = RuleEngine.getRules(ActionType.CALENDAR_EXPORT).any { rule ->
            rule.packageName == packageName &&
            (rule.channelId == null || rule.channelId == channelId) &&
            EventType.REMOVED.name in rule.eventTypes
        }
        if (!shouldUpdate) return

        calendarExporter.updateCalendarEventEndTime(eventId, removalTime)
        Log.d(TAG, "Updated calendar event end time for: $notificationKey")
    }

    /**
     * 檢查是否需要觸發持續提醒
     *
     * 僅 POSTED/UPDATED 觸發（在 processNotification 的 eventType != INITIAL 區塊呼叫）。
     */
    private fun checkPersistentAlert(entity: NotificationEntity, matchCtx: MatchContext) {
        val rule = RuleEngine.findMatchingRule(ActionType.PERSISTENT_ALERT, matchCtx) ?: return
        val action = rule.action as RuleAction.PersistentAlert
        val title = "[${NotificationContentHelper.appName(entity.packageName)}] ${NotificationContentHelper.displayTitle(entity)}"
        alertManager.startAlert(entity.notificationKey, title, entity.text, action.soundUri, action.vibrate)
    }

    /**
     * 檢查是否需要複製通知內容到剪貼簿
     *
     * 無 regex：複製 title + 最完整內容（bigText ?: text）
     * 有 regex：對每個匹配欄位提取所有 capture group，各欄位獨立複製到剪貼簿
     */
    private suspend fun checkClipboardCopy(entity: NotificationEntity, matchCtx: MatchContext) {
        val rule = RuleEngine.findMatchingRule(ActionType.CLIPBOARD_COPY, matchCtx) ?: return
        val keywordMatcher = rule.matchers.filterIsInstance<Matcher.Keyword>().firstOrNull()
        // ClipboardManager 操作需在主線程
        val count = kotlinx.coroutines.withContext(Dispatchers.Main) {
            ClipboardCopyHelper.copyFromMatchContext(
                this@NotificationCaptureService, matchCtx.title, matchCtx.text, matchCtx.bigText, matchCtx.subText, keywordMatcher
            )
        }
        if (count > 0) Log.d(TAG, "Clipboard copy: $count entries from ${entity.packageName}")
    }

    /**
     * 停止持續提醒（供 AlertStopReceiver 呼叫）
     */
    fun stopPersistentAlert() {
        alertManager.stopAlert()
    }

    /**
     * 檢查是否需要自動清除通知
     *
     * 通知已記錄到 DB 後呼叫，僅影響狀態列顯示。
     * POSTED 和 UPDATED 都檢查；UPDATED 時重設延遲計時器。
     */
    private fun checkAutoDismiss(sbn: StatusBarNotification, rankingMap: RankingMap?, eventType: EventType) {
        val key = ApiVersionHelper.getNotificationKey(sbn)
        val channelId = if (ApiVersionHelper.supportsNotificationChannel())
            sbn.notification.channelId else null

        // 建構含 content + channel 屬性的 MatchContext
        val dismissExtras = sbn.notification.extras
        val (dismissImportance, dismissGroupId) = getRankingInfo(key, rankingMap)
        val dismissMatchCtx = MatchContext(
            packageName = sbn.packageName,
            channelId = channelId,
            eventType = eventType,
            title = dismissExtras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString(),
            text = dismissExtras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString(),
            bigText = dismissExtras?.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString(),
            subText = dismissExtras?.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString(),
            channelImportance = dismissImportance,
            channelGroupId = dismissGroupId
        )

        val rule = RuleEngine.findMatchingRule(
            ActionType.AUTO_DISMISS, dismissMatchCtx
        ) ?: return

        // 取消既有排程（UPDATED 時重設計時器）
        pendingDismissJobs.remove(key)?.cancel()

        val delayMs = (rule.action as RuleAction.AutoDismiss).delayMs
        if (delayMs <= 0) {
            try {
                cancelNotification(key)
                Log.d(TAG, "Auto-dismissed notification immediately: $key")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to auto-dismiss notification: $key", e)
            }
        } else {
            pendingDismissJobs[key] = serviceScope.launch {
                delay(delayMs)
                // 確認通知仍在活躍列表中
                val active = try { activeNotifications } catch (e: Exception) { null }
                if (active?.any { ApiVersionHelper.getNotificationKey(it) == key } == true) {
                    try {
                        cancelNotification(key)
                        Log.d(TAG, "Auto-dismissed notification after ${delayMs}ms: $key")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to auto-dismiss notification: $key", e)
                    }
                }
                pendingDismissJobs.remove(key)
            }
        }
    }
}
