package com.notificationmaster.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 通知主記錄 Entity
 * 完整保留所有 NotificationListenerService 可取得的資訊
 */
@Entity(
    tableName = "notifications",
    indices = [
        Index(value = ["notification_key"]),
        Index(value = ["package_name"]),
        Index(value = ["post_time"]),
        Index(value = ["capture_time"]),
        Index(value = ["channel_id"]),
        Index(value = ["content_hash"]),
        Index(value = ["group_key"]),
        Index(value = ["has_content_intent"]),
        Index(value = ["content_intent_creator_package"])
    ]
)
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // === 識別資訊 ===
    /** 通知 Key (API 21+: sbn.key, 之前: packageName|id|tag) */
    @ColumnInfo(name = "notification_key")
    val notificationKey: String,

    /** 來源 App 包名 */
    @ColumnInfo(name = "package_name")
    val packageName: String,

    /** 通知 ID */
    @ColumnInfo(name = "notification_id")
    val notificationId: Int,

    /** 通知 Tag (可能為 null) */
    @ColumnInfo(name = "tag")
    val tag: String?,

    // === 時間資訊 ===
    /** 通知發佈時間 (來自 sbn.postTime) */
    @ColumnInfo(name = "post_time")
    val postTime: Long,

    /** 本 App 擷取時間 */
    @ColumnInfo(name = "capture_time")
    val captureTime: Long,

    /** 通知內的 when 欄位 */
    @ColumnInfo(name = "when_time")
    val whenTime: Long,

    // === 基本內容 ===
    /** 標題 (EXTRA_TITLE) */
    @ColumnInfo(name = "title")
    val title: String?,

    /** 內文 (EXTRA_TEXT) */
    @ColumnInfo(name = "text")
    val text: String?,

    /** 副標題 (EXTRA_SUB_TEXT) */
    @ColumnInfo(name = "sub_text")
    val subText: String?,

    /** 資訊文字 (EXTRA_INFO_TEXT) */
    @ColumnInfo(name = "info_text")
    val infoText: String?,

    /** 摘要文字 (EXTRA_SUMMARY_TEXT) */
    @ColumnInfo(name = "summary_text")
    val summaryText: String?,

    // === 展開內容 ===
    /** BigTextStyle 展開內容 (EXTRA_BIG_TEXT) */
    @ColumnInfo(name = "big_text")
    val bigText: String?,

    /** BigTextStyle 展開標題 (EXTRA_TITLE_BIG) */
    @ColumnInfo(name = "big_title")
    val bigTitle: String?,

    // === Ticker ===
    /** Ticker 文字 */
    @ColumnInfo(name = "ticker_text")
    val tickerText: String?,

    // === Flags 和狀態 ===
    /** 原始 flags 值 */
    @ColumnInfo(name = "flags")
    val flags: Int,

    /** FLAG_ONGOING_EVENT */
    @ColumnInfo(name = "is_ongoing")
    val isOngoing: Boolean,

    /** FLAG_FOREGROUND_SERVICE */
    @ColumnInfo(name = "is_foreground_service")
    val isForegroundService: Boolean,

    /** FLAG_AUTO_CANCEL */
    @ColumnInfo(name = "is_auto_cancel")
    val isAutoCancel: Boolean,

    /** FLAG_NO_CLEAR */
    @ColumnInfo(name = "is_no_clear")
    val isNoClear: Boolean,

    /** FLAG_HIGH_PRIORITY */
    @ColumnInfo(name = "is_high_priority")
    val isHighPriority: Boolean,

    /** FLAG_LOCAL_ONLY */
    @ColumnInfo(name = "is_local_only")
    val isLocalOnly: Boolean,

    /** FLAG_GROUP_SUMMARY */
    @ColumnInfo(name = "is_group_summary")
    val isGroupSummary: Boolean,

    // === 優先級/重要性 ===
    /** priority 值 (API 21-25) */
    @ColumnInfo(name = "priority")
    val priority: Int,

    /** Channel importance (API 26+, 否則 -1) */
    @ColumnInfo(name = "importance")
    val importance: Int,

    /** 推斷是否可能為 Heads-up */
    @ColumnInfo(name = "likely_headsup")
    val likelyHeadsup: Boolean,

    // === 可見性 ===
    /** visibility (VISIBILITY_PUBLIC/PRIVATE/SECRET) */
    @ColumnInfo(name = "visibility")
    val visibility: Int,

    // === 分類 ===
    /** category (CATEGORY_MESSAGE, CATEGORY_CALL 等) */
    @ColumnInfo(name = "category")
    val category: String?,

    // === 群組資訊 ===
    /** groupKey */
    @ColumnInfo(name = "group_key")
    val groupKey: String?,

    /** sortKey */
    @ColumnInfo(name = "sort_key")
    val sortKey: String?,

    // === Channel 資訊 (API 26+) ===
    /** Channel ID */
    @ColumnInfo(name = "channel_id")
    val channelId: String?,

    /** Shortcut ID (API 26+) — 關聯 sharing shortcut，判定對話通知的關鍵欄位 */
    @ColumnInfo(name = "shortcut_id")
    val shortcutId: String? = null,

    // === Bubble 資訊 (API 29+) ===
    /** 是否有 BubbleMetadata */
    @ColumnInfo(name = "has_bubble_metadata")
    val hasBubbleMetadata: Boolean,

    /** Bubble 推薦高度 (dp, API 29+, 0 表示未設定) */
    @ColumnInfo(name = "bubble_desired_height")
    val bubbleDesiredHeight: Int = 0,

    /** Bubble 推薦高度資源 ID (API 29+, 0 表示未設定) */
    @ColumnInfo(name = "bubble_desired_height_res_id")
    val bubbleDesiredHeightResId: Int = 0,

    /** Bubble 是否自動展開 (API 30+) */
    @ColumnInfo(name = "bubble_auto_expand")
    val bubbleAutoExpand: Boolean = false,

    /** Bubble 是否抑制通知顯示 (API 30+) */
    @ColumnInfo(name = "bubble_suppress_notification")
    val bubbleSuppressNotification: Boolean = false,

    // === 顏色 ===
    /** 通知顏色 */
    @ColumnInfo(name = "color")
    val color: Int,

    // === 聲音/震動 ===
    /** 聲音 URI */
    @ColumnInfo(name = "sound_uri")
    val soundUri: String?,

    /** 震動模式 (JSON 陣列) */
    @ColumnInfo(name = "vibrate_pattern")
    val vibratePattern: String?,

    /** LED 顏色 */
    @ColumnInfo(name = "led_argb")
    val ledArgb: Int,

    /** LED 亮燈時間 */
    @ColumnInfo(name = "led_on_ms")
    val ledOnMs: Int,

    /** LED 滅燈時間 */
    @ColumnInfo(name = "led_off_ms")
    val ledOffMs: Int,

    // === 進度 ===
    /** 進度值 */
    @ColumnInfo(name = "progress")
    val progress: Int,

    /** 進度最大值 */
    @ColumnInfo(name = "progress_max")
    val progressMax: Int,

    /** 是否為不確定進度 */
    @ColumnInfo(name = "progress_indeterminate")
    val progressIndeterminate: Boolean,

    // === 計時器 ===
    /** 是否顯示計時器 */
    @ColumnInfo(name = "show_chronometer")
    val showChronometer: Boolean,

    /** 計時器是否倒數 */
    @ColumnInfo(name = "chronometer_count_down")
    val chronometerCountDown: Boolean,

    /** 是否顯示時間 */
    @ColumnInfo(name = "show_when")
    val showWhen: Boolean,

    // === 聯絡人 ===
    /** 相關聯絡人 (JSON 陣列) */
    @ColumnInfo(name = "people")
    val people: String?,

    // === MessagingStyle (API 24+) ===
    /** 是否為 MessagingStyle */
    @ColumnInfo(name = "is_messaging_style")
    val isMessagingStyle: Boolean,

    /** 對話標題 */
    @ColumnInfo(name = "conversation_title")
    val conversationTitle: String?,

    /** 是否為群組對話 */
    @ColumnInfo(name = "is_group_conversation")
    val isGroupConversation: Boolean,

    /** 訊息內容 (JSON 陣列) */
    @ColumnInfo(name = "messages")
    val messages: String?,

    // === 樣式模板 ===
    /** 樣式模板名稱 (EXTRA_TEMPLATE) */
    @ColumnInfo(name = "template")
    val template: String?,

    // === 自訂 View ===
    /** 是否使用自訂 RemoteViews */
    @ColumnInfo(name = "has_custom_content_view")
    val hasCustomContentView: Boolean,

    /** 是否使用自訂大型 RemoteViews */
    @ColumnInfo(name = "has_custom_big_content_view")
    val hasCustomBigContentView: Boolean,

    /** 是否使用自訂 Heads-up RemoteViews */
    @ColumnInfo(name = "has_custom_headsup_content_view")
    val hasCustomHeadsUpContentView: Boolean,

    /** RemoteViews 詳細資訊 (JSON: layoutId, package per view type) */
    @ColumnInfo(name = "remote_views_info")
    val remoteViewsInfo: String? = null,

    // === 完整 Extras (JSON) ===
    /** 完整 extras bundle 序列化為 JSON */
    @ColumnInfo(name = "extras_json")
    val extrasJson: String?,

    // === 去重用 Hash ===
    /** 內容 Hash (用於去重檢視) */
    @ColumnInfo(name = "content_hash")
    val contentHash: String,

    // === 動作數量 ===
    /** Action 按鈕數量 */
    @ColumnInfo(name = "action_count")
    val actionCount: Int,

    // === User ID ===
    /** 使用者 ID (多使用者裝置) */
    @ColumnInfo(name = "user_id")
    val userId: Int,

    // === 持久性類型 ===
    /**
     * 通知持久性類型（推斷值）
     * FOREGROUND_SERVICE / ONGOING / PINNED / TRANSIENT
     */
    @ColumnInfo(name = "persistence_type", defaultValue = "TRANSIENT")
    val persistenceType: String = PersistenceType.TRANSIENT,

    // === Ranking 資訊 ===
    /** Ranking 中的 rank 值 */
    @ColumnInfo(name = "ranking_rank")
    val rankingRank: Int,

    /** 是否為 ambient (低優先級) */
    @ColumnInfo(name = "is_ambient")
    val isAmbient: Boolean,

    /** 是否被暫停 */
    @ColumnInfo(name = "is_suspended")
    val isSuspended: Boolean,

    /** 被抑制的視覺效果 */
    @ColumnInfo(name = "suppressed_visual_effects")
    val suppressedVisualEffects: Int,

    /** 是否為對話通知 (API 31+, Ranking 送達) */
    @ColumnInfo(name = "is_conversation")
    val isConversation: Boolean = false,

    /** 最後一次發出可聽見提示的時間 (API 28+, Ranking 送達, -1 表示不適用) */
    @ColumnInfo(name = "last_audibly_alerted_millis")
    val lastAudiblyAlertedMillis: Long = -1L,

    // === Intent 資訊 ===
    /** 是否有 contentIntent (點擊動作) */
    @ColumnInfo(name = "has_content_intent")
    val hasContentIntent: Boolean,

    /** 是否有 deleteIntent (滑除動作) */
    @ColumnInfo(name = "has_delete_intent")
    val hasDeleteIntent: Boolean,

    /** 是否有 fullScreenIntent (全螢幕動作，如來電/鬧鐘) */
    @ColumnInfo(name = "has_full_screen_intent")
    val hasFullScreenIntent: Boolean,

    /** contentIntent 建立者包名 */
    @ColumnInfo(name = "content_intent_creator_package")
    val contentIntentCreatorPackage: String?,

    /** Intent 完整資訊 (JSON) — contentIntent/deleteIntent/fullScreenIntent/publicVersion */
    @ColumnInfo(name = "intent_info_json")
    val intentInfoJson: String?
)

/**
 * 通知持久性類型常數
 */
object PersistenceType {
    /** 前景服務通知 (FLAG_FOREGROUND_SERVICE) */
    const val FOREGROUND_SERVICE = "FOREGROUND_SERVICE"
    /** 持續通知 (FLAG_ONGOING_EVENT，非前景服務) */
    const val ONGOING = "ONGOING"
    /** 固定通知 (FLAG_NO_CLEAR，非前兩者) */
    const val PINNED = "PINNED"
    /** 短暫通知 (其餘) */
    const val TRANSIENT = "TRANSIENT"
}
