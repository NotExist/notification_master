package com.notificationmaster.core

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import com.notificationmaster.core.compat.ApiVersionHelper
import com.notificationmaster.core.dedup.ContentHashGenerator
import com.notificationmaster.data.db.entity.ActionEntity
import com.notificationmaster.data.db.entity.NotificationEntity
import com.notificationmaster.data.db.entity.PersistenceType
import org.json.JSONArray
import org.json.JSONObject

/**
 * 通知資料提取器
 * 從 StatusBarNotification 和 Notification 物件中提取所有可存取的資訊
 */
class NotificationExtractor(private val context: Context) {

    /**
     * 從 StatusBarNotification 提取完整資訊
     */
    @Suppress("DEPRECATION")
    fun extractNotification(
        sbn: StatusBarNotification,
        rankingMap: RankingMap?,
        captureTime: Long = System.currentTimeMillis()
    ): NotificationEntity {
        val notification = sbn.notification
        val extras = notification.extras ?: Bundle()
        val flags = notification.flags

        // 取得 Ranking 資訊
        val ranking = rankingMap?.let { map ->
            Ranking().also { map.getRanking(ApiVersionHelper.getNotificationKey(sbn), it) }
        }

        // Channel importance
        val channelImportance = if (Build.VERSION.SDK_INT >= 26) {
            ranking?.importance ?: NotificationManager.IMPORTANCE_DEFAULT
        } else {
            -1
        }

        // 提取基本內容
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        // 產生內容 Hash
        val contentHash = ContentHashGenerator.generateHash(
            sbn.packageName,
            title,
            text,
            bigText
        )

        @Suppress("DEPRECATION")
        val notificationPriority = notification.priority

        return NotificationEntity(
            notificationKey = ApiVersionHelper.getNotificationKey(sbn),
            packageName = sbn.packageName,
            notificationId = sbn.id,
            tag = sbn.tag,

            // 時間
            postTime = sbn.postTime,
            captureTime = captureTime,
            whenTime = notification.`when`,

            // 基本內容
            title = title,
            text = text,
            subText = subText,
            infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString(),
            summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString(),

            // 展開內容
            bigText = bigText,
            bigTitle = extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString(),

            // Ticker
            tickerText = notification.tickerText?.toString(),

            // Flags
            flags = flags,
            isOngoing = ApiVersionHelper.isOngoing(flags),
            isForegroundService = ApiVersionHelper.isForegroundService(flags),
            isAutoCancel = ApiVersionHelper.isAutoCancel(flags),
            isNoClear = ApiVersionHelper.isNoClear(flags),
            isHighPriority = ApiVersionHelper.isHighPriority(flags),
            isLocalOnly = ApiVersionHelper.isLocalOnly(flags),
            isGroupSummary = ApiVersionHelper.isGroupSummary(flags),

            // 優先級/重要性
            priority = notificationPriority,
            importance = channelImportance,
            likelyHeadsup = ApiVersionHelper.isLikelyHeadsUp(notification, channelImportance.takeIf { it >= 0 }),

            // 可見性
            visibility = notification.visibility,

            // 分類
            category = notification.category,

            // 群組
            groupKey = notification.group,
            sortKey = notification.sortKey,

            // Channel (API 26+)
            channelId = if (Build.VERSION.SDK_INT >= 26) notification.channelId else null,

            // Shortcut (API 26+)
            shortcutId = if (Build.VERSION.SDK_INT >= 26) notification.shortcutId else null,

            // Bubble (API 29+)
            hasBubbleMetadata = if (Build.VERSION.SDK_INT >= 29) notification.bubbleMetadata != null else false,
            bubbleDesiredHeight = if (Build.VERSION.SDK_INT >= 29) {
                notification.bubbleMetadata?.desiredHeight ?: 0
            } else 0,
            bubbleDesiredHeightResId = if (Build.VERSION.SDK_INT >= 29) {
                notification.bubbleMetadata?.desiredHeightResId ?: 0
            } else 0,
            bubbleAutoExpand = if (Build.VERSION.SDK_INT >= 30) {
                notification.bubbleMetadata?.autoExpandBubble ?: false
            } else false,
            bubbleSuppressNotification = if (Build.VERSION.SDK_INT >= 30) {
                notification.bubbleMetadata?.isNotificationSuppressed ?: false
            } else false,

            // 顏色
            color = notification.color,

            // 聲音/震動
            soundUri = notification.sound?.toString(),
            vibratePattern = notification.vibrate?.let { JSONArray(it.toList()).toString() },
            ledArgb = notification.ledARGB,
            ledOnMs = notification.ledOnMS,
            ledOffMs = notification.ledOffMS,

            // 進度
            progress = extras.getInt(Notification.EXTRA_PROGRESS, 0),
            progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0),
            progressIndeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false),

            // 計時器
            showChronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false),
            chronometerCountDown = if (Build.VERSION.SDK_INT >= 24) {
                extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false)
            } else false,
            showWhen = extras.getBoolean(Notification.EXTRA_SHOW_WHEN, true),

            // 聯絡人
            people = extractPeople(extras),

            // MessagingStyle (API 24+)
            isMessagingStyle = isMessagingStyle(extras),
            conversationTitle = if (Build.VERSION.SDK_INT >= 24) {
                extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            } else null,
            isGroupConversation = if (Build.VERSION.SDK_INT >= 28) {
                extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
            } else false,
            messages = extractMessages(extras),

            // 樣式模板
            template = extras.getString(Notification.EXTRA_TEMPLATE),

            // 自訂 View
            hasCustomContentView = notification.contentView != null,
            hasCustomBigContentView = notification.bigContentView != null,
            hasCustomHeadsUpContentView = notification.headsUpContentView != null,

            // 完整 Extras
            extrasJson = bundleToJson(extras),

            // 持久性類型
            persistenceType = inferPersistenceType(flags),

            // 去重 Hash
            contentHash = contentHash,

            // 動作數量
            actionCount = notification.actions?.size ?: 0,

            // User ID
            userId = sbn.user.hashCode(),

            // Ranking 資訊
            rankingRank = if (Build.VERSION.SDK_INT >= 24) ranking?.rank ?: -1 else -1,
            isAmbient = if (Build.VERSION.SDK_INT >= 24) ranking?.isAmbient ?: false else false,
            isSuspended = if (Build.VERSION.SDK_INT >= 28) ranking?.isSuspended ?: false else false,
            suppressedVisualEffects = if (Build.VERSION.SDK_INT >= 24) {
                ranking?.suppressedVisualEffects ?: 0
            } else 0,
            isConversation = if (Build.VERSION.SDK_INT >= 31) {
                ranking?.isConversation ?: false
            } else false,
            lastAudiblyAlertedMillis = if (Build.VERSION.SDK_INT >= 28) {
                ranking?.lastAudiblyAlertedMillis ?: -1L
            } else -1L,

            // Intent 資訊
            hasContentIntent = notification.contentIntent != null,
            hasDeleteIntent = notification.deleteIntent != null,
            hasFullScreenIntent = notification.fullScreenIntent != null,
            contentIntentCreatorPackage = notification.contentIntent?.creatorPackage,
            intentInfoJson = extractIntentInfo(notification)
        )
    }

    /**
     * 提取 Action 按鈕資訊
     */
    @Suppress("DEPRECATION")
    fun extractActions(
        notification: Notification,
        notificationId: Long,
        captureTime: Long = System.currentTimeMillis()
    ): List<ActionEntity> {
        val actions = notification.actions ?: return emptyList()

        return actions.mapIndexed { index, action ->
            val remoteInputs = action.remoteInputs

            val replyInput = remoteInputs?.firstOrNull { it.allowFreeFormInput }

            ActionEntity(
                notificationId = notificationId,
                actionIndex = index,
                title = action.title?.toString(),
                iconResId = action.icon,
                creatorPackage = action.actionIntent?.creatorPackage,
                semanticAction = if (Build.VERSION.SDK_INT >= 28) {
                    action.semanticAction
                } else 0,
                isReplyAction = replyInput != null,
                replyLabel = replyInput?.label?.toString(),
                replyChoices = replyInput?.choices?.map { it.toString() }?.let { JSONArray(it).toString() },
                remoteInputKey = replyInput?.resultKey,
                allowsFreeFormInput = replyInput?.allowFreeFormInput ?: false,
                isContextual = if (Build.VERSION.SDK_INT >= 29) {
                    action.isContextual
                } else false,
                isAuthenticationRequired = if (Build.VERSION.SDK_INT >= 31) {
                    action.isAuthenticationRequired
                } else false,
                captureTime = captureTime
            )
        }
    }

    /**
     * 提取聯絡人資訊
     */
    private fun extractPeople(extras: Bundle): String? {
        @Suppress("DEPRECATION")
        val people = if (Build.VERSION.SDK_INT >= 28) {
            extras.getParcelableArrayList<android.app.Person>(Notification.EXTRA_PEOPLE_LIST)
                ?.map { it.uri?.toString() ?: it.name?.toString() ?: "" }
        } else {
            extras.getStringArray(Notification.EXTRA_PEOPLE)?.toList()
        }

        return people?.takeIf { it.isNotEmpty() }?.let { JSONArray(it).toString() }
    }

    /**
     * 檢查是否為 MessagingStyle
     */
    private fun isMessagingStyle(extras: Bundle): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        return extras.containsKey(Notification.EXTRA_MESSAGES) ||
               extras.containsKey(Notification.EXTRA_MESSAGING_PERSON)
    }

    /**
     * 提取 MessagingStyle 訊息
     */
    private fun extractMessages(extras: Bundle): String? {
        if (Build.VERSION.SDK_INT < 24) return null

        @Suppress("DEPRECATION")
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (messages.isNullOrEmpty()) return null

        val jsonArray = JSONArray()
        for (msg in messages) {
            if (msg is Bundle) {
                val jsonObj = JSONObject()
                jsonObj.put("text", msg.getCharSequence("text")?.toString())
                jsonObj.put("time", msg.getLong("time"))
                jsonObj.put("sender", msg.getCharSequence("sender")?.toString())
                jsonArray.put(jsonObj)
            }
        }

        return if (jsonArray.length() > 0) jsonArray.toString() else null
    }

    /**
     * Bundle 轉 JSON
     */
    @Suppress("DEPRECATION")
    private fun bundleToJson(bundle: Bundle): String {
        val json = JSONObject()
        for (key in bundle.keySet()) {
            try {
                val value = bundle.get(key)
                when (value) {
                    null -> json.put(key, JSONObject.NULL)
                    is CharSequence -> json.put(key, value.toString())
                    is Number -> json.put(key, value)
                    is Boolean -> json.put(key, value)
                    is Bitmap -> json.put(key, "[Bitmap ${value.width}x${value.height}]")
                    is Bundle -> json.put(key, "[Bundle]")
                    is Array<*> -> json.put(key, JSONArray(value.map { it?.toString() }))
                    else -> if (Build.VERSION.SDK_INT >= 23 && value is android.graphics.drawable.Icon) {
                        json.put(key, "[Icon]")
                    } else {
                        json.put(key, value.javaClass.simpleName)
                    }
                }
            } catch (e: Exception) {
                json.put(key, "[Error: ${e.message}]")
            }
        }
        return json.toString()
    }

    /**
     * 提取 Intent 相關資訊（contentIntent/deleteIntent/fullScreenIntent/publicVersion）
     */
    private fun extractIntentInfo(notification: Notification): String {
        val json = JSONObject()
        json.put("contentIntent", extractPendingIntentInfo(notification.contentIntent))
        json.put("deleteIntent", extractPendingIntentInfo(notification.deleteIntent))
        json.put("fullScreenIntent", extractPendingIntentInfo(notification.fullScreenIntent))
        json.put("publicVersion", notification.publicVersion?.let { extractPublicVersion(it) })
        return json.toString()
    }

    /**
     * 提取單一 PendingIntent 的描述性資訊
     */
    private fun extractPendingIntentInfo(pi: android.app.PendingIntent?): JSONObject? {
        pi ?: return null
        return JSONObject().apply {
            put("creatorPackage", pi.creatorPackage)
            put("creatorUid", pi.creatorUid)
            put("creatorUserHandle", pi.creatorUserHandle?.hashCode())
            if (Build.VERSION.SDK_INT >= 34) {
                put("isActivity", pi.isActivity)
                put("isBroadcast", pi.isBroadcast)
                put("isService", pi.isService)
                put("isForegroundService", pi.isForegroundService)
                put("isImmutable", pi.isImmutable)
            }
        }
    }

    /**
     * 提取 publicVersion（鎖屏顯示版本）的通知內容
     */
    private fun extractPublicVersion(notification: Notification): JSONObject {
        val extras = notification.extras ?: Bundle()
        return JSONObject().apply {
            put("title", extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            put("text", extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            put("subText", extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())
            put("flags", notification.flags)
            put("visibility", notification.visibility)
            put("category", notification.category)
        }
    }

    /**
     * 推斷通知持久性類型
     */
    private fun inferPersistenceType(flags: Int): String {
        return when {
            ApiVersionHelper.isForegroundService(flags) -> PersistenceType.FOREGROUND_SERVICE
            ApiVersionHelper.isOngoing(flags) -> PersistenceType.ONGOING
            ApiVersionHelper.isNoClear(flags) -> PersistenceType.PINNED
            else -> PersistenceType.TRANSIENT
        }
    }

    /**
     * 產生內容快照（用於事件記錄）
     * 涵蓋所有可能在通知更新時變動的欄位，以便完整追蹤差異
     */
    @Suppress("DEPRECATION")
    fun generateContentSnapshot(notification: Notification): String {
        val extras = notification.extras ?: Bundle()
        val json = JSONObject()

        // 基本內容
        json.put("title", extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        json.put("text", extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        json.put("bigText", extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
        json.put("bigTitle", extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString())
        json.put("subText", extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())
        json.put("infoText", extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString())
        json.put("summaryText", extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString())
        json.put("tickerText", notification.tickerText?.toString())

        // 進度
        json.put("progress", extras.getInt(Notification.EXTRA_PROGRESS, 0))
        json.put("progressMax", extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0))
        json.put("progressIndeterminate", extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false))

        // MessagingStyle (API 24+)
        if (Build.VERSION.SDK_INT >= 24) {
            json.put("conversationTitle",
                extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString())
        }
        if (Build.VERSION.SDK_INT >= 28) {
            json.put("isGroupConversation",
                extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false))
        }

        // 樣式模板
        json.put("template", extras.getString(Notification.EXTRA_TEMPLATE))

        // 通知屬性
        json.put("flags", notification.flags)
        json.put("priority", notification.priority)
        json.put("visibility", notification.visibility)
        json.put("category", notification.category)
        json.put("color", notification.color)
        json.put("group", notification.group)
        json.put("sortKey", notification.sortKey)
        json.put("when", notification.`when`)

        // Channel (API 26+)
        if (Build.VERSION.SDK_INT >= 26) {
            json.put("channelId", notification.channelId)
        }

        // 動作數量
        json.put("actionCount", notification.actions?.size ?: 0)

        // 自訂 View 狀態
        json.put("hasCustomContentView", notification.contentView != null)
        json.put("hasCustomBigContentView", notification.bigContentView != null)

        return json.toString()
    }
}
