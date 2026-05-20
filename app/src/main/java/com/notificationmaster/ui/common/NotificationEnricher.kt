package com.notificationmaster.ui.common

import android.util.Log
import com.notificationmaster.core.RankingSnapshotMerger
import com.notificationmaster.core.debug.ProfileLogger
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
 *
 * Phase 26：per-event runCatching 防護。
 * NotificationDisplay.from 內部對 JSON 解析已用 null safety，但仍可能因為奇怪 raw 結構
 * 拋 RuntimeException。per-event try-catch 確保單筆壞 row 不讓整批 enrich 失敗。
 * Batch query 例外（SQLiteException 等）會直接拋出，由呼叫端的 Flow `.catch` 接住。
 */
object NotificationEnricher {

    private const val TAG = "NotificationEnricher"

    suspend fun enrich(
        events: List<NotificationEventEntity>,
        channelDao: ChannelDao,
        rankingObsDao: RankingObservationDao,
        rankingSnapDao: RankingSnapshotDao
    ): List<NotificationDisplay> {
        if (events.isEmpty()) return emptyList()
        val t0 = System.currentTimeMillis()

        // Channel enrichment：API 26+ 用 channels.importance；API <26 沒 channel 概念，map 拿到 -1
        val t1 = System.currentTimeMillis()
        val channelMap: Map<String, Int> = if (events.any { it.channelId != null }) {
            channelDao.getAllChannelsSync()
                .associateBy(
                    keySelector = { "${it.packageName}|${it.channelId}" },
                    valueTransform = { it.importance }
                )
        } else emptyMap()
        val t2 = System.currentTimeMillis()

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
        val t3 = System.currentTimeMillis()

        val result = events.map { event ->
            runCatching {
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
            }.getOrElse { e ->
                Log.e(TAG, "enrich failed for event id=${event.id} key=${event.notificationKey}", e)
                fallbackDisplay(event)
            }
        }
        val t4 = System.currentTimeMillis()

        // Phase 29 profile log：定位 cold start / lazyload / archive 慢的瓶頸
        ProfileLogger.append(
            "Enricher",
            "enrich(${events.size}) total=${t4 - t0}ms " +
                "channels=${t2 - t1}ms ranking=${t3 - t2}ms map=${t4 - t3}ms " +
                "channelsCount=${channelMap.size} keysCount=${keys.size} obsCount=${observations.size}"
        )
        return result
    }

    /**
     * Phase 26：壞 row 的最簡 NotificationDisplay — 從 event entity column 直接取值，
     * 不碰 eventRawJson（parse 例外是觸發 fallback 的主因）。標示 title 讓 user 知情。
     */
    private fun fallbackDisplay(event: NotificationEventEntity): NotificationDisplay {
        return NotificationDisplay(
            event = event,
            // Phase 27：snapshot field 已移除
            isRemoved = event.eventType == com.notificationmaster.data.db.entity.EventType.REMOVED,
            packageName = event.packageName,
            notificationKey = event.notificationKey,
            channelId = event.channelId,
            contentHash = event.contentHash,
            postTime = event.postTime,
            captureTime = event.captureTime,
            whenTime = 0L,
            title = event.title ?: "(parse error)",
            text = event.text,
            bigText = null,
            subText = null,
            infoText = null,
            summaryText = null,
            bigTitle = null,
            tickerText = null,
            conversationTitle = null,
            flags = 0,
            isOngoing = false,
            isAutoCancel = false,
            isNoClear = false,
            isHighPriority = false,
            isLocalOnly = false,
            isGroupSummary = false,
            isForegroundService = false,
            isAudible = event.isAudible,
            likelyHeadsup = event.likelyHeadsup,
            priority = 0,
            visibility = 0,
            category = null,
            groupKey = null,
            sortKey = null,
            color = 0,
            template = null,
            isMessagingStyle = false,
            isGroupConversation = false,
            hasBubbleMetadata = false,
            hasCustomContentView = false,
            hasCustomBigContentView = false,
            hasCustomHeadsUpContentView = false,
            showChronometer = false,
            shortcutId = null,
            importance = -1,
            isConversation = false,
            isAmbient = false,
            isSuspended = false,
            hasContentIntent = false,
            hasFullScreenIntent = false,
            hasDeleteIntent = false
        )
    }
}
