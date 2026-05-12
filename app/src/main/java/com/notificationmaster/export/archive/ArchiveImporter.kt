package com.notificationmaster.export.archive

import android.content.Context
import android.util.Base64
import android.util.Log
import com.notificationmaster.core.media.MediaExtractor
import com.notificationmaster.data.db.entity.EventType
import com.notificationmaster.data.db.entity.NotificationEventEntity
import com.notificationmaster.data.db.entity.NotificationRecordEntity
import com.notificationmaster.data.db.entity.ObservationSource
import com.notificationmaster.data.db.entity.RankingObservationEntity
import com.notificationmaster.data.db.entity.RankingSnapshotEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/**
 * JSON 封存匯入器（Plan 2 Phase 8 重寫）
 *
 * 僅支援匯出格式 v2.0：records / events / rankingObservations / rankingSnapshots / mediaIndex。
 * 舊 v1.0 格式（NotificationEntity 為主體）不再支援。
 */
class ArchiveImporter(private val context: Context) {

    companion object {
        private const val TAG = "ArchiveImporter"
        private const val SUPPORTED_VERSION_PREFIX = "2."
    }

    /**
     * 解析封存檔案
     *
     * @param inputStream 輸入串流
     * @return 解析後的封存資料；不支援版本時 throws
     */
    suspend fun import(inputStream: InputStream): ArchiveData = withContext(Dispatchers.IO) {
        val jsonStr = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val json = JSONObject(jsonStr)

        val exportInfo = parseExportInfo(json.getJSONObject("exportInfo"))
        require(exportInfo.exportVersion.startsWith(SUPPORTED_VERSION_PREFIX)) {
            "不支援的匯出版本：${exportInfo.exportVersion}（需 $SUPPORTED_VERSION_PREFIX*）"
        }

        val environment = parseEnvironment(json.optJSONObject("environment"))
        val archiveRange = parseArchiveRange(json.optJSONObject("archiveRange"))

        val records = parseArray(json.optJSONArray("records"), "record", ::parseRecord)
        val events = parseArray(json.optJSONArray("events"), "event", ::parseEvent)
        val observations = parseArray(
            json.optJSONArray("rankingObservations"), "rankingObservation", ::parseObservation
        )
        val snapshots = parseArray(
            json.optJSONArray("rankingSnapshots"), "rankingSnapshot", ::parseSnapshot
        )

        // 提取內嵌的 Base64 媒體
        val mediaArray = json.optJSONArray("mediaIndex")
        if (mediaArray != null) {
            for (i in 0 until mediaArray.length()) {
                try {
                    val mediaJson = mediaArray.getJSONObject(i)
                    val base64 = mediaJson.optString("base64", "")
                    if (base64.isNotEmpty()) {
                        val fileName = mediaJson.getString("filePath")
                        val mediaDir = File(MediaExtractor.getMediaBaseDir(context), "media")
                        mediaDir.mkdirs()
                        val file = File(mediaDir, fileName)
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
            records = records,
            events = events,
            observations = observations,
            snapshots = snapshots
        )
    }

    private fun <T> parseArray(
        array: org.json.JSONArray?,
        kind: String,
        parser: (JSONObject) -> T
    ): MutableList<T> {
        val out = mutableListOf<T>()
        if (array == null) return out
        for (i in 0 until array.length()) {
            try {
                out.add(parser(array.getJSONObject(i)))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse $kind at index $i", e)
            }
        }
        return out
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
            recordCount = json.optInt("recordCount", 0),
            eventCount = json.optInt("eventCount", 0),
            observationCount = json.optInt("observationCount", 0),
            snapshotCount = json.optInt("snapshotCount", 0)
        )
    }

    private fun parseRecord(json: JSONObject): NotificationRecordEntity {
        val notificationKey = json.getString("notificationKey")
        val packageName = json.getString("packageName")
        require(notificationKey.isNotBlank()) { "Record notificationKey must not be blank" }
        require(packageName.isNotBlank()) { "Record packageName must not be blank" }
        return NotificationRecordEntity(
            notificationKey = notificationKey,
            packageName = packageName,
            channelId = json.optStringOrNull("channelId"),
            notificationId = json.optInt("notificationId", 0),
            tag = json.optStringOrNull("tag"),
            firstSeen = json.getLong("firstSeen"),
            lastSeen = json.getLong("lastSeen"),
            eventCount = json.optInt("eventCount", 0)
        )
    }

    private fun parseEvent(json: JSONObject): NotificationEventEntity {
        val notificationKey = json.getString("notificationKey")
        require(notificationKey.isNotBlank()) { "Event notificationKey must not be blank" }

        // 允許 0 與負值 reason（如 -100 = RECONCILED_AFTER_FACT）
        val rawReason = if (json.has("removalReason")) json.optInt("removalReason", Int.MIN_VALUE) else Int.MIN_VALUE
        val removalReason = if (rawReason == Int.MIN_VALUE) null else rawReason

        return NotificationEventEntity(
            id = json.optLong("id", 0),
            notificationKey = notificationKey,
            eventType = EventType.valueOf(json.getString("eventType")),
            eventTime = json.getLong("eventTime"),
            captureTime = json.optLong("captureTime", json.getLong("eventTime")),
            packageName = json.optString("packageName", ""),
            channelId = json.optStringOrNull("channelId"),
            postTime = json.optLong("postTime", json.getLong("eventTime")),
            contentHash = json.optString("contentHash", ""),
            title = json.optStringOrNull("title"),
            text = json.optStringOrNull("text"),
            removalReason = removalReason,
            removalReasonCategory = json.optStringOrNull("removalReasonCategory"),
            isAudible = json.optBoolean("isAudible", false),
            likelyHeadsup = json.optBoolean("likelyHeadsup", false),
            eventRawJson = json.optString("eventRawJson", "")
        )
    }

    private fun parseObservation(json: JSONObject): RankingObservationEntity {
        val notificationKey = json.getString("notificationKey")
        require(notificationKey.isNotBlank()) { "Observation notificationKey must not be blank" }
        val rankRaw = if (json.has("rank")) json.optInt("rank", Int.MIN_VALUE) else Int.MIN_VALUE
        val lastAudiblyRaw = if (json.has("lastAudiblyAlertedMillis"))
            json.optLong("lastAudiblyAlertedMillis", Long.MIN_VALUE) else Long.MIN_VALUE
        return RankingObservationEntity(
            id = json.optLong("id", 0),
            notificationKey = notificationKey,
            observedAt = json.getLong("observedAt"),
            rankingSnapshotId = json.getLong("rankingSnapshotId"),
            source = ObservationSource.valueOf(json.getString("source")),
            rank = if (rankRaw == Int.MIN_VALUE) null else rankRaw,
            lastAudiblyAlertedMillis = if (lastAudiblyRaw == Long.MIN_VALUE) null else lastAudiblyRaw
        )
    }

    private fun parseSnapshot(json: JSONObject): RankingSnapshotEntity {
        val contentHash = json.getString("contentHash")
        require(contentHash.isNotBlank()) { "Snapshot contentHash must not be blank" }
        return RankingSnapshotEntity(
            id = json.optLong("id", 0),
            contentHash = contentHash,
            rankingJson = json.optString("rankingJson", "{}"),
            firstSeen = json.getLong("firstSeen")
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null
}

/**
 * 封存資料模型
 */
data class ArchiveData(
    val exportInfo: ExportInfo,
    val environment: ArchiveEnvironment?,
    val archiveRange: ArchiveRange?,
    val records: List<NotificationRecordEntity>,
    val events: List<NotificationEventEntity>,
    val observations: List<RankingObservationEntity>,
    val snapshots: List<RankingSnapshotEntity>
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
    val recordCount: Int,
    val eventCount: Int,
    val observationCount: Int,
    val snapshotCount: Int
)
