package com.notificationmaster.ui.detail

import com.notificationmaster.data.db.entity.NotificationEventEntity
import com.notificationmaster.data.db.entity.RankingObservationEntity
import com.notificationmaster.data.db.entity.RankingSnapshotEntity

/**
 * Detail 時間軸 row 模型（Plan 2 Phase 7b-B-4）。
 *
 * 同 notificationKey 的 events ∪ ranking observations 統一在一條時間軸上呈現，
 * 按時間排序。bind 時：
 * - [Event] 顯示事件類型 / 時間 / removalReason；diff 相對前一筆 Event
 * - [Observation] 顯示「RANKING」標籤 / observedAt；diff 相對前一筆 Observation 的
 *   merged ranking JSON
 */
sealed class TimelineRow {
    abstract val time: Long
    abstract val stableId: String

    data class Event(val event: NotificationEventEntity) : TimelineRow() {
        override val time get() = event.eventTime
        override val stableId get() = "e-${event.id}"
    }

    data class Observation(
        val observation: RankingObservationEntity,
        val snapshot: RankingSnapshotEntity
    ) : TimelineRow() {
        override val time get() = observation.observedAt
        override val stableId get() = "o-${observation.id}"
    }
}
