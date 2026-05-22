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
 * - rank（噪音欄位，每次重排都變）獨立存於 observation 自身
 * - lastAudiblyAlertedMillis：phase 31s 後加入 snapshot hash，也存 observation
 *   給 merger 還原當下值；snapshot reuse 場景下 observation 值更準確
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

    /** 噪音欄位：rank（每個 RankingMap 都會變動） */
    @ColumnInfo(name = "rank")
    val rank: Int?,

    /** 噪音欄位：lastAudiblyAlertedMillis（API 29+） */
    @ColumnInfo(name = "last_audibly_alerted_millis")
    val lastAudiblyAlertedMillis: Long?
)
