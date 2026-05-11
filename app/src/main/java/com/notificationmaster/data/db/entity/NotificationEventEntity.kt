package com.notificationmaster.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 通知生命週期事件類型（Plan 2）
 *
 * RANKING 已從 enum 移除：ranking 變動改寫到獨立的 RankingObservationEntity，
 * 不再產生 NotificationEvent。
 */
enum class EventType {
    /** 服務啟動時的現有通知 */
    INITIAL,
    /** 新通知發布 */
    POSTED,
    /** 通知更新 */
    UPDATED,
    /** 通知移除 */
    REMOVED
}

/**
 * 移除原因分類
 *
 * 涵蓋 Android SDK 定義的所有 REASON_* 常數（API 26–33）+ App 自訂類別。
 */
object RemovalReasonCategory {
    // 使用者操作
    const val USER_CLICK = "USER_CLICK"                 // 1
    const val USER_DISMISS = "USER_DISMISS"             // 2
    const val USER_CLEAR_ALL = "USER_CLEAR_ALL"         // 3
    const val USER_STOPPED = "USER_STOPPED"             // 6
    const val USER_SNOOZE = "USER_SNOOZE"               // 18
    const val CLEAR_DATA = "CLEAR_DATA"                 // 21

    // App 操作
    const val APP_CANCEL = "APP_CANCEL"                 // 8
    const val APP_CANCEL_ALL = "APP_CANCEL_ALL"         // 9

    // 監聽器操作
    const val LISTENER_CANCEL = "LISTENER_CANCEL"       // 10
    const val LISTENER_CANCEL_ALL = "LISTENER_CANCEL_ALL" // 11
    const val ASSISTANT_CANCEL = "ASSISTANT_CANCEL"     // 22

    // 系統操作
    const val ERROR = "ERROR"                           // 4
    const val PACKAGE_CHANGED = "PACKAGE_CHANGED"       // 5
    const val PACKAGE_BANNED = "PACKAGE_BANNED"         // 7
    const val GROUP_SUMMARY_CANCELED = "GROUP_SUMMARY_CANCELED" // 12
    const val GROUP_OPTIMIZATION = "GROUP_OPTIMIZATION" // 13
    const val PACKAGE_SUSPENDED = "PACKAGE_SUSPENDED"   // 14
    const val PROFILE_TURNED_OFF = "PROFILE_TURNED_OFF" // 15
    const val UNINSTALLED = "UNINSTALLED"               // 16
    const val CHANNEL_BANNED = "CHANNEL_BANNED"         // 17
    const val TIMEOUT = "TIMEOUT"                       // 19
    const val CHANNEL_REMOVED = "CHANNEL_REMOVED"       // 20

    // App 自訂（Plan 2 reconciliation）
    /** INITIAL 後差集補登錄漏接的 REMOVED（reason code = -100） */
    const val RECONCILED_AFTER_FACT = "RECONCILED_AFTER_FACT"

    const val OTHER = "OTHER"
}

/**
 * 通知生命週期事件 Entity（Plan 2 重寫）
 *
 * 事件為主體，eventRawJson 自包含完整 callback 元資料 + sbn（含 notification 子物件），
 * **不含 ranking**（ranking 在 RankingObservationEntity 獨立軌道）。
 *
 * - PK 為 autoGenerate id
 * - FK 指向 NotificationRecordEntity.notification_key（CASCADE）
 * - 篩選投影 column（is_audible / likely_headsup）寫入時即時計算後快取，
 *   名稱對齊 Plan 1 EventFilterSpec 已使用的欄位
 *
 * 註：原 contentDiff column 已移除。新設計改 read-time 從前後兩個 event 的
 * eventRawJson diff 計算（detail 頁顯示），write-time 不再預存有限欄位。
 */
@Entity(
    tableName = "notification_events",
    foreignKeys = [
        ForeignKey(
            entity = NotificationRecordEntity::class,
            parentColumns = ["notification_key"],
            childColumns = ["notification_key"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["notification_key"]),
        Index(value = ["event_time"]),
        Index(value = ["event_type"]),
        Index(value = ["package_name"]),
        Index(value = ["channel_id"]),
        Index(value = ["post_time"]),
        Index(value = ["content_hash"]),
        Index(value = ["is_audible"]),
        Index(value = ["likely_headsup"])
    ]
)
data class NotificationEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // === 事件身分 ===

    @ColumnInfo(name = "notification_key")
    val notificationKey: String,

    @ColumnInfo(name = "event_type")
    val eventType: EventType,

    /** 事件發生時間（沿用舊 column 語意；POSTED/UPDATED/REMOVED 為 captureTime） */
    @ColumnInfo(name = "event_time")
    val eventTime: Long,

    /** 擷取進入 service 的時間（與 eventTime 通常相同，分開保留以利除錯） */
    @ColumnInfo(name = "capture_time")
    val captureTime: Long,

    // === 索引投影（從 raw.sbn 抽出，供 SQL 篩選與 LIKE 搜尋） ===

    @ColumnInfo(name = "package_name")
    val packageName: String,

    @ColumnInfo(name = "channel_id")
    val channelId: String?,

    @ColumnInfo(name = "post_time")
    val postTime: Long,

    @ColumnInfo(name = "content_hash")
    val contentHash: String,

    @ColumnInfo(name = "title")
    val title: String?,

    @ColumnInfo(name = "text")
    val text: String?,

    // === 事件特有 ===

    /** Android REASON_* (1-22) 或 App 自訂負值（如 -100 = RECONCILED_AFTER_FACT） */
    @ColumnInfo(name = "removal_reason")
    val removalReason: Int?,

    @ColumnInfo(name = "removal_reason_category")
    val removalReasonCategory: String?,

    // === 篩選投影（寫入時即時計算後快取，對齊 Plan 1 EventFilterSpec 欄位） ===

    @ColumnInfo(name = "is_audible")
    val isAudible: Boolean,

    @ColumnInfo(name = "likely_headsup")
    val likelyHeadsup: Boolean,

    // === Raw（callback 元資料 + sbn，不含 ranking） ===

    @ColumnInfo(name = "event_raw_json")
    val eventRawJson: String
)
