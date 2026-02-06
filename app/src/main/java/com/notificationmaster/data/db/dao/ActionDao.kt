package com.notificationmaster.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.notificationmaster.data.db.entity.ActionEntity
import kotlinx.coroutines.flow.Flow

/**
 * 動作按鈕 DAO
 */
@Dao
interface ActionDao {

    // === 插入 ===

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(action: ActionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(actions: List<ActionEntity>): List<Long>

    // === 查詢 ===

    @Query("SELECT * FROM actions WHERE notification_id = :notificationId ORDER BY action_index ASC")
    fun getActionsByNotificationId(notificationId: Long): Flow<List<ActionEntity>>

    @Query("SELECT * FROM actions WHERE notification_id = :notificationId ORDER BY action_index ASC")
    suspend fun getActionsByNotificationIdSync(notificationId: Long): List<ActionEntity>

    @Query("SELECT * FROM actions WHERE notification_id = :notificationId AND is_reply_action = 1 LIMIT 1")
    suspend fun getReplyAction(notificationId: Long): ActionEntity?

    // === 統計 ===

    @Query("SELECT COUNT(*) FROM actions WHERE semantic_action = :semanticAction")
    suspend fun getCountBySemanticAction(semanticAction: Int): Int

    // === 刪除 ===

    @Query("DELETE FROM actions WHERE notification_id = :notificationId")
    suspend fun deleteByNotificationId(notificationId: Long)

    @Query("DELETE FROM actions WHERE capture_time < :beforeTime")
    suspend fun deleteBeforeTime(beforeTime: Long): Int

    @Query("DELETE FROM actions")
    suspend fun deleteAll()
}
