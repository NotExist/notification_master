package com.notificationmaster.core

import android.os.Build
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import com.notificationmaster.core.compat.ApiVersionHelper
import com.notificationmaster.data.db.NotificationDatabase
import com.notificationmaster.data.db.entity.ObservationSource
import com.notificationmaster.data.db.entity.RankingObservationEntity
import com.notificationmaster.data.db.entity.RankingSnapshotEntity
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Ranking 處理器（Plan 2 Phase 5）
 *
 * 統一封裝：
 * 1. 從 RankingMap 抽出對應 sbn.key 的 Ranking entry
 * 2. 序列化為 JSON（透過 RawSerializer）
 * 3. 排除噪音欄位（rank / lastAudiblyAlertedMillis）→ 正規化 JSON + content hash
 * 4. RankingSnapshotDao.getByHash 命中則複用，否則 insert
 * 5. 寫一筆 RankingObservation
 * 6. 回傳當下推斷的 isAudible / likelyHeadsup（給呼叫端寫入 NotificationEvent column）
 *
 * RANKING_UPDATE callback 對 map 每個 key 走相同流程，只是 source = RANKING_UPDATE 且
 * snapshot hash 與該 key 上一筆相同時可跳過 observation 寫入（純噪音變化忽略）。
 */
class RankingProcessor(private val database: NotificationDatabase) {

    /**
     * 對單一 (key, ranking, sbn) 處理。
     *
     * @param sbn 用於 likelyHeadsup / isAudible 推斷（可能為 null：RANKING_UPDATE 流程沒有當前 sbn）
     * @return [Result] 含 snapshotId、observationId、推斷的 isAudible/likelyHeadsup；
     *         ranking 為 null 時回 null
     */
    suspend fun process(
        notificationKey: String,
        ranking: Ranking?,
        sbn: StatusBarNotification?,
        observedAt: Long,
        source: ObservationSource,
        skipIfHashUnchanged: Boolean = false
    ): Result? {
        if (ranking == null) return null

        val rankingJson = serializeRanking(ranking)
        val normalizedJson = normalizeForHash(rankingJson)
        val hash = sha256(normalizedJson.toString())

        val snapshotDao = database.rankingSnapshotDao()
        val existing = snapshotDao.getByHash(hash)

        // RANKING_UPDATE 流程：與該 key 上一筆 observation 的 snapshot 相同則跳過
        if (skipIfHashUnchanged && existing != null) {
            val lastObs = database.rankingObservationDao().getLatestByKey(notificationKey)
            if (lastObs != null && lastObs.rankingSnapshotId == existing.id) {
                return Result(
                    snapshotId = existing.id,
                    observationId = -1L,
                    isAudible = inferIsAudible(ranking, sbn, observedAt),
                    likelyHeadsup = inferLikelyHeadsup(ranking, sbn),
                    skipped = true
                )
            }
        }

        val snapshotId = existing?.id ?: snapshotDao.insertIfAbsent(
            RankingSnapshotEntity(
                contentHash = hash,
                rankingJson = normalizedJson.toString(),
                firstSeen = observedAt
            )
        ).also { id ->
            // insertIfAbsent 在 race condition 下 IGNORE 可能回 -1，重新查 hash 取 id
            if (id < 0) snapshotDao.getByHash(hash)?.id ?: -1L
        }

        val finalSnapshotId = if (snapshotId < 0) {
            snapshotDao.getByHash(hash)?.id ?: return null
        } else snapshotId

        val observationId = database.rankingObservationDao().insert(
            RankingObservationEntity(
                notificationKey = notificationKey,
                observedAt = observedAt,
                rankingSnapshotId = finalSnapshotId,
                source = source,
                rank = ranking.rank.takeIf { it >= 0 },
                lastAudiblyAlertedMillis = if (ApiVersionHelper.supportsLastAudiblyAlerted()) {
                    ranking.lastAudiblyAlertedMillis.takeIf { it > 0 }
                } else null
            )
        )

        return Result(
            snapshotId = finalSnapshotId,
            observationId = observationId,
            isAudible = inferIsAudible(ranking, sbn, observedAt),
            likelyHeadsup = inferLikelyHeadsup(ranking, sbn),
            skipped = false
        )
    }

    /** 對 RankingMap 每個 key 處理（RANKING_UPDATE 流程） */
    suspend fun processRankingMap(rankingMap: RankingMap, observedAt: Long): Int {
        var written = 0
        for (key in rankingMap.orderedKeys) {
            val ranking = Ranking()
            if (!rankingMap.getRanking(key, ranking)) continue
            val result = process(
                notificationKey = key,
                ranking = ranking,
                sbn = null,
                observedAt = observedAt,
                source = ObservationSource.RANKING_UPDATE,
                skipIfHashUnchanged = true
            )
            if (result != null && !result.skipped) written++
        }
        return written
    }

    private fun serializeRanking(ranking: Ranking): JSONObject {
        return RawSerializer.serialize(ranking) as? JSONObject ?: JSONObject()
    }

    /** 排除噪音欄位（rank / lastAudiblyAlertedMillis）後的正規化版本 */
    private fun normalizeForHash(rankingJson: JSONObject): JSONObject {
        return JSONObject(rankingJson.toString()).apply {
            remove("rank")
            remove("lastAudiblyAlertedMillis")
        }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // === 推斷值（沿用 ApiVersionHelper 既有邏輯） ===

    private fun inferIsAudible(
        ranking: Ranking,
        sbn: StatusBarNotification?,
        captureTime: Long
    ): Boolean {
        if (sbn == null) return false
        val importance = if (ApiVersionHelper.supportsDirectReply()) ranking.importance else -1
        val lastAudibly = if (ApiVersionHelper.supportsLastAudiblyAlerted())
            ranking.lastAudiblyAlertedMillis else -1L
        return ApiVersionHelper.isLikelyAudible(
            lastAudibly,
            captureTime,
            importance,
            sbn.notification.flags,
            sbn.notification.sound?.toString(),
            isUpdate = false
        )
    }

    private fun inferLikelyHeadsup(ranking: Ranking, sbn: StatusBarNotification?): Boolean {
        if (sbn == null) return false
        val importance = if (ApiVersionHelper.supportsDirectReply()) ranking.importance else null
        return ApiVersionHelper.isLikelyHeadsUp(sbn.notification, importance)
    }

    /** [process] / [processRankingMap] 的回傳結構 */
    data class Result(
        val snapshotId: Long,
        val observationId: Long,
        val isAudible: Boolean,
        val likelyHeadsup: Boolean,
        /** RANKING_UPDATE 流程下 hash 未變動而跳過寫 observation 時為 true */
        val skipped: Boolean
    )
}
