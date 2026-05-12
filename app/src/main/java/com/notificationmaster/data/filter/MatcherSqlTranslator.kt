package com.notificationmaster.data.filter

import android.util.Log
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
 * Plan 2：target table = `notification_events`（alias `e`）。Matcher 中
 * `notification_events` 沒有對應 column 的部份（flags / importance / big_text /
 * sub_text / channel group / FieldWhitelist 內 events 缺欄的項目）一律
 * **退化 ALWAYS_TRUE 並 log warning**（Plan §F silent fail 決策的延伸），
 * 不再 throw UnsupportedMatcherException。
 *
 * Keyword 的 regex 模式仍無法 SQL 化，會 throw（in-memory 路徑可處理；
 * 列表呈現要支援需另外做 read-time 過濾）。
 */
object MatcherSqlTranslator {

    private const val TAG = "MatcherSqlTranslator"

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
        is Matcher.Package -> Fragment("e.package_name = ?", listOf(matcher.packageName))

        is Matcher.Channel -> Fragment("e.channel_id = ?", listOf(matcher.channelId))

        is Matcher.DerivedProperty -> {
            val parts = mutableListOf<String>()
            val args = mutableListOf<Any?>()
            matcher.isAudible?.let {
                parts += "e.is_audible = ?"
                args += if (it) 1 else 0
            }
            matcher.likelyHeadsup?.let {
                parts += "e.likely_headsup = ?"
                args += if (it) 1 else 0
            }
            matcher.isRemoved?.let {
                parts += if (it) "e.event_type = 'REMOVED'" else "e.event_type != 'REMOVED'"
            }
            if (parts.isEmpty()) Fragment.ALWAYS_TRUE
            else Fragment(parts.joinToString(" AND "), args)
        }

        is Matcher.Flags -> {
            // notification_events 不投影 flags column（flags 為 sbn 層級的屬性，read-time
            // 從 eventRawJson 解析）。SQL 路徑退化 ALWAYS_TRUE，呼叫端若需 flags 篩選
            // 應走 in-memory 路徑或補 NotificationSnapshot parse 後過濾。
            Log.w(TAG, "Flags matcher unsupported on events schema, falling back to ALWAYS_TRUE")
            Fragment.ALWAYS_TRUE
        }

        is Matcher.Keyword -> {
            if (matcher.isRegex) {
                throw UnsupportedMatcherException("Keyword regex 無法 SQL 化（SQLite 無 REGEXP）")
            }
            val cols = matcher.fields.mapNotNull {
                when (it) {
                    KeywordField.TITLE -> "e.title"
                    KeywordField.TEXT -> "e.text"
                    KeywordField.BIG_TEXT, KeywordField.SUB_TEXT -> {
                        // notification_events 只投影 title/text；big_text/sub_text 需要走 snapshot
                        Log.w(TAG, "Keyword field $it unsupported on events schema, ignored in SQL")
                        null
                    }
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
            // events 不投影 importance / channel groupId（這兩個是 channel meta，非 event 屬性）
            if (matcher.minImportance != null) {
                Log.w(TAG, "ChannelProperty.minImportance unsupported on events schema, ignored")
            }
            if (matcher.groupId != null) {
                Log.w(TAG, "ChannelProperty.groupId unsupported on events schema, ignored")
            }
            Fragment.ALWAYS_TRUE
        }

        is Matcher.EventTypes -> {
            // Plan 2：events 表本身有 event_type，直接 IN clause，不再需要 EXISTS subquery
            if (matcher.types.isEmpty()) Fragment.ALWAYS_TRUE
            else {
                val placeholders = List(matcher.types.size) { "?" }.joinToString(",")
                Fragment("e.event_type IN ($placeholders)", matcher.types.toList())
            }
        }

        is Matcher.Field -> {
            val def = FieldWhitelist.require(matcher.field)
            val col = eventsColumnFor(def.column)
            if (col == null) {
                Log.w(TAG, "Field matcher column '${def.column}' not in events schema, falling back to ALWAYS_TRUE")
                Fragment.ALWAYS_TRUE
            } else when (matcher.op) {
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

    /**
     * FieldWhitelist column name → notification_events 表上的對應 `e.<col>`。
     * 不在 events 表的 column 回傳 null（呼叫端退化 ALWAYS_TRUE）。
     */
    private fun eventsColumnFor(whitelistColumn: String): String? = when (whitelistColumn) {
        "post_time" -> "e.post_time"
        "capture_time" -> "e.capture_time"
        "event_time" -> "e.event_time"
        else -> null
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
