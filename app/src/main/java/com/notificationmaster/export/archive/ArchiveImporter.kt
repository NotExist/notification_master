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
        /** W24f：probe 用 IN(:times)，SQLite 變數上限 999 → 批量壓在其下 */
        private const val OBS_BATCH = 500
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
        val media: Int,
        /** W24f：寫入階段的「讀取 / 已匯入 / 略過重複」累計（validate 階段維持預設 0 不顯示） */
        val read: Int = 0,
        val written: Int = 0,
        val deduped: Int = 0
    )

    /** 匯入結果摘要（不再回傳整批資料） */
    data class ImportResult(
        val exportInfo: ExportInfo,
        val environment: ArchiveEnvironment?,
        val records: Int,
        val events: Int,
        val observations: Int,
        val snapshots: Int,
        val mediaRestored: Int,
        /** W23u：為孤兒 event key（無對應 record）補建的 placeholder record 數 */
        val placeholderRecords: Int,
        /** W23x：因內容身分已存在（DB 既有或檔內重複）而略過的 event / observation 數 */
        val dedupedEvents: Int,
        val dedupedObservations: Int
    )

    /** Pass 1 驗證報告：實際讀到的各 section 筆數 + 是否通過計數核對 + 參照完整性 */
    data class ValidationReport(
        val exportInfo: ExportInfo,
        val environment: ArchiveEnvironment?,
        val records: Int,
        val events: Int,
        val observations: Int,
        val snapshots: Int,
        val media: Int,
        /** archiveRange 宣告值存在且與實際相符（無 archiveRange → false，只做結構驗證） */
        val countVerified: Boolean,
        /**
         * 參照完整性：notification_key 出現在 events 卻不在 records 的孤兒集合。
         * events 對 records 有 FK，孤兒會在寫入 commit 時 FK 失敗。pass 2 對這些 key
         * 補 placeholder record 修復（observations 的 key ⊆ event key，故一併涵蓋）。
         */
        val orphanEventKeys: Set<String>
    )

    /**
     * Pass 1：純串流驗證，**不寫 DB**。涵蓋
     * (1) JSON 結構合法 + 截斷偵測（JsonReader 讀到 EOF 缺收尾即拋）
     * (2) exportInfo 版本閘
     * (3) 計數完整性：實際讀到的陣列筆數 vs 檔尾 archiveRange 宣告值，不符即拋
     * (4) 參照完整性：收集 event keys / record keys，算出孤兒 event key（不拋，回報供
     *     pass 2 補 placeholder）
     *
     * O(unique key 數) 記憶體：只持有 key 字串集合 + 計數，不持有資料本體。
     */
    suspend fun validate(
        inputStream: InputStream,
        totalBytes: Long,
        onProgress: (ImportProgress) -> Unit
    ): ValidationReport {
        val counting = CountingInputStream(inputStream)
        val reader = JsonReader(InputStreamReader(counting, Charsets.UTF_8))

        var exportInfo: ExportInfo? = null
        var environment: ArchiveEnvironment? = null
        var declRecords = -1; var declEvents = -1; var declObs = -1; var declSnaps = -1
        var nRecords = 0; var nEvents = 0; var nObs = 0; var nSnaps = 0; var nMedia = 0
        var sinceProgress = 0
        val recordKeys = HashSet<String>()
        val eventKeys = HashSet<String>()

        fun bump() {
            if (++sinceProgress >= PROGRESS_EVERY) {
                sinceProgress = 0
                onProgress(ImportProgress(counting.bytesRead, totalBytes, nRecords, nEvents, nObs, nSnaps, nMedia))
            }
        }
        fun countArray(onEach: () -> Unit): Int {
            var n = 0
            reader.beginArray()
            while (reader.hasNext()) { reader.skipValue(); n++; onEach() }
            reader.endArray()
            return n
        }
        // 輕量擷取陣列元素的單一字串欄位（其餘 skipValue，不建整個 JSONObject）
        fun countKeys(field: String, into: HashSet<String>): Int {
            var n = 0
            reader.beginArray()
            while (reader.hasNext()) {
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == field && reader.peek() == JsonToken.STRING) into.add(reader.nextString())
                    else reader.skipValue()
                }
                reader.endObject()
                n++; bump()
            }
            reader.endArray()
            return n
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
                "archiveRange" -> {
                    val o = readObject(reader)
                    declRecords = o.optInt("recordCount", -1)
                    declEvents = o.optInt("eventCount", -1)
                    declObs = o.optInt("observationCount", -1)
                    declSnaps = o.optInt("snapshotCount", -1)
                }
                "records" -> nRecords = countKeys("notificationKey", recordKeys)
                "events" -> nEvents = countKeys("notificationKey", eventKeys)
                "rankingObservations" -> nObs = countArray { bump() }
                "rankingSnapshots" -> nSnaps = countArray { bump() }
                "mediaIndex" -> nMedia = countArray { bump() }
                else -> reader.skipValue()
            }
        }
        reader.endObject()  // 截斷檔在此拋（缺頂層收尾）

        val info = exportInfo ?: throw IllegalStateException("缺少 exportInfo，非合法封存檔")

        val hasDecl = declRecords >= 0 || declEvents >= 0 || declObs >= 0 || declSnaps >= 0
        if (hasDecl) {
            val mism = buildList {
                if (declRecords >= 0 && declRecords != nRecords) add("records 宣告 $declRecords / 實際 $nRecords")
                if (declEvents >= 0 && declEvents != nEvents) add("events 宣告 $declEvents / 實際 $nEvents")
                if (declObs >= 0 && declObs != nObs) add("observations 宣告 $declObs / 實際 $nObs")
                if (declSnaps >= 0 && declSnaps != nSnaps) add("snapshots 宣告 $declSnaps / 實際 $nSnaps")
            }
            if (mism.isNotEmpty()) {
                throw IllegalStateException("封存檔不完整（計數不符）：${mism.joinToString("；")}")
            }
        }

        // 參照完整性：event key 不在 records → 孤兒（不拋，pass 2 補 placeholder）
        val orphan = eventKeys.filterTo(HashSet()) { it !in recordKeys }

        return ValidationReport(
            exportInfo = info, environment = environment,
            records = nRecords, events = nEvents, observations = nObs,
            snapshots = nSnaps, media = nMedia, countVerified = hasDecl,
            orphanEventKeys = orphan
        )
    }

    /**
     * 串流解析 + 邊讀邊寫入 [database]。
     *
     * **dispatcher 中立**：本函式不切 dispatcher，由呼叫端在
     * `database.withTransaction { }`（room-ktx）內呼叫以取得原子性 — transaction
     * 的 context element 不可被 withContext(IO) 覆蓋，故此處不自行切。
     *
     * @param totalBytes 檔案總位元組（供進度條；未知傳 0 → 進度條 indeterminate）
     * @param placeholderKeys pass 1 算出的孤兒 event key（無對應 record）；遇到該 key 的
     *                        event 時補建 placeholder record 滿足 FK
     * @param onProgress 進度回呼（在 transaction/背景 thread 觸發，呼叫端自行 marshal 到 main）
     */
    suspend fun import(
        inputStream: InputStream,
        database: NotificationDatabase,
        totalBytes: Long,
        placeholderKeys: Set<String>,
        onProgress: (ImportProgress) -> Unit
    ): ImportResult {
        val counting = CountingInputStream(inputStream)
        val reader = JsonReader(InputStreamReader(counting, Charsets.UTF_8))

        var exportInfo: ExportInfo? = null
        var environment: ArchiveEnvironment? = null
        val oldToNewSnapshotId = HashMap<Long, Long>()
        val pendingObs = ArrayList<RankingObservationEntity>()
        val createdPlaceholders = HashSet<String>()

        var recordCount = 0
        var eventCount = 0
        var snapshotCount = 0
        var mediaRestored = 0
        var dedupedEvents = 0
        var dedupedObs = 0
        var obsWritten = 0
        var elementsRead = 0
        var sinceProgress = 0

        fun emitProgress() {
            onProgress(
                ImportProgress(
                    counting.bytesRead, totalBytes,
                    recordCount, eventCount, pendingObs.size, snapshotCount, mediaRestored,
                    read = elementsRead,
                    written = recordCount + eventCount + snapshotCount + obsWritten + mediaRestored,
                    deduped = dedupedEvents + dedupedObs
                )
            )
        }

        val eventBuf = ArrayList<NotificationEventEntity>(EVENT_BATCH)
        suspend fun flushEvents() {
            if (eventBuf.isEmpty()) return
            // W24f：逐批 probe 去重（取代 W23x 整表身分 preload — 記憶體 O(批)、與 DB
            // 大小脫鉤，空 DB 匯入時 probe 趨近零開銷）。event_time IN 只是縮候選集的
            // 漏斗，判定始終是四欄身分全比對（唯一性只影響效能不影響正確性）。
            // transaction 內 SELECT 看得到本次已插入列 → 同時涵蓋「DB 既有」與
            // 「檔內較早批次」的重複；批內重複由 batchSeen 處理。
            val existing = database.notificationEventDao()
                .getIdentitiesByTimesSync(eventBuf.map { it.eventTime }.distinct())
                .mapTo(HashSet()) { it.key() }
            val batchSeen = HashSet<String>()
            val fresh = eventBuf.filter { ev ->
                val idKey = "${ev.notificationKey}|${ev.eventType.name}|${ev.eventTime}|${ev.contentHash}"
                idKey !in existing && batchSeen.add(idKey)
            }
            dedupedEvents += eventBuf.size - fresh.size
            for (ev in fresh) {
                // W23u：孤兒 event key（無對應 record）首次出現時補 placeholder record，
                // 用該 event 的 packageName/channel/時間填充（defer_foreign_keys 容順序）
                if (ev.notificationKey in placeholderKeys && createdPlaceholders.add(ev.notificationKey)) {
                    database.notificationRecordDao().insertIfAbsent(placeholderRecord(ev))
                }
            }
            if (fresh.isNotEmpty()) {
                database.notificationEventDao().insertAll(fresh.map { it.copy(id = 0) })
                eventCount += fresh.size
            }
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
                            // W24f：IGNORE 被略過時回 -1 — recordCount 只計實際新增
                            if (database.notificationRecordDao().insertIfAbsent(it) != -1L) recordCount++
                        }
                        elementsRead++
                        if (++sinceProgress >= PROGRESS_EVERY) { sinceProgress = 0; emitProgress() }
                    }
                    reader.endArray()
                }
                "events" -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        // W24f：去重移到 flushEvents（逐批 probe），此處只收批
                        runCatching { parseEvent(readObject(reader)) }.getOrNull()?.let { ev ->
                            eventBuf.add(ev)
                            if (eventBuf.size >= EVENT_BATCH) flushEvents()
                        }
                        elementsRead++
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
                        elementsRead++
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
                        elementsRead++
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
                        elementsRead++
                        if (++sinceProgress >= PROGRESS_EVERY) { sinceProgress = 0; emitProgress() }
                    }
                    reader.endArray()
                }
                else -> reader.skipValue()  // archiveRange 等：略過（計數由本地重算）
            }
        }
        reader.endObject()

        // observations：snapshotId remap 後逐批 probe 去重 + 批次寫入（W24f，
        // 身分含 remap 後的 snapshotId — probe 撈回的既有列本來就是實際 id，比對一致）
        val obsBatch = ArrayList<RankingObservationEntity>(OBS_BATCH)
        suspend fun flushObs() {
            if (obsBatch.isEmpty()) return
            val existing = database.rankingObservationDao()
                .getIdentitiesByTimesSync(obsBatch.map { it.observedAt }.distinct())
                .mapTo(HashSet()) { it.key() }
            val batchSeen = HashSet<String>()
            val fresh = obsBatch.filter { o ->
                val idKey = "${o.notificationKey}|${o.observedAt}|${o.rankingSnapshotId}|${o.source.name}"
                idKey !in existing && batchSeen.add(idKey)
            }
            dedupedObs += obsBatch.size - fresh.size
            if (fresh.isNotEmpty()) {
                database.rankingObservationDao().insertAll(fresh)
                obsWritten += fresh.size
            }
            obsBatch.clear()
            emitProgress()
        }
        for (obs in pendingObs) {
            val mapped = oldToNewSnapshotId[obs.rankingSnapshotId] ?: continue
            obsBatch.add(obs.copy(id = 0, rankingSnapshotId = mapped))
            if (obsBatch.size >= OBS_BATCH) flushObs()
        }
        flushObs()
        emitProgress()

        val info = exportInfo ?: throw IllegalStateException("缺少 exportInfo，非合法封存檔")
        return ImportResult(
            exportInfo = info,
            environment = environment,
            records = recordCount,
            events = eventCount,
            observations = obsWritten,
            snapshots = snapshotCount,
            mediaRestored = mediaRestored,
            placeholderRecords = createdPlaceholders.size,
            dedupedEvents = dedupedEvents,
            dedupedObservations = dedupedObs
        )
    }

    /**
     * W23u：為孤兒 event（其 notification_key 在檔內無對應 record）補建的最小 record。
     * packageName/channelId/時間取自該 event；notificationId/tag 留預設、eventCount=0。
     */
    private fun placeholderRecord(ev: NotificationEventEntity): NotificationRecordEntity =
        NotificationRecordEntity(
            notificationKey = ev.notificationKey,
            packageName = ev.packageName,
            channelId = ev.channelId,
            notificationId = 0,
            tag = null,
            firstSeen = ev.postTime,
            lastSeen = ev.postTime,
            eventCount = 0
        )

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
