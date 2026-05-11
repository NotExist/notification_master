package com.notificationmaster.core

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import androidx.annotation.RequiresApi
import com.notificationmaster.core.compat.ApiVersionHelper
import com.notificationmaster.core.dedup.ContentHashGenerator
import com.notificationmaster.data.db.entity.ActionEntity
import com.notificationmaster.data.db.entity.EventType
import com.notificationmaster.data.db.entity.NotificationEntity
import com.notificationmaster.data.db.entity.NotificationEventEntity
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 通知資料提取器
 * 從 StatusBarNotification 和 Notification 物件中提取所有可存取的資訊
 */
class NotificationExtractor(private val context: Context) {

    companion object {
        /** JSON 欄位大小上限（64 KB），超過則截斷並附帶標記 */
        internal const val MAX_JSON_SIZE = 64 * 1024

        /** 已知會由 MediaExtractor 另存的 Bitmap extras key → 媒體類型名稱 */
        @SuppressLint("InlinedApi")
        private val EXTRAS_MEDIA_MAPPING = mapOf(
            Notification.EXTRA_LARGE_ICON to "LARGE_ICON",
            Notification.EXTRA_PICTURE to "PICTURE",
            Notification.EXTRA_LARGE_ICON_BIG to "LARGE_ICON_BIG"
        )

        /** 截斷過長 JSON 字串，附帶截斷標記 */
        internal fun truncateJson(json: String): String {
            if (json.length <= MAX_JSON_SIZE) return json
            return json.substring(0, MAX_JSON_SIZE) + "…[truncated, original ${json.length} chars]"
        }
    }

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

        // 取得 Ranking 資訊（getRanking 失敗時視為不可用）
        val ranking = rankingMap?.let { map ->
            val r = Ranking()
            if (map.getRanking(ApiVersionHelper.getNotificationKey(sbn), r)) r else null
        }

        // Channel importance（唯一可靠來源：ranking.importance，API 24+）
        val channelImportance = if (Build.VERSION.SDK_INT >= 24 && ranking != null) {
            ranking.importance
        } else {
            -1
        }

        // 提取基本內容
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val template = extras.getString(Notification.EXTRA_TEMPLATE)

        // 產生內容 Hash
        val contentHash = ContentHashGenerator.generateHash(
            sbn.packageName,
            title,
            text,
            template
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
            isAudible = ApiVersionHelper.isLikelyAudible(
                lastAudiblyAlertedMillis = if (Build.VERSION.SDK_INT >= 29) {
                    ranking?.lastAudiblyAlertedMillis ?: -1L
                } else -1L,
                captureTime = captureTime,
                importance = channelImportance,
                flags = flags,
                soundUri = notification.sound?.toString(),
                isUpdate = false
            ),

            // 可見性
            visibility = notification.visibility,

            // 分類
            category = notification.category,

            // 群組
            groupKey = notification.group,
            overrideGroupKey = if (Build.VERSION.SDK_INT >= 24) sbn.overrideGroupKey else null,
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
            template = template,

            // 自訂 View
            hasCustomContentView = notification.contentView != null,
            hasCustomBigContentView = notification.bigContentView != null,
            hasCustomHeadsUpContentView = notification.headsUpContentView != null,

            // 完整 Extras（截斷過長內容）
            extrasJson = truncateJson(bundleToJson(extras)),

            // 完整原始 dump（截斷過長內容）
            rawDataJson = truncateJson(buildRawDataJson(sbn, notification, ranking)),

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
            lastAudiblyAlertedMillis = if (Build.VERSION.SDK_INT >= 29) {
                ranking?.lastAudiblyAlertedMillis ?: -1L
            } else -1L,

            // Intent 資訊
            hasContentIntent = notification.contentIntent != null,
            hasDeleteIntent = notification.deleteIntent != null,
            hasFullScreenIntent = notification.fullScreenIntent != null,
            contentIntentCreatorPackage = notification.contentIntent?.creatorPackage
        )
    }

    /**
     * 產出 Plan 2 新 schema 下的 [NotificationEventEntity]。
     *
     * 與舊 [extractNotification] 差異：
     * - 不再分解 50+ 欄位 column；只保留索引投影（package/channel/title/text/post_time/content_hash）
     *   與篩選投影（is_audible / likely_headsup），其餘以 [eventRawJson] 自包含
     * - [eventRawJson] 由 [RawSerializer] 序列化 sbn + notification（不含 ranking，ranking 在
     *   RankingObservation 獨立軌道）
     *
     * @param sbn 來源通知
     * @param eventType POSTED/UPDATED/INITIAL/REMOVED
     * @param eventTime 事件發生時間（service 端決定，通常 = captureTime）
     * @param captureTime 擷取進入 service 時間
     * @param isAudible RankingProcessor 推斷後傳入
     * @param likelyHeadsup RankingProcessor 推斷後傳入
     * @param removalReason REMOVED 時系統回傳的原因碼；其他事件 null
     */
    @Suppress("DEPRECATION")
    fun extractEvent(
        sbn: StatusBarNotification,
        eventType: EventType,
        eventTime: Long,
        captureTime: Long,
        isAudible: Boolean,
        likelyHeadsup: Boolean,
        removalReason: Int? = null
    ): NotificationEventEntity {
        val notification = sbn.notification
        val extras = notification.extras ?: Bundle()

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val template = extras.getString(Notification.EXTRA_TEMPLATE)

        val contentHash = ContentHashGenerator.generateHash(
            sbn.packageName, title, text, template
        )

        val rawJson = buildEventRawJson(sbn, eventType, captureTime, removalReason)

        return NotificationEventEntity(
            notificationKey = ApiVersionHelper.getNotificationKey(sbn),
            eventType = eventType,
            eventTime = eventTime,
            captureTime = captureTime,
            packageName = sbn.packageName,
            channelId = if (Build.VERSION.SDK_INT >= 26) notification.channelId else null,
            postTime = sbn.postTime,
            contentHash = contentHash,
            title = title,
            text = text,
            removalReason = removalReason,
            removalReasonCategory = removalReason?.let { ApiVersionHelper.categorizeRemovalReason(it) },
            isAudible = isAudible,
            likelyHeadsup = likelyHeadsup,
            eventRawJson = truncateJson(rawJson)
        )
    }

    /**
     * 建構 eventRawJson：callback 元資料 + sbn（含 notification 子物件），**不含 ranking**。
     *
     * 結構對齊 Plan 2 §3：
     * ```
     * {
     *   "callbackType": "POSTED|UPDATED|INITIAL|REMOVED",
     *   "captureTime": <long>,
     *   "removalReason": <int?>,
     *   "sbn": { ... RawSerializer 序列化結果 ... }
     * }
     * ```
     */
    private fun buildEventRawJson(
        sbn: StatusBarNotification,
        eventType: EventType,
        captureTime: Long,
        removalReason: Int?
    ): String {
        val root = JSONObject()
        root.put("callbackType", eventType.name)
        root.put("captureTime", captureTime)
        if (removalReason != null) root.put("removalReason", removalReason)
        root.put("sbn", RawSerializer.serialize(sbn))
        return root.toString()
    }

    /**
     * 提取 Action 按鈕資訊
     *
     * Plan 2：FK 參數改名 [eventId]，呼叫端在 transaction 中取得 event id 後 copy(eventId = ...)
     * 或先傳 0 再 batch 補正。
     */
    @Suppress("DEPRECATION")
    fun extractActions(
        notification: Notification,
        eventId: Long,
        captureTime: Long = System.currentTimeMillis()
    ): List<ActionEntity> {
        val actions = notification.actions ?: return emptyList()

        return actions.mapIndexed { index, action ->
            val remoteInputs = action.remoteInputs

            val replyInput = remoteInputs?.firstOrNull { it.allowFreeFormInput }

            ActionEntity(
                eventId = eventId,
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
    @SuppressLint("InlinedApi")
    private fun isMessagingStyle(extras: Bundle): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        return extras.containsKey(Notification.EXTRA_MESSAGES) ||
               extras.containsKey(Notification.EXTRA_MESSAGING_PERSON)
    }

    /**
     * 提取 MessagingStyle 訊息
     * 包含基本欄位（text, time, sender）以及媒體附件資訊（type, uri）
     * 和 sender_person 結構化資訊（API 28+）
     */
    @Suppress("DEPRECATION")
    private fun extractMessages(extras: Bundle): String? {
        if (Build.VERSION.SDK_INT < 24) return null

        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (messages.isNullOrEmpty()) return null

        val jsonArray = JSONArray()
        for (msg in messages) {
            if (msg is Bundle) {
                val jsonObj = JSONObject()
                jsonObj.put("text", msg.getCharSequence("text")?.toString())
                jsonObj.put("time", msg.getLong("time"))
                jsonObj.put("sender", msg.getCharSequence("sender")?.toString())
                // 媒體附件資訊 (Message.setData 設定的 mimeType 和 URI)
                msg.getString("type")?.let { jsonObj.put("type", it) }
                msg.getParcelable<Uri>("uri")?.let { jsonObj.put("uri", it.toString()) }
                // sender_person 結構化資訊 (API 28+)
                if (Build.VERSION.SDK_INT >= 28) {
                    msg.getParcelable<android.app.Person>("sender_person")?.let { person ->
                        jsonObj.put("sender_person", personToJson(person))
                    }
                }
                jsonArray.put(jsonObj)
            }
        }

        return if (jsonArray.length() > 0) jsonArray.toString() else null
    }

    /**
     * Person 物件序列化為 JSON（API 28+）
     */
    @RequiresApi(28)
    private fun personToJson(person: android.app.Person): JSONObject {
        return JSONObject().apply {
            put("name", person.name?.toString())
            put("key", person.key)
            put("uri", person.uri?.toString())
            put("isBot", person.isBot)
            put("isImportant", person.isImportant)
            put("hasIcon", person.icon != null)
        }
    }

    /**
     * 計算 Bitmap Hash（與 MediaExtractor.bitmapHash 相同演算法）
     */
    private fun computeBitmapHash(bitmap: Bitmap): String {
        val buffer = java.nio.ByteBuffer.allocate(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(buffer)
        return MessageDigest.getInstance("SHA-256")
            .digest(buffer.array())
            .joinToString("") { "%02x".format(it) }.take(32)
    }

    /**
     * Bundle 轉 JSON（遞迴序列化）
     */
    private fun bundleToJson(bundle: Bundle): String {
        return bundleToJsonObject(bundle, 0).toString()
    }

    private fun bundleToJsonObject(bundle: Bundle, depth: Int): JSONObject {
        val maxDepth = 4
        val json = JSONObject()
        for (key in bundle.keySet()) {
            try {
                @Suppress("DEPRECATION")
                val value = bundle.get(key)

                // 已知 Bitmap extras：附加實際存檔檔名參照
                val mediaType = EXTRAS_MEDIA_MAPPING[key]
                if (mediaType != null && value is Bitmap) {
                    val hash = computeBitmapHash(value)
                    json.put(key, "[Bitmap ${value.width}x${value.height}, saved to ${mediaType}_${hash}.png]")
                    continue
                }

                json.put(key, valueToJson(value, depth, maxDepth))
            } catch (e: Exception) {
                json.put(key, "[Error: ${e.message}]")
            }
        }
        return json
    }

    @Suppress("DEPRECATION")
    private fun valueToJson(value: Any?, depth: Int, maxDepth: Int): Any {
        return when {
            value == null -> JSONObject.NULL
            value is CharSequence -> value.toString()
            value is Number -> value
            value is Boolean -> value
            value is Bitmap -> "[Bitmap ${value.width}x${value.height}]"
            value is Bundle -> {
                if (depth < maxDepth) bundleToJsonObject(value, depth + 1)
                else "[Bundle depth>$maxDepth]"
            }
            value is Array<*> -> {
                val arr = JSONArray()
                for (item in value) {
                    arr.put(valueToJson(item, depth + 1, maxDepth))
                }
                arr
            }
            value is Uri -> value.toString()
            Build.VERSION.SDK_INT >= 28 && value is android.app.Person -> personToJson(value)
            Build.VERSION.SDK_INT >= 23 && value is Icon -> "[Icon]"
            value is IntArray -> JSONArray(value.toList())
            value is LongArray -> JSONArray(value.toList())
            value is BooleanArray -> JSONArray(value.toList())
            else -> "[${value.javaClass.simpleName}]"
        }
    }

    /**
     * 提取單一 PendingIntent 的描述性資訊
     */
    private fun extractPendingIntentInfo(pi: android.app.PendingIntent?): JSONObject? {
        pi ?: return null
        return JSONObject().apply {
            put("creatorPackage", pi.creatorPackage)
            put("creatorUid", pi.creatorUid)
            put("creatorUserHandle", pi.creatorUserHandle.hashCode())
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
     * 解析 RemoteViews layout resource name
     * 透過來源 App 的 Context 取得 resource name（如 "com.whatsapp:layout/notification_content"）
     */
    private fun resolveLayoutName(packageName: String, layoutId: Int): String? {
        return try {
            val pkgCtx = context.createPackageContext(packageName, 0)
            pkgCtx.resources.getResourceName(layoutId)
        } catch (_: Exception) { null }
    }

    /**
     * 建構完整原始資料 JSON (SBN + Notification + Ranking)
     * 與 extrasJson 合起來 = 100% 原始資料
     */
    @Suppress("DEPRECATION")
    private fun buildRawDataJson(
        sbn: StatusBarNotification,
        notification: Notification,
        ranking: Ranking?
    ): String {
        val root = JSONObject()

        // === sbn 區塊 ===
        root.put("sbn", JSONObject().apply {
            put("isClearable", sbn.isClearable)
            if (Build.VERSION.SDK_INT >= 24) {
                put("overrideGroupKey", sbn.overrideGroupKey)
            }
            if (Build.VERSION.SDK_INT >= 29) {
                put("uid", sbn.uid)
            }
        })

        // === notification 區塊 (非 extras 的部分) ===
        root.put("notification", JSONObject().apply {
            put("number", notification.number)
            put("defaults", notification.defaults)
            put("flagsDecoded", decodeFlagsToJson(notification.flags))

            if (Build.VERSION.SDK_INT >= 26) {
                put("timeoutAfter", notification.timeoutAfter)
                put("badgeIconType", notification.badgeIconType)
                put("settingsText", notification.settingsText?.toString())
            }

            if (Build.VERSION.SDK_INT >= 29) {
                put("locusId", notification.locusId?.id)
                put("allowSystemGeneratedContextualActions",
                    notification.allowSystemGeneratedContextualActions)
            }

            // 整合 Intent 資訊
            put("intents", JSONObject().apply {
                put("contentIntent", extractPendingIntentInfo(notification.contentIntent))
                put("deleteIntent", extractPendingIntentInfo(notification.deleteIntent))
                put("fullScreenIntent", extractPendingIntentInfo(notification.fullScreenIntent))
                put("publicVersion", notification.publicVersion?.let { extractPublicVersion(it) })
                // Action 的 PendingIntent 資訊
                notification.actions?.let { actions ->
                    if (actions.isNotEmpty()) {
                        put("actions", JSONArray().apply {
                            for ((i, action) in actions.withIndex()) {
                                put(JSONObject().apply {
                                    put("index", i)
                                    put("title", action.title?.toString())
                                    put("actionIntent", extractPendingIntentInfo(action.actionIntent))
                                })
                            }
                        })
                    }
                }
            })

            // 整合 RemoteViews 資訊
            put("remoteViews", JSONObject().apply {
                notification.contentView?.let {
                    put("contentView", JSONObject().apply {
                        put("layoutId", it.layoutId)
                        put("package", it.`package`)
                        resolveLayoutName(it.`package`, it.layoutId)?.let { name ->
                            put("layoutName", name)
                        }
                    })
                }
                notification.bigContentView?.let {
                    put("bigContentView", JSONObject().apply {
                        put("layoutId", it.layoutId)
                        put("package", it.`package`)
                        resolveLayoutName(it.`package`, it.layoutId)?.let { name ->
                            put("layoutName", name)
                        }
                    })
                }
                notification.headsUpContentView?.let {
                    put("headsUpContentView", JSONObject().apply {
                        put("layoutId", it.layoutId)
                        put("package", it.`package`)
                        resolveLayoutName(it.`package`, it.layoutId)?.let { name ->
                            put("layoutName", name)
                        }
                    })
                }
            })
        })

        // === ranking 區塊 (ranking 非 null 時才產生) ===
        if (ranking != null) {
            root.put("ranking", JSONObject().apply {
                if (Build.VERSION.SDK_INT >= 24) {
                    put("rank", ranking.rank)
                    put("importance", ranking.importance)
                    put("isAmbient", ranking.isAmbient)
                    put("suppressedVisualEffects", ranking.suppressedVisualEffects)
                }
                if (Build.VERSION.SDK_INT >= 26) {
                    put("overrideGroupKey", ranking.overrideGroupKey)
                }
                if (Build.VERSION.SDK_INT >= 28) {
                    put("isSuspended", ranking.isSuspended)
                    put("canShowBadge", ranking.canShowBadge())
                    ranking.channel?.let { ch ->
                        put("channel", JSONObject().apply {
                            put("id", ch.id)
                            put("name", ch.name?.toString())
                            put("description", ch.description)
                            put("importance", ch.importance)
                            put("group", ch.group)
                        })
                    }
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    put("lastAudiblyAlertedMillis", ranking.lastAudiblyAlertedMillis)
                    put("canBubble", ranking.canBubble())
                    // smartReplies
                    val replies = ranking.smartReplies
                    if (replies.isNotEmpty()) {
                        put("smartReplies", JSONArray().apply {
                            for (reply in replies) put(reply.toString())
                        })
                    }
                    // smartActions
                    val smartActions = ranking.smartActions
                    if (smartActions.isNotEmpty()) {
                        put("smartActions", smartActionsToJson(smartActions))
                    }
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    put("isConversation", ranking.isConversation)
                    ranking.conversationShortcutInfo?.let { info ->
                        put("shortcutInfo", JSONObject().apply {
                            put("id", info.id)
                            put("shortLabel", info.shortLabel?.toString())
                            put("longLabel", info.longLabel?.toString())
                        })
                    }
                }
            })
        }

        return root.toString()
    }

    /**
     * 解碼 Notification flags bitmask 為各 FLAG_* 布林值
     */
    @Suppress("DEPRECATION")
    private fun decodeFlagsToJson(flags: Int): JSONObject {
        return JSONObject().apply {
            put("FLAG_SHOW_LIGHTS", (flags and Notification.FLAG_SHOW_LIGHTS) != 0)
            put("FLAG_ONGOING_EVENT", (flags and Notification.FLAG_ONGOING_EVENT) != 0)
            put("FLAG_INSISTENT", (flags and Notification.FLAG_INSISTENT) != 0)
            put("FLAG_ONLY_ALERT_ONCE", (flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0)
            put("FLAG_AUTO_CANCEL", (flags and Notification.FLAG_AUTO_CANCEL) != 0)
            put("FLAG_NO_CLEAR", (flags and Notification.FLAG_NO_CLEAR) != 0)
            put("FLAG_FOREGROUND_SERVICE", (flags and Notification.FLAG_FOREGROUND_SERVICE) != 0)
            put("FLAG_HIGH_PRIORITY", (flags and Notification.FLAG_HIGH_PRIORITY) != 0)
            put("FLAG_LOCAL_ONLY", (flags and Notification.FLAG_LOCAL_ONLY) != 0)
            put("FLAG_GROUP_SUMMARY", (flags and Notification.FLAG_GROUP_SUMMARY) != 0)
            if (Build.VERSION.SDK_INT >= 29) {
                put("FLAG_BUBBLE", (flags and Notification.FLAG_BUBBLE) != 0)
            }
        }
    }

    /**
     * Smart Actions 轉為 JSONArray
     */
    private fun smartActionsToJson(actions: List<Notification.Action>): JSONArray {
        return JSONArray().apply {
            for (action in actions) {
                put(JSONObject().apply {
                    put("title", action.title?.toString())
                    if (Build.VERSION.SDK_INT >= 28) {
                        put("semanticAction", action.semanticAction)
                    }
                    if (Build.VERSION.SDK_INT >= 29) {
                        put("isContextual", action.isContextual)
                    }
                    // 首個 remoteInput 的摘要
                    action.remoteInputs?.firstOrNull()?.let { input ->
                        put("remoteInput", JSONObject().apply {
                            put("key", input.resultKey)
                            put("label", input.label?.toString())
                        })
                    }
                })
            }
        }
    }
}
