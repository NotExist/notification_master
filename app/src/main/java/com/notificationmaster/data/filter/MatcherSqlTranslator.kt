package com.notificationmaster.data.filter

import android.os.Build
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
 * Plan 2 / Phase 12：target table = `notification_events`（alias `e`）。
 * - **`Matcher.Package` / `Channel` / `EventTypes` / `DerivedProperty`**：events 表已投影，純 column 比較
 * - **`Matcher.ChannelProperty`**：events 表無 channel meta，但用 correlated subquery 走 `channels` 表（API 21+）
 * - **`Matcher.Flags`**：events 表無 flags column。API 27+ 用 `json_extract` 從 `event_raw_json` 取；
 *   API <27 退化 ALWAYS_TRUE（SQLite 3.18+ 才支援 json_extract，對應 Android API 27+；
 *   呼叫端應透過 [requiresJsonExtract] 預先檢查並在 UI disable）
 * - **`Matcher.Keyword(BIG_TEXT/SUB_TEXT)`**：同 Flags，API 27+ 用 json_extract，API <27 退化
 * - **`Matcher.Field`**：未投影欄位退化 ALWAYS_TRUE（FieldWhitelist 已限制到 events 表有的 column）
 * - **Keyword regex**：仍無法 SQL 化（SQLite 無 REGEXP），呼叫端走 in-memory 路徑
 */
object MatcherSqlTranslator {

    private const val TAG = "MatcherSqlTranslator"

    /** SQLite 3.18+（Android API 27+）才支援 json_extract */
    private const val MIN_JSON_EXTRACT_API = 27

    data class Fragment(val sql: String, val args: List<Any?>) {
        companion object {
            val ALWAYS_TRUE = Fragment("1=1", emptyList())
        }
    }

    class UnsupportedMatcherException(msg: String) : IllegalArgumentException(msg)

    private const val LIKE_ESCAPE = '\\'

    /** 當前裝置 SQLite 是否支援 json_extract（API 27+） */
    val supportsJsonExtract: Boolean
        get() = Build.VERSION.SDK_INT >= MIN_JSON_EXTRACT_API

    /**
     * UI 預檢用：matcher 是否需要 json_extract 才能完整 SQL 化。
     * 若 [supportsJsonExtract] 為 false 且本回傳 true，呼叫端應在 UI disable 該條件。
     */
    fun requiresJsonExtract(matcher: Matcher): Boolean = when (matcher) {
        is Matcher.Flags -> matcher.requiredFlags != 0 || matcher.excludedFlags != 0
        is Matcher.Keyword -> matcher.fields.any {
            it == KeywordField.BIG_TEXT || it == KeywordField.SUB_TEXT
        }
        else -> false
    }

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

        is Matcher.Flags -> translateFlags(matcher)

        is Matcher.Keyword -> translateKeyword(matcher)

        is Matcher.ChannelProperty -> translateChannelProperty(matcher)

        is Matcher.EventTypes -> {
            // Plan 2：events 表本身有 event_type，直接 IN clause
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
     * Flags matcher：API 27+ 用 json_extract 從 `$.sbn.notification.flags` 取出 bitmask；
     * API <27 退化 ALWAYS_TRUE（UI 端應 disable 防止建到無效規則）。
     */
    private fun translateFlags(matcher: Matcher.Flags): Fragment {
        if (matcher.requiredFlags == 0 && matcher.excludedFlags == 0) return Fragment.ALWAYS_TRUE
        if (!supportsJsonExtract) {
            Log.w(TAG, "Flags matcher requires json_extract (API 27+), falling back to ALWAYS_TRUE on API ${Build.VERSION.SDK_INT}")
            return Fragment.ALWAYS_TRUE
        }
        val parts = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        val flagsExpr = "CAST(json_extract(e.event_raw_json, '$.sbn.notification.flags') AS INTEGER)"
        if (matcher.requiredFlags != 0) {
            // required flags 中的每個 bit 都必須被設定
            parts += "($flagsExpr & ?) = ?"
            args += matcher.requiredFlags
            args += matcher.requiredFlags
        }
        if (matcher.excludedFlags != 0) {
            // excluded flags 中的任一 bit 都不可被設定
            parts += "($flagsExpr & ?) = 0"
            args += matcher.excludedFlags
        }
        return Fragment(parts.joinToString(" AND "), args)
    }

    /**
     * Keyword matcher：TITLE/TEXT 走投影 column，BIG_TEXT/SUB_TEXT 在 API 27+ 走 json_extract、
     * API <27 從 SQL 過濾條件中忽略（UI 端應 disable 對應 checkbox）。
     */
    private fun translateKeyword(matcher: Matcher.Keyword): Fragment {
        if (matcher.isRegex) {
            throw UnsupportedMatcherException("Keyword regex 無法 SQL 化（SQLite 無 REGEXP）")
        }
        data class FieldExpr(val expr: String)
        val exprs = matcher.fields.mapNotNull { field ->
            when (field) {
                KeywordField.TITLE -> FieldExpr("e.title")
                KeywordField.TEXT -> FieldExpr("e.text")
                KeywordField.BIG_TEXT -> {
                    if (supportsJsonExtract) {
                        FieldExpr("json_extract(e.event_raw_json, '$.sbn.notification.extras.\"android.bigText\"')")
                    } else {
                        Log.w(TAG, "Keyword field BIG_TEXT requires json_extract (API 27+), ignored on API ${Build.VERSION.SDK_INT}")
                        null
                    }
                }
                KeywordField.SUB_TEXT -> {
                    if (supportsJsonExtract) {
                        FieldExpr("json_extract(e.event_raw_json, '$.sbn.notification.extras.\"android.subText\"')")
                    } else {
                        Log.w(TAG, "Keyword field SUB_TEXT requires json_extract (API 27+), ignored on API ${Build.VERSION.SDK_INT}")
                        null
                    }
                }
            }
        }
        if (exprs.isEmpty()) return Fragment.ALWAYS_TRUE
        val pattern = "%" + escapeLike(matcher.pattern) + "%"
        val sql = exprs.joinToString(" OR ") { "${it.expr} LIKE ? ESCAPE '\\'" }
        return Fragment(sql, List(exprs.size) { pattern })
    }

    /**
     * ChannelProperty matcher：channels 表有 importance / group_id column，用 correlated subquery
     * 從 events 對應 (package_name, channel_id) 查 channels 表。API 21+ 全支援。
     *
     * 注意：events.channel_id IS NULL 或 channels 表無對應 row 時，subquery 回傳 NULL，
     * 比較永遠為 false → 自然過濾掉 pre-API-26 通知或 channel meta 尚未補齊的 row。
     */
    private fun translateChannelProperty(matcher: Matcher.ChannelProperty): Fragment {
        val parts = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        if (matcher.minImportance != null) {
            parts += "(SELECT importance FROM channels WHERE package_name = e.package_name AND channel_id = e.channel_id) >= ?"
            args += matcher.minImportance
        }
        if (matcher.groupId != null) {
            parts += "(SELECT group_id FROM channels WHERE package_name = e.package_name AND channel_id = e.channel_id) = ?"
            args += matcher.groupId
        }
        return if (parts.isEmpty()) Fragment.ALWAYS_TRUE
        else Fragment(parts.joinToString(" AND "), args)
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
