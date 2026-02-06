package com.notificationmaster.data.db

import androidx.room.TypeConverter
import com.notificationmaster.data.db.entity.EventType
import com.notificationmaster.data.db.entity.MediaType

/**
 * Room 類型轉換器
 */
class Converters {

    // === EventType ===

    @TypeConverter
    fun fromEventType(value: EventType): String = value.name

    @TypeConverter
    fun toEventType(value: String): EventType = EventType.valueOf(value)

    // === MediaType ===

    @TypeConverter
    fun fromMediaType(value: MediaType): String = value.name

    @TypeConverter
    fun toMediaType(value: String): MediaType = MediaType.valueOf(value)
}
