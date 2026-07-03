package com.notificationmaster.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.notificationmaster.data.db.entity.EventType
import com.notificationmaster.data.db.entity.NotificationEventEntity
import com.notificationmaster.data.filter.EventFilterSpec
import com.notificationmaster.data.filter.EventFilterSqlBuilder
import com.notificationmaster.data.filter.toFilterSpec
import kotlinx.coroutines.flow.Flow

/**
 * 通知事件 DAO（Plan 2 重寫）
 *
 * 事件為主體，所有查詢以 notification_key 為自然關聯（不再用舊的 notification_id Long FK）。
 * Phase 9：RawQuery + EventFilterSpec extension 上線，list / count 兩條路徑改由 events 表承載。
 */
@Dao
interface NotificationEventDao {

    // === 通用 FilterSpec 查詢（Plan 1 / Plan 2 共用模型） ===

    /**
     * 以 SupportSQLiteQuery 回傳 events Flow，底層 SQL 由 [EventFilterSqlBuilder] 產生。
     * 使用 extension 函式 [query] 從 [EventFilterSpec] 發動查詢。
     */
    @RawQuery(observedEntities = [NotificationEventEntity::class])
    fun queryEvents(query: SupportSQLiteQuery): Flow<List<NotificationEventEntity>>

    /** 同步版本（Widget / binder thread 用） */
    @RawQuery
    fun queryEventsSync(query: SupportSQLiteQuery): List<NotificationEventEntity>

    /** 以 SupportSQLiteQuery 回傳 COUNT Flow（預覽/計數器用） */
    @RawQuery(observedEntities = [NotificationEventEntity::class])
    fun countEvents(query: SupportSQLiteQuery): Flow<Int>

    /** 同步 COUNT（Widget 備援） */
    @RawQuery
    fun countEventsSync(query: SupportSQLiteQuery): Int

    // === 插入 ===

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: NotificationEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(events: List<NotificationEventEntity>): List<Long>

    // === 基本查詢 ===

    @Query("SELECT * FROM notification_events WHERE id = :id")
    suspend fun getById(id: Long): NotificationEventEntity?

    @Query("SELECT * FROM notification_events WHERE notification_key = :key ORDER BY event_time ASC")
    fun getEventsByKey(key: String): Flow<List<NotificationEventEntity>>

    @Query("SELECT * FROM notification_events WHERE notification_key = :key ORDER BY event_time ASC")
    suspend fun getEventsByKeySync(key: String): List<NotificationEventEntity>

    @Query("SELECT * FROM notification_events WHERE notification_key = :key ORDER BY event_time DESC LIMIT 1")
    suspend fun getLatestEventByKey(key: String): NotificationEventEntity?

    /**
     * W23e：Detail 生命週期時間軸分批載入用 — 由新到舊一批一批撈，
     * 避免同 nkey 上千 event（含 raw_json）單一 query 撐爆 CursorWindow / 阻塞首屏。
     */
    @Query("""
        SELECT * FROM notification_events
        WHERE notification_key = :key
        ORDER BY event_time DESC, id DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getEventsByKeyPagedDesc(key: String, limit: Int, offset: Int): List<NotificationEventEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM notification_events WHERE notification_key = :key)")
    suspend fun existsByKey(key: String): Boolean

    /**
     * W23d：封存匯出分批用 — keyset（id 遞增）分批撈時間範圍內 events，
     * 一次載入整個範圍（含 raw_json）會撐爆記憶體造成 main thread 卡死 / ANR。
     */
    @Query("""
        SELECT * FROM notification_events
        WHERE event_time BETWEEN :startTime AND :endTime AND id > :afterId
        ORDER BY id ASC
        LIMIT :limit
    """)
    suspend fun getEventsByTimeRangePagedSync(
        startTime: Long,
        endTime: Long,
        afterId: Long,
        limit: Int
    ): List<NotificationEventEntity>

    /** W23w：匯出進度分母 — 範圍內 event 總數 */
    @Query("SELECT COUNT(*) FROM notification_events WHERE event_time BETWEEN :startTime AND :endTime")
    suspend fun getCountByTimeRangeSync(startTime: Long, endTime: Long): Int

    /**
     * W23x：匯入去重用 — 既有 events 的內容身分投影（不含 raw_json，輕量）。
     * 身分 = notification_key + event_type + event_time + content_hash。
     */
    @Query("SELECT notification_key AS notificationKey, event_type AS eventType, event_time AS eventTime, content_hash AS contentHash FROM notification_events")
    suspend fun getAllEventIdentitiesSync(): List<EventIdentity>

    @Query("""
        SELECT * FROM notification_events
        WHERE event_time BETWEEN :startTime AND :endTime
        ORDER BY event_time DESC
    """)
    fun getEventsByTimeRange(startTime: Long, endTime: Long): Flow<List<NotificationEventEntity>>

    /** ArchiveExporter 用：取時間範圍內所有 events（升冪，便於匯入後順序回放） */
    @Query("""
        SELECT * FROM notification_events
        WHERE event_time BETWEEN :startTime AND :endTime
        ORDER BY event_time ASC
    """)
    suspend fun getEventsByTimeRangeSync(startTime: Long, endTime: Long): List<NotificationEventEntity>

    @Query("""
        SELECT * FROM notification_events
        WHERE event_type = :eventType
        ORDER BY event_time DESC
        LIMIT :limit
    """)
    suspend fun getEventsByType(eventType: EventType, limit: Int = 100): List<NotificationEventEntity>

    // === UI / Timeline 通用查詢（從舊 NotificationDao 改寫對應 events 版本） ===

    /** 全表最早 post_time（Timeline 漸進載入用） */
    @Query("SELECT MIN(post_time) FROM notification_events")
    suspend fun getEarliestPostTime(): Long?

    /**
     * 當下為「已移除」的 notification_key 集合（Timeline 已移除淡化用）。
     *
     * Phase 31u：改為「最後一個 event 是 REMOVED」判定，跟 [getActiveRecordKeys]
     * 對稱。原先用「曾經有 REMOVED」會把「POSTED → REMOVED → UPDATED」（系統允許
     * dismiss 後再復活、Android 系統行為支援）也錯標為 removed → 即使該通知還在
     * 通知欄、UI 仍顯示已移除。
     */
    // Plan 2 W1.c：getRemovedNotificationKeysFlow 移除 — row.isRemoved 由 NotificationEnricher
    // 廣義計算寫入 NotificationDisplay.isRemoved 屬性，不再需要 record-level Flow

    /**
     * Plan 2 W1.b：每 notification_key 的最新一筆 event（給 NotificationEnricher 計算
     * 廣義 row.isRemoved 用）。
     *
     * 「row 後有同 nkey 任何事件 OR 該 nkey 最終 REMOVED」 = 廣義 isRemoved=true。
     * Enricher 對 events batch 拿 key list 後 query 此方法，比對每 row 的 event_time
     * 與「該 nkey 最新 event_time」+ 看「該 nkey 最新 event_type 是否 REMOVED」算出。
     *
     * **不過 EventFilterSqlBuilder 排除 REMOVED**：此 query 走自己的 SQL 不過 builder，
     * 才能拿到「最新事件可能是 REMOVED」的真實狀態。
     *
     * W23i：correlated subquery（外層每列 × 內層重掃同 key 全列 = O(K²)）改寫為
     * GROUP BY 形式（單次掃描 = O(K)，同 EventFilterSqlBuilder dedup 模式）。
     * 熱點 key（常駐通知整夜 UPDATED）累積數千列時，舊寫法單條 query 可達 30 秒。
     */
    @Query("""
        SELECT * FROM notification_events WHERE id IN (
            SELECT id FROM (
                SELECT id, MAX(event_time) FROM notification_events
                WHERE notification_key IN (:keys)
                GROUP BY notification_key
            )
        )
    """)
    suspend fun getLatestEventByKeysSync(keys: List<String>): List<NotificationEventEntity>

    /**
     * UI 搜尋（標題 / 內文 / raw 全文 / 媒體檔名 LIKE）。
     *
     * W24a：搜尋改「時間切片分批」，取代舊單發全表掃 + GROUP BY dedup 版本。
     *
     * 動機：raw_json LIKE 無法走 index = 全表掃（4000+ 列 × 3-4KB ≈ 十幾 MB 文字），
     * 舊版單發 query 一次掃完才回，大資料時 UI 只能乾等。切片模型讓每批只掃
     * 固定列數的時間視窗，結果由新到舊逐批浮現、批間可取消（Room suspend query
     * 的取消粒度不可靠，批間是保證取消點）。
     *
     * 設計（呼叫端 = SearchViewModel）：
     * - [getEventTimeAtOffsetSync] 先以 event_time index 取「cursor 往下第 N 列」的
     *   時間界標（index-only，快）→ 本批掃描範圍 (界標, cursor]
     * - Light / Raw 兩變體：Light 只比對 title / text / 媒體檔名（小欄位，快）；
     *   Raw 加 event_raw_json LIKE（進階/除錯用，UI 開關預設關）
     * - 舊版的 GROUP BY notification_key（每 key 取最新命中）語意移到呼叫端：
     *   掃描方向新→舊，首見 key 即該 key 最新命中，seenKeys 濾掉其餘 — 等價
     * - cursor 用 `<=`（邊界列重覆由呼叫端 seenEventIds 去重），避免同 ms 邊界漏列
     *
     * 媒體檔名（W22z 語意保留）：LEFT JOIN media_attachments + file_path LIKE，
     * 以另存檔名（`{pkg}_{mediaType}_{hash}.ext`）回查 event；JOIN 造成的重覆列
     * 由 DISTINCT 收斂。
     */
    @Query("""
        SELECT event_time FROM notification_events
        WHERE event_time <= :beforeTime
        ORDER BY event_time DESC
        LIMIT 1 OFFSET :rows
    """)
    suspend fun getEventTimeAtOffsetSync(beforeTime: Long, rows: Int): Long?

    /** W24a：搜尋切片 — Light（title / text / 媒體檔名） */
    @Query("""
        SELECT DISTINCT e.* FROM notification_events e
        LEFT JOIN media_attachments m ON m.event_id = e.id
        WHERE e.event_time <= :beforeTime AND e.event_time > :afterTime
          AND (e.title LIKE '%' || :query || '%'
           OR e.text LIKE '%' || :query || '%'
           OR m.file_path LIKE '%' || :query || '%')
        ORDER BY e.event_time DESC
        LIMIT :limit
    """)
    suspend fun searchEventsLightBatch(
        query: String,
        beforeTime: Long,
        afterTime: Long,
        limit: Int
    ): List<NotificationEventEntity>

    /** W24a：搜尋切片 — Raw（Light + event_raw_json 全文，慢，開關控制） */
    @Query("""
        SELECT DISTINCT e.* FROM notification_events e
        LEFT JOIN media_attachments m ON m.event_id = e.id
        WHERE e.event_time <= :beforeTime AND e.event_time > :afterTime
          AND (e.title LIKE '%' || :query || '%'
           OR e.text LIKE '%' || :query || '%'
           OR e.event_raw_json LIKE '%' || :query || '%'
           OR m.file_path LIKE '%' || :query || '%')
        ORDER BY e.event_time DESC
        LIMIT :limit
    """)
    suspend fun searchEventsRawBatch(
        query: String,
        beforeTime: Long,
        afterTime: Long,
        limit: Int
    ): List<NotificationEventEntity>

    /**
     * 預覽用：最近 N 筆 events（含同 key 多筆）。Filter dialog 預覽匹配把同 key 多 event
     * groupBy 後用 in-memory matcher 判斷「任一 event 匹配」。
     */
    @Query("SELECT * FROM notification_events ORDER BY event_time DESC LIMIT :limit")
    suspend fun getRecentEvents(limit: Int): List<NotificationEventEntity>

    // === Reconciliation 用（Plan 2 §K） ===

    /**
     * 取「最後事件不是 REMOVED」的 record key 集合 = 當下視為 active 的通知。
     *
     * INITIAL 完成後與 initialKeys 做差集，本機有但 INITIAL 沒列出的 key
     * 即為漏接的 removal，由 reconciler 補一筆 REMOVED event（reason=-100）。
     */
    @Query("""
        SELECT r.notification_key FROM notification_records r
        WHERE (
            SELECT event_type FROM notification_events
            WHERE notification_key = r.notification_key
            ORDER BY event_time DESC LIMIT 1
        ) != 'REMOVED'
    """)
    suspend fun getActiveRecordKeys(): List<String>

    // === 統計 ===

    @Query("SELECT COUNT(*) FROM notification_events WHERE notification_key = :key")
    suspend fun getEventCountByKey(key: String): Int

    @Query("SELECT COUNT(*) FROM notification_events WHERE event_type = :eventType AND event_time >= :startTime")
    suspend fun getEventCountByType(eventType: EventType, startTime: Long): Int

    // === 刪除 ===

    @Query("DELETE FROM notification_events WHERE event_time < :beforeTime")
    suspend fun deleteBeforeTime(beforeTime: Long): Int

    @Query("DELETE FROM notification_events")
    suspend fun deleteAll()

    // === W23f：DB 統計（Settings debug 用） ===

    @Query("SELECT COUNT(*) FROM notification_events")
    suspend fun getTotalCountSync(): Int

    @Query("""
        SELECT event_type AS name, COUNT(*) AS cnt FROM notification_events
        GROUP BY event_type ORDER BY cnt DESC
    """)
    suspend fun getCountByTypeSync(): List<GroupCount>

    @Query("SELECT IFNULL(SUM(LENGTH(event_raw_json)), 0) FROM notification_events")
    suspend fun getRawJsonTotalBytesSync(): Long

    @Query("""
        SELECT notification_key AS name, COUNT(*) AS cnt FROM notification_events
        GROUP BY notification_key ORDER BY cnt DESC LIMIT :limit
    """)
    suspend fun getTopKeysByCountSync(limit: Int): List<GroupCount>

    @Query("""
        SELECT package_name AS name, COUNT(*) AS cnt FROM notification_events
        GROUP BY package_name ORDER BY cnt DESC LIMIT :limit
    """)
    suspend fun getTopPackagesByCountSync(limit: Int): List<GroupCount>

    // === W23q：匯入後重建 archive 聚合（app_sources / channels）===
    // count 語意對齊 service gate（POSTED+UPDATED+INITIAL 累計、排除 REMOVED，
    // 同 EventFilterSqlBuilder 的統一排除）

    @Query("""
        SELECT package_name AS packageName, COUNT(*) AS cnt,
               MIN(event_time) AS firstTime, MAX(event_time) AS lastTime
        FROM notification_events
        WHERE event_type != 'REMOVED'
        GROUP BY package_name
    """)
    suspend fun getPackageAggregatesSync(): List<PackageAggregate>

    @Query("""
        SELECT package_name AS packageName, channel_id AS channelId, COUNT(*) AS cnt,
               MIN(event_time) AS firstTime, MAX(event_time) AS lastTime
        FROM notification_events
        WHERE event_type != 'REMOVED' AND channel_id IS NOT NULL
        GROUP BY package_name, channel_id
    """)
    suspend fun getChannelAggregatesSync(): List<ChannelAggregate>
}

/** W23f：GROUP BY 統計回傳列（name = 分組值，cnt = 筆數） */
data class GroupCount(val name: String, val cnt: Int)

/** W23x：event 內容身分投影（匯入去重；不含 raw_json） */
data class EventIdentity(
    val notificationKey: String,
    val eventType: com.notificationmaster.data.db.entity.EventType,
    val eventTime: Long,
    val contentHash: String
) {
    /** 去重比對鍵 */
    fun key(): String = "$notificationKey|${eventType.name}|$eventTime|$contentHash"
}

/** W23q：per-package 聚合（匯入後重建 app_sources 用） */
data class PackageAggregate(val packageName: String, val cnt: Int, val firstTime: Long, val lastTime: Long)

/** W23q：per-channel 聚合（匯入後重建 channels 用） */
data class ChannelAggregate(
    val packageName: String,
    val channelId: String,
    val cnt: Int,
    val firstTime: Long,
    val lastTime: Long
)

// === FilterSpec 快捷 extension（Plan 1 / Plan 2 共用） ===

/** 以 [EventFilterSpec] 查詢 events 列表（Flow） */
fun NotificationEventDao.query(spec: EventFilterSpec): Flow<List<NotificationEventEntity>> =
    queryEvents(EventFilterSqlBuilder.build(spec))

/** 以 [EventFilterSpec] 查詢 events 列表（同步，供 Widget binder thread 使用） */
fun NotificationEventDao.querySync(spec: EventFilterSpec): List<NotificationEventEntity> =
    queryEventsSync(EventFilterSqlBuilder.build(spec))

/** 以 [EventFilterSpec] 計算符合筆數（Flow） */
fun NotificationEventDao.count(spec: EventFilterSpec): Flow<Int> =
    countEvents(EventFilterSqlBuilder.buildCount(spec))

/** 以 [EventFilterSpec] 計算符合筆數（同步） */
fun NotificationEventDao.countSync(spec: EventFilterSpec): Int =
    countEventsSync(EventFilterSqlBuilder.buildCount(spec))

// === LIST_FILTER Rule 快捷 overload ===

fun NotificationEventDao.query(rule: com.notificationmaster.core.filter.Rule): Flow<List<NotificationEventEntity>> =
    query(rule.toFilterSpec())

fun NotificationEventDao.querySync(rule: com.notificationmaster.core.filter.Rule): List<NotificationEventEntity> =
    querySync(rule.toFilterSpec())

fun NotificationEventDao.count(rule: com.notificationmaster.core.filter.Rule): Flow<Int> =
    count(rule.toFilterSpec())
