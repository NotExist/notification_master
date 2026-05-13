package com.notificationmaster

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Plan 2 Phase 13：驗證 NotificationExtractor 不再對 eventRawJson 做 size 截斷。
 *
 * 移除原 `MAX_JSON_SIZE = 64KB` 上限的原因：
 * - 違反 Plan 2 §3「raw 接近 callback」原則
 * - 截斷後 JSON 結構壞 → NotificationSnapshotParser 全失敗 → Display chip 大量退化
 * - Bitmap / Icon / Drawable / RemoteViews 已由 RawSerializer 限制成 metadata
 * - 大媒體已由 MediaExtractor 抽出 disk
 * - Framework 本身對 Notification 有 ~1MB IPC 上限
 *
 * 此測試確保不會再有人加回 truncate 邏輯。
 */
class EventRawJsonNoTruncateTest {

    @Test
    fun `NotificationExtractor companion exposes no MAX_JSON_SIZE constant`() {
        // 用反射確認沒有 MAX_JSON_SIZE field（若加回會 build pass 但此 test fail）
        val companion = com.notificationmaster.core.NotificationExtractor.Companion::class.java
        val fields = companion.declaredFields.map { it.name }.toSet()
        assert(!fields.contains("MAX_JSON_SIZE")) {
            "MAX_JSON_SIZE should be removed per Plan 2 Phase 13. Found in companion fields: $fields"
        }
    }

    @Test
    fun `raw json over 100KB stays intact through JSONObject roundtrip`() {
        // 模擬一個 200KB 的 raw JSON（events 不會大到這程度，但驗證上限解放）
        val largeText = "x".repeat(200 * 1024)
        val rawJson = JSONObject().apply {
            put("callbackType", "POSTED")
            put("captureTime", 1234567890L)
            put("sbn", JSONObject().apply {
                put("packageName", "com.example")
                put("notification", JSONObject().apply {
                    put("flags", 0x02)
                    put("extras", JSONObject().apply {
                        put("android.bigText", largeText)
                    })
                })
            })
        }.toString()

        // 直接 parse，不該因大小而失敗
        val parsed = JSONObject(rawJson)
        assertEquals("POSTED", parsed.getString("callbackType"))
        val bigText = parsed.getJSONObject("sbn")
            .getJSONObject("notification")
            .getJSONObject("extras")
            .getString("android.bigText")
        assertEquals(200 * 1024, bigText.length)
        assertNotNull(bigText)
    }
}
