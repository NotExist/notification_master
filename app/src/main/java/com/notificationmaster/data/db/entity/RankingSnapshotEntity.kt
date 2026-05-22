package com.notificationmaster.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Ranking 快照（dedup 儲存）— Plan 2
 *
 * `contentHash` 計算前排除 `rank`（每次重排都變、跟內容無關的真噪音）。
 * 其餘欄位（importance / channel / smartReplies / smartActions / isAmbient / isSuspended /
 * canBubble / canShowBadge / suppressedVisualEffects / isConversation /
 * conversationShortcutInfo / overrideGroupKey / **lastAudiblyAlertedMillis**）參與 hash。
 *
 * Phase 31s：lastAudiblyAlertedMillis 從噪音清單移除，加入 hash 計算。
 * 該值只在 NMS 真的播放時才更新，加入 hash 不會爆炸；正好讓「真的響過」事件
 * 在 detail timeline 留下 observation 痕跡。
 *
 * 完整還原原始 ranking entry = snapshot.rankingJson + observation.{rank, lastAudiblyAlertedMillis}
 * （observation.lastAudibly 仍會覆蓋 snapshot 內的同欄位 — snapshot reuse 時 snapshot
 * 內 lastAudibly 可能 stale，靠 observation 還原當下值）
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

    /** 正規化版本（不含 rank；phase 31s 起含 lastAudiblyAlertedMillis） */
    @ColumnInfo(name = "ranking_json")
    val rankingJson: String,

    @ColumnInfo(name = "first_seen")
    val firstSeen: Long
)
