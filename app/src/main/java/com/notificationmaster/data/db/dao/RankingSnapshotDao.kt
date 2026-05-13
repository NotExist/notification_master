package com.notificationmaster.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.notificationmaster.data.db.entity.RankingSnapshotEntity

/**
 * Ranking snapshot DAO（Plan 2）
 *
 * 由 service 在寫 observation 前先呼叫 [getOrInsertByHash]，達到內容 dedup。
 */
@Dao
interface RankingSnapshotDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(snapshot: RankingSnapshotEntity): Long

    @Query("SELECT * FROM ranking_snapshots WHERE content_hash = :hash LIMIT 1")
    suspend fun getByHash(hash: String): RankingSnapshotEntity?

    @Query("SELECT * FROM ranking_snapshots WHERE id = :id")
    suspend fun getById(id: Long): RankingSnapshotEntity?

    /** Phase 14 Q2-B：batch lookup */
    @Query("SELECT * FROM ranking_snapshots WHERE id IN (:ids)")
    suspend fun getByIdsSync(ids: List<Long>): List<RankingSnapshotEntity>

    @Query("SELECT COUNT(*) FROM ranking_snapshots")
    suspend fun getTotalCount(): Int

    @Query("DELETE FROM ranking_snapshots")
    suspend fun deleteAll()
}
