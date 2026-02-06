package com.notificationmaster.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 裝置狀態快照 Entity
 * 獨立表格，與 NotificationEntity 以 FK 關聯
 * 記錄通知到達時的裝置執行狀態（非 Notification API 資料）
 */
@Entity(
    tableName = "device_states",
    foreignKeys = [
        ForeignKey(
            entity = NotificationEntity::class,
            parentColumns = ["id"],
            childColumns = ["notification_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["notification_id"], unique = true),
        Index(value = ["capture_time"])
    ]
)
data class DeviceStateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 關聯的通知記錄 ID */
    @ColumnInfo(name = "notification_id")
    val notificationId: Long,

    /** 擷取時間 */
    @ColumnInfo(name = "capture_time")
    val captureTime: Long,

    /** 響鈴模式：AudioManager.RINGER_MODE_* (0=靜音/1=震動/2=正常) */
    @ColumnInfo(name = "ringer_mode")
    val ringerMode: Int,

    /** 螢幕是否亮起：PowerManager.isInteractive() (API 20+) */
    @ColumnInfo(name = "is_screen_on")
    val isScreenOn: Boolean,

    /** 電池電量 0-100，-1 表示無法取得 */
    @ColumnInfo(name = "battery_level")
    val batteryLevel: Int,

    /**
     * 電池狀態：BatteryManager.BATTERY_STATUS_* (1-5)
     * 1=UNKNOWN, 2=CHARGING, 3=DISCHARGING, 4=NOT_CHARGING, 5=FULL
     * -1 表示無法取得
     */
    @ColumnInfo(name = "battery_status")
    val batteryStatus: Int,

    /** 是否有網路連線，null 表示無權限 */
    @ColumnInfo(name = "is_connected")
    val isConnected: Boolean?,

    /**
     * 網路連線類型：ConnectivityManager.TYPE_*
     * -1 表示無連線或無法取得
     */
    @ColumnInfo(name = "connection_type")
    val connectionType: Int
)
