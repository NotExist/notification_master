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

    @Query("SELECT * FROM notifications WHERE notification_key = :key ORDER BY post_time ASC")
    suspend fun getByNotificationKey(key: String): List<NotificationEntity>

    // === 查詢 - 時間範圍 ===

    @Query("""
        SELECT * FROM notifications
        WHERE id IN (
            SELECT MAX(id) FROM notifications
            WHERE post_time BETWEEN :startTime AND :endTime
            GROUP BY notification_key
        )
        ORDER BY post_time DESC
        LIMIT :limit
    """)
    fun getNotificationsByTimeRange(startTime: Long, endTime: Long, limit: Int = 500): Flow<List<NotificationEntity>>

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
     * 去重查詢（檢視層去重，非儲存層）
     * 第一層：GROUP BY notification_key → 同一系統通知的多次更新僅取最新
     * 第二層：GROUP BY content_hash → 不同通知但內容相同者再合併
     */
    @Query("""
        SELECT * FROM notifications
        WHERE id IN (
            SELECT MAX(id) FROM notifications
            WHERE id IN (
                SELECT MAX(id) FROM notifications
                WHERE post_time BETWEEN :startTime AND :endTime
                GROUP BY notification_key
            )
            GROUP BY content_hash
        )
        ORDER BY post_time DESC
        LIMIT :limit
    """)
    fun getDeduplicatedNotifications(startTime: Long, endTime: Long, limit: Int = 500): Flow<List<NotificationEntity>>

    /**
     * 取得同 content_hash 的所有去重後通知（用於展開相似列表）
     * 每個 notification_key 取最新一筆
     */
    @Query("""
        SELECT * FROM notifications
        WHERE id IN (
            SELECT MAX(id) FROM notifications
            WHERE post_time BETWEEN :startTime AND :endTime
            GROUP BY notification_key
        )
        AND content_hash = :hash
        ORDER BY post_time DESC
    """)
    suspend fun getSimilarNotifications(hash: String, startTime: Long, endTime: Long): List<NotificationEntity>

    /**
     * 取得相同 hash 的通知數量
     */
    @Query("""
        SELECT COUNT(*) FROM notifications
        WHERE content_hash = :hash AND post_time BETWEEN :startTime AND :endTime
    """)
    suspend fun getCountByHash(hash: String, startTime: Long, endTime: Long): Int

    /**
     * 取得去重合併數量 — 在「最新版本」中，有幾個不同 notification_key 共享此 content_hash
     */
    @Query("""
        SELECT COUNT(DISTINCT notification_key) FROM notifications
        WHERE id IN (
            SELECT MAX(id) FROM notifications
            WHERE post_time BETWEEN :startTime AND :endTime
            GROUP BY notification_key
        )
        AND content_hash = :hash
    """)
    suspend fun getDeduplicatedCount(hash: String, startTime: Long, endTime: Long): Int

    // === 查詢 - Audible ===

    /**
     * 取得最近有聲通知（每個 notification_key 取最新一筆）
     */
    @Query("""
        SELECT * FROM notifications
        WHERE id IN (
            SELECT MAX(id) FROM notifications
            WHERE is_audible = 1
            GROUP BY notification_key
        )
        ORDER BY post_time DESC
        LIMIT :limit
    """)
    fun getRecentAudibleNotifications(limit: Int = 20): Flow<List<NotificationEntity>>

    // === 查詢 - Heads-up ===

    /**
     * 取得最近 Heads-up 通知（每個 notification_key 取最新一筆）
     */
    @Query("""
        SELECT * FROM notifications
        WHERE id IN (
            SELECT MAX(id) FROM notifications
            WHERE likely_headsup = 1
            GROUP BY notification_key
        )
        ORDER BY post_time DESC
        LIMIT :limit
    """)
    fun getRecentHeadsupNotifications(limit: Int = 20): Flow<List<NotificationEntity>>

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
