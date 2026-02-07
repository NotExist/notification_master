package com.notificationmaster.export.archive

import android.content.Context
import android.os.Build
import android.util.Base64
import com.notificationmaster.BuildConfig
import com.notificationmaster.data.db.NotificationDatabase
import com.notificationmaster.data.db.entity.NotificationEntity
import com.notificationmaster.data.db.entity.NotificationEventEntity
import com.notificationmaster.data.model.EnvironmentInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * JSON 封存匯出器
 * 將通知記錄和事件歷程匯出為結構化 JSON
 * 包含環境資訊、完整通知記錄、事件歷程、媒體索引
 */
class ArchiveExporter(
    private val context: Context,
    private val database: NotificationDatabase
) {

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * 匯出指定時間範圍的資料
     *
     * @param startTime 起始時間（毫秒）
     * @param endTime 結束時間（毫秒）
     * @param outputStream 輸出串流
     * @param includeMediaBase64 是否內嵌媒體 Base64（小型檔案用）
     */
    suspend fun export(
        startTime: Long,
        endTime: Long,
        outputStream: OutputStream,
        includeMediaBase64: Boolean = false
    ): ArchiveStats = withContext(Dispatchers.IO) {
        val notifications = database.notificationDao()
            .getNotificationsByTimeRangePaged(startTime, endTime, Int.MAX_VALUE, 0)

        val allEvents = mutableListOf<NotificationEventEntity>()
        val mediaIndex = JSONArray()

        for (notification in notifications) {
            val events = database.notificationEventDao()
                .getEventsByNotificationKey(notification.notificationKey)
            allEvents.addAll(events)

            // 媒體索引
            val attachments = database.mediaAttachmentDao()
                .getAttachmentsByNotificationIdSync(notification.id)
            for (attachment in attachments) {
                val mediaJson = JSONObject().apply {
                    put("notificationId", notification.id)
                    put("mediaType", attachment.mediaType.name)
                    put("filePath", attachment.filePath)
                    put("mimeType", attachment.mimeType)
                    put("fileSize", attachment.fileSize)
                    put("width", attachment.width)
                    put("height", attachment.height)
                    put("contentHash", attachment.contentHash)

                    if (includeMediaBase64) {
                        val file = File(context.filesDir, attachment.filePath)
                        if (file.exists()) {
                            put("base64", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
                        }
                    }
                }
                mediaIndex.put(mediaJson)
            }
        }

        val archive = buildArchiveJson(
            startTime, endTime,
            notifications, allEvents, mediaIndex
        )

        outputStream.write(archive.toString(2).toByteArray(Charsets.UTF_8))
        outputStream.flush()

        ArchiveStats(
            notificationCount = notifications.size,
            eventCount = allEvents.size,
            mediaCount = mediaIndex.length()
        )
    }

    private fun buildArchiveJson(
        startTime: Long,
        endTime: Long,
        notifications: List<NotificationEntity>,
        events: List<NotificationEventEntity>,
        mediaIndex: JSONArray
    ): JSONObject {
        val envInfo = EnvironmentInfo.create(
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE.toLong()
        )

        return JSONObject().apply {
            // 匯出資訊
            put("exportInfo", JSONObject().apply {
                put("exportTime", isoFormat.format(Date()))
                put("exportVersion", "1.0")
                put("appVersion", envInfo.appVersion)
                put("appVersionCode", envInfo.appVersionCode)
            })

            // 環境資訊
            put("environment", JSONObject().apply {
                put("androidVersion", envInfo.androidVersion)
                put("apiLevel", envInfo.apiLevel)
                put("sdkInt", envInfo.sdkInt)
                put("deviceModel", envInfo.deviceModel)
                put("deviceManufacturer", envInfo.deviceManufacturer)
                put("supportedFeatures", JSONObject().apply {
                    val features = envInfo.supportedFeatures
                    put("notificationChannel", features.notificationChannel)
                    put("messagingStyle", features.messagingStyle)
                    put("bubbles", features.bubbles)
                    put("directReply", features.directReply)
                    put("semanticAction", features.semanticAction)
                    put("rankingDetails", features.rankingDetails)
                })
            })

            // 時間範圍
            put("archiveRange", JSONObject().apply {
                put("startTime", isoFormat.format(Date(startTime)))
                put("endTime", isoFormat.format(Date(endTime)))
                put("notificationCount", notifications.size)
                put("eventCount", events.size)
            })

            // 通知資料
            put("notifications", JSONArray().apply {
                for (n in notifications) {
                    put(notificationToJson(n))
                }
            })

            // 事件資料
            put("events", JSONArray().apply {
                for (e in events) {
                    put(eventToJson(e))
                }
            })

            // 媒體索引
            put("mediaIndex", mediaIndex)
        }
    }

    private fun notificationToJson(n: NotificationEntity): JSONObject {
        return JSONObject().apply {
            put("id", n.id)
            put("notificationKey", n.notificationKey)
            put("packageName", n.packageName)
            put("notificationId", n.notificationId)
            put("tag", n.tag)
            put("postTime", n.postTime)
            put("captureTime", n.captureTime)
            put("whenTime", n.whenTime)
            put("title", n.title)
            put("text", n.text)
            put("subText", n.subText)
            put("infoText", n.infoText)
            put("summaryText", n.summaryText)
            put("bigText", n.bigText)
            put("bigTitle", n.bigTitle)
            put("tickerText", n.tickerText)
            put("flags", n.flags)
            put("isOngoing", n.isOngoing)
            put("isForegroundService", n.isForegroundService)
            put("isAutoCancel", n.isAutoCancel)
            put("isNoClear", n.isNoClear)
            put("isGroupSummary", n.isGroupSummary)
            put("priority", n.priority)
            put("importance", n.importance)
            put("likelyHeadsup", n.likelyHeadsup)
            put("visibility", n.visibility)
            put("category", n.category)
            put("groupKey", n.groupKey)
            put("sortKey", n.sortKey)
            put("channelId", n.channelId)
            put("hasBubbleMetadata", n.hasBubbleMetadata)
            put("color", n.color)
            put("contentHash", n.contentHash)
            put("actionCount", n.actionCount)
            put("persistenceType", n.persistenceType)
            put("template", n.template)
            put("isMessagingStyle", n.isMessagingStyle)
            put("conversationTitle", n.conversationTitle)
            put("isGroupConversation", n.isGroupConversation)
            put("messages", n.messages)
            put("extrasJson", n.extrasJson)
        }
    }

    private fun eventToJson(e: NotificationEventEntity): JSONObject {
        return JSONObject().apply {
            put("id", e.id)
            put("notificationId", e.notificationId)
            put("notificationKey", e.notificationKey)
            put("eventType", e.eventType.name)
            put("eventTime", e.eventTime)
            put("removalReason", e.removalReason)
            put("removalReasonCategory", e.removalReasonCategory)
            put("rankingRank", e.rankingRank)
            put("rankingImportance", e.rankingImportance)
            put("isAmbient", e.isAmbient)
            put("isSuspended", e.isSuspended)
            put("suppressedVisualEffects", e.suppressedVisualEffects)
            put("contentSnapshot", e.contentSnapshot)
        }
    }
}

data class ArchiveStats(
    val notificationCount: Int,
    val eventCount: Int,
    val mediaCount: Int
)
