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

    @Query("SELECT EXISTS(SELECT 1 FROM notification_events WHERE notification_key = :key)")
    suspend fun existsByKey(key: String): Boolean

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
     */
    @Query("""
        SELECT e.* FROM notification_events e
        WHERE notification_key IN (:keys)
          AND event_time = (
              SELECT MAX(event_time) FROM notification_events e2
              WHERE e2.notification_key = e.notification_key
          )
    """)
    suspend fun getLatestEventByKeysSync(keys: List<String>): List<NotificationEventEntity>

    /**
     * 同 content_hash 的不同 notification_key 數量（去重模式 similar 提示）
     */
    @Query("""
        SELECT COUNT(DISTINCT notification_key) FROM notification_events
        WHERE id IN (
            SELECT id FROM (
                SELECT id, MAX(event_time) FROM notification_events
                WHERE post_time BETWEEN :startTime AND :endTime
                GROUP BY notification_key
            )
        )
        AND content_hash = :hash
    """)
    suspend fun getDeduplicatedCount(hash: String, startTime: Long, endTime: Long): Int

    /**
     * 取同 content_hash 的相似事件（每個 notification_key 取最新 event_time 一筆）
     */
    @Query("""
        SELECT * FROM notification_events
        WHERE id IN (
            SELECT id FROM (
                SELECT id, MAX(event_time) FROM notification_events
                WHERE post_time BETWEEN :startTime AND :endTime
                GROUP BY notification_key
            )
        )
        AND content_hash = :hash
        ORDER BY post_time DESC
    """)
    suspend fun getSimilarEvents(hash: String, startTime: Long, endTime: Long): List<NotificationEventEntity>

    /**
     * UI 搜尋（標題 / 內文 / raw 全文 / 媒體檔名 LIKE）。
     *
     * Plan 2 Phase 16：除 title / text 兩個投影 column，也直接對 event_raw_json 做 LIKE。
     * 這樣 bigText / subText / summaryText / extras 內任何字串值都能被搜尋命中
     * （e.g. MessagingStyle 訊息內容、ticker 等）。
     *
     * Plan 2 W22z：回補 LEFT JOIN media_attachments + file_path LIKE，讓使用者以
     * 另存出來的媒體檔名（`{pkg}_{mediaType}_{hash}.ext`）回查對應 event。Phase 16
     * 暫時拿掉時的「待 FK 對齊」條件已滿足（MediaAttachmentEntity.event_id 指向
     * NotificationEventEntity.id 並有 index）。
     *
     * 副作用：raw LIKE 可能命中 JSON 結構字（如 "_type"），但實務上使用者搜尋字串
     * 很少剛好等於 JSON key name，可接受。SQLite LIKE 對中等大小字串（每筆 ≤ 100KB）
     * 配合 LIMIT 100 在實機上仍可秒級回應。
     */
    @Query("""
        SELECT * FROM notification_events
        WHERE id IN (
            SELECT id FROM (
                SELECT e.id AS id, MAX(e.event_time) FROM notification_events e
                LEFT JOIN media_attachments m ON m.event_id = e.id
                WHERE e.title LIKE '%' || :query || '%'
                   OR e.text LIKE '%' || :query || '%'
                   OR e.event_raw_json LIKE '%' || :query || '%'
                   OR m.file_path LIKE '%' || :query || '%'
                GROUP BY e.notification_key
            )
        )
        ORDER BY event_time DESC
        LIMIT :limit
    """)
    suspend fun searchEvents(query: String, limit: Int = 100): List<NotificationEventEntity>

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
}

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
