package com.notificationmaster.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.notificationmaster.data.db.entity.ChannelEntity
import kotlinx.coroutines.flow.Flow

/**
 * 通知 Channel DAO
 */
@Dao
interface ChannelDao {

    // === 插入/更新 ===

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(channel: ChannelEntity): Long

    @Update
    suspend fun update(channel: ChannelEntity)

    @Query("""
        UPDATE channels
        SET notification_count = notification_count + 1, last_updated = :updateTime
        WHERE package_name = :packageName AND channel_id = :channelId
    """)
    suspend fun incrementNotificationCount(packageName: String, channelId: String, updateTime: Long)

    @Query("""
        UPDATE channels
        SET channel_name = :channelName, description = :description, importance = :importance,
            group_id = :groupId, show_badge = :showBadge, can_bubble = :canBubble,
            sound_uri = :soundUri, vibrate_pattern = :vibratePattern, light_color = :lightColor,
            lock_screen_visibility = :lockScreenVisibility, is_blocked = :isBlocked,
            notification_count = notification_count + 1, last_updated = :updateTime
        WHERE package_name = :packageName AND channel_id = :channelId
    """)
    suspend fun updateChannelInfoAndIncrement(
        packageName: String, channelId: String,
        channelName: String?, description: String?, importance: Int, groupId: String?,
        showBadge: Boolean, canBubble: Boolean, soundUri: String?, vibratePattern: String?,
        lightColor: Int, lockScreenVisibility: Int, isBlocked: Boolean, updateTime: Long
    )

    // === 查詢 ===

    @Query("SELECT * FROM channels ORDER BY notification_count DESC")
    fun getAllChannels(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE package_name = :packageName ORDER BY notification_count DESC")
    fun getChannelsByPackage(packageName: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE package_name = :packageName ORDER BY notification_count DESC")
    suspend fun getByPackageName(packageName: String): List<ChannelEntity>

    @Query("""
        SELECT * FROM channels
        WHERE package_name = :packageName AND channel_id = :channelId
        LIMIT 1
    """)
    suspend fun getByPackageAndChannelId(packageName: String, channelId: String): ChannelEntity?

    @Query("SELECT * FROM channels WHERE app_source_id = :appSourceId ORDER BY notification_count DESC")
    fun getChannelsByAppSourceId(appSourceId: Long): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun getById(id: Long): ChannelEntity?

    // === 統計 ===

    @Query("SELECT COUNT(*) FROM channels")
    suspend fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM channels WHERE package_name = :packageName")
    suspend fun getCountByPackage(packageName: String): Int

    // === 更新狀態 ===

    @Query("UPDATE channels SET is_blocked = :blocked WHERE package_name = :packageName AND channel_id = :channelId")
    suspend fun setBlocked(packageName: String, channelId: String, blocked: Boolean)

    // === 刪除 ===

    @Query("DELETE FROM channels WHERE package_name = :packageName AND channel_id = :channelId")
    suspend fun deleteByPackageAndChannelId(packageName: String, channelId: String)

    @Query("DELETE FROM channels WHERE package_name = :packageName")
    suspend fun deleteByPackage(packageName: String)

    @Query("DELETE FROM channels")
    suspend fun deleteAll()
}
