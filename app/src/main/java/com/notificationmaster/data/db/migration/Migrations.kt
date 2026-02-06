package com.notificationmaster.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 資料庫遷移定義
 */
object Migrations {

    /**
     * Version 1 → 2
     * - 新增 device_states 表（裝置狀態快照）
     * - notifications 表新增 persistence_type 欄位
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 1. 建立 device_states 表
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `device_states` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `notification_id` INTEGER NOT NULL,
                    `capture_time` INTEGER NOT NULL,
                    `ringer_mode` INTEGER NOT NULL,
                    `is_screen_on` INTEGER NOT NULL,
                    `battery_level` INTEGER NOT NULL,
                    `battery_status` INTEGER NOT NULL,
                    `is_connected` INTEGER,
                    `connection_type` INTEGER NOT NULL,
                    FOREIGN KEY(`notification_id`) REFERENCES `notifications`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
            """)

            // 2. 建立索引
            db.execSQL("""
                CREATE UNIQUE INDEX IF NOT EXISTS `index_device_states_notification_id`
                ON `device_states` (`notification_id`)
            """)
            db.execSQL("""
                CREATE INDEX IF NOT EXISTS `index_device_states_capture_time`
                ON `device_states` (`capture_time`)
            """)

            // 3. notifications 表新增 persistence_type 欄位
            db.execSQL("""
                ALTER TABLE `notifications`
                ADD COLUMN `persistence_type` TEXT NOT NULL DEFAULT 'TRANSIENT'
            """)
        }
    }
}
