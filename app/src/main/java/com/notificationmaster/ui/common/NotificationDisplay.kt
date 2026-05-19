package com.notificationmaster.ui.common

import com.notificationmaster.core.NotificationSnapshotParser
import com.notificationmaster.data.db.entity.EventType
import com.notificationmaster.data.db.entity.NotificationEventEntity
import com.notificationmaster.data.model.NotificationSnapshot
import org.json.JSONObject

/**
 * UI 層渲染用的攤平資料（NotificationEventEntity + Snapshot 合成）。
 *
 * Plan 2：NotificationEntity 已移除，list adapter / widget / shortcut / detail 全部吃
 * [NotificationEventEntity]，呈現用屬性多數來自 snapshot 解析。為避免每個 Adapter 各自重複
 * parse 邏輯，集中於此 helper。
 *
 * Ranking-dependent 欄位（importance / isConversation / isAmbient / isSuspended /
 * lastAudiblyAlertedMillis）目前以預設值（-1 / false / -1L）填入；Phase 7b/9 收尾時
 * 由 RankingSnapshotMerger 合併最近一筆 ranking observation 補齊。
 */
data class NotificationDisplay(
    val event: NotificationEventEntity,

    // Phase 27：移除 snapshot 欄位（之前是 NotificationSnapshot? 持有 raw JSONObject）。
    // 攤平到下方純值欄位後不再持有 reference，list 內 N 個 display 從 ~50KB/筆 降到 ~2KB/筆。
    // 需要原始 snapshot 的場合（Detail Fragment）改為從 [event.eventRawJson] lazy parse。

    // === 事件層級 ===
    val isRemoved: Boolean,

    // === 識別 ===
    val packageName: String,
    val notificationKey: String,
    val channelId: String?,
    val contentHash: String,

    // === 時間 ===
    val postTime: Long,
    val captureTime: Long,
    val whenTime: Long,

    // === 文字內容 ===
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val infoText: String?,
    val summaryText: String?,
    val bigTitle: String?,
    val tickerText: String?,
    val conversationTitle: String?,

    // === flags（從 Notification.flags bitmask 解出，事件層級捕獲時的快照） ===
    val flags: Int,
    val isOngoing: Boolean,
    val isAutoCancel: Boolean,
    val isNoClear: Boolean,
    val isHighPriority: Boolean,
    val isLocalOnly: Boolean,
    val isGroupSummary: Boolean,
    val isForegroundService: Boolean,

    // === 推斷屬性（events 表 column） ===
    val isAudible: Boolean,
    val likelyHeadsup: Boolean,

    // === Notification 屬性（snapshot 解析） ===
    val priority: Int,
    val visibility: Int,
    val category: String?,
    val groupKey: String?,
    val sortKey: String?,
    val color: Int,

    // === Style / 模板 ===
    val template: String?,
    val isMessagingStyle: Boolean,
    val isGroupConversation: Boolean,
    val hasBubbleMetadata: Boolean,
    val hasCustomContentView: Boolean,
    val hasCustomBigContentView: Boolean,
    val hasCustomHeadsUpContentView: Boolean,
    val showChronometer: Boolean,
    val shortcutId: String?,

    // === Ranking-dependent（暫缺，Phase 7b 補入） ===
    val importance: Int,
    val isConversation: Boolean,
    val isAmbient: Boolean,
    val isSuspended: Boolean,

    // === Intent 屬性（snapshot 內 dig） ===
    val hasContentIntent: Boolean,
    val hasFullScreenIntent: Boolean,
    val hasDeleteIntent: Boolean
) {
    /** event PK（nav arg / DiffUtil 主鍵） */
    val eventId: Long get() = event.id

    /**
     * Phase 14 Q2-A/B：Display 渲染所需的外部 enrichment 資料。
     * - [channelImportance]：來自 [com.notificationmaster.data.db.entity.ChannelEntity.importance]（API 26+）
     *   API <26 或 channel 尚未補齊 → -1（呼叫端 fallback 用 priority）
     * - [mergedRankingJson]：來自 [com.notificationmaster.core.RankingSnapshotMerger.merge]
     *   解析後可填入 [isAmbient] / [isSuspended] / [isConversation]
     */
    data class Enrichment(
        val channelImportance: Int = -1,
        val mergedRankingJson: JSONObject? = null
    ) {
        val isAmbient: Boolean = mergedRankingJson?.optBoolean("isAmbient", false) ?: false
        val isSuspended: Boolean = mergedRankingJson?.optBoolean("isSuspended", false) ?: false
        val isConversation: Boolean = mergedRankingJson?.optBoolean("isConversation", false) ?: false
    }

    companion object {

        fun from(
            event: NotificationEventEntity,
            enrichment: Enrichment = Enrichment()
        ): NotificationDisplay {
            val snap = NotificationSnapshotParser.parse(event.eventRawJson)
            val flags = snap?.flags ?: 0
            val notif = snap?.notification
            val extras = snap?.extras
            val template = extras?.optStringOrNull("android.template")
                ?: notif?.optStringOrNull("template")
            return NotificationDisplay(
                event = event,
                // Phase 27：snap 是 local val，攤平後 function 結束 GC，display 不持有 reference
                isRemoved = event.eventType == EventType.REMOVED,
                packageName = event.packageName,
                notificationKey = event.notificationKey,
                channelId = event.channelId,
                contentHash = event.contentHash,
                postTime = event.postTime,
                captureTime = event.captureTime,
                whenTime = notif?.optLongOrNull("when") ?: 0L,
                title = event.title ?: snap?.title,
                text = event.text ?: snap?.text,
                bigText = snap?.bigText,
                subText = snap?.subText,
                infoText = snap?.infoText,
                summaryText = snap?.summaryText,
                bigTitle = snap?.bigTitle,
                tickerText = snap?.tickerText,
                conversationTitle = extras?.optStringOrNull("android.conversationTitle"),
                flags = flags,
                isOngoing = (flags and FLAG_ONGOING) != 0,
                isAutoCancel = (flags and FLAG_AUTO_CANCEL) != 0,
                isNoClear = (flags and FLAG_NO_CLEAR) != 0,
                isHighPriority = (flags and FLAG_HIGH_PRIORITY) != 0,
                isLocalOnly = (flags and FLAG_LOCAL_ONLY) != 0,
                isGroupSummary = (flags and FLAG_GROUP_SUMMARY) != 0,
                isForegroundService = (flags and FLAG_FOREGROUND_SERVICE) != 0,
                isAudible = event.isAudible,
                likelyHeadsup = event.likelyHeadsup,
                priority = notif?.optIntOrNull("priority") ?: 0,
                visibility = snap?.visibility ?: 0,
                category = snap?.category,
                groupKey = snap?.groupKey,
                sortKey = notif?.optStringOrNull("sortKey"),
                color = snap?.color ?: 0,
                template = template,
                isMessagingStyle = template?.endsWith("MessagingStyle") == true,
                isGroupConversation = extras?.optBoolean("android.isGroupConversation", false) == true,
                hasBubbleMetadata = notif?.optJSONObject("bubbleMetadata") != null,
                hasCustomContentView = notif?.optJSONObject("contentView") != null,
                hasCustomBigContentView = notif?.optJSONObject("bigContentView") != null,
                hasCustomHeadsUpContentView = notif?.optJSONObject("headsUpContentView") != null,
                showChronometer = extras?.optBoolean("android.showChronometer", false) == true,
                shortcutId = notif?.optStringOrNull("shortcutId"),
                importance = enrichment.channelImportance,
                isConversation = enrichment.isConversation ||
                    (template?.endsWith("MessagingStyle") == true &&
                        !notif?.optStringOrNull("shortcutId").isNullOrEmpty()),
                isAmbient = enrichment.isAmbient,
                isSuspended = enrichment.isSuspended,
                hasContentIntent = notif?.optJSONObject("contentIntent") != null,
                hasFullScreenIntent = notif?.optJSONObject("fullScreenIntent") != null,
                hasDeleteIntent = notif?.optJSONObject("deleteIntent") != null
            )
        }

        // Notification.flags bit 定義（從 framework 抄出，避免 import android.app.Notification）
        private const val FLAG_ONGOING = 0x02
        private const val FLAG_AUTO_CANCEL = 0x10
        private const val FLAG_NO_CLEAR = 0x20
        private const val FLAG_FOREGROUND_SERVICE = 0x40
        private const val FLAG_HIGH_PRIORITY = 0x80
        private const val FLAG_LOCAL_ONLY = 0x100
        private const val FLAG_GROUP_SUMMARY = 0x200
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null
