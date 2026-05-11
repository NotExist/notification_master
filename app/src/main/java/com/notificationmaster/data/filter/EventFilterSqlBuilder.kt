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
 * Target = `notifications` 表。Plan 2 完成後改 `notification_events`，
 * spec 表達層不變，僅此類別內部翻譯改寫。
 */
object EventFilterSqlBuilder {

    fun build(spec: EventFilterSpec): SupportSQLiteQuery = buildInternal(spec, count = false)

    fun buildCount(spec: EventFilterSpec): SupportSQLiteQuery = buildInternal(spec, count = true)

    private fun buildInternal(spec: EventFilterSpec, count: Boolean): SupportSQLiteQuery {
        val args = mutableListOf<Any?>()
        val where = StringBuilder("1=1")

        // matchers
        val matcherFragment = MatcherSqlTranslator.toFragment(spec.matchers)
        if (matcherFragment.sql != "1=1") {
            where.append(" AND ").append(matcherFragment.sql)
            args.addAll(matcherFragment.args)
        }

        // 時間範圍
        if (spec.timeFrom != null) {
            where.append(" AND n.post_time >= ?")
            args.add(spec.timeFrom)
        }
        if (spec.timeTo != null) {
            where.append(" AND n.post_time <= ?")
            args.add(spec.timeTo)
        }

        val orderSql = orderBySql(spec.orderBy)
        val limitSql = if (spec.limit != null) " LIMIT ${spec.limit.toInt()}" else ""

        val sql = when {
            count && spec.deduplicate -> """
                SELECT COUNT(*) FROM (
                    SELECT n.notification_key FROM notifications n
                    WHERE $where
                    GROUP BY n.notification_key
                )
            """.trimIndent()

            count && !spec.deduplicate -> """
                SELECT COUNT(*) FROM notifications n
                WHERE $where
            """.trimIndent()

            spec.deduplicate -> """
                SELECT * FROM notifications WHERE id IN (
                    SELECT id FROM (
                        SELECT n.id, MAX(n.post_time) FROM notifications n
                        WHERE $where
                        GROUP BY n.notification_key
                    )
                )
                $orderSql$limitSql
            """.trimIndent()

            else -> """
                SELECT n.* FROM notifications n
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
        // Event time：以該通知最新事件時間排序（Plan 2：events 改以 notification_key 關聯）
        OrderBy.EventTimeDesc ->
            "ORDER BY (SELECT MAX(event_time) FROM notification_events e WHERE e.notification_key = notifications.notification_key) DESC"
        OrderBy.EventTimeAsc ->
            "ORDER BY (SELECT MAX(event_time) FROM notification_events e WHERE e.notification_key = notifications.notification_key) ASC"
    }
}
