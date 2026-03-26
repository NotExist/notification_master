package com.notificationmaster

import com.notificationmaster.core.filter.ActionType
import com.notificationmaster.core.filter.KeywordField
import com.notificationmaster.core.filter.MatchContext
import com.notificationmaster.core.filter.Matcher
import com.notificationmaster.core.filter.Rule
import com.notificationmaster.core.filter.RuleAction
import com.notificationmaster.data.db.entity.EventType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rule / Matcher / RuleAction JSON 序列化與匹配邏輯測試
 */
class RuleTest {

    // ========== Matcher 匹配 ==========

    @Test
    fun `Package matcher matches exact packageName`() {
        val m = Matcher.Package("com.example.app")
        val ctx = MatchContext(packageName = "com.example.app", channelId = null)
        assertTrue(m.matches(ctx))
    }

    @Test
    fun `Package matcher rejects different packageName`() {
        val m = Matcher.Package("com.example.app")
        val ctx = MatchContext(packageName = "com.other.app", channelId = null)
        assertFalse(m.matches(ctx))
    }

    @Test
    fun `Channel matcher matches exact channelId`() {
        val m = Matcher.Channel("messages")
        val ctx = MatchContext(packageName = "com.example.app", channelId = "messages")
        assertTrue(m.matches(ctx))
    }

    @Test
    fun `Channel matcher rejects null channelId`() {
        val m = Matcher.Channel("messages")
        val ctx = MatchContext(packageName = "com.example.app", channelId = null)
        assertFalse(m.matches(ctx))
    }

    @Test
    fun `EventTypes matcher passes when eventType in set`() {
        val m = Matcher.EventTypes(setOf("POSTED", "UPDATED"))
        val ctx = MatchContext(packageName = "x", channelId = null, eventType = EventType.POSTED)
        assertTrue(m.matches(ctx))
    }

    @Test
    fun `EventTypes matcher fails when eventType not in set`() {
        val m = Matcher.EventTypes(setOf("POSTED"))
        val ctx = MatchContext(packageName = "x", channelId = null, eventType = EventType.REMOVED)
        assertFalse(m.matches(ctx))
    }

    @Test
    fun `EventTypes matcher rejects when eventType is null`() {
        val m = Matcher.EventTypes(setOf("POSTED"))
        val ctx = MatchContext(packageName = "x", channelId = null, eventType = null)
        assertFalse(m.matches(ctx))
    }

    @Test
    fun `Keyword matcher substring match case-insensitive`() {
        val m = Matcher.Keyword("廣告", setOf(KeywordField.TITLE), isRegex = false)
        val ctx = MatchContext(packageName = "x", channelId = null, title = "今日廣告推送")
        assertTrue(m.matches(ctx))
    }

    @Test
    fun `Keyword matcher fails when no matching text`() {
        val m = Matcher.Keyword("廣告", setOf(KeywordField.TITLE), isRegex = false)
        val ctx = MatchContext(packageName = "x", channelId = null, title = "重要通知")
        assertFalse(m.matches(ctx))
    }

    @Test
    fun `Keyword matcher fails when field is null`() {
        val m = Matcher.Keyword("test", setOf(KeywordField.TITLE), isRegex = false)
        val ctx = MatchContext(packageName = "x", channelId = null, title = null)
        assertFalse(m.matches(ctx))
    }

    @Test
    fun `Keyword matcher regex mode`() {
        val m = Matcher.Keyword("\\d{3,}", setOf(KeywordField.TEXT), isRegex = true)
        val ctx = MatchContext(packageName = "x", channelId = null, text = "驗證碼 1234")
        assertTrue(m.matches(ctx))
    }

    @Test
    fun `Keyword matcher checks multiple fields`() {
        val m = Matcher.Keyword("urgent", setOf(KeywordField.TITLE, KeywordField.TEXT), isRegex = false)
        val ctx = MatchContext(packageName = "x", channelId = null, title = "hello", text = "this is urgent")
        assertTrue(m.matches(ctx))
    }

    @Test
    fun `ChannelProperty matcher minImportance`() {
        val m = Matcher.ChannelProperty(minImportance = 3)
        assertTrue(m.matches(MatchContext(packageName = "x", channelId = null, channelImportance = 4)))
        assertTrue(m.matches(MatchContext(packageName = "x", channelId = null, channelImportance = 3)))
        assertFalse(m.matches(MatchContext(packageName = "x", channelId = null, channelImportance = 2)))
    }

    @Test
    fun `ChannelProperty matcher fails when importance is null`() {
        val m = Matcher.ChannelProperty(minImportance = 3)
        assertFalse(m.matches(MatchContext(packageName = "x", channelId = null, channelImportance = null)))
    }

    @Test
    fun `ChannelProperty matcher groupId`() {
        val m = Matcher.ChannelProperty(groupId = "social")
        assertTrue(m.matches(MatchContext(packageName = "x", channelId = null, channelGroupId = "social")))
        assertFalse(m.matches(MatchContext(packageName = "x", channelId = null, channelGroupId = "work")))
    }

    // ========== Rule 匹配 ==========

    @Test
    fun `Rule matches when all matchers pass`() {
        val rule = Rule(
            matchers = listOf(
                Matcher.Package("com.example.app"),
                Matcher.EventTypes(setOf("POSTED"))
            ),
            action = RuleAction.SkipRecord
        )
        val ctx = MatchContext(packageName = "com.example.app", channelId = null, eventType = EventType.POSTED)
        assertTrue(rule.matches(ctx))
    }

    @Test
    fun `Rule fails when any matcher fails`() {
        val rule = Rule(
            matchers = listOf(
                Matcher.Package("com.example.app"),
                Matcher.EventTypes(setOf("POSTED"))
            ),
            action = RuleAction.SkipRecord
        )
        val ctx = MatchContext(packageName = "com.example.app", channelId = null, eventType = EventType.REMOVED)
        assertFalse(rule.matches(ctx))
    }

    // ========== Rule 便利屬性 ==========

    @Test
    fun `isChannelLevel returns true when Channel matcher present`() {
        val rule = Rule(
            matchers = listOf(Matcher.Package("x"), Matcher.Channel("ch")),
            action = RuleAction.SkipRecord
        )
        assertTrue(rule.isChannelLevel)
    }

    @Test
    fun `isChannelLevel returns false when no Channel matcher`() {
        val rule = Rule(
            matchers = listOf(Matcher.Package("x")),
            action = RuleAction.SkipRecord
        )
        assertFalse(rule.isChannelLevel)
    }

    @Test
    fun `packageName returns Package matcher value`() {
        val rule = Rule(
            matchers = listOf(Matcher.Package("com.test.app")),
            action = RuleAction.CalendarExport
        )
        assertEquals("com.test.app", rule.packageName)
    }

    @Test
    fun `channelId returns null for package-level rule`() {
        val rule = Rule(
            matchers = listOf(Matcher.Package("x")),
            action = RuleAction.SkipRecord
        )
        assertNull(rule.channelId)
    }

    @Test
    fun `channelId returns value for channel-level rule`() {
        val rule = Rule(
            matchers = listOf(Matcher.Package("x"), Matcher.Channel("ch1")),
            action = RuleAction.SkipRecord
        )
        assertEquals("ch1", rule.channelId)
    }

    // ========== Matcher JSON ==========

    @Test
    fun `Package matcher JSON round-trip`() {
        val m = Matcher.Package("com.example.app")
        val restored = Matcher.fromJson(m.toJson())
        assertEquals(m, restored)
    }

    @Test
    fun `Channel matcher JSON round-trip`() {
        val m = Matcher.Channel("my_channel")
        val restored = Matcher.fromJson(m.toJson())
        assertEquals(m, restored)
    }

    @Test
    fun `EventTypes matcher JSON round-trip`() {
        val m = Matcher.EventTypes(setOf("POSTED", "UPDATED", "REMOVED"))
        val restored = Matcher.fromJson(m.toJson())
        assertEquals(m, restored)
    }

    @Test
    fun `Keyword matcher JSON round-trip`() {
        val m = Matcher.Keyword("test", setOf(KeywordField.TITLE, KeywordField.TEXT), isRegex = true)
        val restored = Matcher.fromJson(m.toJson()) as Matcher.Keyword
        assertEquals(m.pattern, restored.pattern)
        assertEquals(m.fields, restored.fields)
        assertEquals(m.isRegex, restored.isRegex)
    }

    @Test
    fun `ChannelProperty matcher JSON round-trip`() {
        val m = Matcher.ChannelProperty(minImportance = 3, groupId = "social")
        val restored = Matcher.fromJson(m.toJson()) as Matcher.ChannelProperty
        assertEquals(m.minImportance, restored.minImportance)
        assertEquals(m.groupId, restored.groupId)
    }

    @Test
    fun `ChannelProperty matcher JSON with null fields`() {
        val m = Matcher.ChannelProperty(minImportance = null, groupId = null)
        val restored = Matcher.fromJson(m.toJson()) as Matcher.ChannelProperty
        assertNull(restored.minImportance)
        assertNull(restored.groupId)
    }

    // ========== RuleAction JSON ==========

    @Test
    fun `SkipRecord action JSON round-trip`() {
        val a = RuleAction.SkipRecord
        val restored = RuleAction.fromJson(a.toJson())
        assertEquals(ActionType.SKIP_RECORD, restored.actionType)
    }

    @Test
    fun `CalendarExport action JSON round-trip`() {
        val a = RuleAction.CalendarExport
        val restored = RuleAction.fromJson(a.toJson())
        assertEquals(ActionType.CALENDAR_EXPORT, restored.actionType)
    }

    @Test
    fun `AutoDismiss action JSON round-trip`() {
        val a = RuleAction.AutoDismiss(delayMs = 300000)
        val restored = RuleAction.fromJson(a.toJson()) as RuleAction.AutoDismiss
        assertEquals(300000L, restored.delayMs)
    }

    @Test
    fun `AutoDismiss action JSON default delay`() {
        val json = JSONObject().apply { put("type", "AutoDismiss") }
        val restored = RuleAction.fromJson(json) as RuleAction.AutoDismiss
        assertEquals(0L, restored.delayMs)
    }

    // ========== Rule JSON ==========

    @Test
    fun `Rule JSON round-trip with all matcher types`() {
        val original = Rule(
            id = "test-rule-1",
            matchers = listOf(
                Matcher.Package("com.example.app"),
                Matcher.Channel("messages"),
                Matcher.EventTypes(setOf("POSTED", "UPDATED")),
                Matcher.Keyword("hello", setOf(KeywordField.TITLE), isRegex = false)
            ),
            action = RuleAction.AutoDismiss(delayMs = 60000),
            createdAt = 12345L
        )

        val restored = Rule.fromJson(original.toJson())

        assertEquals(original.id, restored.id)
        assertEquals(original.matchers.size, restored.matchers.size)
        assertEquals(original.action.actionType, restored.action.actionType)
        assertEquals(60000L, (restored.action as RuleAction.AutoDismiss).delayMs)
        assertEquals(original.createdAt, restored.createdAt)
        assertEquals(original.packageName, restored.packageName)
        assertEquals(original.channelId, restored.channelId)
    }

    @Test
    fun `Rule JSON round-trip with SkipRecord`() {
        val original = Rule(
            id = "sr-1",
            matchers = listOf(Matcher.Package("com.example.app")),
            action = RuleAction.SkipRecord,
            createdAt = 100L
        )

        val restored = Rule.fromJson(original.toJson())
        assertEquals(ActionType.SKIP_RECORD, restored.action.actionType)
    }
}
