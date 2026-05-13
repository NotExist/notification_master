package com.notificationmaster.ui.common

import com.notificationmaster.core.RankingSnapshotMerger
import com.notificationmaster.data.db.dao.ChannelDao
import com.notificationmaster.data.db.dao.RankingObservationDao
import com.notificationmaster.data.db.dao.RankingSnapshotDao
import com.notificationmaster.data.db.entity.NotificationEventEntity

/**
 * Plan 2 Phase 14 Q2-A/B：對一批 events 批次預載 channel + ranking enrichment
 * 後 map 為 [NotificationDisplay]。
 *
 * 設計：
 * - 一次 query channels 表 + 一次 query 各 key 最新 RankingObservation + 一次 query 對應 RankingSnapshot
 * - 對 N 筆 events，最多 3 次 batch query（O(1) 在 query 數量上），避免 N+1
 * - Timeline / Search / Archive 共用同一條 enrichment 路徑，list-time chip 一致
 */
object NotificationEnricher {

    suspend fun enrich(
        events: List<NotificationEventEntity>,
        channelDao: ChannelDao,
        rankingObsDao: RankingObservationDao,
        rankingSnapDao: RankingSnapshotDao
    ): List<NotificationDisplay> {
        if (events.isEmpty()) return emptyList()

        // Channel enrichment：API 26+ 用 channels.importance；API <26 沒 channel 概念，map 拿到 -1
        val channelMap: Map<String, Int> = if (events.any { it.channelId != null }) {
            channelDao.getAllChannelsSync()
                .associateBy(
                    keySelector = { "${it.packageName}|${it.channelId}" },
                    valueTransform = { it.importance }
                )
        } else emptyMap()

        // Ranking enrichment：每個 key 最新 observation + 對應 snapshot
        val keys = events.map { it.notificationKey }.distinct()
        val observations = rankingObsDao.getLatestByKeysSync(keys)
        val snapshotIds = observations.map { it.rankingSnapshotId }.toSet()
        val snapshots = rankingSnapDao.getByIdsSync(snapshotIds.toList())
            .associateBy { it.id }
        val rankingJsonMap: Map<String, org.json.JSONObject?> = observations.associate { obs ->
            val snap = snapshots[obs.rankingSnapshotId]
            obs.notificationKey to (snap?.let {
                RankingSnapshotMerger.merge(it, obs)
            })
        }

        return events.map { event ->
            val channelKey = event.channelId?.let { "${event.packageName}|$it" }
            val importance = channelKey?.let { channelMap[it] } ?: -1
            val mergedJson = rankingJsonMap[event.notificationKey]
            NotificationDisplay.from(
                event,
                NotificationDisplay.Enrichment(
                    channelImportance = importance,
                    mergedRankingJson = mergedJson
                )
            )
        }
    }
}
