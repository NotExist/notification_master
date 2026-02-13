package com.notificationmaster.export.archive

import android.content.Context
import android.util.Base64
import android.util.Log
import com.notificationmaster.core.media.MediaExtractor
import com.notificationmaster.data.db.entity.EventType
import com.notificationmaster.data.db.entity.NotificationEntity
import com.notificationmaster.data.db.entity.NotificationEventEntity
import com.notificationmaster.data.db.entity.PersistenceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/**
 * JSON 封存匯入器
 * 解析 JSON 封存檔並載入為唯讀資料
 */
class ArchiveImporter(private val context: Context) {

    companion object {
        private const val TAG = "ArchiveImporter"
    }

    /**
     * 解析封存檔案
     *
     * @param inputStream 輸入串流
     * @return 解析後的封存資料
     */
    suspend fun import(inputStream: InputStream): ArchiveData = withContext(Dispatchers.IO) {
        val jsonStr = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val json = JSONObject(jsonStr)

        val exportInfo = parseExportInfo(json.getJSONObject("exportInfo"))
        val environment = parseEnvironment(json.optJSONObject("environment"))
        val archiveRange = parseArchiveRange(json.optJSONObject("archiveRange"))

        val notifications = mutableListOf<NotificationEntity>()
        val notificationsArray = json.optJSONArray("notifications")
        if (notificationsArray != null) {
            for (i in 0 until notificationsArray.length()) {
                try {
                    notifications.add(parseNotification(notificationsArray.getJSONObject(i)))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse notification at index $i", e)
                }
            }
        }

        val events = mutableListOf<NotificationEventEntity>()
        val eventsArray = json.optJSONArray("events")
        if (eventsArray != null) {
            for (i in 0 until eventsArray.length()) {
                try {
                    events.add(parseEvent(eventsArray.getJSONObject(i)))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse event at index $i", e)
                }
            }
        }

        // 提取內嵌的 Base64 媒體
        val mediaArray = json.optJSONArray("mediaIndex")
        if (mediaArray != null) {
            for (i in 0 until mediaArray.length()) {
                try {
                    val mediaJson = mediaArray.getJSONObject(i)
                    val base64 = mediaJson.optString("base64", "")
                    if (base64.isNotEmpty()) {
                        val filePath = mediaJson.getString("filePath")
                        val file = File(MediaExtractor.getMediaBaseDir(context), filePath)
                        file.parentFile?.mkdirs()
                        file.writeBytes(Base64.decode(base64, Base64.NO_WRAP))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to restore media at index $i", e)
                }
            }
        }

        ArchiveData(
            exportInfo = exportInfo,
            environment = environment,
            archiveRange = archiveRange,
            notifications = notifications,
            events = events
        )
    }

    private fun parseExportInfo(json: JSONObject): ExportInfo {
        return ExportInfo(
            exportTime = json.optString("exportTime", ""),
            exportVersion = json.optString("exportVersion", "1.0"),
            appVersion = json.optString("appVersion", ""),
            appVersionCode = json.optLong("appVersionCode", 0)
        )
    }

    private fun parseEnvironment(json: JSONObject?): ArchiveEnvironment? {
        if (json == null) return null
        return ArchiveEnvironment(
            androidVersion = json.optString("androidVersion", ""),
            apiLevel = json.optInt("apiLevel", 0),
            deviceModel = json.optString("deviceModel", ""),
            deviceManufacturer = json.optString("deviceManufacturer", "")
        )
    }

    private fun parseArchiveRange(json: JSONObject?): ArchiveRange? {
        if (json == null) return null
        return ArchiveRange(
            startTime = json.optString("startTime", ""),
            endTime = json.optString("endTime", ""),
            notificationCount = json.optInt("notificationCount", 0),
            eventCount = json.optInt("eventCount", 0)
        )
    }

    private fun parseNotification(json: JSONObject): NotificationEntity {
        return NotificationEntity(
            id = json.optLong("id", 0),
            notificationKey = json.getString("notificationKey"),
            packageName = json.getString("packageName"),
            notificationId = json.optInt("notificationId", 0),
            tag = json.optString("tag").takeIf { it != "null" && it.isNotEmpty() },
            postTime = json.getLong("postTime"),
            captureTime = json.getLong("captureTime"),
            whenTime = json.optLong("whenTime", 0),
            title = json.optString("title").takeIf { it != "null" && it.isNotEmpty() },
            text = json.optString("text").takeIf { it != "null" && it.isNotEmpty() },
            subText = json.optString("subText").takeIf { it != "null" && it.isNotEmpty() },
            infoText = json.optString("infoText").takeIf { it != "null" && it.isNotEmpty() },
            summaryText = json.optString("summaryText").takeIf { it != "null" && it.isNotEmpty() },
            bigText = json.optString("bigText").takeIf { it != "null" && it.isNotEmpty() },
            bigTitle = json.optString("bigTitle").takeIf { it != "null" && it.isNotEmpty() },
            tickerText = json.optString("tickerText").takeIf { it != "null" && it.isNotEmpty() },
            flags = json.optInt("flags", 0),
            isOngoing = json.optBoolean("isOngoing", false),
            isForegroundService = json.optBoolean("isForegroundService", false),
            isAutoCancel = json.optBoolean("isAutoCancel", false),
            isNoClear = json.optBoolean("isNoClear", false),
            isHighPriority = json.optBoolean("isHighPriority", false),
            isLocalOnly = json.optBoolean("isLocalOnly", false),
            isGroupSummary = json.optBoolean("isGroupSummary", false),
            priority = json.optInt("priority", 0),
            importance = json.optInt("importance", -1),
            likelyHeadsup = json.optBoolean("likelyHeadsup", false),
            isAudible = json.optBoolean("isAudible", false),
            visibility = json.optInt("visibility", 0),
            category = json.optString("category").takeIf { it != "null" && it.isNotEmpty() },
            groupKey = json.optString("groupKey").takeIf { it != "null" && it.isNotEmpty() },
            overrideGroupKey = json.optString("overrideGroupKey").takeIf { it != "null" && it.isNotEmpty() },
            sortKey = json.optString("sortKey").takeIf { it != "null" && it.isNotEmpty() },
            channelId = json.optString("channelId").takeIf { it != "null" && it.isNotEmpty() },
            hasBubbleMetadata = json.optBoolean("hasBubbleMetadata", false),
            color = json.optInt("color", 0),
            soundUri = null,
            vibratePattern = null,
            ledArgb = 0,
            ledOnMs = 0,
            ledOffMs = 0,
            progress = json.optInt("progress", 0),
            progressMax = json.optInt("progressMax", 0),
            progressIndeterminate = json.optBoolean("progressIndeterminate", false),
            showChronometer = json.optBoolean("showChronometer", false),
            chronometerCountDown = json.optBoolean("chronometerCountDown", false),
            showWhen = json.optBoolean("showWhen", true),
            people = null,
            isMessagingStyle = json.optBoolean("isMessagingStyle", false),
            conversationTitle = json.optString("conversationTitle").takeIf { it != "null" && it.isNotEmpty() },
            isGroupConversation = json.optBoolean("isGroupConversation", false),
            messages = json.optString("messages").takeIf { it != "null" && it.isNotEmpty() },
            template = json.optString("template").takeIf { it != "null" && it.isNotEmpty() },
            hasCustomContentView = false,
            hasCustomBigContentView = false,
            hasCustomHeadsUpContentView = false,
            extrasJson = json.optString("extrasJson").takeIf { it != "null" && it.isNotEmpty() },
            rawDataJson = json.optString("rawDataJson").takeIf { it != "null" && it.isNotEmpty() },
            contentHash = json.optString("contentHash", ""),
            actionCount = json.optInt("actionCount", 0),
            userId = 0,
            persistenceType = json.optString("persistenceType", PersistenceType.TRANSIENT),
            rankingRank = json.optInt("rankingRank", -1),
            isAmbient = json.optBoolean("isAmbient", false),
            isSuspended = json.optBoolean("isSuspended", false),
            suppressedVisualEffects = json.optInt("suppressedVisualEffects", 0),
            hasContentIntent = json.optBoolean("hasContentIntent", false),
            hasDeleteIntent = json.optBoolean("hasDeleteIntent", false),
            hasFullScreenIntent = json.optBoolean("hasFullScreenIntent", false),
            contentIntentCreatorPackage = json.optString("contentIntentCreatorPackage").takeIf { it != "null" && it.isNotEmpty() }
        )
    }

    private fun parseEvent(json: JSONObject): NotificationEventEntity {
        return NotificationEventEntity(
            id = json.optLong("id", 0),
            notificationId = json.getLong("notificationId"),
            notificationKey = json.getString("notificationKey"),
            eventType = EventType.valueOf(json.getString("eventType")),
            eventTime = json.getLong("eventTime"),
            removalReason = json.optInt("removalReason", -1).takeIf { it >= 0 },
            removalReasonCategory = json.optString("removalReasonCategory").takeIf { it != "null" && it.isNotEmpty() },
            rankingRank = json.optInt("rankingRank", -1).takeIf { it >= 0 },
            rankingImportance = json.optInt("rankingImportance", -1).takeIf { it >= 0 },
            isAmbient = if (json.has("isAmbient")) json.optBoolean("isAmbient") else null,
            isSuspended = if (json.has("isSuspended")) json.optBoolean("isSuspended") else null,
            suppressedVisualEffects = json.optInt("suppressedVisualEffects", -1).takeIf { it >= 0 },
            contentDiff = json.optString("contentDiff").takeIf { it != "null" && it.isNotEmpty() }
        )
    }
}

/**
 * 封存資料模型
 */
data class ArchiveData(
    val exportInfo: ExportInfo,
    val environment: ArchiveEnvironment?,
    val archiveRange: ArchiveRange?,
    val notifications: List<NotificationEntity>,
    val events: List<NotificationEventEntity>
)

data class ExportInfo(
    val exportTime: String,
    val exportVersion: String,
    val appVersion: String,
    val appVersionCode: Long
)

data class ArchiveEnvironment(
    val androidVersion: String,
    val apiLevel: Int,
    val deviceModel: String,
    val deviceManufacturer: String
)

data class ArchiveRange(
    val startTime: String,
    val endTime: String,
    val notificationCount: Int,
    val eventCount: Int
)
