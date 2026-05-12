package com.notificationmaster.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.room.withTransaction
import com.notificationmaster.R
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.ui.widget.NotificationWidgetProvider
import com.notificationmaster.core.InitialReconciler
import com.notificationmaster.core.NotificationExtractor
import com.notificationmaster.core.RankingProcessor
import com.notificationmaster.core.cache.PendingIntentCache
import com.notificationmaster.core.alert.AlertData
import com.notificationmaster.core.alert.PersistentAlertManager
import com.notificationmaster.core.action.ClipboardCopyHelper
import com.notificationmaster.core.content.ExportDetailLevel
import com.notificationmaster.core.content.NotificationContentHelper
import com.notificationmaster.core.filter.ActionType
import com.notificationmaster.core.filter.MatchContext
import com.notificationmaster.core.filter.Matcher
import com.notificationmaster.core.filter.RuleAction
import com.notificationmaster.core.filter.RuleEngine
import com.notificationmaster.core.filter.RuleRepository
import com.notificationmaster.core.compat.ApiVersionHelper
import com.notificationmaster.data.db.NotificationDatabase
import com.notificationmaster.data.db.entity.AppSourceEntity
import com.notificationmaster.data.db.entity.ChannelEntity
import com.notificationmaster.data.db.entity.EventType
import com.notificationmaster.data.db.entity.NotificationEventEntity
import com.notificationmaster.data.db.entity.NotificationRecordEntity
import com.notificationmaster.data.db.entity.ObservationSource
import com.notificationmaster.core.capture.DeviceStateCapture
import com.notificationmaster.core.media.MediaExtractor
import com.notificationmaster.core.prefs.AppPreferences
import com.notificationmaster.debug.DebugDumper
import com.notificationmaster.export.calendar.CalendarExporter
import com.notificationmaster.export.calendar.CalendarExportLog
import com.notificationmaster.ui.common.NotificationDisplay
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

/**
 * 通知擷取服務
 * 繼承 NotificationListenerService，攔截所有系統通知
 */
class NotificationCaptureService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var database: NotificationDatabase
    private lateinit var extractor: NotificationExtractor
    private lateinit var rankingProcessor: RankingProcessor
    private lateinit var initialReconciler: InitialReconciler
    private lateinit var debugDumper: DebugDumper
    private lateinit var deviceStateCapture: DeviceStateCapture
    private lateinit var mediaExtractor: MediaExtractor
    private lateinit var calendarExporter: CalendarExporter
    private lateinit var alertManager: PersistentAlertManager

    /** 追蹤延遲清除的排程任務，key = notification key */
    private val pendingDismissJobs = ConcurrentHashMap<String, Job>()

    /**
     * 追蹤即時日曆匯出的事件 ID，key = notification key，value = (calendarId, eventId) list。
     *
     * 一則通知可能同時匹配多條 CALENDAR_EXPORT 規則，每條規則寫入不同日曆 ⇒
     * 同 notificationKey 對應多筆 (calendarId, eventId)。REMOVED 時對所有 entry 各自更新 endTime。
     */
    private val calendarExportMap = ConcurrentHashMap<String, MutableList<Pair<Long, Long>>>()

    companion object {
        private const val TAG = "NotificationCapture"
        private const val RANKING_TRIGGER_NOTIFICATION_ID = 900_002

        @Volatile
        var isConnected = false
            private set

        /** RankingMap 是否已被填充（至少收到一次非空 RankingMap） */
        @Volatile
        var isRankingMapPopulated = false
            private set

        /**
         * RankingMap 警告橫幅狀態（事件驅動）
         * true = 已連線但 RankingMap 尚未填充（API 24+）
         * false = 未連線、或 RankingMap 已填充
         */
        val showRankingBanner = androidx.lifecycle.MutableLiveData(false)

        // 用於 UI 層查詢服務狀態
        private var instance: NotificationCaptureService? = null

        fun getInstance(): NotificationCaptureService? = instance
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        database = NotificationMasterApp.getInstance().database
        extractor = NotificationExtractor(this)
        rankingProcessor = RankingProcessor(database)
        initialReconciler = InitialReconciler(database)
        debugDumper = DebugDumper(this)
        deviceStateCapture = DeviceStateCapture(this)
        mediaExtractor = MediaExtractor(this)
        calendarExporter = CalendarExporter(this)
        // 帳號資訊已由 per-rule calendarId 取代全域設定，每次寫入時依日曆即時查詢設定
        alertManager = PersistentAlertManager(this)
        RuleRepository.load(this)
        instance = this

        // RankingMap 探針（debug 模式限定）
        if (debugDumper.isEnabled) {
            serviceScope.launch {
                val createTime = System.currentTimeMillis()
                var i = 0
                while (true) {
                    kotlinx.coroutines.delay(5_000)
                    i++
                    val elapsed = System.currentTimeMillis() - createTime
                    val probeMap = try { getCurrentRanking() } catch (_: Exception) { null }
                    val entries = probeMap?.orderedKeys?.size ?: -1
                    val connected = isConnected
                    val status = when {
                        probeMap == null -> "null"
                        entries == 0 -> "0 entries"
                        else -> "$entries entries"
                    }
                    Log.d(TAG, "RankingMap probe #$i: ${elapsed}ms, connected=$connected, $status")
                    if (probeMap != null && entries > 0) {
                        dumpRankingMap(probeMap, "probe_${elapsed}ms")
                        break
                    } else {
                        val dumpDir = java.io.File(getExternalFilesDir(null) ?: filesDir, "channel_dump").apply { mkdirs() }
                        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(java.util.Date())
                        java.io.File(dumpDir, "probe_${elapsed}ms_$ts.txt")
                            .writeText("source=probe #$i\nelapsed=${elapsed}ms\nisConnected=$connected\ngetCurrentRanking()=$status\n")
                    }
                }
            }
        }
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

        // 同步捕獲快照（在回呼的 binder thread 上，此時 activeNotifications 一定可用）
        val snapshot = try { activeNotifications?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
        val rankingSnapshot = try { getCurrentRanking() } catch (_: Exception) { null }

        // RankingMap 狀態檢查
        if (!isRankingMapPopulated && rankingSnapshot?.orderedKeys?.isNotEmpty() == true) {
            isRankingMapPopulated = true
            showRankingBanner.postValue(false)
            Log.i(TAG, "RankingMap populated (via onListenerConnected)")
        }
        // 連線後若 RankingMap 仍為空（OEM 異常），通知 UI 顯示警告
        if (Build.VERSION.SDK_INT >= 24 && !isRankingMapPopulated) {
            showRankingBanner.postValue(true)
        }
        rankingSnapshot?.let { dumpRankingMap(it, "listener_connected") }

        // Debug dump
        debugDumper.dumpActiveNotifications(snapshot.toTypedArray(), rankingSnapshot)

        // 若保活已開啟，確保前景服務運行中
        if (AppPreferences.isNlsKeepaliveEnabled(this)) {
            NlsKeepaliveService.start(this)
        }

        // 擷取所有現有通知（標記為 INITIAL），使用快照而非重新呼叫 getter
        Log.i(TAG, "Processing ${snapshot.size} existing notifications")
        serviceScope.launch {
            processInitialNotifications(snapshot, rankingSnapshot, "onListenerConnected")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "Listener disconnected")
        PendingIntentCache.clear()
        isConnected = false
        isRankingMapPopulated = false
        showRankingBanner.postValue(false)
    }

    /**
     * 備援：擷取目前所有活躍通知（冪等）
     * 供下拉刷新和 ensureServiceConnected 呼叫
     */
    fun captureActiveNotifications() {
        if (!isConnected) {
            Log.w(TAG, "captureActiveNotifications: service not connected, skip")
            return
        }
        serviceScope.launch {
            val notifications = try {
                (activeNotifications ?: emptyArray()).toList()
            } catch (e: Exception) {
                Log.e(TAG, "captureActiveNotifications: failed to get active notifications", e)
                emptyList()
            }
            if (notifications.isEmpty()) return@launch

            val rankingMap = try { getCurrentRanking() } catch (_: Exception) { null }
            rankingMap?.let { dumpRankingMap(it, "capture_active") }
            // 有通知但 RankingMap 仍為空 → 補發 banner（OEM 提前綁定後授權再下拉的情境）
            if (Build.VERSION.SDK_INT >= 24 && !isRankingMapPopulated
                && (rankingMap == null || rankingMap.orderedKeys.isEmpty())) {
                showRankingBanner.postValue(true)
            }
            processInitialNotifications(notifications, rankingMap, "captureActiveNotifications")
        }
    }

    /**
     * 透過發送自身通知觸發 POSTED callback，使系統填充 mRankingMap。
     * 用於 OEM 裝置在 onListenerConnected 時未提供 RankingMap 的 workaround。
     */
    fun triggerRankingMapUpdate() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val notification = androidx.core.app.NotificationCompat.Builder(this, NlsKeepaliveService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.ranking_trigger_notification_text))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
            .setOngoing(false)
            .build()
        nm.notify(RANKING_TRIGGER_NOTIFICATION_ID, notification)
        Log.i(TAG, "triggerRankingMapUpdate: notification sent")
    }

    /**
     * 共用的 INITIAL 事件處理（冪等）
     *
     * - 以 NotificationRecord.existsByKey 跳過已存在的記錄
     * - 完成 INITIAL upsert 後跑 [InitialReconciler]（Plan 2 §K）：對「本機 active 但 INITIAL 未列出」
     *   的 record 補一筆 REMOVED event（reason=-100）
     * - onListenerConnected 和 captureActiveNotifications 共用此方法
     */
    private suspend fun processInitialNotifications(
        notifications: List<StatusBarNotification>,
        rankingMap: RankingMap?,
        caller: String
    ) {
        var newCount = 0
        var skipCount = 0
        val initialKeys = mutableSetOf<String>()
        for (sbn in notifications) {
            try {
                val key = ApiVersionHelper.getNotificationKey(sbn)
                initialKeys.add(key)
                // PendingIntentCache 僅存於 in-memory，process 重啟後必須無條件重建。
                // record 去重只保證不重複寫 DB，不能連帶讓 cache 也跳過，否則
                // 舊通知在 Detail 頁會全部顯示灰燈（即便 token 仍在 shade 中有效）。
                cachePendingIntents(sbn)

                if (database.notificationRecordDao().getByKey(key) != null) {
                    skipCount++
                    // 補齊 channel 資料（已存在的通知可能 channel name 為 null）
                    if (ApiVersionHelper.supportsNotificationChannel() && rankingMap != null) {
                        refreshChannelFromRanking(sbn, rankingMap, System.currentTimeMillis())
                    }
                } else {
                    processNotification(sbn, EventType.INITIAL, rankingMap)
                    newCount++
                }
            } catch (e: Exception) {
                Log.e(TAG, "$caller: error processing ${sbn.packageName}", e)
            }
        }
        Log.i(TAG, "$caller complete: $newCount new, $skipCount skipped")

        // Reconciliation：補登錄漏接的 REMOVED（OEM 凍結 / 服務瞬斷情境）
        // 安全保護在 InitialReconciler 內：activeKeys 非空但 initialKeys 為空時跳過
        try {
            val reconciled = initialReconciler.reconcile(initialKeys, System.currentTimeMillis())
            if (reconciled > 0) {
                Log.i(TAG, "$caller reconciliation: $reconciled missing REMOVED補登錄")
            }
        } catch (e: Exception) {
            Log.e(TAG, "$caller: reconciliation failed", e)
        }
    }

    /**
     * 補齊 channel 資料（只補空缺）
     * 當 ChannelEntity 存在但 channelName 為 null 時，從 ranking.channel 取得並更新。
     */
    private suspend fun refreshChannelFromRanking(
        sbn: StatusBarNotification,
        rankingMap: RankingMap,
        captureTime: Long
    ) {
        if (Build.VERSION.SDK_INT < 26) return
        val channelId = sbn.notification.channelId ?: return
        val existing = database.channelDao().getByPackageAndChannelId(sbn.packageName, channelId)
        if (existing == null || existing.channelName != null) return

        val ranking = Ranking()
        if (rankingMap.getRanking(ApiVersionHelper.getNotificationKey(sbn), ranking)) {
            ranking.channel?.let { updateChannel(sbn.packageName, channelId, captureTime, it) }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        Log.d(TAG, "Notification posted: ${sbn.packageName} - ${ApiVersionHelper.getNotificationKey(sbn)}")
        if (!isRankingMapPopulated && rankingMap?.orderedKeys?.isNotEmpty() == true) {
            isRankingMapPopulated = true
            showRankingBanner.postValue(false)
            Log.i(TAG, "RankingMap populated (via POSTED)")
        }
        rankingMap?.let { dumpRankingMap(it, "posted") }
        debugDumper.dumpEvent(sbn, "POSTED", rankingMap)

        serviceScope.launch {
            try {
                // 檢查是否為更新（Plan 2：以 NotificationRecord 為唯一存在依據）
                val key = ApiVersionHelper.getNotificationKey(sbn)
                val isUpdate = database.notificationRecordDao().getByKey(key) != null
                val eventType = if (isUpdate) EventType.UPDATED else EventType.POSTED

                processNotification(sbn, eventType, rankingMap)
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
        if (!isRankingMapPopulated && rankingMap?.orderedKeys?.isNotEmpty() == true) {
            isRankingMapPopulated = true
            showRankingBanner.postValue(false)
            Log.i(TAG, "RankingMap populated (via REMOVED)")
        }
        rankingMap?.let { dumpRankingMap(it, "removed") }
        debugDumper.dumpEvent(sbn, "REMOVED", rankingMap, removalReason = reason)

        // 取消待處理的延遲清除排程（通知已被移除，無需再清除）
        val dismissKey = ApiVersionHelper.getNotificationKey(sbn)
        pendingDismissJobs.remove(dismissKey)?.cancel()

        serviceScope.launch {
            try {
                processRemoval(sbn, reason, rankingMap)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing removed notification", e)
            }
        }
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap) {
        Log.d(TAG, "Ranking update received")
        if (!isRankingMapPopulated && rankingMap.orderedKeys.isNotEmpty()) {
            isRankingMapPopulated = true
            showRankingBanner.postValue(false)
            Log.i(TAG, "RankingMap populated (via RANKING_UPDATE)")
        }
        dumpRankingMap(rankingMap, "ranking_update")
        debugDumper.dumpRankingUpdate(rankingMap)

        serviceScope.launch {
            try {
                processRankingUpdate(rankingMap)
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
        val rankInfo = getRankingInfo(ApiVersionHelper.getNotificationKey(sbn), rankingMap)
        val notifFlags = sbn.notification.flags
        val matchCtx = MatchContext(
            packageName = sbn.packageName,
            channelId = filterChannelId,
            eventType = eventType,
            title = extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString(),
            text = extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString(),
            bigText = extras?.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString(),
            subText = extras?.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString(),
            channelImportance = rankInfo.importance,
            channelGroupId = rankInfo.groupId,
            flags = notifFlags,
            likelyHeadsup = ApiVersionHelper.isLikelyHeadsUp(sbn.notification, rankInfo.importance),
            isAudible = ApiVersionHelper.isLikelyAudible(
                rankInfo.lastAudiblyAlertedMillis, captureTime,
                rankInfo.importance ?: -1, notifFlags,
                sbn.notification.sound?.toString(),
                eventType == EventType.UPDATED
            )
        )
        if (RuleEngine.matches(ActionType.SKIP_RECORD, matchCtx)) {
            Log.d(TAG, "Filtered: ${sbn.packageName}/$filterChannelId event=$eventType")
            return
        }

        // 1. 提取 NotificationEvent（新 schema 主體；自包含 eventRawJson）+ 攤平成 display 給下游 helper
        val eventEntity = extractor.extractEvent(
            sbn = sbn,
            eventType = eventType,
            eventTime = captureTime,
            captureTime = captureTime,
            isAudible = matchCtx.isAudible,
            likelyHeadsup = matchCtx.likelyHeadsup,
            removalReason = null
        )
        val display = NotificationDisplay.from(eventEntity)
        val key = eventEntity.notificationKey

        // 2. 提取 Actions / 媒體 / 裝置狀態（eventId 留 0，transaction 內取得 event id 後補正）
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

        // 3. Transaction 寫入新 schema（record + event + ranking observation + children）
        val eventDbId = database.withTransaction {
            // 3.1 upsert NotificationRecord（自然主鍵 = notification_key）
            val record = database.notificationRecordDao().getByKey(key)
            if (record == null) {
                database.notificationRecordDao().insertIfAbsent(
                    NotificationRecordEntity(
                        notificationKey = key,
                        packageName = sbn.packageName,
                        channelId = eventEntity.channelId,
                        notificationId = sbn.id,
                        tag = sbn.tag,
                        firstSeen = captureTime,
                        lastSeen = captureTime,
                        eventCount = 1
                    )
                )
            } else {
                database.notificationRecordDao().bumpEventCount(key, captureTime)
            }

            // 3.2 寫 NotificationEvent
            val eventId = database.notificationEventDao().insert(eventEntity)

            // 3.3 children FK 改 event_id 後寫入
            if (actions.isNotEmpty()) {
                database.actionDao().insertAll(actions.map { it.copy(eventId = eventId) })
            }
            if (mediaAttachments.isNotEmpty()) {
                database.mediaAttachmentDao().insertAll(mediaAttachments.map { it.copy(eventId = eventId) })
            }
            if (deviceState != null) {
                database.deviceStateDao().insert(deviceState.copy(eventId = eventId))
            }

            // 3.4 寫 RankingObservation（新軌道，與 NotificationEvent 並行）
            val obsSource = when (eventType) {
                EventType.POSTED -> ObservationSource.POSTED
                EventType.UPDATED -> ObservationSource.UPDATED
                EventType.INITIAL -> ObservationSource.INITIAL
                EventType.REMOVED -> ObservationSource.REMOVED
            }
            rankingProcessor.process(
                notificationKey = key,
                ranking = getRankingEntry(key, rankingMap),
                sbn = sbn,
                observedAt = captureTime,
                source = obsSource,
                skipIfHashUnchanged = false
            )

            eventId
        }

        // 4. 快取 PendingIntent 參照（不需 transaction）
        cachePendingIntents(sbn)

        // 5. 更新 App 來源
        updateAppSource(sbn.packageName, captureTime)

        // 6. 更新 Channel (API 26+)
        // 唯一可靠來源：ranking.channel（API 26+）
        if (ApiVersionHelper.supportsNotificationChannel() && eventEntity.channelId != null) {
            val notificationChannel: android.app.NotificationChannel? = if (rankingMap != null) {
                val ranking = Ranking()
                if (rankingMap.getRanking(key, ranking)) ranking.channel else null
            } else null
            updateChannel(sbn.packageName, eventEntity.channelId, captureTime, notificationChannel)
        }

        // 7. 各 ActionType 獨立判斷 eventType 是否在允許範圍
        if (eventType in ActionType.AUTO_DISMISS.allowedEventTypes) {
            checkAutoDismiss(sbn, rankingMap, eventType)
        }
        if (eventType in ActionType.CALENDAR_EXPORT.allowedEventTypes) {
            checkRealtimeCalendarExport(display, matchCtx)
        }
        if (eventType in ActionType.PERSISTENT_ALERT.allowedEventTypes) {
            checkPersistentAlert(display, matchCtx, sbn, eventType)
        }
        if (eventType in ActionType.CLIPBOARD_COPY.allowedEventTypes) {
            checkClipboardCopy(display.packageName, matchCtx)
        }

        Log.d(TAG, "Saved event: $eventDbId, type: $eventType")

        // 通知 Widget 更新
        NotificationWidgetProvider.notifyUpdate(this@NotificationCaptureService)
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
            channelGroupId = channelEntity?.groupId,
            flags = sbn.notification.flags
        )
        if (RuleEngine.matches(ActionType.SKIP_RECORD, removalMatchCtx)) {
            PendingIntentCache.remove(key)
            return
        }

        // NLS 重連競態：先收到 REMOVED 而新 schema 內無 record。從 sbn 補建一筆。
        val existingRecord = database.notificationRecordDao().getByKey(key)
        database.withTransaction {
            if (existingRecord == null) {
                Log.w(TAG, "No existing record for removal, creating from sbn: $key")
                database.notificationRecordDao().insertIfAbsent(
                    NotificationRecordEntity(
                        notificationKey = key,
                        packageName = sbn.packageName,
                        channelId = filterChannelId,
                        notificationId = sbn.id,
                        tag = sbn.tag,
                        firstSeen = captureTime,
                        lastSeen = captureTime,
                        eventCount = 1
                    )
                )
            } else {
                database.notificationRecordDao().bumpEventCount(key, captureTime)
            }

            // 寫 REMOVED 事件（新 schema），eventRawJson 含 sbn 當下狀態
            val removedEvent = extractor.extractEvent(
                sbn = sbn,
                eventType = EventType.REMOVED,
                eventTime = captureTime,
                captureTime = captureTime,
                isAudible = false,           // REMOVED 不再產生提示
                likelyHeadsup = false,
                removalReason = reason
            )
            database.notificationEventDao().insert(removedEvent)

            // 寫 RankingObservation (source = REMOVED)
            rankingProcessor.process(
                notificationKey = key,
                ranking = getRankingEntry(key, rankingMap),
                sbn = sbn,
                observedAt = captureTime,
                source = ObservationSource.REMOVED,
                skipIfHashUnchanged = false
            )
        }

        Log.d(TAG, "Recorded removal event for: $key, reason: $reason")

        // 即時日曆匯出 — 更新結束時間
        checkRealtimeCalendarRemoval(
            key, captureTime, sbn.packageName,
            if (ApiVersionHelper.supportsNotificationChannel()) sbn.notification.channelId else null
        )

        PendingIntentCache.remove(key)

        // 通知 Widget 更新
        NotificationWidgetProvider.notifyUpdate(this@NotificationCaptureService)
    }

    /**
     * 處理 Ranking 更新（Plan 2 §4）
     *
     * RANKING_UPDATE callback 不再產生 NotificationEvent。對 RankingMap 每個 key：
     * - RankingProcessor 比對該 key 上一筆 observation 的 snapshot hash
     * - 不同才寫一筆 observation（純噪音變化 rank/lastAudibly 跳過）
     * - 順帶補齊 ChannelEntity（過去 RANKING_UPDATE 唯一能拿到 channel 補空缺的機會）
     */
    private suspend fun processRankingUpdate(rankingMap: RankingMap) {
        if (!ApiVersionHelper.supportsDirectReply()) return  // Ranking 詳細資訊需要 API 24+

        val captureTime = System.currentTimeMillis()
        val written = rankingProcessor.processRankingMap(rankingMap, captureTime)
        if (written > 0) Log.d(TAG, "Ranking observations written: $written")

        // Channel 補齊（只補 channelName 為 null 的記錄）
        if (!ApiVersionHelper.supportsNotificationChannel()) return
        for (key in rankingMap.orderedKeys) {
            val ranking = Ranking()
            if (!rankingMap.getRanking(key, ranking)) continue
            val ch = ranking.channel ?: continue
            // 從新 schema 的 record 取 package/channel 資訊（單一 source）
            val rec = database.notificationRecordDao().getByKey(key) ?: continue
            val pkgName = rec.packageName
            val chId = rec.channelId ?: continue
            val existing = database.channelDao().getByPackageAndChannelId(pkgName, chId)
            if (existing != null && existing.channelName == null) {
                updateChannel(pkgName, chId, captureTime, ch)
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
     * Dump 完整 RankingMap 到外部目錄（debug 用）
     */
    private fun dumpRankingMap(rankingMap: RankingMap, source: String) {
        if (!debugDumper.isEnabled) return
        try {
            val dumpDir = java.io.File(getExternalFilesDir(null) ?: filesDir, "channel_dump").apply { mkdirs() }
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(java.util.Date())
            val sb = StringBuilder()
            val keys = rankingMap.orderedKeys
            sb.appendLine("source=$source")
            sb.appendLine("RankingMap: ${keys.size} entries")
            sb.appendLine()
            for (key in keys) {
                val ranking = Ranking()
                val ok = rankingMap.getRanking(key, ranking)
                sb.appendLine("key=$key")
                sb.appendLine("  getRanking=$ok")
                if (ok && Build.VERSION.SDK_INT >= 26) {
                    sb.appendLine("  channel=${ranking.channel}")
                    sb.appendLine("  importance=${ranking.importance}")
                }
                sb.appendLine()
            }
            java.io.File(dumpDir, "${source}_$ts.txt").writeText(sb.toString())
        } catch (_: Exception) { }
    }

    /**
     * 更新 Channel 記錄 (API 26+)
     *
     * @param notificationChannel 從 Ranking.getChannel()（API 26+）取得
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
                // 有完整 channel 資訊，同時更新 metadata 和計數
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
                // Channel 資訊不可用時僅遞增計數
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
                importance = notificationChannel?.importance ?: -1,
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
     * 從 RankingMap 取得指定通知的 importance 和 channel groupId
     */
    private data class RankingInfo(
        val importance: Int? = null,
        val groupId: String? = null,
        val lastAudiblyAlertedMillis: Long = -1L
    )

    private fun getRankingInfo(key: String, rankingMap: RankingMap?): RankingInfo {
        if (rankingMap == null) return RankingInfo()
        val ranking = Ranking()
        if (!rankingMap.getRanking(key, ranking)) return RankingInfo()
        // Ranking.importance requires API 24+, Ranking.channel requires API 26+
        val importance = if (ApiVersionHelper.supportsDirectReply()) ranking.importance else null
        val groupId = if (ApiVersionHelper.supportsNotificationChannel()) ranking.channel?.group else null
        val lastAudibly = if (ApiVersionHelper.supportsLastAudiblyAlerted()) ranking.lastAudiblyAlertedMillis else -1L
        return RankingInfo(importance, groupId, lastAudibly)
    }

    /**
     * 取出 [key] 對應的 [Ranking] 物件；rankingMap null 或查不到 → null
     */
    private fun getRankingEntry(key: String, rankingMap: RankingMap?): Ranking? {
        if (rankingMap == null) return null
        val ranking = Ranking()
        return if (rankingMap.getRanking(key, ranking)) ranking else null
    }

    /**
     * 為指定 calendarId 設定 exporter 的目標帳號（CALLER_IS_SYNCADAPTER 寫入需要）。
     * 找不到日曆 → 回傳 false（呼叫端應視為「目標消失」跳過）。
     */
    private fun applyTargetAccount(calendarId: Long): Boolean {
        val cal = calendarExporter.getAvailableCalendars().firstOrNull { it.id == calendarId }
            ?: return false
        calendarExporter.setTargetAccount(cal.accountName, cal.accountType)
        return true
    }

    /**
     * 檢查是否需要即時匯出到日曆。
     * 條件：即時匯出總開關啟用 + 至少一條 CALENDAR_EXPORT rule 匹配。
     * 每條匹配的 rule 各自寫入到自身 calendarId（per-rule calendar）。
     */
    private fun checkRealtimeCalendarExport(
        display: NotificationDisplay,
        matchCtx: MatchContext
    ) {
        if (!AppPreferences.isRealtimeCalendarEnabled(this)) {
            CalendarExportLog.log(display.packageName, "skipped", "disabled")
            return
        }

        val matched = RuleEngine.findAllMatchingRules(ActionType.CALENDAR_EXPORT, matchCtx)
        if (matched.isEmpty()) {
            CalendarExportLog.log(display.packageName, "skipped",
                "no match: ${matchCtx.channelId} event=${matchCtx.eventType}")
            return
        }

        for (rule in matched) {
            val targetId = (rule.action as? RuleAction.CalendarExport)?.calendarId
            if (targetId == null) {
                CalendarExportLog.log(display.packageName, "skipped",
                    "rule ${rule.id}: calendarId not configured")
                continue
            }
            if (!applyTargetAccount(targetId)) {
                CalendarExportLog.log(display.packageName, "skipped",
                    "rule ${rule.id}: target calendar #$targetId not found")
                continue
            }
            // 一律建立新日曆事件（電話類 App 重用通知 key，不能以 key 判斷是否為「同一事件」）
            val eventId = calendarExporter.exportSingleNotification(display, targetId, ExportDetailLevel.FULL)
            if (eventId > 0) {
                calendarExportMap.getOrPut(display.notificationKey) { mutableListOf() }
                    .add(targetId to eventId)
                CalendarExportLog.log(display.packageName, "exported",
                    "rule ${rule.id} cal=$targetId eventId=$eventId")
            } else {
                CalendarExportLog.log(display.packageName, "failed",
                    "rule ${rule.id} cal=$targetId returnValue=$eventId")
            }
        }
    }

    /**
     * 通知移除時更新對應日曆事件的結束時間。
     * 輕量級檢查：packageName/channelId 匹配 + EventTypes 包含 REMOVED。
     * 對所有先前匯出（多 calendar）的事件各自更新 endTime。
     */
    private fun checkRealtimeCalendarRemoval(
        notificationKey: String,
        removalTime: Long,
        packageName: String,
        channelId: String?
    ) {
        if (!AppPreferences.isRealtimeCalendarEnabled(this)) {
            CalendarExportLog.log(packageName, "removal_skipped", "disabled")
            return
        }

        // 取出所有 packageName/channelId 匹配且含 REMOVED 的 CALENDAR_EXPORT rule
        val applicableRules = RuleEngine.getRules(ActionType.CALENDAR_EXPORT).filter { rule ->
            rule.packageName == packageName &&
                (rule.channelId == null || rule.channelId == channelId) &&
                EventType.REMOVED.name in rule.eventTypes
        }
        if (applicableRules.isEmpty()) {
            CalendarExportLog.log(packageName, "removal_skipped", "no REMOVED in rule")
            return
        }

        // 1. 嘗試用記憶體 map 中已記錄的 (calendarId, eventId)
        val tracked = calendarExportMap.remove(notificationKey).orEmpty()

        // 2. Process 重啟後 map 為空 → 對每條 applicable rule 的 calendarId 各自 findEventByNotificationKey
        val targets: List<Pair<Long, Long>> = if (tracked.isNotEmpty()) {
            tracked
        } else {
            applicableRules
                .mapNotNull { (it.action as? RuleAction.CalendarExport)?.calendarId }
                .distinct()
                .mapNotNull { calId ->
                    if (!applyTargetAccount(calId)) return@mapNotNull null
                    val eId = calendarExporter.findEventByNotificationKey(calId, notificationKey)
                    if (eId > 0) calId to eId else null
                }
        }

        if (targets.isEmpty()) {
            CalendarExportLog.log(packageName, "removal_skipped", "no calendar event found")
            return
        }

        for ((calId, eventId) in targets) {
            // 不同 calendar 可能屬不同帳號，每筆事件更新前重設 target account
            if (!applyTargetAccount(calId)) {
                CalendarExportLog.log(packageName, "end_time_failed",
                    "cal=$calId eventId=$eventId target missing")
                continue
            }
            val success = calendarExporter.updateCalendarEventEndTime(eventId, removalTime)
            if (success) {
                CalendarExportLog.log(packageName, "end_time_updated", "cal=$calId eventId=$eventId")
            } else {
                CalendarExportLog.log(packageName, "end_time_failed", "cal=$calId eventId=$eventId")
            }
        }
    }

    /**
     * 檢查是否需要觸發持續提醒
     *
     * 僅 POSTED/UPDATED 觸發（在 processNotification 的 eventType != INITIAL 區塊呼叫）。
     */
    private fun checkPersistentAlert(
        display: NotificationDisplay,
        matchCtx: MatchContext,
        sbn: StatusBarNotification,
        eventType: EventType
    ) {
        val rule = RuleEngine.findMatchingRule(ActionType.PERSISTENT_ALERT, matchCtx) ?: return
        val action = rule.action as RuleAction.PersistentAlert
        val appName = NotificationContentHelper.appName(this, display.packageName)
        val actions = sbn.notification.actions
            ?.filter { it.remoteInputs.isNullOrEmpty() }
            ?.toTypedArray()

        alertManager.startAlert(AlertData(
            notificationKey = display.notificationKey,
            title = "[$appName] ${NotificationContentHelper.displayTitle(display)}",
            text = display.text,
            soundUri = action.soundUri,
            vibrate = action.vibrate,
            audioStream = action.audioStream,
            appName = appName,
            packageName = display.packageName,
            eventType = eventType.name,
            timestamp = display.captureTime,
            subText = display.subText,
            bigText = display.bigText,
            contentIntent = sbn.notification.contentIntent,
            actions = actions
        ))
    }

    /**
     * 檢查是否需要複製通知內容到剪貼簿
     *
     * 無 regex：複製 title + 最完整內容（bigText ?: text）
     * 有 regex：對每個匹配欄位提取所有 capture group，各欄位獨立複製到剪貼簿
     */
    private suspend fun checkClipboardCopy(packageName: String, matchCtx: MatchContext) {
        val rule = RuleEngine.findMatchingRule(ActionType.CLIPBOARD_COPY, matchCtx) ?: return
        val keywordMatcher = rule.matchers.filterIsInstance<Matcher.Keyword>().firstOrNull()
        // ClipboardManager 操作需在主線程
        val count = kotlinx.coroutines.withContext(Dispatchers.Main) {
            ClipboardCopyHelper.copyFromMatchContext(
                this@NotificationCaptureService, matchCtx.title, matchCtx.text, matchCtx.bigText, matchCtx.subText, keywordMatcher
            )
        }
        if (count > 0) Log.d(TAG, "Clipboard copy: $count entries from $packageName")
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
        val dismissRankInfo = getRankingInfo(key, rankingMap)
        val dismissMatchCtx = MatchContext(
            packageName = sbn.packageName,
            channelId = channelId,
            eventType = eventType,
            title = dismissExtras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString(),
            text = dismissExtras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString(),
            bigText = dismissExtras?.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString(),
            subText = dismissExtras?.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString(),
            channelImportance = dismissRankInfo.importance,
            channelGroupId = dismissRankInfo.groupId,
            flags = sbn.notification.flags
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
