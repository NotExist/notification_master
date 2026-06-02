package com.notificationmaster.data.filter

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.notificationmaster.core.filter.OrderBy

/**
 * FilterSpec → SupportSQLiteQuery
 *
 * matchers 經 [MatcherSqlTranslator] 翻譯為 WHERE fragment（AND 組合），
 * 加上 spec 自身的時間範圍 / deduplicate / orderBy / limit 控制。
 *
 * Plan 2：target 表 = `notification_events`（alias `e`）。
 * - deduplicate ON：每個 notification_key 取最新 event_time 對應的 event（單筆）
 * - deduplicate OFF：所有 events 各自一筆
 *
 * 時間範圍仍以 `post_time` 為比對基準（events 表本身有 post_time column，取自 sbn.postTime）。
 */
object EventFilterSqlBuilder {

    fun build(spec: EventFilterSpec): SupportSQLiteQuery = buildInternal(spec, count = false)

    fun buildCount(spec: EventFilterSpec): SupportSQLiteQuery = buildInternal(spec, count = true)

    private fun buildInternal(spec: EventFilterSpec, count: Boolean): SupportSQLiteQuery {
        val args = mutableListOf<Any?>()
        val where = StringBuilder("1=1")

        // Plan 2 W1：所有 query 排除 REMOVED event row（REMOVED 不顯示為 list row，
        // 改成「對前 row 的 attribute」 — 在 NotificationEnricher 計算 row.isRemoved 廣義屬性）
        where.append(" AND e.event_type != 'REMOVED'")

        // matchers
        val matcherFragment = MatcherSqlTranslator.toFragment(spec.matchers)
        if (matcherFragment.sql != "1=1") {
            where.append(" AND ").append(matcherFragment.sql)
            args.addAll(matcherFragment.args)
        }

        // 時間範圍
        if (spec.timeFrom != null) {
            where.append(" AND e.post_time >= ?")
            args.add(spec.timeFrom)
        }
        if (spec.timeTo != null) {
            where.append(" AND e.post_time <= ?")
            args.add(spec.timeTo)
        }

        val orderSql = orderBySql(spec.orderBy)
        val limitSql = if (spec.limit != null) " LIMIT ${spec.limit.toInt()}" else ""

        val sql = when {
            count && spec.deduplicate -> """
                SELECT COUNT(*) FROM (
                    SELECT e.notification_key FROM notification_events e
                    WHERE $where
                    GROUP BY e.notification_key
                )
            """.trimIndent()

            count && !spec.deduplicate -> """
                SELECT COUNT(*) FROM notification_events e
                WHERE $where
            """.trimIndent()

            spec.deduplicate -> """
                SELECT * FROM notification_events WHERE id IN (
                    SELECT id FROM (
                        SELECT e.id, MAX(e.event_time) FROM notification_events e
                        WHERE $where
                        GROUP BY e.notification_key
                    )
                )
                $orderSql$limitSql
            """.trimIndent()

            else -> """
                SELECT e.* FROM notification_events e
                WHERE $where
                $orderSql$limitSql
            """.trimIndent()
        }

        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    private fun orderBySql(orderBy: OrderBy): String = when (orderBy) {
        OrderBy.PostTimeDesc -> "ORDER BY post_time DESC"
        OrderBy.PostTimeAsc -> "ORDER BY post_time ASC"
        OrderBy.CaptureTimeDesc -> "ORDER BY capture_time DESC"
        OrderBy.CaptureTimeAsc -> "ORDER BY capture_time ASC"
        OrderBy.EventTimeDesc -> "ORDER BY event_time DESC"
        OrderBy.EventTimeAsc -> "ORDER BY event_time ASC"
    }
}
