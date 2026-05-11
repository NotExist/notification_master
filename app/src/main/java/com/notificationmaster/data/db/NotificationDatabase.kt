package com.notificationmaster.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import java.io.File
import com.notificationmaster.data.db.dao.ActionDao
import com.notificationmaster.data.db.dao.AppSourceDao
import com.notificationmaster.data.db.dao.ChannelDao
import com.notificationmaster.data.db.dao.DeviceStateDao
import com.notificationmaster.data.db.dao.MediaAttachmentDao
import com.notificationmaster.data.db.dao.NotificationDao
import com.notificationmaster.data.db.dao.NotificationEventDao
import com.notificationmaster.data.db.entity.ActionEntity
import com.notificationmaster.data.db.entity.AppSourceEntity
import com.notificationmaster.data.db.entity.ChannelEntity
import com.notificationmaster.data.db.entity.DeviceStateEntity
import com.notificationmaster.data.db.entity.MediaAttachmentEntity
import com.notificationmaster.data.db.entity.NotificationEntity
import com.notificationmaster.data.db.entity.NotificationEventEntity
import com.notificationmaster.data.db.entity.NotificationRecordEntity
import com.notificationmaster.data.db.entity.RankingObservationEntity
import com.notificationmaster.data.db.entity.RankingSnapshotEntity

/**
 * Room 資料庫
 *
 * Plan 2 版本：v1 → v2 schema 翻轉（events 為主、record 聚合錨點、ranking 獨立軌道）。
 * NotificationEntity / NotificationDao 仍暫存於 entities 列表，待 Phase 5 service rewrite 完成後拆除。
 *
 * Migration 策略：fallbackToDestructiveMigration（架構級重構，無法線性 migrate；
 * 使用者升級前可透過「資料管理 → 匯出 JSON」備份）。
 */
@Database(
    entities = [
        NotificationEntity::class,
        NotificationEventEntity::class,
        NotificationRecordEntity::class,
        RankingSnapshotEntity::class,
        RankingObservationEntity::class,
        MediaAttachmentEntity::class,
        ActionEntity::class,
        AppSourceEntity::class,
        ChannelEntity::class,
        DeviceStateEntity::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class NotificationDatabase : RoomDatabase() {

    // === DAO ===

    abstract fun notificationDao(): NotificationDao
    abstract fun notificationEventDao(): NotificationEventDao
    abstract fun mediaAttachmentDao(): MediaAttachmentDao
    abstract fun actionDao(): ActionDao
    abstract fun appSourceDao(): AppSourceDao
    abstract fun channelDao(): ChannelDao
    abstract fun deviceStateDao(): DeviceStateDao

    companion object {
        const val DATABASE_NAME = "notification_master.db"

        @Volatile
        private var instance: NotificationDatabase? = null

        fun getInstance(context: Context): NotificationDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also { instance = it }
            }
        }

        private fun buildDatabase(context: Context): NotificationDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                NotificationDatabase::class.java,
                DATABASE_NAME
            )
            // Plan 2 schema 翻轉，無法線性 migrate；舊資料丟失，使用者應先匯出備份
            .fallbackToDestructiveMigration()
            .build()
        }

        /**
         * 取得資料庫檔案大小（db + WAL + SHM）
         * @return Triple(db, wal, shm) in bytes
         */
        fun getDatabaseFileSize(context: Context): Triple<Long, Long, Long> {
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            val walFile = File(dbFile.path + "-wal")
            val shmFile = File(dbFile.path + "-shm")
            return Triple(
                if (dbFile.exists()) dbFile.length() else 0L,
                if (walFile.exists()) walFile.length() else 0L,
                if (shmFile.exists()) shmFile.length() else 0L
            )
        }
    }
}
