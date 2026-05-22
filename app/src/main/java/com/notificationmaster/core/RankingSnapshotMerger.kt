package com.notificationmaster.core

import com.notificationmaster.data.db.entity.RankingObservationEntity
import com.notificationmaster.data.db.entity.RankingSnapshotEntity
import org.json.JSONObject

/**
 * Ranking observation + snapshot → 完整 ranking entry 還原（Plan 2 Phase 3）
 *
 * 寫入時 RankingSnapshot.contentHash 排除 rank（噪音欄位）達到 dedup。
 * Phase 31t：rank 完全移除（observation 不存、snapshot 也不存、不顯示 — rank 是
 * 排序，不能準確記錄為時間序列 observation，顯示反而誤導）。
 * Phase 31s：lastAudiblyAlertedMillis 加入 snapshot hash，snapshot.rankingJson 內含；
 * observation 仍存 lastAudibly（snapshot reuse 時 snapshot 內可能是其他 observation
 * 寫入時的舊值，observation 值才準確），merge 用 observation 覆蓋同欄位。
 *
 * 用於 Detail 頁時間軸 ranking observation 點擊展開、debug dump 還原等。
 */
object RankingSnapshotMerger {

    /**
     * 合併 snapshot 的正規化 ranking JSON 與 observation 的噪音欄位，回傳完整 entry。
     *
     * @param snapshot 該 observation 引用的 RankingSnapshot
     * @param observation 觀察點本身（含 lastAudiblyAlertedMillis；phase 31t 起不再含 rank）
     * @return 完整 ranking JSON（含所有欄位）；snapshot 解析失敗時回 null
     */
    fun merge(
        snapshot: RankingSnapshotEntity,
        observation: RankingObservationEntity
    ): JSONObject? {
        return try {
            JSONObject(snapshot.rankingJson).apply {
                if (observation.lastAudiblyAlertedMillis != null) {
                    put("lastAudiblyAlertedMillis", observation.lastAudiblyAlertedMillis)
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
