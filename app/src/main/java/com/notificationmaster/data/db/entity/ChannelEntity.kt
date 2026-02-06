package com.notificationmaster.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 通知 Channel Entity (API 26+)
 * 記錄 NotificationChannel 資訊
 */
@Entity(
    tableName = "channels",
    foreignKeys = [
        ForeignKey(
            entity = AppSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["app_source_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["app_source_id"]),
        Index(value = ["channel_id", "package_name"], unique = true)
    ]
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 關聯的 App Source ID */
    @ColumnInfo(name = "app_source_id")
    val appSourceId: Long,

    /** App 包名 */
    @ColumnInfo(name = "package_name")
    val packageName: String,

    /** Channel ID */
    @ColumnInfo(name = "channel_id")
    val channelId: String,

    /** Channel 名稱 */
    @ColumnInfo(name = "channel_name")
    val channelName: String?,

    /** Channel 描述 */
    @ColumnInfo(name = "description")
    val description: String?,

    /** 重要性等級 */
    @ColumnInfo(name = "importance")
    val importance: Int,

    /** Channel Group ID */
    @ColumnInfo(name = "group_id")
    val groupId: String?,

    /** 是否顯示 Badge */
    @ColumnInfo(name = "show_badge")
    val showBadge: Boolean,

    /** 是否允許 Bubble */
    @ColumnInfo(name = "can_bubble")
    val canBubble: Boolean,

    /** 聲音 URI */
    @ColumnInfo(name = "sound_uri")
    val soundUri: String?,

    /** 震動模式 (JSON 陣列) */
    @ColumnInfo(name = "vibrate_pattern")
    val vibratePattern: String?,

    /** LED 顏色 */
    @ColumnInfo(name = "light_color")
    val lightColor: Int,

    /** 鎖屏可見性 */
    @ColumnInfo(name = "lock_screen_visibility")
    val lockScreenVisibility: Int,

    /** 是否被使用者封鎖 */
    @ColumnInfo(name = "is_blocked")
    val isBlocked: Boolean,

    /** 首次出現時間 */
    @ColumnInfo(name = "first_seen")
    val firstSeen: Long,

    /** 最後更新時間 */
    @ColumnInfo(name = "last_updated")
    val lastUpdated: Long,

    /** 通知總數 */
    @ColumnInfo(name = "notification_count")
    val notificationCount: Int
)
