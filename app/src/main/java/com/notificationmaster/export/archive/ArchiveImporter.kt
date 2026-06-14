package com.notificationmaster.export.archive

import android.content.Context
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import com.notificationmaster.core.media.MediaExtractor
import com.notificationmaster.data.db.NotificationDatabase
import com.notificationmaster.data.db.entity.EventType
import com.notificationmaster.data.db.entity.NotificationEventEntity
import com.notificationmaster.data.db.entity.NotificationRecordEntity
import com.notificationmaster.data.db.entity.ObservationSource
import com.notificationmaster.data.db.entity.RankingObservationEntity
import com.notificationmaster.data.db.entity.RankingSnapshotEntity
import org.json.JSONObject
import java.io.InputStream
import java.io.File
import java.io.InputStreamReader

/**
 * JSON 封存匯入器（W23r：串流改寫）
 *
 * 舊版用 `readText()` + `JSONObject` 整檔 DOM parse，記憶體成本按 JSON **節點數**
 * 計（org.json 每節點一個 HashMap）。大型備份（萬筆 events + 數萬筆 mediaIndex
 * metadata）節點暴增逼近預設 heap → stop-the-world GC 凍結 main thread → ANR。
 *
 * 改用 [JsonReader] 串流：頂層 key 逐一處理，陣列元素逐筆解析後即插入 DB
 * （events/snapshots 邊讀邊批次 insert，不累積），唯一 buffer 是輕量的
 * observations（需等 snapshots 插完才能 remap snapshotId，故先緩存後 flush）。
 * 峰值記憶體與檔案大小脫鉤。對稱 [ArchiveExporter] 的 JsonWriter 串流。
 *
 * 僅支援匯出格式 v2.0。
 */
class ArchiveImporter(private val context: Context) {

    companion object {
        private const val TAG = "ArchiveImporter"
        private const val SUPPORTED_VERSION_PREFIX = "2."
        private const val EVENT_BATCH = 500
        private const val OBS_BATCH = 1000
        /** 進度回呼節流：每處理這麼多筆元素回報一次 */
        private const val PROGRESS_EVERY = 500
    }

    /** 匯入進度（bytesRead/totalBytes 供 determinate 進度條；各 section count 供文字） */
    data class ImportProgress(
        val bytesRead: Long,
        val totalBytes: Long,
        val records: Int,
        val events: Int,
        val observations: Int,
        val snapshots: Int,
        val media: Int
    )

    /** 匯入結果摘要（不再回傳整批資料） */
    data class ImportResult(
        val exportInfo: ExportInfo,
        val environment: ArchiveEnvironment?,
        val records: Int,
        val events: Int,
        val observations: Int,
        val snapshots: Int,
        val mediaRestored: Int
    )

    /**
     * 串流解析 + 邊讀邊寫入 [database]。
     *
     * **dispatcher 中立**：本函式不切 dispatcher，由呼叫端在
     * `database.withTransaction { }`（room-ktx）內呼叫以取得原子性 — transaction
     * 的 context element 不可被 withContext(IO) 覆蓋，故此處不自行切。
     *
     * @param totalBytes 檔案總位元組（供進度條；未知傳 0 → 進度條 indeterminate）
     * @param onProgress 進度回呼（在 transaction/背景 thread 觸發，呼叫端自行 marshal 到 main）
     */
    suspend fun import(
        inputStream: InputStream,
        database: NotificationDatabase,
        totalBytes: Long,
        onProgress: (ImportProgress) -> Unit
    ): ImportResult {
        val counting = CountingInputStream(inputStream)
        val reader = JsonReader(InputStreamReader(counting, Charsets.UTF_8))

        var exportInfo: ExportInfo? = null
        var environment: ArchiveEnvironment? = null
        val oldToNewSnapshotId = HashMap<Long, Long>()
        val pendingObs = ArrayList<RankingObservationEntity>()

        var recordCount = 0
        var eventCount = 0
        var snapshotCount = 0
        var mediaRestored = 0
        var sinceProgress = 0

        fun emitProgress() {
            onProgress(
                ImportProgress(
                    counting.bytesRead, totalBytes,
                    recordCount, eventCount, pendingObs.size, snapshotCount, mediaRestored
                )
            )
        }

        val eventBuf = ArrayList<NotificationEventEntity>(EVENT_BATCH)
        suspend fun flushEvents() {
            if (eventBuf.isEmpty()) return
            database.notificationEventDao().insertAll(eventBuf.map { it.copy(id = 0) })
            eventBuf.clear()
        }

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "exportInfo" -> {
                    exportInfo = parseExportInfo(readObject(reader))
                    require(exportInfo!!.exportVersion.startsWith(SUPPORTED_VERSION_PREFIX)) {
                        "不支援的匯出版本：${exportInfo!!.exportVersion}（需 $SUPPORTED_VERSION_PREFIX*）"
                    }
                }
                "environment" -> environment = parseEnvironment(readObject(reader))
                "records" -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        runCatching { parseRecord(readObject(reader)) }.getOrNull()?.let {
                            database.notificationRecordDao().insertIfAbsent(it)
                            recordCount++
                        }
                        if (++sinceProgress >= PROGRESS_EVERY) { sinceProgress = 0; emitProgress() }
                    }
                    reader.endArray()
                }
                "events" -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        runCatching { parseEvent(readObject(reader)) }.getOrNull()?.let {
                            eventBuf.add(it)
                            eventCount++
                            if (eventBuf.size >= EVENT_BATCH) flushEvents()
                        }
                        if (++sinceProgress >= PROGRESS_EVERY) { sinceProgress = 0; emitProgress() }
                    }
                    flushEvents()
                    reader.endArray()
                }
                "rankingSnapshots" -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        runCatching { parseSnapshot(readObject(reader)) }.getOrNull()?.let { snap ->
                            val existing = database.rankingSnapshotDao().getByHash(snap.contentHash)
                            val newId = existing?.id
                                ?: database.rankingSnapshotDao().insertIfAbsent(snap.copy(id = 0))
                            oldToNewSnapshotId[snap.id] = newId
                            snapshotCount++
                        }
                        if (++sinceProgress >= PROGRESS_EVERY) { sinceProgress = 0; emitProgress() }
                    }
                    reader.endArray()
                }
                "rankingObservations" -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        // 先緩存（輕量 entity），endObject 後才能 remap snapshotId
                        runCatching { parseObservation(readObject(reader)) }.getOrNull()
                            ?.let { pendingObs.add(it) }
                        if (++sinceProgress >= PROGRESS_EVERY) { sinceProgress = 0; emitProgress() }
                    }
                    reader.endArray()
                }
                "mediaIndex" -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        val m = readObject(reader)
                        val base64 = m.optString("base64", "")
                        if (base64.isNotEmpty()) {
                            runCatching {
                                val fileName = m.getString("filePath")
                                val mediaDir = File(MediaExtractor.getMediaBaseDir(context), "media")
                                mediaDir.mkdirs()
                                File(mediaDir, fileName).writeBytes(Base64.decode(base64, Base64.NO_WRAP))
                                mediaRestored++
                            }.onFailure { Log.w(TAG, "Failed to restore media", it) }
                        }
                        if (++sinceProgress >= PROGRESS_EVERY) { sinceProgress = 0; emitProgress() }
                    }
                    reader.endArray()
                }
                else -> reader.skipValue()  // archiveRange 等：略過（計數由本地重算）
            }
        }
        reader.endObject()

        // observations：snapshotId remap 後批次 flush
        var obsCount = 0
        val obsBatch = ArrayList<RankingObservationEntity>(OBS_BATCH)
        for (obs in pendingObs) {
            val mapped = oldToNewSnapshotId[obs.rankingSnapshotId] ?: continue
            obsBatch.add(obs.copy(id = 0, rankingSnapshotId = mapped))
            if (obsBatch.size >= OBS_BATCH) {
                database.rankingObservationDao().insertAll(obsBatch)
                obsCount += obsBatch.size
                obsBatch.clear()
            }
        }
        if (obsBatch.isNotEmpty()) {
            database.rankingObservationDao().insertAll(obsBatch)
            obsCount += obsBatch.size
        }
        emitProgress()

        val info = exportInfo ?: throw IllegalStateException("缺少 exportInfo，非合法封存檔")
        return ImportResult(
            exportInfo = info,
            environment = environment,
            records = recordCount,
            events = eventCount,
            observations = obsCount,
            snapshots = snapshotCount,
            mediaRestored = mediaRestored
        )
    }

    // === JsonReader → 單一 JSONObject（每元素一次，讀完即丟，記憶體 O(1 元素)） ===

    /** 把當前 JsonReader 位置的一個 object 讀成 [JSONObject]（巢狀沿用 org.json 表示） */
    private fun readObject(reader: JsonReader): JSONObject {
        val obj = JSONObject()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            obj.put(name, readValue(reader))
        }
        reader.endObject()
        return obj
    }

    private fun readValue(reader: JsonReader): Any? = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> readObject(reader)
        JsonToken.BEGIN_ARRAY -> {
            val arr = org.json.JSONArray()
            reader.beginArray()
            while (reader.hasNext()) arr.put(readValue(reader))
            reader.endArray()
            arr
        }
        JsonToken.STRING -> reader.nextString()
        // 數字一律以字串收（避免 long/double 歧義），entity builder 再依欄位轉型
        JsonToken.NUMBER -> reader.nextString()
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
        else -> { reader.skipValue(); JSONObject.NULL }
    }

    // === entity 解析（與舊版語意一致，吃 JSONObject） ===

    private fun parseExportInfo(json: JSONObject): ExportInfo = ExportInfo(
        exportTime = json.optString("exportTime", ""),
        exportVersion = json.optString("exportVersion", "1.0"),
        appVersion = json.optString("appVersion", ""),
        appVersionCode = json.optLong("appVersionCode", 0)
    )

    private fun parseEnvironment(json: JSONObject): ArchiveEnvironment = ArchiveEnvironment(
        androidVersion = json.optString("androidVersion", ""),
        apiLevel = json.optInt("apiLevel", 0),
        deviceModel = json.optString("deviceModel", ""),
        deviceManufacturer = json.optString("deviceManufacturer", "")
    )

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

        // Phase 16 向下相容：舊匯出檔含 removalReason column 但 eventRawJson 缺 → 補進 root
        var rawJsonStr = json.optString("eventRawJson", "")
        if (json.has("removalReason") && rawJsonStr.isNotEmpty()) {
            try {
                val rawObj = JSONObject(rawJsonStr)
                if (!rawObj.has("removalReason")) {
                    rawObj.put("removalReason", json.optInt("removalReason"))
                    rawJsonStr = rawObj.toString()
                }
            } catch (_: Exception) { /* malformed raw 保留原樣 */ }
        }

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
            isAudible = json.optBoolean("isAudible", false),
            likelyHeadsup = json.optBoolean("likelyHeadsup", false),
            eventRawJson = rawJsonStr
        )
    }

    private fun parseObservation(json: JSONObject): RankingObservationEntity {
        val notificationKey = json.getString("notificationKey")
        require(notificationKey.isNotBlank()) { "Observation notificationKey must not be blank" }
        val lastAudiblyRaw = if (json.has("lastAudiblyAlertedMillis"))
            json.optLong("lastAudiblyAlertedMillis", Long.MIN_VALUE) else Long.MIN_VALUE
        return RankingObservationEntity(
            id = json.optLong("id", 0),
            notificationKey = notificationKey,
            observedAt = json.getLong("observedAt"),
            rankingSnapshotId = json.getLong("rankingSnapshotId"),
            source = ObservationSource.valueOf(json.getString("source")),
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

    /** 計數用 InputStream wrapper（供進度條 bytesRead） */
    private class CountingInputStream(private val src: InputStream) : InputStream() {
        @Volatile var bytesRead: Long = 0L
            private set

        override fun read(): Int = src.read().also { if (it >= 0) bytesRead++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            src.read(b, off, len).also { if (it > 0) bytesRead += it }

        override fun close() = src.close()
        override fun available(): Int = src.available()
    }
}

// === 匯出資料模型（exportInfo / environment 仍供結果摘要與相容） ===

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
