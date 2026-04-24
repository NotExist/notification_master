package com.notificationmaster.data.filter

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * FilterSpec → SupportSQLiteQuery
 *
 * 安全要點：
 * - 所有使用者值透過 `?` 參數綁定
 * - 欄位名稱經 [FilterFieldWhitelist] 檢查，拒絕白名單外的 field
 * - LIKE 值 escape `%` / `_` / `\`
 *
 * 第一版 target 為 `notifications` 表。Plan 2 完成後改 `notification_events`，
 * spec 表達層不變，僅此類別內部翻譯改寫。
 */
object EventFilterSqlBuilder {

    /** LIKE 的 escape 字元，查詢時附上 `ESCAPE '\'` */
    private const val LIKE_ESCAPE = '\\'

    fun build(spec: EventFilterSpec): SupportSQLiteQuery = buildInternal(spec, count = false)

    fun buildCount(spec: EventFilterSpec): SupportSQLiteQuery = buildInternal(spec, count = true)

    private fun buildInternal(spec: EventFilterSpec, count: Boolean): SupportSQLiteQuery {
        val args = mutableListOf<Any?>()
        val where = StringBuilder("1=1")
        val needsMediaJoin = spec.keyword != null

        // === 基本欄位 ===
        if (spec.packageName != null) {
            where.append(" AND n.package_name = ?")
            args.add(spec.packageName)
        }
        if (spec.channelId != null) {
            where.append(" AND n.channel_id = ?")
            args.add(spec.channelId)
        }
        if (spec.notificationKey != null) {
            where.append(" AND n.notification_key = ?")
            args.add(spec.notificationKey)
        }
        if (spec.isAudible != null) {
            where.append(" AND n.is_audible = ?")
            args.add(if (spec.isAudible) 1 else 0)
        }
        if (spec.likelyHeadsup != null) {
            where.append(" AND n.likely_headsup = ?")
            args.add(if (spec.likelyHeadsup) 1 else 0)
        }
        if (spec.isRemoved != null) {
            where.append(if (spec.isRemoved) " AND n.removed_at IS NOT NULL" else " AND n.removed_at IS NULL")
        }
        if (spec.timeFrom != null) {
            where.append(" AND n.post_time >= ?")
            args.add(spec.timeFrom)
        }
        if (spec.timeTo != null) {
            where.append(" AND n.post_time <= ?")
            args.add(spec.timeTo)
        }
        if (spec.keyword != null) {
            val kw = "%" + escapeLike(spec.keyword) + "%"
            where.append(
                " AND (n.title LIKE ? ESCAPE '\\'" +
                    " OR n.text LIKE ? ESCAPE '\\'" +
                    " OR n.big_text LIKE ? ESCAPE '\\'" +
                    " OR n.sub_text LIKE ? ESCAPE '\\'" +
                    " OR m.file_path LIKE ? ESCAPE '\\')"
            )
            repeat(5) { args.add(kw) }
        }

        // === extraPredicates（白名單內） ===
        for (p in spec.extraPredicates) {
            val def = FilterFieldWhitelist.require(p.field)
            val col = "n.${def.column}"
            when (p.op) {
                Op.IS_NULL -> where.append(" AND $col IS NULL")
                Op.IS_NOT_NULL -> where.append(" AND $col IS NOT NULL")
                Op.IN -> {
                    val vs = p.values ?: error("IN op requires values")
                    val placeholders = List(vs.size) { "?" }.joinToString(",")
                    where.append(" AND $col IN ($placeholders)")
                    vs.forEach { args.add(coerceValue(def.type, it)) }
                }
                Op.LIKE -> {
                    where.append(" AND $col LIKE ? ESCAPE '\\'")
                    args.add("%" + escapeLike(p.value?.toString() ?: "") + "%")
                }
                Op.EQ -> {
                    where.append(" AND $col = ?")
                    args.add(coerceValue(def.type, p.value))
                }
                Op.NEQ -> {
                    where.append(" AND $col != ?")
                    args.add(coerceValue(def.type, p.value))
                }
                Op.LT -> {
                    where.append(" AND $col < ?")
                    args.add(coerceValue(def.type, p.value))
                }
                Op.LTE -> {
                    where.append(" AND $col <= ?")
                    args.add(coerceValue(def.type, p.value))
                }
                Op.GT -> {
                    where.append(" AND $col > ?")
                    args.add(coerceValue(def.type, p.value))
                }
                Op.GTE -> {
                    where.append(" AND $col >= ?")
                    args.add(coerceValue(def.type, p.value))
                }
            }
        }

        val joinSql = if (needsMediaJoin) " LEFT JOIN media_attachments m ON m.notification_id = n.id" else ""
        val orderSql = orderBySql(spec.orderBy)
        val limitSql = if (spec.limit != null) " LIMIT ${spec.limit.toInt()}" else ""

        val sql = when {
            count && spec.deduplicate -> """
                SELECT COUNT(*) FROM (
                    SELECT n.notification_key FROM notifications n$joinSql
                    WHERE $where
                    GROUP BY n.notification_key
                )
            """.trimIndent()

            count && !spec.deduplicate -> """
                SELECT COUNT(DISTINCT n.id) FROM notifications n$joinSql
                WHERE $where
            """.trimIndent()

            spec.deduplicate -> """
                SELECT * FROM notifications WHERE id IN (
                    SELECT id FROM (
                        SELECT n.id, MAX(n.post_time) FROM notifications n$joinSql
                        WHERE $where
                        GROUP BY n.notification_key
                    )
                )
                $orderSql$limitSql
            """.trimIndent()

            else -> """
                SELECT DISTINCT n.* FROM notifications n$joinSql
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
    }

    /** LIKE pattern escape：`%` / `_` / `\` 前置 `\` */
    private fun escapeLike(s: String): String {
        val sb = StringBuilder(s.length + 4)
        for (c in s) {
            if (c == LIKE_ESCAPE || c == '%' || c == '_') sb.append(LIKE_ESCAPE)
            sb.append(c)
        }
        return sb.toString()
    }

    /** 依 FieldDef.type 把 JSON 還原的 Number/Bool/String 強制為對應型別 */
    private fun coerceValue(type: FilterFieldWhitelist.Type, value: Any?): Any? {
        if (value == null) return null
        return when (type) {
            FilterFieldWhitelist.Type.INT -> when (value) {
                is Number -> value.toInt()
                is Boolean -> if (value) 1 else 0
                is String -> value.toInt()
                else -> value
            }
            FilterFieldWhitelist.Type.LONG -> when (value) {
                is Number -> value.toLong()
                is String -> value.toLong()
                else -> value
            }
            FilterFieldWhitelist.Type.BOOL -> when (value) {
                is Boolean -> if (value) 1 else 0
                is Number -> if (value.toInt() != 0) 1 else 0
                is String -> if (value.toBoolean()) 1 else 0
                else -> value
            }
            FilterFieldWhitelist.Type.TEXT -> value.toString()
        }
    }
}
