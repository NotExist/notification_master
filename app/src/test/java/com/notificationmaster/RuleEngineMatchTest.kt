package com.notificationmaster

import com.notificationmaster.core.filter.ActionType
import com.notificationmaster.core.filter.KeywordField
import com.notificationmaster.core.filter.MatchContext
import com.notificationmaster.core.filter.Matcher
import com.notificationmaster.core.filter.Rule
import com.notificationmaster.core.filter.RuleAction
import com.notificationmaster.core.filter.RuleEngine
import com.notificationmaster.data.db.entity.EventType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RuleEngine 匹配邏輯測試
 *
 * 使用 setRulesForTesting() 直接設定規則，避免依賴 Android Context。
 */
class RuleEngineMatchTest {

    @Before
    fun setUp() {
        RuleEngine.clearForTesting()
    }

    @After
    fun tearDown() {
        RuleEngine.clearForTesting()
    }

    // ========== findMatchingRule / matches ==========

    @Test
    fun `matches returns false when no rules exist`() {
        assertFalse(
            RuleEngine.matches(
                ActionType.SKIP_RECORD,
                MatchContext(packageName = "com.example.app", channelId = "ch1")
            )
        )
    }

    @Test
    fun `matches returns true for package-level rule`() {
        RuleEngine.setRulesForTesting(listOf(
            Rule(
                matchers = listOf(
                    Matcher.Package("com.example.app"),
                    Matcher.EventTypes(setOf("POSTED", "UPDATED"))
                ),
                action = RuleAction.SkipRecord
            )
        ))

        assertTrue(
            RuleEngine.matches(
                ActionType.SKIP_RECORD,
                MatchContext(
                    packageName = "com.example.app",
                    channelId = "any_channel",
                    eventType = EventType.POSTED
                )
            )
        )
    }

    @Test
    fun `matches returns false for non-matching eventType`() {
        RuleEngine.setRulesForTesting(listOf(
            Rule(
                matchers = listOf(
                    Matcher.Package("com.example.app"),
                    Matcher.EventTypes(setOf("POSTED"))
                ),
                action = RuleAction.SkipRecord
            )
        ))

        assertFalse(
            RuleEngine.matches(
                ActionType.SKIP_RECORD,
                MatchContext(
                    packageName = "com.example.app",
                    channelId = null,
                    eventType = EventType.REMOVED
                )
            )
        )
    }

    @Test
    fun `matches returns false for different package`() {
        RuleEngine.setRulesForTesting(listOf(
            Rule(
                matchers = listOf(Matcher.Package("com.example.app")),
                action = RuleAction.SkipRecord
            )
        ))

        assertFalse(
            RuleEngine.matches(
                ActionType.SKIP_RECORD,
                MatchContext(packageName = "com.different.app", channelId = null)
            )
        )
    }

    @Test
    fun `channel-level rule takes priority over package-level`() {
        RuleEngine.setRulesForTesting(listOf(
            Rule(
                matchers = listOf(
                    Matcher.Package("com.example.app"),
                    Matcher.EventTypes(setOf("POSTED", "UPDATED", "REMOVED"))
                ),
                action = RuleAction.SkipRecord
            ),
            Rule(
                matchers = listOf(
                    Matcher.Package("com.example.app"),
                    Matcher.Channel("special"),
                    Matcher.EventTypes(setOf("REMOVED"))
                ),
                action = RuleAction.SkipRecord
            )
        ))

        // channel "special" + POSTED → channel 級規則不包含 POSTED → false（被 shadow）
        assertFalse(
            RuleEngine.matches(
                ActionType.SKIP_RECORD,
                MatchContext(
                    packageName = "com.example.app",
                    channelId = "special",
                    eventType = EventType.POSTED
                )
            )
        )

        // channel "special" + REMOVED → channel 級規則包含 → true
        assertTrue(
            RuleEngine.matches(
                ActionType.SKIP_RECORD,
                MatchContext(
                    packageName = "com.example.app",
                    channelId = "special",
                    eventType = EventType.REMOVED
                )
            )
        )

        // channel "other" → 無 channel 匹配 → 降級到 package 級 → POSTED 在範圍內 → true
        assertTrue(
            RuleEngine.matches(
                ActionType.SKIP_RECORD,
                MatchContext(
                    packageName = "com.example.app",
                    channelId = "other",
                    eventType = EventType.POSTED
                )
            )
        )
    }

    @Test
    fun `different actionTypes are independent`() {
        RuleEngine.setRulesForTesting(listOf(
            Rule(
                matchers = listOf(Matcher.Package("com.example.app")),
                action = RuleAction.SkipRecord
            )
        ))

        assertTrue(
            RuleEngine.matches(
                ActionType.SKIP_RECORD,
                MatchContext(packageName = "com.example.app", channelId = null)
            )
        )
        assertFalse(
            RuleEngine.matches(
                ActionType.CALENDAR_EXPORT,
                MatchContext(packageName = "com.example.app", channelId = null)
            )
        )
    }

    @Test
    fun `findMatchingRule returns rule with correct action`() {
        val autoDismissRule = Rule(
            matchers = listOf(Matcher.Package("com.example.app")),
            action = RuleAction.AutoDismiss(delayMs = 300000)
        )
        RuleEngine.setRulesForTesting(listOf(autoDismissRule))

        val result = RuleEngine.findMatchingRule(
            ActionType.AUTO_DISMISS,
            MatchContext(packageName = "com.example.app", channelId = null)
        )

        assertNotNull(result)
        assertEquals(300000L, (result!!.action as RuleAction.AutoDismiss).delayMs)
    }

    @Test
    fun `findMatchingRule returns null when no match`() {
        RuleEngine.setRulesForTesting(listOf(
            Rule(
                matchers = listOf(Matcher.Package("com.example.app")),
                action = RuleAction.SkipRecord
            )
        ))

        val result = RuleEngine.findMatchingRule(
            ActionType.AUTO_DISMISS,
            MatchContext(packageName = "com.example.app", channelId = null)
        )
        assertNull(result)
    }

    // ========== matchesSource ==========

    @Test
    fun `matchesSource matches package-level`() {
        RuleEngine.setRulesForTesting(listOf(
            Rule(
                matchers = listOf(Matcher.Package("com.example.calendar")),
                action = RuleAction.CalendarExport
            )
        ))

        assertTrue(
            RuleEngine.matchesSource(ActionType.CALENDAR_EXPORT, "com.example.calendar", "any_ch")
        )
    }

    @Test
    fun `matchesSource matches channel-level`() {
        RuleEngine.setRulesForTesting(listOf(
            Rule(
                matchers = listOf(
                    Matcher.Package("com.example.calendar"),
                    Matcher.Channel("reminders")
                ),
                action = RuleAction.CalendarExport
            )
        ))

        assertTrue(
            RuleEngine.matchesSource(ActionType.CALENDAR_EXPORT, "com.example.calendar", "reminders")
        )
    }

    @Test
    fun `matchesSource returns false for non-matching package`() {
        RuleEngine.setRulesForTesting(listOf(
            Rule(
                matchers = listOf(Matcher.Package("com.example.calendar")),
                action = RuleAction.CalendarExport
            )
        ))

        assertFalse(
            RuleEngine.matchesSource(ActionType.CALENDAR_EXPORT, "com.different.app", null)
        )
    }

    // ========== Keyword 整合匹配 ==========

    @Test
    fun `rule with keyword matcher filters by content`() {
        RuleEngine.setRulesForTesting(listOf(
            Rule(
                matchers = listOf(
                    Matcher.Package("com.example.app"),
                    Matcher.Keyword("廣告", setOf(KeywordField.TITLE, KeywordField.TEXT))
                ),
                action = RuleAction.AutoDismiss(delayMs = 0)
            )
        ))

        // 標題含「廣告」→ 匹配
        assertTrue(
            RuleEngine.matches(
                ActionType.AUTO_DISMISS,
                MatchContext(
                    packageName = "com.example.app",
                    channelId = null,
                    title = "雙11廣告活動"
                )
            )
        )

        // 無關鍵字 → 不匹配
        assertFalse(
            RuleEngine.matches(
                ActionType.AUTO_DISMISS,
                MatchContext(
                    packageName = "com.example.app",
                    channelId = null,
                    title = "重要通知",
                    text = "您的訂單已出貨"
                )
            )
        )
    }

    @Test
    fun `keyword matcher matches bigText and subText fields`() {
        RuleEngine.setRulesForTesting(listOf(
            Rule(
                matchers = listOf(
                    Matcher.Package("com.example.app"),
                    Matcher.Keyword("優惠", setOf(KeywordField.BIG_TEXT, KeywordField.SUB_TEXT))
                ),
                action = RuleAction.SkipRecord
            )
        ))

        // bigText 含關鍵字 → 匹配
        assertTrue(
            RuleEngine.matches(
                ActionType.SKIP_RECORD,
                MatchContext(
                    packageName = "com.example.app",
                    channelId = null,
                    title = "普通標題",
                    text = "普通內容",
                    bigText = "超級優惠活動詳情"
                )
            )
        )

        // subText 含關鍵字 → 匹配
        assertTrue(
            RuleEngine.matches(
                ActionType.SKIP_RECORD,
                MatchContext(
                    packageName = "com.example.app",
                    channelId = null,
                    subText = "限時優惠"
                )
            )
        )

        // 關鍵字僅在 title（未列入搜尋欄位）→ 不匹配
        assertFalse(
            RuleEngine.matches(
                ActionType.SKIP_RECORD,
                MatchContext(
                    packageName = "com.example.app",
                    channelId = null,
                    title = "優惠通知",
                    text = "一般內容"
                )
            )
        )
    }

    @Test
    fun `keyword regex matcher with content fields`() {
        RuleEngine.setRulesForTesting(listOf(
            Rule(
                matchers = listOf(
                    Matcher.Package("com.example.app"),
                    Matcher.Keyword("\\d{4}-\\d{2}-\\d{2}", setOf(KeywordField.TEXT), isRegex = true)
                ),
                action = RuleAction.SkipRecord
            )
        ))

        // 含日期格式 → 匹配
        assertTrue(
            RuleEngine.matches(
                ActionType.SKIP_RECORD,
                MatchContext(
                    packageName = "com.example.app",
                    channelId = null,
                    text = "活動日期 2024-01-15 開始"
                )
            )
        )

        // 不含日期格式 → 不匹配
        assertFalse(
            RuleEngine.matches(
                ActionType.SKIP_RECORD,
                MatchContext(
                    packageName = "com.example.app",
                    channelId = null,
                    text = "沒有日期的文字"
                )
            )
        )
    }

    @Test
    fun `keyword matcher with null content fields does not match`() {
        RuleEngine.setRulesForTesting(listOf(
            Rule(
                matchers = listOf(
                    Matcher.Package("com.example.app"),
                    Matcher.Keyword("test", setOf(KeywordField.TITLE, KeywordField.TEXT))
                ),
                action = RuleAction.SkipRecord
            )
        ))

        // content 欄位為 null → keyword 不匹配 → rule 不匹配
        assertFalse(
            RuleEngine.matches(
                ActionType.SKIP_RECORD,
                MatchContext(
                    packageName = "com.example.app",
                    channelId = null,
                    title = null,
                    text = null
                )
            )
        )
    }

    // ========== ChannelProperty 整合匹配 ==========

    @Test
    fun `rule with channelProperty minImportance filters by importance`() {
        RuleEngine.setRulesForTesting(listOf(
            Rule(
                matchers = listOf(
                    Matcher.Package("com.example.app"),
                    Matcher.ChannelProperty(minImportance = 4)
                ),
                action = RuleAction.AutoDismiss(delayMs = 0)
            )
        ))

        // importance=4 (HIGH) → 匹配
        assertTrue(
            RuleEngine.matches(
                ActionType.AUTO_DISMISS,
                MatchContext(
                    packageName = "com.example.app",
                    channelId = null,
                    channelImportance = 4
                )
            )
        )

        // importance=5 (MAX) → 匹配
        assertTrue(
            RuleEngine.matches(
                ActionType.AUTO_DISMISS,
                MatchContext(
                    packageName = "com.example.app",
                    channelId = null,
                    channelImportance = 5
                )
            )
        )

        // importance=3 (DEFAULT) → 不匹配
        assertFalse(
            RuleEngine.matches(
                ActionType.AUTO_DISMISS,
                MatchContext(
                    packageName = "com.example.app",
                    channelId = null,
                    channelImportance = 3
                )
            )
        )

        // importance 為 null → 不匹配
        assertFalse(
            RuleEngine.matches(
                ActionType.AUTO_DISMISS,
                MatchContext(
                    packageName = "com.example.app",
                    channelId = null,
                    channelImportance = null
                )
            )
        )
    }

    @Test
    fun `rule with channelProperty groupId filters by group`() {
        RuleEngine.setRulesForTesting(listOf(
            Rule(
                matchers = listOf(
                    Matcher.Package("com.example.app"),
                    Matcher.ChannelProperty(groupId = "social")
                ),
                action = RuleAction.SkipRecord
            )
        ))

        // groupId="social" → 匹配
        assertTrue(
            RuleEngine.matches(
                ActionType.SKIP_RECORD,
                MatchContext(
                    packageName = "com.example.app",
                    channelId = null,
                    channelGroupId = "social"
                )
            )
        )

        // groupId="work" → 不匹配
        assertFalse(
            RuleEngine.matches(
                ActionType.SKIP_RECORD,
                MatchContext(
                    packageName = "com.example.app",
                    channelId = null,
                    channelGroupId = "work"
                )
            )
        )

        // groupId 為 null → 不匹配
        assertFalse(
            RuleEngine.matches(
                ActionType.SKIP_RECORD,
                MatchContext(
                    packageName = "com.example.app",
                    channelId = null,
                    channelGroupId = null
                )
            )
        )
    }

    @Test
    fun `rule with keyword and channelProperty requires both to match`() {
        RuleEngine.setRulesForTesting(listOf(
            Rule(
                matchers = listOf(
                    Matcher.Package("com.example.app"),
                    Matcher.Keyword("廣告", setOf(KeywordField.TITLE)),
                    Matcher.ChannelProperty(minImportance = 3)
                ),
                action = RuleAction.SkipRecord
            )
        ))

        // 兩者都符合 → 匹配
        assertTrue(
            RuleEngine.matches(
                ActionType.SKIP_RECORD,
                MatchContext(
                    packageName = "com.example.app",
                    channelId = null,
                    title = "廣告推送",
                    channelImportance = 4
                )
            )
        )

        // keyword 符合但 importance 不足 → 不匹配
        assertFalse(
            RuleEngine.matches(
                ActionType.SKIP_RECORD,
                MatchContext(
                    packageName = "com.example.app",
                    channelId = null,
                    title = "廣告推送",
                    channelImportance = 2
                )
            )
        )

        // importance 符合但 keyword 不符 → 不匹配
        assertFalse(
            RuleEngine.matches(
                ActionType.SKIP_RECORD,
                MatchContext(
                    packageName = "com.example.app",
                    channelId = null,
                    title = "重要通知",
                    channelImportance = 4
                )
            )
        )
    }

    // ========== hasRules / getRules / isAllEmpty ==========

    @Test
    fun `hasRules returns false when no rules`() {
        assertFalse(RuleEngine.hasRules(ActionType.SKIP_RECORD))
    }

    @Test
    fun `hasRules returns true when rules exist`() {
        RuleEngine.setRulesForTesting(listOf(
            Rule(
                matchers = listOf(Matcher.Package("com.example.app")),
                action = RuleAction.SkipRecord
            )
        ))
        assertTrue(RuleEngine.hasRules(ActionType.SKIP_RECORD))
    }

    @Test
    fun `getRules filters by actionType`() {
        RuleEngine.setRulesForTesting(listOf(
            Rule(
                matchers = listOf(Matcher.Package("com.a")),
                action = RuleAction.SkipRecord
            ),
            Rule(
                matchers = listOf(Matcher.Package("com.b")),
                action = RuleAction.CalendarExport
            ),
            Rule(
                matchers = listOf(Matcher.Package("com.c")),
                action = RuleAction.AutoDismiss()
            )
        ))

        assertEquals(1, RuleEngine.getRules(ActionType.SKIP_RECORD).size)
        assertEquals(1, RuleEngine.getRules(ActionType.CALENDAR_EXPORT).size)
        assertEquals(1, RuleEngine.getRules(ActionType.AUTO_DISMISS).size)
        assertEquals(3, RuleEngine.getRules().size)
    }

    @Test
    fun `isAllEmpty returns true when no rules`() {
        assertTrue(RuleEngine.isAllEmpty())
    }

    @Test
    fun `isAllEmpty returns false when rules exist`() {
        RuleEngine.setRulesForTesting(listOf(
            Rule(
                matchers = listOf(Matcher.Package("com.example.app")),
                action = RuleAction.CalendarExport
            )
        ))
        assertFalse(RuleEngine.isAllEmpty())
    }
}
