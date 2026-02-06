package com.notificationmaster.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 來源 App Entity
 * 記錄發送通知的 App 資訊
 */
@Entity(
    tableName = "app_sources",
    indices = [
        Index(value = ["package_name"], unique = true)
    ]
)
data class AppSourceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** App 包名 */
    @ColumnInfo(name = "package_name")
    val packageName: String,

    /** App 名稱 */
    @ColumnInfo(name = "app_name")
    val appName: String?,

    /** App 版本名稱 */
    @ColumnInfo(name = "version_name")
    val versionName: String?,

    /** App 版本號 */
    @ColumnInfo(name = "version_code")
    val versionCode: Long?,

    /** App 圖示路徑 */
    @ColumnInfo(name = "icon_path")
    val iconPath: String?,

    /** 首次出現時間 */
    @ColumnInfo(name = "first_seen")
    val firstSeen: Long,

    /** 最後更新時間 */
    @ColumnInfo(name = "last_updated")
    val lastUpdated: Long,

    /** 通知總數 */
    @ColumnInfo(name = "notification_count")
    val notificationCount: Int,

    /** 是否為系統 App */
    @ColumnInfo(name = "is_system_app")
    val isSystemApp: Boolean,

    /** 是否已停用 */
    @ColumnInfo(name = "is_disabled")
    val isDisabled: Boolean,

    /** 是否已解除安裝 */
    @ColumnInfo(name = "is_uninstalled")
    val isUninstalled: Boolean
)
