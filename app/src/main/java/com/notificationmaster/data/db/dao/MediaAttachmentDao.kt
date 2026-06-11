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

    @Query("SELECT * FROM media_attachments WHERE event_id = :eventId")
    fun getAttachmentsByEventId(eventId: Long): Flow<List<MediaAttachmentEntity>>

    @Query("SELECT * FROM media_attachments WHERE event_id = :eventId")
    suspend fun getAttachmentsByEventIdSync(eventId: Long): List<MediaAttachmentEntity>

    /** W23d：封存匯出 batch IN（呼叫端自行 chunk 控制 SQLite 變數上限） */
    @Query("SELECT * FROM media_attachments WHERE event_id IN (:eventIds)")
    suspend fun getAttachmentsByEventIdsSync(eventIds: List<Long>): List<MediaAttachmentEntity>

    /**
     * W22ac：取得同 notification_key 所有 event 的 attachments，按 content_hash dedup
     * 保留每組最早的 capture_time row（最早出現代表性 row），排除已知失敗 trace
     * （content_hash 開頭 save_failed_ / extract_failed_）跟 unavailable URI trace。
     *
     * 用於 Detail 「該通知歷史附件」總覽：使用者點 UPDATED event detail 看到本 event
     * 缺某些圖時，可從這區塊看到同 nkey 其他 event 留下的圖（hash 去重避免同樣的
     * SMALL_ICON 重複呈現）。
     */
    @Query("""
        SELECT m.* FROM media_attachments m
        INNER JOIN notification_events e ON e.id = m.event_id
        WHERE e.notification_key = :notificationKey
          AND m.file_path != ''
          AND m.content_hash NOT LIKE 'save_failed_%'
          AND m.content_hash NOT LIKE 'extract_failed_%'
          AND m.content_hash NOT LIKE 'unavailable_%'
          AND m.id IN (
              SELECT MIN(m2.id) FROM media_attachments m2
              INNER JOIN notification_events e2 ON e2.id = m2.event_id
              WHERE e2.notification_key = :notificationKey
              GROUP BY m2.content_hash
          )
        ORDER BY m.capture_time DESC
    """)
    suspend fun getAttachmentsByNotificationKeyDedup(notificationKey: String): List<MediaAttachmentEntity>

    @Query("""
        SELECT * FROM media_attachments
        WHERE event_id = :eventId AND media_type = :mediaType
        LIMIT 1
    """)
    suspend fun getAttachmentByType(eventId: Long, mediaType: MediaType): MediaAttachmentEntity?

    @Query("SELECT * FROM media_attachments WHERE content_hash = :hash LIMIT 1")
    suspend fun getByHash(hash: String): MediaAttachmentEntity?

    // === 統計 ===

    @Query("SELECT SUM(file_size) FROM media_attachments")
    suspend fun getTotalSize(): Long?

    @Query("SELECT COUNT(*) FROM media_attachments")
    suspend fun getTotalCount(): Int

    // === 刪除 ===

    @Query("DELETE FROM media_attachments WHERE event_id = :eventId")
    suspend fun deleteByEventId(eventId: Long)

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
