package com.notificationmaster.export.archive

import android.content.Context
import android.util.Base64
import android.util.JsonWriter
import com.notificationmaster.BuildConfig
import com.notificationmaster.core.debug.ProfileLogger
import com.notificationmaster.core.media.MediaExtractor
import com.notificationmaster.data.db.NotificationDatabase
import com.notificationmaster.data.db.entity.NotificationEventEntity
import com.notificationmaster.data.db.entity.NotificationRecordEntity
import com.notificationmaster.data.db.entity.RankingObservationEntity
import com.notificationmaster.data.db.entity.RankingSnapshotEntity
import com.notificationmaster.data.model.EnvironmentInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
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
 *
 * W23d 串流化：修前一次 getEventsByTimeRangeSync 撈整範圍（含 raw_json）+ per-key/per-event
 * N+1 query + `JSONObject.toString(2)` 把整包 JSON 組成單一 String（大資料量數百 MB →
 * OOM / GC 風暴 → 卡死 ANR）。改用 [JsonWriter] 直寫 OutputStream，events 以 keyset 分批
 * 撈、其餘 entity 以 batch IN 查詢。頂層 key 改為 events 先寫、archiveRange（統計）最後寫
 * — ArchiveImporter（W23r 起亦串流）逐 key 處理、不依賴 key 順序。
 */
class ArchiveExporter(
    private val context: Context,
    private val database: NotificationDatabase
) {

    companion object {
        const val EXPORT_VERSION = "2.0"

        /** W23d：events keyset 分批每批筆數 */
        private const val EVENT_BATCH_SIZE = 500

        /** W23d：batch IN 每批變數數（SQLite 預設上限 999） */
        private const val IN_CHUNK_SIZE = 800
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
        includeMediaBase64: Boolean = false,
        onProgress: (written: Int, total: Int) -> Unit = { _, _ -> }
    ): ArchiveStats = withContext(Dispatchers.IO) {
        val tStart = System.currentTimeMillis()
        fun stage(msg: String) =
            ProfileLogger.append("Export", "$msg since-start=${System.currentTimeMillis() - tStart}ms")

        stage("export start range=$startTime..$endTime")
        // W23w：進度分母 — 範圍內 event 總數（events 是匯出主成本，以此驅動進度條）
        val totalEvents = database.notificationEventDao().getCountByTimeRangeSync(startTime, endTime)
        onProgress(0, totalEvents)
        val writer = JsonWriter(
            BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8), 128 * 1024)
        )
        writer.setIndent("  ")
        writer.beginObject()

        writeExportInfo(writer)
        writeEnvironment(writer)

        // 1. events：keyset 分批直寫；同時收集 keys 與 eventId → key 對映（mediaIndex 用）
        val keys = LinkedHashSet<String>()
        val eventKeyById = LinkedHashMap<Long, String>()
        writer.name("events").beginArray()
        var afterId = 0L
        while (true) {
            val batch = database.notificationEventDao()
                .getEventsByTimeRangePagedSync(startTime, endTime, afterId, EVENT_BATCH_SIZE)
            if (batch.isEmpty()) break
            for (e in batch) {
                writeEvent(writer, e)
                keys.add(e.notificationKey)
                eventKeyById[e.id] = e.notificationKey
            }
            afterId = batch.last().id
            onProgress(eventKeyById.size, totalEvents)
            if (batch.size < EVENT_BATCH_SIZE) break
        }
        writer.endArray()
        val eventCount = eventKeyById.size
        stage("events done count=$eventCount keys=${keys.size}")

        // 2. records：events 涉及的 notification_key（batch IN）
        var recordCount = 0
        writer.name("records").beginArray()
        for (chunk in keys.chunked(IN_CHUNK_SIZE)) {
            val records = database.notificationRecordDao().getByKeysSync(chunk)
            for (r in records) writeRecord(writer, r)
            recordCount += records.size
        }
        writer.endArray()
        stage("records done count=$recordCount")

        // 3. ranking observations：keys 對應 observation（batch IN），限制在時間範圍
        var observationCount = 0
        val snapshotIds = LinkedHashSet<Long>()
        writer.name("rankingObservations").beginArray()
        for (chunk in keys.chunked(IN_CHUNK_SIZE)) {
            val observations = database.rankingObservationDao().getByKeysSync(chunk)
                .filter { it.observedAt in startTime..endTime }
            for (o in observations) {
                writeObservation(writer, o)
                snapshotIds.add(o.rankingSnapshotId)
            }
            observationCount += observations.size
        }
        writer.endArray()
        stage("observations done count=$observationCount")

        // 4. ranking snapshots：observations 涉及的 snapshotId（batch IN）
        var snapshotCount = 0
        writer.name("rankingSnapshots").beginArray()
        for (chunk in snapshotIds.chunked(IN_CHUNK_SIZE)) {
            val snapshots = database.rankingSnapshotDao().getByIdsSync(chunk)
            for (s in snapshots) writeSnapshot(writer, s)
            snapshotCount += snapshots.size
        }
        writer.endArray()
        stage("snapshots done count=$snapshotCount")

        // 5. 媒體索引：依 events.id 撈 attachments（batch IN）
        var mediaCount = 0
        writer.name("mediaIndex").beginArray()
        for (chunk in eventKeyById.keys.chunked(IN_CHUNK_SIZE)) {
            val attachments = database.mediaAttachmentDao().getAttachmentsByEventIdsSync(chunk)
            for (attachment in attachments) {
                writer.beginObject()
                writer.name("eventId").value(attachment.eventId)
                writer.name("notificationKey").value(eventKeyById[attachment.eventId])
                writer.name("mediaType").value(attachment.mediaType.name)
                writer.name("filePath").value(attachment.filePath)
                writer.name("mimeType").value(attachment.mimeType)
                writer.name("fileSize").value(attachment.fileSize)
                writer.name("width").value(attachment.width)
                writer.name("height").value(attachment.height)
                writer.name("contentHash").value(attachment.contentHash)
                writer.name("captureTime").value(attachment.captureTime)
                attachment.sourceUri?.let { writer.name("sourceUri").value(it) }
                if (includeMediaBase64) {
                    val bytes = MediaExtractor.readMediaBytes(context, attachment.filePath)
                    if (bytes != null) {
                        writer.name("base64").value(Base64.encodeToString(bytes, Base64.NO_WRAP))
                    }
                }
                writer.endObject()
            }
            mediaCount += attachments.size
        }
        writer.endArray()
        stage("media done count=$mediaCount")

        // 6. 時間範圍 + 統計（最後寫 — 統計值需走完前面各段才知道）
        writer.name("archiveRange").beginObject()
        writer.name("startTime").value(isoFormat.format(Date(startTime)))
        writer.name("endTime").value(isoFormat.format(Date(endTime)))
        writer.name("recordCount").value(recordCount)
        writer.name("eventCount").value(eventCount)
        writer.name("observationCount").value(observationCount)
        writer.name("snapshotCount").value(snapshotCount)
        writer.endObject()

        writer.endObject()
        writer.flush()
        stage("export done")

        ArchiveStats(
            recordCount = recordCount,
            eventCount = eventCount,
            observationCount = observationCount,
            snapshotCount = snapshotCount,
            mediaCount = mediaCount
        )
    }

    private fun writeExportInfo(writer: JsonWriter) {
        val envInfo = environmentInfo()
        writer.name("exportInfo").beginObject()
        writer.name("exportTime").value(isoFormat.format(Date()))
        writer.name("exportVersion").value(EXPORT_VERSION)
        writer.name("appVersion").value(envInfo.appVersion)
        writer.name("appVersionCode").value(envInfo.appVersionCode)
        writer.endObject()
    }

    private fun writeEnvironment(writer: JsonWriter) {
        val envInfo = environmentInfo()
        writer.name("environment").beginObject()
        writer.name("androidVersion").value(envInfo.androidVersion)
        writer.name("apiLevel").value(envInfo.apiLevel)
        writer.name("sdkInt").value(envInfo.sdkInt)
        writer.name("deviceModel").value(envInfo.deviceModel)
        writer.name("deviceManufacturer").value(envInfo.deviceManufacturer)
        writer.name("supportedFeatures").beginObject()
        val features = envInfo.supportedFeatures
        writer.name("notificationChannel").value(features.notificationChannel)
        writer.name("messagingStyle").value(features.messagingStyle)
        writer.name("bubbles").value(features.bubbles)
        writer.name("directReply").value(features.directReply)
        writer.name("semanticAction").value(features.semanticAction)
        writer.name("rankingDetails").value(features.rankingDetails)
        writer.endObject()
        writer.endObject()
    }

    private fun environmentInfo(): EnvironmentInfo = EnvironmentInfo.create(
        BuildConfig.VERSION_NAME,
        BuildConfig.VERSION_CODE.toLong()
    )

    // null 欄位不寫 key — 對齊修前 JSONObject.put(name, null) 略過行為
    // （Android org.json 的 optString 會把 JSON null 讀成字串 "null"，importer 不可吃到 null 值）

    private fun writeRecord(writer: JsonWriter, r: NotificationRecordEntity) {
        writer.beginObject()
        writer.name("notificationKey").value(r.notificationKey)
        writer.name("packageName").value(r.packageName)
        r.channelId?.let { writer.name("channelId").value(it) }
        writer.name("notificationId").value(r.notificationId)
        r.tag?.let { writer.name("tag").value(it) }
        writer.name("firstSeen").value(r.firstSeen)
        writer.name("lastSeen").value(r.lastSeen)
        writer.name("eventCount").value(r.eventCount)
        writer.endObject()
    }

    private fun writeEvent(writer: JsonWriter, e: NotificationEventEntity) {
        writer.beginObject()
        writer.name("id").value(e.id)
        writer.name("notificationKey").value(e.notificationKey)
        writer.name("eventType").value(e.eventType.name)
        writer.name("eventTime").value(e.eventTime)
        writer.name("captureTime").value(e.captureTime)
        writer.name("packageName").value(e.packageName)
        e.channelId?.let { writer.name("channelId").value(it) }
        writer.name("postTime").value(e.postTime)
        writer.name("contentHash").value(e.contentHash)
        e.title?.let { writer.name("title").value(it) }
        e.text?.let { writer.name("text").value(it) }
        // Phase 16：removalReason 已從 entity column 移除，整段在 eventRawJson.removalReason 內
        writer.name("isAudible").value(e.isAudible)
        writer.name("likelyHeadsup").value(e.likelyHeadsup)
        writer.name("eventRawJson").value(e.eventRawJson)
        writer.endObject()
    }

    private fun writeObservation(writer: JsonWriter, o: RankingObservationEntity) {
        writer.beginObject()
        writer.name("id").value(o.id)
        writer.name("notificationKey").value(o.notificationKey)
        writer.name("observedAt").value(o.observedAt)
        writer.name("rankingSnapshotId").value(o.rankingSnapshotId)
        writer.name("source").value(o.source.name)
        o.lastAudiblyAlertedMillis?.let { writer.name("lastAudiblyAlertedMillis").value(it) }
        writer.endObject()
    }

    private fun writeSnapshot(writer: JsonWriter, s: RankingSnapshotEntity) {
        writer.beginObject()
        writer.name("id").value(s.id)
        writer.name("contentHash").value(s.contentHash)
        writer.name("rankingJson").value(s.rankingJson)
        writer.name("firstSeen").value(s.firstSeen)
        writer.endObject()
    }
}

data class ArchiveStats(
    val recordCount: Int,
    val eventCount: Int,
    val observationCount: Int,
    val snapshotCount: Int,
    val mediaCount: Int
)
