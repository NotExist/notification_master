package com.notificationmaster.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.notificationmaster.data.db.entity.EventType
import com.notificationmaster.data.db.entity.NotificationEntity
import kotlinx.coroutines.flow.Flow

/**
 * 通知 + 事件類型的組合結果（事件優先查詢用）
 */
data class NotificationWithEventType(
    @Embedded val notification: NotificationEntity,
    @ColumnInfo(name = "event_type") val eventType: EventType?
)

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

    /**
     * 事件優先查詢：展開最近 :limit 筆通知的所有事件（預覽匹配用）
     * 每筆結果為一個 (notification, event_type) 組合，同一通知可能出現多次。
     * 呼叫端以 groupBy + any 判斷是否有任一事件匹配。
     */
    @Query("""
        SELECT n.*, e.event_type
        FROM notifications n
        INNER JOIN notification_events e ON e.notification_id = n.id
        WHERE n.id IN (
            SELECT id FROM notifications ORDER BY post_time DESC LIMIT :limit
        )
        ORDER BY n.post_time DESC, e.event_time ASC
    """)
    suspend fun getRecentEventsWithNotifications(limit: Int): List<NotificationWithEventType>

    @Query("SELECT * FROM notifications WHERE id = :id")
    suspend fun getById(id: Long): NotificationEntity?

    @Query("SELECT * FROM notifications WHERE notification_key = :key ORDER BY capture_time DESC LIMIT 1")
    suspend fun getLatestByKey(key: String): NotificationEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM notifications WHERE notification_key = :key)")
    suspend fun existsByKey(key: String): Boolean

    @Query("SELECT * FROM notifications WHERE notification_key = :key ORDER BY post_time ASC")
    suspend fun getByNotificationKey(key: String): List<NotificationEntity>

    /** 取得同 notification_key 的所有 Entity ID，按 post_time ASC 排列 */
    @Query("SELECT id FROM notifications WHERE notification_key = :key ORDER BY post_time ASC")
    suspend fun getEntityIdsByKey(key: String): List<Long>

    // === 查詢 - 時間範圍 ===

    @Query("""
        SELECT * FROM notifications
        WHERE id IN (
            SELECT MAX(id) FROM notifications
            WHERE post_time BETWEEN :startTime AND :endTime
            GROUP BY notification_key
        )
        ORDER BY post_time DESC
    """)
    fun getNotificationsByTimeRange(startTime: Long, endTime: Long): Flow<List<NotificationEntity>>

    /** 取得指定時間範圍的通知（每個 key 最新一筆），suspend 版本 */
    @Query("""
        SELECT * FROM notifications
        WHERE id IN (
            SELECT MAX(id) FROM notifications
            WHERE post_time BETWEEN :startTime AND :endTime
            GROUP BY notification_key
        )
        ORDER BY post_time DESC
    """)
    suspend fun getNotificationsByDayRange(startTime: Long, endTime: Long): List<NotificationEntity>

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
    """)
    fun getDeduplicatedNotifications(startTime: Long, endTime: Long): Flow<List<NotificationEntity>>

    /** 去重版本 suspend 查詢 */
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
    """)
    suspend fun getDeduplicatedByDayRange(startTime: Long, endTime: Long): List<NotificationEntity>

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

    // === 查詢 - 已移除 ===

    /**
     * 取得最近被移除的通知（每個 notification_key 取最新一筆）
     * 按移除事件時間倒序排列
     */
    @Query("""
        SELECT n.* FROM notifications n
        INNER JOIN (
            SELECT e.notification_id, MAX(e.event_time) AS removal_time
            FROM notification_events e
            WHERE e.event_type = 'REMOVED'
            GROUP BY e.notification_id
        ) r ON n.id = r.notification_id
        WHERE n.id IN (
            SELECT MAX(n2.id) FROM notifications n2
            INNER JOIN notification_events e2 ON n2.id = e2.notification_id
            WHERE e2.event_type = 'REMOVED'
            GROUP BY n2.notification_key
        )
        ORDER BY r.removal_time DESC
        LIMIT :limit
    """)
    fun getRecentDismissedNotifications(limit: Int = 30): Flow<List<NotificationEntity>>

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

    /** 依 App 查詢，每個 notification_key 取最新一筆 */
    @Query("""
        SELECT * FROM notifications
        WHERE id IN (
            SELECT MAX(id) FROM notifications
            WHERE package_name = :packageName
            GROUP BY notification_key
        )
        ORDER BY post_time DESC
    """)
    fun getLatestNotificationsByPackage(packageName: String): Flow<List<NotificationEntity>>

    /** 依 Channel 查詢，每個 notification_key 取最新一筆 */
    @Query("""
        SELECT * FROM notifications
        WHERE id IN (
            SELECT MAX(id) FROM notifications
            WHERE package_name = :packageName AND channel_id = :channelId
            GROUP BY notification_key
        )
        ORDER BY post_time DESC
    """)
    fun getLatestNotificationsByChannel(packageName: String, channelId: String): Flow<List<NotificationEntity>>

    // === 查詢 - 搜尋 ===

    @Query("""
        SELECT DISTINCT n.* FROM notifications n
        LEFT JOIN media_attachments m ON n.id = m.notification_id
        WHERE n.title LIKE '%' || :query || '%'
           OR n.text LIKE '%' || :query || '%'
           OR n.big_text LIKE '%' || :query || '%'
           OR n.sub_text LIKE '%' || :query || '%'
           OR m.file_path LIKE '%' || :query || '%'
        ORDER BY n.post_time DESC
        LIMIT :limit
    """)
    suspend fun searchNotifications(query: String, limit: Int = 100): List<NotificationEntity>

    // === 查詢 - 最早記錄 ===

    @Query("SELECT MIN(post_time) FROM notifications")
    suspend fun getEarliestPostTime(): Long?

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
