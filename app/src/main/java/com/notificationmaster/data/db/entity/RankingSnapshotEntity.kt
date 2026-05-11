package com.notificationmaster.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Ranking 快照（dedup 儲存）— Plan 2
 *
 * `contentHash` 計算前先排除 `rank` 與 `lastAudiblyAlertedMillis`（每次都會變的噪音），
 * 其餘欄位（importance / channel / smartReplies / smartActions / isAmbient / isSuspended /
 * canBubble / canShowBadge / suppressedVisualEffects / isConversation /
 * conversationShortcutInfo / overrideGroupKey）參與 hash。
 *
 * 完整還原原始 ranking entry = snapshot.rankingJson + observation.{rank, lastAudiblyAlertedMillis}
 */
@Entity(
    tableName = "ranking_snapshots",
    indices = [
        Index(value = ["content_hash"], unique = true)
    ]
)
data class RankingSnapshotEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 排除噪音欄位後的正規化 hash */
    @ColumnInfo(name = "content_hash")
    val contentHash: String,

    /** 正規化版本（不含 rank / lastAudiblyAlertedMillis） */
    @ColumnInfo(name = "ranking_json")
    val rankingJson: String,

    @ColumnInfo(name = "first_seen")
    val firstSeen: Long
)
