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
 *
 * 涵蓋 Android SDK 定義的所有 REASON_* 常數（API 26–33）。
 * 每個常數對應一個獨立的分類值，確保完整反映系統回報的移除原因。
 */
object RemovalReasonCategory {
    // 使用者操作
    const val USER_CLICK = "USER_CLICK"                 // 1: 使用者點擊通知
    const val USER_DISMISS = "USER_DISMISS"             // 2: 使用者滑動清除
    const val USER_CLEAR_ALL = "USER_CLEAR_ALL"         // 3: 使用者全部清除
    const val USER_STOPPED = "USER_STOPPED"             // 6: 使用者強制停止 App
    const val USER_SNOOZE = "USER_SNOOZE"               // 18: 使用者暫停通知
    const val CLEAR_DATA = "CLEAR_DATA"                 // 21: 使用者清除 App 資料

    // App 操作
    const val APP_CANCEL = "APP_CANCEL"                 // 8: App 程式取消
    const val APP_CANCEL_ALL = "APP_CANCEL_ALL"         // 9: App 程式取消全部

    // 監聽器操作
    const val LISTENER_CANCEL = "LISTENER_CANCEL"       // 10: 監聽器取消
    const val LISTENER_CANCEL_ALL = "LISTENER_CANCEL_ALL" // 11: 監聽器取消全部
    const val ASSISTANT_CANCEL = "ASSISTANT_CANCEL"     // 22: 數位助理取消

    // 系統操作
    const val ERROR = "ERROR"                           // 4: 系統錯誤
    const val PACKAGE_CHANGED = "PACKAGE_CHANGED"       // 5: App 更新
    const val PACKAGE_BANNED = "PACKAGE_BANNED"         // 7: App 通知被封鎖
    const val GROUP_SUMMARY_CANCELED = "GROUP_SUMMARY_CANCELED" // 12: 群組摘要取消
    const val GROUP_OPTIMIZATION = "GROUP_OPTIMIZATION" // 13: 群組最佳化
    const val PACKAGE_SUSPENDED = "PACKAGE_SUSPENDED"   // 14: App 被暫停
    const val PROFILE_TURNED_OFF = "PROFILE_TURNED_OFF" // 15: 工作設定檔關閉
    const val UNINSTALLED = "UNINSTALLED"               // 16: App 已解除安裝
    const val CHANNEL_BANNED = "CHANNEL_BANNED"         // 17: Channel 被禁用
    const val TIMEOUT = "TIMEOUT"                       // 19: 超時自動移除
    const val CHANNEL_REMOVED = "CHANNEL_REMOVED"       // 20: Channel 已移除

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

    // === 變動內容 ===
    /** 變動內容 (JSON，僅 UPDATED 事件有值，記錄與前一版本的差異) */
    @ColumnInfo(name = "content_diff")
    val contentDiff: String?
)
