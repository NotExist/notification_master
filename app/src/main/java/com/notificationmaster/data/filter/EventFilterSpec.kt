package com.notificationmaster.data.filter

import com.notificationmaster.core.filter.ActionType
import com.notificationmaster.core.filter.KeywordField
import com.notificationmaster.core.filter.Matcher
import com.notificationmaster.core.filter.OrderBy
import com.notificationmaster.core.filter.Rule
import com.notificationmaster.core.filter.RuleAction

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

    /** 是否為「全部」 */
    fun isAll(): Boolean =
        matchers.isEmpty() && timeFrom == null && timeTo == null &&
            !deduplicate && limit == null

    companion object {

        /** Timeline 初始 spec（去重模式，無其他 matcher） */
        val Deduplicated = EventFilterSpec(deduplicate = true)
        /** 全部通知（無篩選） */
        val All = EventFilterSpec()
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

/**
 * 將 LIST_FILTER Rule 轉為 [EventFilterSpec]（合併 matchers 與 ListFilter action 中的顯示控制）。
 */
fun Rule.toFilterSpec(): EventFilterSpec {
    val listFilter = action as? RuleAction.ListFilter
        ?: throw IllegalArgumentException("Rule is not LIST_FILTER: ${action.actionType}")
    return EventFilterSpec(
        matchers = matchers,
        orderBy = listFilter.orderBy,
        limit = listFilter.limit,
        deduplicate = listFilter.deduplicate,
        timeFrom = listFilter.timeFrom,
        timeTo = listFilter.timeTo
    )
}

fun Rule.isListFilter(): Boolean = action.actionType == ActionType.LIST_FILTER
