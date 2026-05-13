package com.notificationmaster

import com.notificationmaster.core.NotificationExtractor
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
 */
class EventRawJsonNoTruncateTest {

    /**
     * 直接行為驗證：NotificationExtractor 不該再有任何 truncate 相關函式。
     * 若有人未來把截斷邏輯加回（不論換什麼名字），用反射掃 `truncate` 關鍵字能捕捉。
     */
    @Test
    fun `NotificationExtractor exposes no truncate function`() {
        val clazz = NotificationExtractor::class.java
        val instanceFns = clazz.declaredMethods.map { it.name }
        val companionFns = clazz.declaredClasses
            .firstOrNull { it.simpleName == "Companion" }
            ?.declaredMethods?.map { it.name }.orEmpty()

        val truncateLike = (instanceFns + companionFns)
            .filter { it.contains("truncate", ignoreCase = true) }

        assert(truncateLike.isEmpty()) {
            "NotificationExtractor should not have any truncate function per Plan 2 Phase 13. " +
                "Found: $truncateLike (instance=$instanceFns, companion=$companionFns)"
        }
    }

    /**
     * 行為驗證：超過原 64KB 上限的 JSON 內容能完整通過 JSONObject roundtrip
     * 不被任何截斷邏輯損壞。
     */
    @Test
    fun `raw json over 100KB stays intact through JSONObject roundtrip`() {
        val largeText = "x".repeat(200 * 1024)  // 200KB > 舊 64KB 上限 3 倍
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
