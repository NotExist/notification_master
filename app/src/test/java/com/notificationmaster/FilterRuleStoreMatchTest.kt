package com.notificationmaster

import com.notificationmaster.core.filter.FilterCategory
import com.notificationmaster.core.filter.FilterRule
import com.notificationmaster.core.filter.FilterRuleStore
import com.notificationmaster.data.db.entity.EventType
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FilterRuleStore 匹配邏輯測試
 *
 * 使用 reflection 直接操作 rulesMap 以避免依賴 Android Context。
 */
class FilterRuleStoreMatchTest {

    private lateinit var rulesMapField: java.lang.reflect.Field

    @Suppress("UNCHECKED_CAST")
    private fun setRules(category: FilterCategory, rules: List<FilterRule>) {
        val map = rulesMapField.get(FilterRuleStore) as MutableMap<FilterCategory, List<FilterRule>>
        map[category] = rules
    }

    @Suppress("UNCHECKED_CAST")
    private fun clearAllRules() {
        val map = rulesMapField.get(FilterRuleStore) as MutableMap<FilterCategory, List<FilterRule>>
        map.clear()
    }

    @Before
    fun setUp() {
        rulesMapField = FilterRuleStore::class.java.getDeclaredField("rulesMap")
        rulesMapField.isAccessible = true
        clearAllRules()
    }

    @After
    fun tearDown() {
        clearAllRules()
    }

    // === matches() ===

    @Test
    fun `matches returns false when no rules exist`() {
        assertFalse(
            FilterRuleStore.matches(
                FilterCategory.NOTIFICATION,
                "com.example.app",
                "channel_1",
                EventType.POSTED
            )
        )
    }

    @Test
    fun `matches returns true for package-level rule with matching eventType`() {
        setRules(
            FilterCategory.NOTIFICATION,
            listOf(
                FilterRule(
                    packageName = "com.example.app",
                    channelId = null,
                    eventTypes = setOf("POSTED", "UPDATED")
                )
            )
        )

        assertTrue(
            FilterRuleStore.matches(
                FilterCategory.NOTIFICATION,
                "com.example.app",
                "any_channel",
                EventType.POSTED
            )
        )
    }

    @Test
    fun `matches returns false for package-level rule with non-matching eventType`() {
        setRules(
            FilterCategory.NOTIFICATION,
            listOf(
                FilterRule(
                    packageName = "com.example.app",
                    channelId = null,
                    eventTypes = setOf("POSTED")
                )
            )
        )

        assertFalse(
            FilterRuleStore.matches(
                FilterCategory.NOTIFICATION,
                "com.example.app",
                "any_channel",
                EventType.REMOVED
            )
        )
    }

    @Test
    fun `matches returns true for channel-level exact match`() {
        setRules(
            FilterCategory.NOTIFICATION,
            listOf(
                FilterRule(
                    packageName = "com.example.app",
                    channelId = "important",
                    eventTypes = setOf("POSTED")
                )
            )
        )

        assertTrue(
            FilterRuleStore.matches(
                FilterCategory.NOTIFICATION,
                "com.example.app",
                "important",
                EventType.POSTED
            )
        )
    }

    @Test
    fun `matches channel-level rule takes priority over package-level`() {
        setRules(
            FilterCategory.NOTIFICATION,
            listOf(
                FilterRule(
                    packageName = "com.example.app",
                    channelId = null,
                    eventTypes = setOf("POSTED", "UPDATED", "REMOVED")
                ),
                FilterRule(
                    packageName = "com.example.app",
                    channelId = "special",
                    eventTypes = setOf("REMOVED")  // channel 級只過濾 REMOVED
                )
            )
        )

        // channel "special" + POSTED → channel 級規則不包含 POSTED → false
        assertFalse(
            FilterRuleStore.matches(
                FilterCategory.NOTIFICATION,
                "com.example.app",
                "special",
                EventType.POSTED
            )
        )

        // channel "special" + REMOVED → channel 級規則包含 → true
        assertTrue(
            FilterRuleStore.matches(
                FilterCategory.NOTIFICATION,
                "com.example.app",
                "special",
                EventType.REMOVED
            )
        )

        // channel "other" → 無 channel 精確匹配 → 降級到 package 級 → POSTED 在範圍內 → true
        assertTrue(
            FilterRuleStore.matches(
                FilterCategory.NOTIFICATION,
                "com.example.app",
                "other",
                EventType.POSTED
            )
        )
    }

    @Test
    fun `matches returns false for different package`() {
        setRules(
            FilterCategory.NOTIFICATION,
            listOf(
                FilterRule(
                    packageName = "com.example.app",
                    channelId = null,
                    eventTypes = setOf("POSTED")
                )
            )
        )

        assertFalse(
            FilterRuleStore.matches(
                FilterCategory.NOTIFICATION,
                "com.different.app",
                null,
                EventType.POSTED
            )
        )
    }

    @Test
    fun `matches with different categories are independent`() {
        setRules(
            FilterCategory.NOTIFICATION,
            listOf(
                FilterRule(
                    packageName = "com.example.app",
                    channelId = null,
                    eventTypes = setOf("POSTED")
                )
            )
        )
        // CALENDAR_EXPORT 沒有規則

        assertTrue(
            FilterRuleStore.matches(
                FilterCategory.NOTIFICATION,
                "com.example.app",
                null,
                EventType.POSTED
            )
        )
        assertFalse(
            FilterRuleStore.matches(
                FilterCategory.CALENDAR_EXPORT,
                "com.example.app",
                null,
                EventType.POSTED
            )
        )
    }

    // === matchesSource() ===

    @Test
    fun `matchesSource returns true for matching package`() {
        setRules(
            FilterCategory.CALENDAR_EXPORT,
            listOf(
                FilterRule(
                    packageName = "com.example.calendar",
                    channelId = null,
                    eventTypes = setOf("POSTED")
                )
            )
        )

        assertTrue(
            FilterRuleStore.matchesSource(
                FilterCategory.CALENDAR_EXPORT,
                "com.example.calendar",
                "any_channel"
            )
        )
    }

    @Test
    fun `matchesSource returns true for matching channel`() {
        setRules(
            FilterCategory.CALENDAR_EXPORT,
            listOf(
                FilterRule(
                    packageName = "com.example.calendar",
                    channelId = "reminders",
                    eventTypes = setOf("POSTED")
                )
            )
        )

        assertTrue(
            FilterRuleStore.matchesSource(
                FilterCategory.CALENDAR_EXPORT,
                "com.example.calendar",
                "reminders"
            )
        )
    }

    @Test
    fun `matchesSource returns false for non-matching package`() {
        setRules(
            FilterCategory.CALENDAR_EXPORT,
            listOf(
                FilterRule(
                    packageName = "com.example.calendar",
                    channelId = null,
                    eventTypes = setOf("POSTED")
                )
            )
        )

        assertFalse(
            FilterRuleStore.matchesSource(
                FilterCategory.CALENDAR_EXPORT,
                "com.different.app",
                null
            )
        )
    }

    // === hasRules() / isAllEmpty() ===

    @Test
    fun `hasRules returns false when no rules`() {
        assertFalse(FilterRuleStore.hasRules(FilterCategory.NOTIFICATION))
    }

    @Test
    fun `hasRules returns true when rules exist`() {
        setRules(
            FilterCategory.NOTIFICATION,
            listOf(
                FilterRule(
                    packageName = "com.example.app",
                    channelId = null,
                    eventTypes = setOf("POSTED")
                )
            )
        )

        assertTrue(FilterRuleStore.hasRules(FilterCategory.NOTIFICATION))
    }

    @Test
    fun `isAllEmpty returns true when all categories empty`() {
        assertTrue(FilterRuleStore.isAllEmpty())
    }

    @Test
    fun `isAllEmpty returns false when any category has rules`() {
        setRules(
            FilterCategory.CALENDAR_EXPORT,
            listOf(
                FilterRule(
                    packageName = "com.example.app",
                    channelId = null,
                    eventTypes = setOf("POSTED")
                )
            )
        )

        assertFalse(FilterRuleStore.isAllEmpty())
    }
}
