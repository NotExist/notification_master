package com.notificationmaster.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 語意動作類型 (API 28+)
 * 對應 Notification.Action.SEMANTIC_ACTION_*
 */
object SemanticAction {
    const val NONE = 0
    const val REPLY = 1
    const val MARK_AS_READ = 2
    const val MARK_AS_UNREAD = 3
    const val DELETE = 4
    const val ARCHIVE = 5
    const val MUTE = 6
    const val UNMUTE = 7
    const val THUMBS_UP = 8
    const val THUMBS_DOWN = 9
    const val CALL = 10
}

/**
 * 動作按鈕 Entity
 * 記錄通知的 Action 按鈕資訊
 *
 * Plan 2：FK 改指 NotificationEventEntity（per-event 子物件，每個事件各自的 actions snapshot）
 */
@Entity(
    tableName = "actions",
    foreignKeys = [
        ForeignKey(
            entity = NotificationEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["event_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["event_id"])
    ]
)
data class ActionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 關聯的事件 ID（NotificationEventEntity.id） */
    @ColumnInfo(name = "event_id")
    val eventId: Long,

    /** 動作在陣列中的索引 */
    @ColumnInfo(name = "action_index")
    val actionIndex: Int,

    /** 動作標題 */
    @ColumnInfo(name = "title")
    val title: String?,

    /** 圖示資源 ID */
    @ColumnInfo(name = "icon_res_id")
    val iconResId: Int,

    /** PendingIntent 建立者 App 包名 (API 17+) */
    @ColumnInfo(name = "creator_package")
    val creatorPackage: String?,

    /** 語意動作類型 (API 28+) */
    @ColumnInfo(name = "semantic_action")
    val semanticAction: Int,

    /** 是否為回覆動作 */
    @ColumnInfo(name = "is_reply_action")
    val isReplyAction: Boolean,

    /** 回覆提示文字 (直接回覆的 label) */
    @ColumnInfo(name = "reply_label")
    val replyLabel: String?,

    /** 回覆預設選項 (JSON 陣列) */
    @ColumnInfo(name = "reply_choices")
    val replyChoices: String?,

    /** RemoteInput 的 key */
    @ColumnInfo(name = "remote_input_key")
    val remoteInputKey: String?,

    /** 是否允許自由輸入 */
    @ColumnInfo(name = "allows_free_form_input")
    val allowsFreeFormInput: Boolean,

    /** 是否為 contextual action (API 29+) */
    @ColumnInfo(name = "is_contextual")
    val isContextual: Boolean,

    /** 是否需要認證才能執行 (API 31+) */
    @ColumnInfo(name = "is_authentication_required")
    val isAuthenticationRequired: Boolean,

    /** 擷取時間 */
    @ColumnInfo(name = "capture_time")
    val captureTime: Long
)
