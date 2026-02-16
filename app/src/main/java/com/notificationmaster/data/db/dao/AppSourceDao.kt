package com.notificationmaster.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.notificationmaster.data.db.entity.AppSourceEntity
import kotlinx.coroutines.flow.Flow

/**
 * 來源 App DAO
 */
@Dao
interface AppSourceDao {

    // === 插入/更新 ===

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(appSource: AppSourceEntity): Long

    @Update
    suspend fun update(appSource: AppSourceEntity)

    @Query("""
        UPDATE app_sources
        SET notification_count = notification_count + 1, last_updated = :updateTime
        WHERE package_name = :packageName
    """)
    suspend fun incrementNotificationCount(packageName: String, updateTime: Long)

    // === 查詢 ===

    @Query("SELECT * FROM app_sources ORDER BY notification_count DESC")
    fun getAllAppSources(): Flow<List<AppSourceEntity>>

    @Query("SELECT * FROM app_sources ORDER BY notification_count DESC")
    suspend fun getAll(): List<AppSourceEntity>

    @Query("SELECT * FROM app_sources WHERE package_name = :packageName LIMIT 1")
    suspend fun getByPackageName(packageName: String): AppSourceEntity?

    @Query("SELECT * FROM app_sources WHERE id = :id")
    suspend fun getById(id: Long): AppSourceEntity?

    @Query("SELECT * FROM app_sources ORDER BY notification_count DESC LIMIT :limit")
    suspend fun getTopApps(limit: Int): List<AppSourceEntity>

    @Query("SELECT * FROM app_sources WHERE is_uninstalled = 0 ORDER BY notification_count DESC")
    fun getInstalledApps(): Flow<List<AppSourceEntity>>

    // === 統計 ===

    @Query("SELECT COUNT(*) FROM app_sources")
    suspend fun getTotalCount(): Int

    @Query("SELECT SUM(notification_count) FROM app_sources")
    suspend fun getTotalNotificationCount(): Int?

    // === 更新狀態 ===

    @Query("UPDATE app_sources SET is_uninstalled = 1 WHERE package_name = :packageName")
    suspend fun markAsUninstalled(packageName: String)

    @Query("UPDATE app_sources SET is_disabled = :disabled WHERE package_name = :packageName")
    suspend fun setDisabled(packageName: String, disabled: Boolean)

    // === 刪除 ===

    @Query("DELETE FROM app_sources WHERE package_name = :packageName")
    suspend fun deleteByPackageName(packageName: String)

    @Query("DELETE FROM app_sources")
    suspend fun deleteAll()
}
