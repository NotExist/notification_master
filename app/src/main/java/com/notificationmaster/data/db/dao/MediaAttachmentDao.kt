package com.notificationmaster.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.notificationmaster.data.db.entity.MediaAttachmentEntity
import com.notificationmaster.data.db.entity.MediaType
import kotlinx.coroutines.flow.Flow

/**
 * 媒體附件 DAO
 */
@Dao
interface MediaAttachmentDao {

    // === 插入 ===

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attachment: MediaAttachmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(attachments: List<MediaAttachmentEntity>): List<Long>

    // === 查詢 ===

    @Query("SELECT * FROM media_attachments WHERE notification_id = :notificationId")
    fun getAttachmentsByNotificationId(notificationId: Long): Flow<List<MediaAttachmentEntity>>

    @Query("SELECT * FROM media_attachments WHERE notification_id = :notificationId")
    suspend fun getAttachmentsByNotificationIdSync(notificationId: Long): List<MediaAttachmentEntity>

    @Query("""
        SELECT * FROM media_attachments
        WHERE notification_id = :notificationId AND media_type = :mediaType
        LIMIT 1
    """)
    suspend fun getAttachmentByType(notificationId: Long, mediaType: MediaType): MediaAttachmentEntity?

    @Query("SELECT * FROM media_attachments WHERE content_hash = :hash LIMIT 1")
    suspend fun getByHash(hash: String): MediaAttachmentEntity?

    // === 統計 ===

    @Query("SELECT SUM(file_size) FROM media_attachments")
    suspend fun getTotalSize(): Long?

    @Query("SELECT COUNT(*) FROM media_attachments")
    suspend fun getTotalCount(): Int

    // === 刪除 ===

    @Query("DELETE FROM media_attachments WHERE notification_id = :notificationId")
    suspend fun deleteByNotificationId(notificationId: Long)

    @Query("DELETE FROM media_attachments WHERE capture_time < :beforeTime")
    suspend fun deleteBeforeTime(beforeTime: Long): Int

    /**
     * 取得指定時間之前的所有附件 (用於刪除前取得檔案路徑)
     */
    @Query("SELECT file_path FROM media_attachments WHERE capture_time < :beforeTime")
    suspend fun getFilePathsBeforeTime(beforeTime: Long): List<String>

    @Query("DELETE FROM media_attachments")
    suspend fun deleteAll()
}
