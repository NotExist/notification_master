package com.notificationmaster

import com.notificationmaster.core.filter.FilterRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FilterRule JSON 序列化/反序列化測試
 */
class FilterRuleTest {

    @Test
    fun `toJson produces expected structure`() {
        val rule = FilterRule(
            id = "test-id",
            packageName = "com.example.app",
            channelId = "channel_1",
            eventTypes = setOf("POSTED", "UPDATED"),
            createdAt = 1000L
        )

        val json = rule.toJson()

        assertEquals("test-id", json.getString("id"))
        assertEquals("com.example.app", json.getString("packageName"))
        assertEquals("channel_1", json.getString("channelId"))
        assertEquals(1000L, json.getLong("createdAt"))

        val types = json.getJSONArray("eventTypes")
        val typeSet = (0 until types.length()).map { types.getString(it) }.toSet()
        assertEquals(setOf("POSTED", "UPDATED"), typeSet)
    }

    @Test
    fun `toJson with null channelId produces JSONObject NULL`() {
        val rule = FilterRule(
            id = "test-id",
            packageName = "com.example.app",
            channelId = null,
            eventTypes = setOf("POSTED")
        )

        val json = rule.toJson()
        assertTrue(json.isNull("channelId"))
    }

    @Test
    fun `fromJson parses correctly`() {
        val json = JSONObject().apply {
            put("id", "rule-1")
            put("packageName", "com.example.app")
            put("channelId", "ch_1")
            put("eventTypes", JSONArray(listOf("POSTED", "REMOVED")))
            put("createdAt", 2000L)
        }

        val rule = FilterRule.fromJson(json)

        assertEquals("rule-1", rule.id)
        assertEquals("com.example.app", rule.packageName)
        assertEquals("ch_1", rule.channelId)
        assertEquals(setOf("POSTED", "REMOVED"), rule.eventTypes)
        assertEquals(2000L, rule.createdAt)
    }

    @Test
    fun `fromJson with null channelId`() {
        val json = JSONObject().apply {
            put("id", "rule-2")
            put("packageName", "com.example.app")
            put("channelId", JSONObject.NULL)
            put("eventTypes", JSONArray(listOf("POSTED")))
        }

        val rule = FilterRule.fromJson(json)
        assertNull(rule.channelId)
    }

    @Test
    fun `toJson then fromJson round-trip preserves data`() {
        val original = FilterRule(
            id = "round-trip-id",
            packageName = "com.example.test",
            channelId = "my_channel",
            eventTypes = setOf("INITIAL", "POSTED", "UPDATED"),
            createdAt = 12345L
        )

        val restored = FilterRule.fromJson(original.toJson())

        assertEquals(original.id, restored.id)
        assertEquals(original.packageName, restored.packageName)
        assertEquals(original.channelId, restored.channelId)
        assertEquals(original.eventTypes, restored.eventTypes)
        assertEquals(original.createdAt, restored.createdAt)
    }

    @Test
    fun `round-trip with null channelId`() {
        val original = FilterRule(
            id = "null-ch",
            packageName = "com.example.app",
            channelId = null,
            eventTypes = setOf("REMOVED"),
            createdAt = 999L
        )

        val restored = FilterRule.fromJson(original.toJson())
        assertNull(restored.channelId)
        assertEquals(original.eventTypes, restored.eventTypes)
    }

    @Test
    fun `fromJson handles legacy excludedEventTypes key`() {
        val json = JSONObject().apply {
            put("id", "legacy-1")
            put("packageName", "com.example.app")
            put("channelId", JSONObject.NULL)
            put("excludedEventTypes", JSONArray(listOf("POSTED", "UPDATED")))
        }

        val rule = FilterRule.fromJson(json)
        assertEquals(setOf("POSTED", "UPDATED"), rule.eventTypes)
    }

    @Test
    fun `fromJson handles legacy IGNORE_ALL mode with empty eventTypes`() {
        val json = JSONObject().apply {
            put("id", "legacy-2")
            put("packageName", "com.example.app")
            put("channelId", JSONObject.NULL)
            put("mode", "IGNORE_ALL")
            // 沒有 eventTypes → 全選
        }

        val rule = FilterRule.fromJson(json)

        // IGNORE_ALL 應該轉為全部 EventType
        assertTrue(rule.eventTypes.containsAll(
            listOf("INITIAL", "POSTED", "UPDATED", "REMOVED", "RANKING")
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fromJson with blank packageName throws`() {
        val json = JSONObject().apply {
            put("id", "bad-rule")
            put("packageName", "")
            put("channelId", JSONObject.NULL)
            put("eventTypes", JSONArray(listOf("POSTED")))
        }

        FilterRule.fromJson(json)
    }
}
