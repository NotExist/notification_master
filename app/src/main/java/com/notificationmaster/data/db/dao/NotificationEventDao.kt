package com.notificationmaster.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.notificationmaster.data.db.entity.EventType
import com.notificationmaster.data.db.entity.NotificationEventEntity
import kotlinx.coroutines.flow.Flow

/**
 * 通知事件 DAO
 */
@Dao
interface NotificationEventDao {

    // === 插入 ===

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: NotificationEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<NotificationEventEntity>): List<Long>

    // === 查詢 ===

    @Query("SELECT * FROM notification_events WHERE notification_id = :notificationId ORDER BY event_time ASC")
    fun getEventsByNotificationId(notificationId: Long): Flow<List<NotificationEventEntity>>

    /** 取得指定 notificationId 的所有事件（suspend 版本） */
    @Query("SELECT * FROM notification_events WHERE notification_id = :notificationId ORDER BY event_time ASC")
    suspend fun getEventsByNotificationIdSync(notificationId: Long): List<NotificationEventEntity>

    @Query("SELECT * FROM notification_events WHERE notification_key = :key ORDER BY event_time ASC")
    fun getEventsByKey(key: String): Flow<List<NotificationEventEntity>>

    @Query("SELECT * FROM notification_events WHERE notification_key = :key ORDER BY event_time ASC")
    suspend fun getEventsByNotificationKey(key: String): List<NotificationEventEntity>

    @Query("SELECT * FROM notification_events WHERE notification_key = :key ORDER BY event_time DESC LIMIT 1")
    suspend fun getLatestEventByKey(key: String): NotificationEventEntity?

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

    // === 統計 ===

    @Query("SELECT COUNT(*) FROM notification_events WHERE notification_key = :key")
    suspend fun getEventCountByKey(key: String): Int

    @Query("SELECT COUNT(*) FROM notification_events WHERE event_type = :eventType AND event_time >= :startTime")
    suspend fun getEventCountByType(eventType: EventType, startTime: Long): Int

    // === 刪除 ===

    @Query("DELETE FROM notification_events WHERE notification_id = :notificationId")
    suspend fun deleteByNotificationId(notificationId: Long)

    @Query("DELETE FROM notification_events WHERE event_time < :beforeTime")
    suspend fun deleteBeforeTime(beforeTime: Long): Int

    @Query("DELETE FROM notification_events")
    suspend fun deleteAll()
}
