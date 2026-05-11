package com.notificationmaster.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.notificationmaster.data.db.entity.DeviceStateEntity

/**
 * 裝置狀態 DAO
 */
@Dao
interface DeviceStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(state: DeviceStateEntity): Long

    @Query("SELECT * FROM device_states WHERE event_id = :eventId LIMIT 1")
    suspend fun getByEventId(eventId: Long): DeviceStateEntity?

    @Query("SELECT * FROM device_states WHERE capture_time BETWEEN :startTime AND :endTime ORDER BY capture_time DESC")
    suspend fun getByTimeRange(startTime: Long, endTime: Long): List<DeviceStateEntity>

    @Query("DELETE FROM device_states WHERE capture_time < :beforeTime")
    suspend fun deleteBeforeTime(beforeTime: Long): Int

    @Query("DELETE FROM device_states")
    suspend fun deleteAll()
}
