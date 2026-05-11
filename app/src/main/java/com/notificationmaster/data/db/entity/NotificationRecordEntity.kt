package com.notificationmaster.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 通知記錄聚合錨點 Entity（Plan 2 資料模型重構）
 *
 * 自然主鍵 = sbn.key，作為同一通知所有事件 / ranking observation 的父表。
 * 翻轉舊架構（NotificationEntity 為主、NotificationEvent 為附屬）的語意倒置：
 * 事件才是時間線上的主軸，Record 僅做聚合索引。
 *
 * 新事件寫入時若 record 不存在則新建（firstSeen / lastSeen / eventCount 由 service upsert）。
 */
@Entity(
    tableName = "notification_records",
    indices = [
        Index(value = ["package_name"]),
        Index(value = ["channel_id"]),
        Index(value = ["last_seen"])
    ]
)
data class NotificationRecordEntity(
    /** 自然主鍵：sbn.key */
    @PrimaryKey
    @ColumnInfo(name = "notification_key")
    val notificationKey: String,

    @ColumnInfo(name = "package_name")
    val packageName: String,

    @ColumnInfo(name = "channel_id")
    val channelId: String?,

    /** sbn.id */
    @ColumnInfo(name = "notification_id")
    val notificationId: Int,

    @ColumnInfo(name = "tag")
    val tag: String?,

    /** 首個事件擷取時間 */
    @ColumnInfo(name = "first_seen")
    val firstSeen: Long,

    /** 最後事件擷取時間（隨新事件 upsert 更新） */
    @ColumnInfo(name = "last_seen")
    val lastSeen: Long,

    /** 該 record 累計事件數（不含 ranking observation） */
    @ColumnInfo(name = "event_count")
    val eventCount: Int
)
