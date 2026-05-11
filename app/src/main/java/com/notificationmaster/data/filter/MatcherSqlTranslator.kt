package com.notificationmaster.data.filter

import com.notificationmaster.core.filter.FieldOp
import com.notificationmaster.core.filter.FieldWhitelist
import com.notificationmaster.core.filter.KeywordField
import com.notificationmaster.core.filter.Matcher

/**
 * 把 RuleEngine 的 [Matcher] 翻譯為 SQL WHERE fragment。
 *
 * 設計理念：列表篩選（Timeline / Widget）與 RuleEngine 的即時匹配共用同一套
 * Matcher model；in-memory 路徑由 [Matcher.matches] 處理，SQL 路徑由本類別處理。
 *
 * Target table alias = `n`（notifications）。EventTypes 走 EXISTS subquery 對到
 * `notification_events`。
 *
 * Keyword 的 regex 模式無法用 SQLite 標準函式表達，會拋 [UnsupportedMatcherException]；
 * ChannelProperty.groupId 因 NotificationEntity 沒儲存 channel group，亦不支援。
 */
object MatcherSqlTranslator {

    data class Fragment(val sql: String, val args: List<Any?>) {
        companion object {
            val ALWAYS_TRUE = Fragment("1=1", emptyList())
        }
    }

    class UnsupportedMatcherException(msg: String) : IllegalArgumentException(msg)

    private const val LIKE_ESCAPE = '\\'

    /** 多個 matcher 以 AND 串接成單一 fragment（args 順序對齊 ?） */
    fun toFragment(matchers: List<Matcher>): Fragment {
        if (matchers.isEmpty()) return Fragment.ALWAYS_TRUE
        val sb = StringBuilder()
        val args = mutableListOf<Any?>()
        var first = true
        for (m in matchers) {
            val f = translate(m)
            if (f.sql == "1=1") continue
            if (first) first = false else sb.append(" AND ")
            sb.append('(').append(f.sql).append(')')
            args.addAll(f.args)
        }
        return if (sb.isEmpty()) Fragment.ALWAYS_TRUE else Fragment(sb.toString(), args)
    }

    fun translate(matcher: Matcher): Fragment = when (matcher) {
        is Matcher.Package -> Fragment("n.package_name = ?", listOf(matcher.packageName))

        is Matcher.Channel -> Fragment("n.channel_id = ?", listOf(matcher.channelId))

        is Matcher.DerivedProperty -> {
            val parts = mutableListOf<String>()
            val args = mutableListOf<Any?>()
            matcher.isAudible?.let {
                parts += "n.is_audible = ?"
                args += if (it) 1 else 0
            }
            matcher.likelyHeadsup?.let {
                parts += "n.likely_headsup = ?"
                args += if (it) 1 else 0
            }
            matcher.isRemoved?.let {
                parts += if (it) "n.removed_at IS NOT NULL" else "n.removed_at IS NULL"
            }
            if (parts.isEmpty()) Fragment.ALWAYS_TRUE
            else Fragment(parts.joinToString(" AND "), args)
        }

        is Matcher.Flags -> {
            val parts = mutableListOf<String>()
            val args = mutableListOf<Any?>()
            if (matcher.requiredFlags != 0) {
                parts += "(n.flags & ?) = ?"
                args += matcher.requiredFlags
                args += matcher.requiredFlags
            }
            if (matcher.excludedFlags != 0) {
                parts += "(n.flags & ?) = 0"
                args += matcher.excludedFlags
            }
            if (parts.isEmpty()) Fragment.ALWAYS_TRUE
            else Fragment(parts.joinToString(" AND "), args)
        }

        is Matcher.Keyword -> {
            if (matcher.isRegex) {
                throw UnsupportedMatcherException("Keyword regex 無法 SQL 化（SQLite 無 REGEXP）")
            }
            val cols = matcher.fields.map {
                when (it) {
                    KeywordField.TITLE -> "n.title"
                    KeywordField.TEXT -> "n.text"
                    KeywordField.BIG_TEXT -> "n.big_text"
                    KeywordField.SUB_TEXT -> "n.sub_text"
                }
            }
            if (cols.isEmpty()) Fragment.ALWAYS_TRUE
            else {
                val pattern = "%" + escapeLike(matcher.pattern) + "%"
                val sql = cols.joinToString(" OR ") { "$it LIKE ? ESCAPE '\\'" }
                Fragment(sql, List(cols.size) { pattern })
            }
        }

        is Matcher.ChannelProperty -> {
            if (matcher.groupId != null) {
                // NotificationEntity 不存 channel group → list 篩選不支援
                throw UnsupportedMatcherException("ChannelProperty.groupId 無對應 column")
            }
            val parts = mutableListOf<String>()
            val args = mutableListOf<Any?>()
            matcher.minImportance?.let {
                parts += "n.importance >= ?"
                args += it
            }
            if (parts.isEmpty()) Fragment.ALWAYS_TRUE
            else Fragment(parts.joinToString(" AND "), args)
        }

        is Matcher.EventTypes -> {
            // 列表篩選的「事件型別」詮釋為「該通知曾發生過此型別事件」
            // Plan 2：notification_events 表 FK 改 notification_key（不再持有 notification_id Long）
            val placeholders = List(matcher.types.size) { "?" }.joinToString(",")
            Fragment(
                "EXISTS (SELECT 1 FROM notification_events e WHERE e.notification_key = n.notification_key AND e.event_type IN ($placeholders))",
                matcher.types.toList()
            )
        }

        is Matcher.Field -> {
            val def = FieldWhitelist.require(matcher.field)
            val col = "n.${def.column}"
            when (matcher.op) {
                FieldOp.IS_NULL -> Fragment("$col IS NULL", emptyList())
                FieldOp.IS_NOT_NULL -> Fragment("$col IS NOT NULL", emptyList())
                FieldOp.LIKE -> Fragment(
                    "$col LIKE ? ESCAPE '\\'",
                    listOf("%" + escapeLike(matcher.value.orEmpty()) + "%")
                )
                FieldOp.EQ -> Fragment("$col = ?", listOf(coerceValue(def.type, matcher.value)))
                FieldOp.NEQ -> Fragment("$col != ?", listOf(coerceValue(def.type, matcher.value)))
                FieldOp.LT -> Fragment("$col < ?", listOf(coerceValue(def.type, matcher.value)))
                FieldOp.LTE -> Fragment("$col <= ?", listOf(coerceValue(def.type, matcher.value)))
                FieldOp.GT -> Fragment("$col > ?", listOf(coerceValue(def.type, matcher.value)))
                FieldOp.GTE -> Fragment("$col >= ?", listOf(coerceValue(def.type, matcher.value)))
            }
        }
    }

    private fun coerceValue(type: FieldWhitelist.Type, raw: String?): Any? {
        if (raw == null) return null
        return when (type) {
            FieldWhitelist.Type.INT -> raw.toIntOrNull() ?: 0
            FieldWhitelist.Type.LONG -> raw.toLongOrNull() ?: 0L
            FieldWhitelist.Type.BOOL -> if (raw.equals("true", ignoreCase = true) || raw == "1") 1 else 0
            FieldWhitelist.Type.TEXT -> raw
        }
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
}
