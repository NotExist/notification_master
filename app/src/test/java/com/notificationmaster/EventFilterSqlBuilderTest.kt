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
    fun `DerivedProperty isRemoved=true emits event_type = REMOVED`() {
        val spec = EventFilterSpec(
            matchers = listOf(Matcher.DerivedProperty(isRemoved = true))
        )
        val q = EventFilterSqlBuilder.build(spec)
        // MatcherSqlTranslator 對 isRemoved=true 用 e.event_type = ?
        assertTrue("expected event_type predicate: ${q.sql}", q.sql.contains("e.event_type"))
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
}
