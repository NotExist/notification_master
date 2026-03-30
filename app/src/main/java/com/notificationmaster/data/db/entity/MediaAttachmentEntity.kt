package com.notificationmaster.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 媒體附件類型
 */
enum class MediaType {
    /** 大圖示 (EXTRA_LARGE_ICON) */
    LARGE_ICON,
    /** BigPictureStyle 圖片 (EXTRA_PICTURE) */
    PICTURE,
    /** 小圖示 */
    SMALL_ICON,
    /** BigPictureStyle 大圖示 (EXTRA_LARGE_ICON_BIG) */
    LARGE_ICON_BIG,
    /** MessagingStyle 對話頭像 (Person.getIcon(), API 28+) */
    MESSAGING_AVATAR,
    /** MessagingStyle 訊息中的媒體附件 (Message.setData URI) */
    MESSAGE_MEDIA,
    /** 其他附件 */
    OTHER
}

/**
 * 媒體附件 Entity
 * 儲存通知中的圖片等媒體資源
 */
@Entity(
    tableName = "media_attachments",
    foreignKeys = [
        ForeignKey(
            entity = NotificationEntity::class,
            parentColumns = ["id"],
            childColumns = ["notification_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["notification_id"]),
        Index(value = ["media_type"]),
        Index(value = ["content_hash"])
    ]
)
data class MediaAttachmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 關聯的通知記錄 ID */
    @ColumnInfo(name = "notification_id")
    val notificationId: Long,

    /** 媒體類型 */
    @ColumnInfo(name = "media_type")
    val mediaType: MediaType,

    /** 媒體檔名（如 "com.example_PICTURE_abc123.png"） */
    @ColumnInfo(name = "file_path")
    val filePath: String,

    /** MIME 類型 */
    @ColumnInfo(name = "mime_type")
    val mimeType: String,

    /** 檔案大小 (bytes) */
    @ColumnInfo(name = "file_size")
    val fileSize: Long,

    /** 圖片寬度 */
    @ColumnInfo(name = "width")
    val width: Int,

    /** 圖片高度 */
    @ColumnInfo(name = "height")
    val height: Int,

    /** 擷取時間 */
    @ColumnInfo(name = "capture_time")
    val captureTime: Long,

    /** Hash (用於去重) */
    @ColumnInfo(name = "content_hash")
    val contentHash: String,

    /** 原始來源 URI（content:// URI，無法提取時保留供參考） */
    @ColumnInfo(name = "source_uri")
    val sourceUri: String? = null
)
