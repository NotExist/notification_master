package com.notificationmaster.core

import com.notificationmaster.data.db.entity.RankingObservationEntity
import com.notificationmaster.data.db.entity.RankingSnapshotEntity
import org.json.JSONObject

/**
 * Ranking observation + snapshot → 完整 ranking entry 還原（Plan 2 Phase 3）
 *
 * 寫入時 RankingSnapshot.contentHash 排除 rank / lastAudiblyAlertedMillis 噪音欄位
 * 達到 dedup；讀回時把 observation 的噪音欄位合併回去 = 原始 ranking entry。
 *
 * 用於 Detail 頁時間軸 ranking observation 點擊展開、debug dump 還原等。
 */
object RankingSnapshotMerger {

    /**
     * 合併 snapshot 的正規化 ranking JSON 與 observation 的噪音欄位，回傳完整 entry。
     *
     * @param snapshot 該 observation 引用的 RankingSnapshot
     * @param observation 觀察點本身（含 rank / lastAudiblyAlertedMillis）
     * @return 完整 ranking JSON（含所有欄位）；snapshot 解析失敗時回 null
     */
    fun merge(
        snapshot: RankingSnapshotEntity,
        observation: RankingObservationEntity
    ): JSONObject? {
        return try {
            JSONObject(snapshot.rankingJson).apply {
                if (observation.rank != null) put("rank", observation.rank)
                if (observation.lastAudiblyAlertedMillis != null) {
                    put("lastAudiblyAlertedMillis", observation.lastAudiblyAlertedMillis)
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
