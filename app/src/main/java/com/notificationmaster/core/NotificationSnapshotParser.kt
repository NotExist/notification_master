package com.notificationmaster.core

import android.util.Log
import com.notificationmaster.data.model.NotificationSnapshot
import org.json.JSONObject

/**
 * NotificationEventEntity.eventRawJson → NotificationSnapshot 解析（Plan 2 Phase 3）
 *
 * 解析失敗時回 null（呼叫端可顯示「raw 解析失敗」placeholder）。
 */
object NotificationSnapshotParser {

    private const val TAG = "NotificationSnapshotParser"

    /**
     * 解析 eventRawJson 為 NotificationSnapshot。
     * @param rawJson Service 寫入的 raw JSON 字串
     * @return NotificationSnapshot；JSON 格式錯誤時回 null
     */
    fun parse(rawJson: String): NotificationSnapshot? {
        return try {
            NotificationSnapshot(JSONObject(rawJson))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse eventRawJson", e)
            null
        }
    }
}
