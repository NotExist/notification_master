package com.notificationmaster.data.filter

import com.notificationmaster.core.filter.KeywordField
import com.notificationmaster.core.filter.Matcher
import org.json.JSONArray
import org.json.JSONObject

/**
 * 通用事件篩選條件（Plan 1 — prowling-quiet-lynx）
 *
 * 模型：複用 RuleEngine 的 [Matcher]（`core/filter/Rule.kt`），加上列表顯示控制
 * （deduplicate / orderBy / limit / timeFrom / timeTo）。
 *
 * 兩條執行路徑共用此模型：
 * - **SQL 路徑**（[com.notificationmaster.data.db.dao.query]）：[MatcherSqlTranslator]
 *   把 matchers 轉成 WHERE fragment，下壓查詢
 * - **In-memory 路徑**（RuleEngine）：[Matcher.matches] 對單一 [com.notificationmaster.core.filter.MatchContext]
 */
data class EventFilterSpec(
    /** 篩選 matchers，AND 組合（與 Rule.matchers 相同語意） */
    val matchers: List<Matcher> = emptyList(),

    // === 列表顯示控制（不影響 in-memory 比對） ===
    val timeFrom: Long? = null,
    val timeTo: Long? = null,
    val deduplicate: Boolean = false,
    val orderBy: OrderBy = OrderBy.PostTimeDesc,
    val limit: Int? = null
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("matchers", JSONArray(matchers.map { it.toJson() }))
        if (timeFrom != null) put("timeFrom", timeFrom)
        if (timeTo != null) put("timeTo", timeTo)
        if (deduplicate) put("deduplicate", true)
        put("orderBy", orderBy.name)
        if (limit != null) put("limit", limit)
    }

    fun toJsonString(): String = toJson().toString()

    /** 是否為「全部」 */
    fun isAll(): Boolean =
        matchers.isEmpty() && timeFrom == null && timeTo == null &&
            !deduplicate && limit == null

    companion object {

        // === 系統 built-in preset（與 Shortcut 對齊） ===
        val RecentAudible = EventFilterSpec(
            matchers = listOf(Matcher.DerivedProperty(isAudible = true)),
            deduplicate = true,
            limit = 20
        )
        val RecentHeadsup = EventFilterSpec(
            matchers = listOf(Matcher.DerivedProperty(likelyHeadsup = true)),
            deduplicate = true,
            limit = 20
        )
        val RecentDismissed = EventFilterSpec(
            matchers = listOf(Matcher.DerivedProperty(isRemoved = true)),
            deduplicate = true,
            limit = 30
        )
        val Deduplicated = EventFilterSpec(deduplicate = true)
        val All = EventFilterSpec()

        fun fromJson(json: JSONObject): EventFilterSpec {
            val matchers = if (json.has("matchers") && !json.isNull("matchers")) {
                val arr = json.getJSONArray("matchers")
                (0 until arr.length()).map { Matcher.fromJson(arr.getJSONObject(it)) }
            } else emptyList()

            return EventFilterSpec(
                matchers = matchers,
                timeFrom = if (json.has("timeFrom") && !json.isNull("timeFrom")) json.getLong("timeFrom") else null,
                timeTo = if (json.has("timeTo") && !json.isNull("timeTo")) json.getLong("timeTo") else null,
                deduplicate = json.optBoolean("deduplicate", false),
                orderBy = OrderBy.fromName(json.optString("orderBy", OrderBy.PostTimeDesc.name)),
                limit = if (json.has("limit") && !json.isNull("limit")) json.getInt("limit") else null
            )
        }

        fun fromJsonString(json: String): EventFilterSpec = fromJson(JSONObject(json))
    }

    // === 與舊扁平欄位的相容讀取 helper（UI 層讀取常用欄位用） ===

    val packageName: String? get() = matchers.filterIsInstance<Matcher.Package>().firstOrNull()?.packageName
    val channelId: String? get() = matchers.filterIsInstance<Matcher.Channel>().firstOrNull()?.channelId
    val isAudible: Boolean? get() = derivedFlag { it.isAudible }
    val likelyHeadsup: Boolean? get() = derivedFlag { it.likelyHeadsup }
    val isRemoved: Boolean? get() = derivedFlag { it.isRemoved }
    val keyword: String? get() = matchers.filterIsInstance<Matcher.Keyword>().firstOrNull()
        ?.takeUnless { it.isRegex }?.pattern

    private inline fun derivedFlag(extract: (Matcher.DerivedProperty) -> Boolean?): Boolean? =
        matchers.filterIsInstance<Matcher.DerivedProperty>().firstNotNullOfOrNull(extract)
}

/** 從核心 chip 狀態建構簡單 spec（Timeline 用）。所有為 null 的欄位 = 不限。 */
fun coreFilterSpecOf(
    isAudible: Boolean? = null,
    likelyHeadsup: Boolean? = null,
    isRemoved: Boolean? = null,
    deduplicate: Boolean = false,
    timeFrom: Long? = null,
    timeTo: Long? = null
): EventFilterSpec {
    val matchers = mutableListOf<Matcher>()
    if (isAudible != null || likelyHeadsup != null || isRemoved != null) {
        matchers += Matcher.DerivedProperty(
            isAudible = isAudible,
            likelyHeadsup = likelyHeadsup,
            isRemoved = isRemoved
        )
    }
    return EventFilterSpec(
        matchers = matchers,
        deduplicate = deduplicate,
        timeFrom = timeFrom,
        timeTo = timeTo
    )
}

@Suppress("unused") // KeywordField 給將來 UI 編輯器使用
private val keywordFieldsAllText = setOf(
    KeywordField.TITLE, KeywordField.TEXT, KeywordField.BIG_TEXT, KeywordField.SUB_TEXT
)
