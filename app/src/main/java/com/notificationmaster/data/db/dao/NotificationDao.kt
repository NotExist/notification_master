package com.notificationmaster.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.notificationmaster.data.db.entity.NotificationEntity
import kotlinx.coroutines.flow.Flow

/**
 * 通知記錄 DAO
 */
@Dao
interface NotificationDao {

    // === 插入 ===

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(notification: NotificationEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(notifications: List<NotificationEntity>): List<Long>

    // === 查詢 - 全部 ===

    @Query("SELECT * FROM notifications ORDER BY post_time DESC")
    fun getAllNotifications(): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications ORDER BY post_time DESC LIMIT :limit OFFSET :offset")
    suspend fun getNotificationsPaged(limit: Int, offset: Int): List<NotificationEntity>

    @Query("SELECT * FROM notifications WHERE id = :id")
    suspend fun getById(id: Long): NotificationEntity?

    @Query("SELECT * FROM notifications WHERE notification_key = :key ORDER BY capture_time DESC LIMIT 1")
    suspend fun getLatestByKey(key: String): NotificationEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM notifications WHERE notification_key = :key)")
    suspend fun existsByKey(key: String): Boolean

    // === 查詢 - 時間範圍 ===

    @Query("""
        SELECT * FROM notifications
        WHERE post_time BETWEEN :startTime AND :endTime
        ORDER BY post_time DESC
    """)
    fun getNotificationsByTimeRange(startTime: Long, endTime: Long): Flow<List<NotificationEntity>>

    @Query("""
        SELECT * FROM notifications
        WHERE post_time BETWEEN :startTime AND :endTime
        ORDER BY post_time DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getNotificationsByTimeRangePaged(
        startTime: Long,
        endTime: Long,
        limit: Int,
        offset: Int
    ): List<NotificationEntity>

    // === 查詢 - 去重顯示 ===

    /**
     * 去重查詢 - 每個 content_hash 只取最新插入的一筆
     * 注意：這是檢視層的去重，不是儲存層
     *
     * 使用 MAX(id) 而非 MAX(post_time) 來保證每個 hash 只回傳一行，
     * 因為同一通知的 POSTED/UPDATED 事件共用相同 post_time 但有不同 id。
     */
    @Query("""
        SELECT * FROM notifications
        WHERE id IN (
            SELECT MAX(id) FROM notifications
            WHERE post_time BETWEEN :startTime AND :endTime
            GROUP BY content_hash
        )
        ORDER BY post_time DESC
    """)
    fun getDeduplicatedNotifications(startTime: Long, endTime: Long): Flow<List<NotificationEntity>>

    /**
     * 取得相同 hash 的通知數量
     */
    @Query("""
        SELECT COUNT(*) FROM notifications
        WHERE content_hash = :hash AND post_time BETWEEN :startTime AND :endTime
    """)
    suspend fun getCountByHash(hash: String, startTime: Long, endTime: Long): Int

    // === 查詢 - 按來源 ===

    @Query("""
        SELECT * FROM notifications
        WHERE package_name = :packageName
        ORDER BY post_time DESC
    """)
    fun getNotificationsByPackage(packageName: String): Flow<List<NotificationEntity>>

    @Query("""
        SELECT * FROM notifications
        WHERE package_name = :packageName AND channel_id = :channelId
        ORDER BY post_time DESC
    """)
    fun getNotificationsByChannel(packageName: String, channelId: String): Flow<List<NotificationEntity>>

    // === 查詢 - 搜尋 ===

    @Query("""
        SELECT * FROM notifications
        WHERE title LIKE '%' || :query || '%'
           OR text LIKE '%' || :query || '%'
           OR big_text LIKE '%' || :query || '%'
           OR sub_text LIKE '%' || :query || '%'
        ORDER BY post_time DESC
        LIMIT :limit
    """)
    suspend fun searchNotifications(query: String, limit: Int = 100): List<NotificationEntity>

    // === 統計 ===

    @Query("SELECT COUNT(*) FROM notifications")
    suspend fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM notifications WHERE post_time >= :startOfDay")
    suspend fun getTodayCount(startOfDay: Long): Int

    @Query("SELECT COUNT(*) FROM notifications WHERE package_name = :packageName")
    suspend fun getCountByPackage(packageName: String): Int

    @Query("SELECT DISTINCT package_name FROM notifications ORDER BY package_name")
    suspend fun getAllPackageNames(): List<String>

    // === 刪除 ===

    @Query("DELETE FROM notifications WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM notifications WHERE post_time < :beforeTime")
    suspend fun deleteBeforeTime(beforeTime: Long): Int

    @Query("DELETE FROM notifications")
    suspend fun deleteAll()
}
