package com.notificationmaster.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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
import com.notificationmaster.data.db.migration.Migrations

/**
 * Room 資料庫
 */
@Database(
    entities = [
        NotificationEntity::class,
        NotificationEventEntity::class,
        MediaAttachmentEntity::class,
        ActionEntity::class,
        AppSourceEntity::class,
        ChannelEntity::class,
        DeviceStateEntity::class
    ],
    version = 3,
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
            .addMigrations(Migrations.MIGRATION_1_2)
            .fallbackToDestructiveMigration()
            .build()
        }
    }
}
