package com.notificationmaster.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Ranking observation 來源 callback。
 */
enum class ObservationSource {
    POSTED,
    UPDATED,
    INITIAL,
    REMOVED,
    RANKING_UPDATE
}

/**
 * Ranking 觀察點 — Plan 2
 *
 * 對應某個 callback 在某時刻對某 notificationKey 觀察到的 ranking 狀態：
 * - snapshot 部分（重複內容自動 dedup）→ 引用 RankingSnapshotEntity
 * - lastAudiblyAlertedMillis：phase 31s 起加入 snapshot hash（真的響過才會變動，
 *   不會爆）；observation 同時存一份，snapshot reuse 時用 observation 值還原（snapshot
 *   內可能是其他 observation 寫入時的舊值）
 *
 * Phase 31t：rank（排序）完全移除 — 既不入 hash 也不存 observation。原因：rank 變化
 * 不能準確記錄為時間序列 observation，顯示 rank=x 反而誤導 user 以為某時刻 rank 變成
 * 該值。徹底處理 = 不存不顯示。
 *
 * 寫入規則：
 *   POSTED / UPDATED / INITIAL / REMOVED → 對應事件同時寫一筆 observation
 *   RANKING_UPDATE → 對 RankingMap 每個 key，與該 key 上一筆 observation 的 snapshot 比對；
 *                    snapshot 變化才寫（純噪音變化跳過）
 */
@Entity(
    tableName = "ranking_observations",
    foreignKeys = [
        ForeignKey(
            entity = NotificationRecordEntity::class,
            parentColumns = ["notification_key"],
            childColumns = ["notification_key"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = RankingSnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["ranking_snapshot_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["notification_key"]),
        Index(value = ["observed_at"]),
        Index(value = ["ranking_snapshot_id"])
    ]
)
data class RankingObservationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "notification_key")
    val notificationKey: String,

    @ColumnInfo(name = "observed_at")
    val observedAt: Long,

    @ColumnInfo(name = "ranking_snapshot_id")
    val rankingSnapshotId: Long,

    @ColumnInfo(name = "source")
    val source: ObservationSource,

    /**
     * lastAudiblyAlertedMillis（API 29+）— NMS 維護的「最後播放聲響時刻」。
     * Phase 31s 起加入 snapshot hash；observation 仍存以還原當下準確值。
     */
    @ColumnInfo(name = "last_audibly_alerted_millis")
    val lastAudiblyAlertedMillis: Long?
)
