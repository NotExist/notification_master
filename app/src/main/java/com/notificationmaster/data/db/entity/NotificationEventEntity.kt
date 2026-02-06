package com.notificationmaster.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 通知生命週期事件類型
 */
enum class EventType {
    /** 服務啟動時的現有通知 */
    INITIAL,
    /** 新通知發布 */
    POSTED,
    /** 通知更新 */
    UPDATED,
    /** 通知移除 */
    REMOVED,
    /** 排序/Ranking 變更 */
    RANKING
}

/**
 * 移除原因分類
 */
object RemovalReasonCategory {
    const val USER_CLICK = "USER_CLICK"
    const val USER_SNOOZE = "USER_SNOOZE"
    const val APP_CANCEL = "APP_CANCEL"
    const val APP_CANCEL_ALL = "APP_CANCEL_ALL"
    const val LISTENER_OR_SWIPE = "LISTENER_OR_SWIPE"
    const val TIMEOUT = "TIMEOUT"
    const val CHANNEL_BANNED = "CHANNEL_BANNED"
    const val UNINSTALLED = "UNINSTALLED"
    const val OTHER = "OTHER"
}

/**
 * 通知生命週期事件 Entity
 * 記錄 INITIAL/POSTED/UPDATED/REMOVED/RANKING 事件
 */
@Entity(
    tableName = "notification_events",
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
        Index(value = ["notification_key"]),
        Index(value = ["event_time"]),
        Index(value = ["event_type"])
    ]
)
data class NotificationEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 關聯的通知記錄 ID */
    @ColumnInfo(name = "notification_id")
    val notificationId: Long,

    /** 通知 Key (方便查詢) */
    @ColumnInfo(name = "notification_key")
    val notificationKey: String,

    /** 事件類型 */
    @ColumnInfo(name = "event_type")
    val eventType: EventType,

    /** 事件發生時間 */
    @ColumnInfo(name = "event_time")
    val eventTime: Long,

    // === 移除相關 ===
    /** 原始移除原因值 (僅 REMOVED 事件有值) */
    @ColumnInfo(name = "removal_reason")
    val removalReason: Int?,

    /** 移除原因分類 (僅 REMOVED 事件有值) */
    @ColumnInfo(name = "removal_reason_category")
    val removalReasonCategory: String?,

    // === Ranking 相關 ===
    /** Ranking 中的 rank 值 (RANKING 事件) */
    @ColumnInfo(name = "ranking_rank")
    val rankingRank: Int?,

    /** importance 值 */
    @ColumnInfo(name = "ranking_importance")
    val rankingImportance: Int?,

    /** 是否為 ambient */
    @ColumnInfo(name = "is_ambient")
    val isAmbient: Boolean?,

    /** 是否被暫停 */
    @ColumnInfo(name = "is_suspended")
    val isSuspended: Boolean?,

    /** 被抑制的視覺效果 */
    @ColumnInfo(name = "suppressed_visual_effects")
    val suppressedVisualEffects: Int?,

    // === 內容快照 ===
    /** 內容快照 (JSON，用於追蹤更新變化) */
    @ColumnInfo(name = "content_snapshot")
    val contentSnapshot: String?
)
