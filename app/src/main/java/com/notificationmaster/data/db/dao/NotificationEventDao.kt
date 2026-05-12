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

    /** 有過 REMOVED 事件的 notification_key 集合（Timeline 已移除淡化用） */
    @Query("""
        SELECT DISTINCT notification_key FROM notification_events
        WHERE event_type = 'REMOVED'
    """)
    fun getRemovedNotificationKeysFlow(): Flow<List<String>>

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
     * UI 搜尋（標題 / 內文 LIKE）。
     *
     * Plan 2：events 表只投影 title / text；big_text / sub_text 由 snapshot 內提供，
     * SQL 搜尋暫時涵蓋不到。檔名 LIKE 反查（透過 media_attachments JOIN）待
     * media_attachments FK 重新對齊 event_id 後重做。
     */
    @Query("""
        SELECT * FROM notification_events
        WHERE id IN (
            SELECT id FROM (
                SELECT id, MAX(event_time) FROM notification_events
                WHERE title LIKE '%' || :query || '%'
                   OR text LIKE '%' || :query || '%'
                GROUP BY notification_key
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

    /** 依 App 取得每個 notification_key 的最新一筆 event（Archive 詳情列表用） */
    @Query("""
        SELECT * FROM notification_events
        WHERE id IN (
            SELECT id FROM (
                SELECT id, MAX(event_time) FROM notification_events
                WHERE package_name = :packageName
                GROUP BY notification_key
            )
        )
        ORDER BY post_time DESC
    """)
    fun getLatestEventsByPackage(packageName: String): Flow<List<NotificationEventEntity>>

    /** 依 Channel 取得每個 notification_key 的最新一筆 event（Archive 詳情列表用） */
    @Query("""
        SELECT * FROM notification_events
        WHERE id IN (
            SELECT id FROM (
                SELECT id, MAX(event_time) FROM notification_events
                WHERE package_name = :packageName AND channel_id = :channelId
                GROUP BY notification_key
            )
        )
        ORDER BY post_time DESC
    """)
    fun getLatestEventsByChannel(packageName: String, channelId: String): Flow<List<NotificationEventEntity>>

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
