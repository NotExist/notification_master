package com.notificationmaster.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.notificationmaster.data.db.entity.ObservationSource
import com.notificationmaster.data.db.entity.RankingObservationEntity
import kotlinx.coroutines.flow.Flow

/**
 * Ranking observation DAO（Plan 2）
 *
 * 觀察點記錄某時刻 callback 對某 notificationKey 的 ranking 狀態，與 RankingSnapshot
 * 透過 ranking_snapshot_id FK 關聯，dedup 邏輯由 service 負責（先 query snapshot
 * by hash，無則 insert，再用 id 寫 observation）。
 */
@Dao
interface RankingObservationDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(observation: RankingObservationEntity): Long

    /** W23r：串流匯入批次插入（單一 transaction，取代逐筆 insert 的 N 次 fsync） */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(observations: List<RankingObservationEntity>): List<Long>

    @Query("SELECT * FROM ranking_observations WHERE notification_key = :key ORDER BY observed_at ASC")
    fun getByKey(key: String): Flow<List<RankingObservationEntity>>

    @Query("SELECT * FROM ranking_observations WHERE notification_key = :key ORDER BY observed_at ASC")
    suspend fun getByKeySync(key: String): List<RankingObservationEntity>

    /** W23d：封存匯出 batch IN（呼叫端自行 chunk 控制 SQLite 變數上限） */
    @Query("SELECT * FROM ranking_observations WHERE notification_key IN (:keys) ORDER BY observed_at ASC")
    suspend fun getByKeysSync(keys: List<String>): List<RankingObservationEntity>

    @Query("SELECT * FROM ranking_observations WHERE notification_key = :key ORDER BY observed_at DESC LIMIT 1")
    suspend fun getLatestByKey(key: String): RankingObservationEntity?

    /**
     * Phase 14 Q2-B：batch query 多個 key 各自最新 observation。
     *
     * W23i：correlated subquery（O(K²)）改寫為 GROUP BY 形式（O(K)），
     * 同 NotificationEventDao.getLatestEventByKeysSync — observations 隨每次
     * RANKING update 累積，熱點 key 數千列時舊寫法達秒級。
     */
    @Query("""
        SELECT * FROM ranking_observations WHERE id IN (
            SELECT id FROM (
                SELECT id, MAX(observed_at) FROM ranking_observations
                WHERE notification_key IN (:keys)
                GROUP BY notification_key
            )
        )
    """)
    suspend fun getLatestByKeysSync(keys: List<String>): List<RankingObservationEntity>

    @Query("""
        SELECT * FROM ranking_observations
        WHERE notification_key = :key AND source = :source
        ORDER BY observed_at DESC LIMIT 1
    """)
    suspend fun getLatestByKeyAndSource(key: String, source: ObservationSource): RankingObservationEntity?

    @Query("SELECT COUNT(*) FROM ranking_observations")
    suspend fun getTotalCount(): Int

    @Query("DELETE FROM ranking_observations WHERE observed_at < :beforeTime")
    suspend fun deleteBeforeTime(beforeTime: Long): Int

    @Query("DELETE FROM ranking_observations")
    suspend fun deleteAll()
}
