package com.notificationmaster.export.archive

import android.content.Context
import android.util.Base64
import com.notificationmaster.BuildConfig
import com.notificationmaster.core.media.MediaExtractor
import com.notificationmaster.data.db.NotificationDatabase
import com.notificationmaster.data.db.entity.NotificationEventEntity
import com.notificationmaster.data.db.entity.NotificationRecordEntity
import com.notificationmaster.data.db.entity.RankingObservationEntity
import com.notificationmaster.data.db.entity.RankingSnapshotEntity
import com.notificationmaster.data.model.EnvironmentInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * JSON 封存匯出器（Plan 2 Phase 8 重寫）
 *
 * 匯出格式 v2：以 NotificationEventEntity 為主體 + NotificationRecordEntity 聚合錨點 +
 * RankingSnapshotEntity / RankingObservationEntity 獨立軌道 + 媒體索引。
 * 對應 Plan 2 重構後的資料模型，舊 v1 格式不再支援。
 */
class ArchiveExporter(
    private val context: Context,
    private val database: NotificationDatabase
) {

    companion object {
        const val EXPORT_VERSION = "2.0"
    }

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * 匯出指定時間範圍的資料
     *
     * @param startTime 起始時間（毫秒，比對 event_time）
     * @param endTime 結束時間（毫秒，比對 event_time）
     * @param outputStream 輸出串流
     * @param includeMediaBase64 是否內嵌媒體 Base64（小型檔案用）
     */
    suspend fun export(
        startTime: Long,
        endTime: Long,
        outputStream: OutputStream,
        includeMediaBase64: Boolean = false
    ): ArchiveStats = withContext(Dispatchers.IO) {
        // 1. 時間範圍內的所有 events
        val events = database.notificationEventDao()
            .getEventsByTimeRangeSync(startTime, endTime)

        // 2. 收集 events 涉及的 notification_key → records
        val keys = events.map { it.notificationKey }.toSet()
        val records = keys.mapNotNull { database.notificationRecordDao().getByKey(it) }

        // 3. 媒體索引：依 events.id 撈 attachments
        val mediaIndex = JSONArray()
        for (event in events) {
            val attachments = database.mediaAttachmentDao()
                .getAttachmentsByEventIdSync(event.id)
            for (attachment in attachments) {
                val mediaJson = JSONObject().apply {
                    put("eventId", event.id)
                    put("notificationKey", event.notificationKey)
                    put("mediaType", attachment.mediaType.name)
                    put("filePath", attachment.filePath)
                    put("mimeType", attachment.mimeType)
                    put("fileSize", attachment.fileSize)
                    put("width", attachment.width)
                    put("height", attachment.height)
                    put("contentHash", attachment.contentHash)
                    put("captureTime", attachment.captureTime)
                    attachment.sourceUri?.let { put("sourceUri", it) }

                    if (includeMediaBase64) {
                        val bytes = MediaExtractor.readMediaBytes(context, attachment.filePath)
                        if (bytes != null) {
                            put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        }
                    }
                }
                mediaIndex.put(mediaJson)
            }
        }

        // 4. ranking observations：keys 對應 observation，限制在時間範圍
        val observations = keys.flatMap {
            database.rankingObservationDao().getByKeySync(it)
        }.filter { it.observedAt in startTime..endTime }

        // 5. ranking snapshots：observations 涉及的 snapshotId
        val snapshotIds = observations.map { it.rankingSnapshotId }.toSet()
        val snapshots = snapshotIds.mapNotNull { database.rankingSnapshotDao().getById(it) }

        val archive = buildArchiveJson(
            startTime, endTime,
            records, events, observations, snapshots, mediaIndex
        )

        outputStream.write(archive.toString(2).toByteArray(Charsets.UTF_8))
        outputStream.flush()

        ArchiveStats(
            recordCount = records.size,
            eventCount = events.size,
            observationCount = observations.size,
            snapshotCount = snapshots.size,
            mediaCount = mediaIndex.length()
        )
    }

    private fun buildArchiveJson(
        startTime: Long,
        endTime: Long,
        records: List<NotificationRecordEntity>,
        events: List<NotificationEventEntity>,
        observations: List<RankingObservationEntity>,
        snapshots: List<RankingSnapshotEntity>,
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
                put("exportVersion", EXPORT_VERSION)
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
                put("recordCount", records.size)
                put("eventCount", events.size)
                put("observationCount", observations.size)
                put("snapshotCount", snapshots.size)
            })

            // 通知聚合錨點
            put("records", JSONArray().apply {
                for (r in records) put(recordToJson(r))
            })

            // 事件
            put("events", JSONArray().apply {
                for (e in events) put(eventToJson(e))
            })

            // Ranking observation
            put("rankingObservations", JSONArray().apply {
                for (o in observations) put(observationToJson(o))
            })

            // Ranking snapshot
            put("rankingSnapshots", JSONArray().apply {
                for (s in snapshots) put(snapshotToJson(s))
            })

            // 媒體索引
            put("mediaIndex", mediaIndex)
        }
    }

    private fun recordToJson(r: NotificationRecordEntity): JSONObject =
        JSONObject().apply {
            put("notificationKey", r.notificationKey)
            put("packageName", r.packageName)
            put("channelId", r.channelId)
            put("notificationId", r.notificationId)
            put("tag", r.tag)
            put("firstSeen", r.firstSeen)
            put("lastSeen", r.lastSeen)
            put("eventCount", r.eventCount)
        }

    private fun eventToJson(e: NotificationEventEntity): JSONObject =
        JSONObject().apply {
            put("id", e.id)
            put("notificationKey", e.notificationKey)
            put("eventType", e.eventType.name)
            put("eventTime", e.eventTime)
            put("captureTime", e.captureTime)
            put("packageName", e.packageName)
            put("channelId", e.channelId)
            put("postTime", e.postTime)
            put("contentHash", e.contentHash)
            put("title", e.title)
            put("text", e.text)
            // Phase 16：removalReason 已從 entity column 移除，整段在 eventRawJson.removalReason 內
            put("isAudible", e.isAudible)
            put("likelyHeadsup", e.likelyHeadsup)
            put("eventRawJson", e.eventRawJson)
        }

    private fun observationToJson(o: RankingObservationEntity): JSONObject =
        JSONObject().apply {
            put("id", o.id)
            put("notificationKey", o.notificationKey)
            put("observedAt", o.observedAt)
            put("rankingSnapshotId", o.rankingSnapshotId)
            put("source", o.source.name)
            put("rank", o.rank)
            put("lastAudiblyAlertedMillis", o.lastAudiblyAlertedMillis)
        }

    private fun snapshotToJson(s: RankingSnapshotEntity): JSONObject =
        JSONObject().apply {
            put("id", s.id)
            put("contentHash", s.contentHash)
            put("rankingJson", s.rankingJson)
            put("firstSeen", s.firstSeen)
        }
}

data class ArchiveStats(
    val recordCount: Int,
    val eventCount: Int,
    val observationCount: Int,
    val snapshotCount: Int,
    val mediaCount: Int
)
