package com.notificationmaster.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.notificationmaster.data.db.entity.NotificationRecordEntity
import kotlinx.coroutines.flow.Flow

/**
 * 通知聚合錨點 DAO（Plan 2）
 *
 * notification_key 是自然主鍵，service 寫事件時若 record 不存在則 insert，
 * 已存在則更新 lastSeen / eventCount。
 */
@Dao
interface NotificationRecordDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(record: NotificationRecordEntity): Long

    @Update
    suspend fun update(record: NotificationRecordEntity)

    @Query("SELECT * FROM notification_records WHERE notification_key = :key")
    suspend fun getByKey(key: String): NotificationRecordEntity?

    /** W23d：封存匯出 batch IN（呼叫端自行 chunk 控制 SQLite 變數上限） */
    @Query("SELECT * FROM notification_records WHERE notification_key IN (:keys)")
    suspend fun getByKeysSync(keys: List<String>): List<NotificationRecordEntity>

    @Query("SELECT * FROM notification_records WHERE package_name = :packageName ORDER BY last_seen DESC")
    fun getByPackage(packageName: String): Flow<List<NotificationRecordEntity>>

    @Query("""
        SELECT * FROM notification_records
        WHERE package_name = :packageName AND channel_id = :channelId
        ORDER BY last_seen DESC
    """)
    fun getByPackageAndChannel(packageName: String, channelId: String): Flow<List<NotificationRecordEntity>>

    @Query("UPDATE notification_records SET last_seen = :lastSeen, event_count = event_count + 1 WHERE notification_key = :key")
    suspend fun bumpEventCount(key: String, lastSeen: Long)

    @Query("SELECT COUNT(*) FROM notification_records")
    suspend fun getTotalCount(): Int

    /** 今日有新事件的 record 數（lastSeen >= startOfDay） */
    @Query("SELECT COUNT(*) FROM notification_records WHERE last_seen >= :startOfDay")
    suspend fun getTodayCount(startOfDay: Long): Int

    @Query("SELECT MIN(first_seen) FROM notification_records")
    suspend fun getEarliestFirstSeen(): Long?

    @Query("DELETE FROM notification_records WHERE last_seen < :beforeTime")
    suspend fun deleteBeforeTime(beforeTime: Long): Int

    @Query("DELETE FROM notification_records")
    suspend fun deleteAll()
}
