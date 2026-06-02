package com.notificationmaster

import com.notificationmaster.core.filter.Matcher
import com.notificationmaster.core.filter.OrderBy
import com.notificationmaster.data.filter.EventFilterSpec
import com.notificationmaster.data.filter.EventFilterSqlBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan 2 Phase 10 — EventFilterSqlBuilder 結構性驗證
 *
 * 只測試 SQL 字串結構（target 表、JOIN、WHERE 片段、deduplicate 結構），不接觸 Room runtime。
 * 真實查詢正確性以實機運行為準（CI 環境無 SQLite 可單測 Room RawQuery）。
 */
class EventFilterSqlBuilderTest {

    private fun sqlOf(spec: EventFilterSpec) =
        EventFilterSqlBuilder.build(spec).sql

    private fun countSqlOf(spec: EventFilterSpec) =
        EventFilterSqlBuilder.buildCount(spec).sql

    // ===== target table =====

    @Test
    fun `target table is notification_events not notifications`() {
        val sql = sqlOf(EventFilterSpec.All)
        assertTrue("expected notification_events in $sql", sql.contains("notification_events"))
        assertFalse(
            "should not reference legacy notifications table: $sql",
            Regex("\\bFROM\\s+notifications\\b").containsMatchIn(sql)
        )
    }

    // ===== deduplicate ON vs OFF =====

    @Test
    fun `deduplicate OFF returns all events`() {
        val sql = sqlOf(EventFilterSpec.All)
        assertTrue(sql.contains("SELECT e.* FROM notification_events e"))
        // 非去重時不應有 MAX/GROUP BY 結構
        assertFalse(sql.contains("MAX(e.event_time)"))
    }

    @Test
    fun `deduplicate ON uses MAX event_time grouped by key`() {
        val sql = sqlOf(EventFilterSpec.Deduplicated)
        assertTrue("expected MAX(e.event_time): $sql", sql.contains("MAX(e.event_time)"))
        assertTrue("expected GROUP BY e.notification_key: $sql",
            sql.contains("GROUP BY e.notification_key"))
    }

    // ===== timeFrom/timeTo =====

    @Test
    fun `timeFrom adds post_time gte predicate`() {
        val spec = EventFilterSpec(timeFrom = 1000L)
        val q = EventFilterSqlBuilder.build(spec)
        assertTrue(q.sql.contains("e.post_time >= ?"))
        // 第一個 arg 是 1000L
        // SupportSQLiteQuery 的 args 不直接暴露，但 SimpleSQLiteQuery argCount = 1
        assertEquals(1, q.argCount)
    }

    @Test
    fun `timeFrom and timeTo combine into BETWEEN-like predicates`() {
        val spec = EventFilterSpec(timeFrom = 1000L, timeTo = 2000L)
        val q = EventFilterSqlBuilder.build(spec)
        assertTrue(q.sql.contains("e.post_time >= ?"))
        assertTrue(q.sql.contains("e.post_time <= ?"))
        assertEquals(2, q.argCount)
    }

    // ===== orderBy =====

    @Test
    fun `default orderBy is post_time DESC`() {
        val sql = sqlOf(EventFilterSpec.All)
        assertTrue(sql.contains("ORDER BY post_time DESC"))
    }

    @Test
    fun `EventTimeAsc maps to ORDER BY event_time ASC`() {
        val sql = sqlOf(EventFilterSpec(orderBy = OrderBy.EventTimeAsc))
        assertTrue(sql.contains("ORDER BY event_time ASC"))
    }

    @Test
    fun `CaptureTimeDesc maps to ORDER BY capture_time DESC`() {
        val sql = sqlOf(EventFilterSpec(orderBy = OrderBy.CaptureTimeDesc))
        assertTrue(sql.contains("ORDER BY capture_time DESC"))
    }

    // ===== limit =====

    @Test
    fun `limit appends LIMIT clause`() {
        val sql = sqlOf(EventFilterSpec(limit = 50))
        assertTrue(sql.contains("LIMIT 50"))
    }

    @Test
    fun `null limit produces no LIMIT clause`() {
        val sql = sqlOf(EventFilterSpec.All)
        assertFalse(sql.contains("LIMIT"))
    }

    // ===== matcher 投影 =====

    @Test
    fun `Package matcher emits package_name predicate`() {
        val spec = EventFilterSpec(matchers = listOf(Matcher.Package("com.foo")))
        val q = EventFilterSqlBuilder.build(spec)
        assertTrue(q.sql.contains("e.package_name = ?"))
        assertEquals(1, q.argCount)
    }

    @Test
    fun `Channel matcher emits channel_id predicate`() {
        val spec = EventFilterSpec(matchers = listOf(Matcher.Channel("ch1")))
        val q = EventFilterSqlBuilder.build(spec)
        assertTrue(q.sql.contains("e.channel_id = ?"))
    }

    @Test
    fun `DerivedProperty isAudible emits is_audible predicate`() {
        val spec = EventFilterSpec(
            matchers = listOf(Matcher.DerivedProperty(isAudible = true))
        )
        val q = EventFilterSqlBuilder.build(spec)
        assertTrue(q.sql.contains("e.is_audible = ?"))
    }

    @Test
    fun `DerivedProperty likelyHeadsup emits likely_headsup predicate`() {
        val spec = EventFilterSpec(
            matchers = listOf(Matcher.DerivedProperty(likelyHeadsup = true))
        )
        val q = EventFilterSqlBuilder.build(spec)
        assertTrue(q.sql.contains("e.likely_headsup = ?"))
    }

    @Test
    fun `all queries exclude REMOVED row by default (Plan 2 W1)`() {
        // Plan 2 W1：EventFilterSqlBuilder 全 query 加 event_type != 'REMOVED'
        // REMOVED event 不顯示為 list row，作為「前 row 的 attribute」
        val spec = EventFilterSpec()
        val q = EventFilterSqlBuilder.build(spec)
        assertTrue("expected REMOVED exclusion: ${q.sql}", q.sql.contains("e.event_type != 'REMOVED'"))
    }

    // ===== count 對應 =====

    @Test
    fun `buildCount returns SELECT COUNT for non-dedup`() {
        val sql = countSqlOf(EventFilterSpec.All)
        assertTrue(sql.contains("SELECT COUNT(*) FROM notification_events"))
    }

    @Test
    fun `buildCount returns dedup count via subquery`() {
        val sql = countSqlOf(EventFilterSpec.Deduplicated)
        assertTrue(sql.contains("SELECT COUNT(*) FROM"))
        assertTrue(sql.contains("GROUP BY e.notification_key"))
    }

    // ===== Phase 12：ChannelProperty correlated subquery =====

    @Test
    fun `ChannelProperty minImportance uses correlated subquery to channels table`() {
        val spec = EventFilterSpec(
            matchers = listOf(Matcher.ChannelProperty(minImportance = 3))
        )
        val q = EventFilterSqlBuilder.build(spec)
        assertTrue(
            "expected correlated subquery: ${q.sql}",
            q.sql.contains("SELECT importance FROM channels WHERE package_name = e.package_name AND channel_id = e.channel_id")
        )
        assertTrue(q.sql.contains(">= ?"))
        assertEquals(1, q.argCount)
    }

    @Test
    fun `ChannelProperty groupId uses correlated subquery to channels table`() {
        val spec = EventFilterSpec(
            matchers = listOf(Matcher.ChannelProperty(groupId = "social"))
        )
        val q = EventFilterSqlBuilder.build(spec)
        assertTrue(
            "expected correlated subquery: ${q.sql}",
            q.sql.contains("SELECT group_id FROM channels WHERE package_name = e.package_name AND channel_id = e.channel_id")
        )
        assertEquals(1, q.argCount)
    }

    // ===== Phase 12：API <27 退化（test env SDK_INT==0） =====

    @Test
    fun `Flags matcher falls back to ALWAYS_TRUE on test env (API below 27)`() {
        val spec = EventFilterSpec(
            matchers = listOf(Matcher.Flags(requiredFlags = 0x02))
        )
        val sql = sqlOf(spec)
        // 退化後 SQL 內不應包含 flag 條件，整體 where 等同 1=1
        assertFalse("should not contain json_extract on test env: $sql", sql.contains("json_extract"))
    }

    @Test
    fun `Keyword BIG_TEXT only falls back when json_extract unavailable`() {
        val spec = EventFilterSpec(
            matchers = listOf(Matcher.Keyword(
                pattern = "abc",
                fields = setOf(com.notificationmaster.core.filter.KeywordField.BIG_TEXT)
            ))
        )
        val sql = sqlOf(spec)
        // test env 走退化（單 BIG_TEXT 無其他 column → 整個 Keyword 退化 ALWAYS_TRUE）
        assertFalse(sql.contains("json_extract"))
    }

    @Test
    fun `Keyword TITLE plus BIG_TEXT keeps TITLE column on test env`() {
        val spec = EventFilterSpec(
            matchers = listOf(Matcher.Keyword(
                pattern = "abc",
                fields = setOf(
                    com.notificationmaster.core.filter.KeywordField.TITLE,
                    com.notificationmaster.core.filter.KeywordField.BIG_TEXT
                )
            ))
        )
        val q = EventFilterSqlBuilder.build(spec)
        // TITLE 仍應產生 LIKE；BIG_TEXT 在 test env 被忽略
        assertTrue(q.sql.contains("e.title LIKE"))
        assertFalse(q.sql.contains("json_extract"))
        assertEquals(1, q.argCount)
    }

    // ===== Phase 12：requiresJsonExtract 預檢 =====

    @Test
    fun `requiresJsonExtract returns true for non-empty Flags matcher`() {
        val matcher = Matcher.Flags(requiredFlags = 0x02)
        assertTrue(com.notificationmaster.data.filter.MatcherSqlTranslator.requiresJsonExtract(matcher))
    }

    @Test
    fun `requiresJsonExtract returns false for empty Flags matcher`() {
        val matcher = Matcher.Flags()
        assertFalse(com.notificationmaster.data.filter.MatcherSqlTranslator.requiresJsonExtract(matcher))
    }

    @Test
    fun `requiresJsonExtract returns true for Keyword with BIG_TEXT field`() {
        val matcher = Matcher.Keyword(
            pattern = "x",
            fields = setOf(com.notificationmaster.core.filter.KeywordField.BIG_TEXT)
        )
        assertTrue(com.notificationmaster.data.filter.MatcherSqlTranslator.requiresJsonExtract(matcher))
    }

    @Test
    fun `requiresJsonExtract returns false for Keyword limited to TITLE TEXT`() {
        val matcher = Matcher.Keyword(
            pattern = "x",
            fields = setOf(
                com.notificationmaster.core.filter.KeywordField.TITLE,
                com.notificationmaster.core.filter.KeywordField.TEXT
            )
        )
        assertFalse(com.notificationmaster.data.filter.MatcherSqlTranslator.requiresJsonExtract(matcher))
    }

    @Test
    fun `requiresJsonExtract returns false for ChannelProperty matcher`() {
        // ChannelProperty 走 correlated subquery 不需 json_extract
        val matcher = Matcher.ChannelProperty(minImportance = 3)
        assertFalse(com.notificationmaster.data.filter.MatcherSqlTranslator.requiresJsonExtract(matcher))
    }
}
