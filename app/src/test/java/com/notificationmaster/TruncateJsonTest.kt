package com.notificationmaster

import com.notificationmaster.core.NotificationExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NotificationExtractor.truncateJson 單元測試
 */
class TruncateJsonTest {

    @Test
    fun `short string is not truncated`() {
        val input = """{"key":"value"}"""
        val result = NotificationExtractor.truncateJson(input)
        assertEquals(input, result)
    }

    @Test
    fun `string exactly at limit is not truncated`() {
        val input = "x".repeat(NotificationExtractor.MAX_JSON_SIZE)
        val result = NotificationExtractor.truncateJson(input)
        assertEquals(input, result)
    }

    @Test
    fun `string over limit is truncated with marker`() {
        val overSize = NotificationExtractor.MAX_JSON_SIZE + 100
        val input = "x".repeat(overSize)
        val result = NotificationExtractor.truncateJson(input)

        assertTrue(result.length < input.length)
        assertTrue(result.contains("[truncated, original $overSize chars]"))
        assertTrue(result.startsWith("x".repeat(NotificationExtractor.MAX_JSON_SIZE)))
    }

    @Test
    fun `empty string is not truncated`() {
        val result = NotificationExtractor.truncateJson("")
        assertEquals("", result)
    }
}
