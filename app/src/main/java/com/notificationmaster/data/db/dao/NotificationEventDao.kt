package com.notificationmaster.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.notificationmaster.data.db.entity.EventType
import com.notificationmaster.data.db.entity.NotificationEventEntity
import kotlinx.coroutines.flow.Flow

/**
 * 通知事件 DAO（Plan 2 重寫）
 *
 * 事件為主體，所有查詢以 notification_key 為自然關聯（不再用舊的 notification_id Long FK）。
 * RawQuery + EventFilterSpec extension 留待 Phase 9（與 EventFilterSqlBuilder 一起 retarget）。
 */
@Dao
interface NotificationEventDao {

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

    @Query("""
        SELECT * FROM notification_events
        WHERE event_type = :eventType
        ORDER BY event_time DESC
        LIMIT :limit
    """)
    suspend fun getEventsByType(eventType: EventType, limit: Int = 100): List<NotificationEventEntity>

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
