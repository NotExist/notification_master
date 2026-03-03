package com.notificationmaster.core.filter

import com.notificationmaster.data.db.entity.EventType
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 處置行為類型
 */
enum class ActionType {
    SKIP_RECORD,
    CALENDAR_EXPORT,
    AUTO_DISMISS
}

/**
 * Keyword 匹配欄位
 */
enum class KeywordField {
    TITLE, TEXT, BIG_TEXT, SUB_TEXT
}

/**
 * 匹配上下文：封裝通知的各項屬性供 Matcher 判斷
 */
data class MatchContext(
    val packageName: String,
    val channelId: String?,
    val eventType: EventType? = null,
    // 內容欄位（KeywordMatcher 需要）
    val title: String? = null,
    val text: String? = null,
    val bigText: String? = null,
    val subText: String? = null,
    // Channel 屬性（ChannelPropertyMatcher 需要）
    val channelImportance: Int? = null,
    val channelGroupId: String? = null
)

/**
 * 匹配條件（sealed interface）
 *
 * 一條 Rule 內的多個 Matcher 以 AND 邏輯組合。
 */
sealed interface Matcher {

    fun matches(context: MatchContext): Boolean
    fun toJson(): JSONObject

    /** 精確匹配 packageName */
    data class Package(val packageName: String) : Matcher {
        override fun matches(context: MatchContext) = context.packageName == packageName
        override fun toJson() = JSONObject().apply {
            put("type", "Package")
            put("packageName", packageName)
        }
    }

    /** 精確匹配 channelId */
    data class Channel(val channelId: String) : Matcher {
        override fun matches(context: MatchContext) = context.channelId == channelId
        override fun toJson() = JSONObject().apply {
            put("type", "Channel")
            put("channelId", channelId)
        }
    }

    /** 匹配事件類型集合 */
    data class EventTypes(val types: Set<String>) : Matcher {
        override fun matches(context: MatchContext): Boolean {
            val et = context.eventType ?: return true  // 未提供時視為通過
            return et.name in types
        }

        override fun toJson() = JSONObject().apply {
            put("type", "EventTypes")
            put("types", JSONArray(types.toList()))
        }
    }

    /** 子字串或 regex 匹配通知內容 */
    data class Keyword(
        val pattern: String,
        val fields: Set<KeywordField>,
        val isRegex: Boolean = false
    ) : Matcher {

        private val compiledRegex: Regex? by lazy {
            if (isRegex) {
                try { Regex(pattern) } catch (_: Exception) { null }
            } else null
        }

        override fun matches(context: MatchContext): Boolean {
            val textsToCheck = fields.mapNotNull { field ->
                when (field) {
                    KeywordField.TITLE -> context.title
                    KeywordField.TEXT -> context.text
                    KeywordField.BIG_TEXT -> context.bigText
                    KeywordField.SUB_TEXT -> context.subText
                }
            }
            if (textsToCheck.isEmpty()) return false

            return if (isRegex) {
                val regex = compiledRegex ?: return false
                textsToCheck.any { regex.containsMatchIn(it) }
            } else {
                textsToCheck.any { it.contains(pattern, ignoreCase = true) }
            }
        }

        override fun toJson() = JSONObject().apply {
            put("type", "Keyword")
            put("pattern", pattern)
            put("fields", JSONArray(fields.map { it.name }))
            put("isRegex", isRegex)
        }
    }

    /** 依 channel 屬性匹配 */
    data class ChannelProperty(
        val minImportance: Int? = null,
        val groupId: String? = null
    ) : Matcher {
        override fun matches(context: MatchContext): Boolean {
            if (minImportance != null) {
                val importance = context.channelImportance ?: return false
                if (importance < minImportance) return false
            }
            if (groupId != null) {
                if (context.channelGroupId != groupId) return false
            }
            return true
        }

        override fun toJson() = JSONObject().apply {
            put("type", "ChannelProperty")
            put("minImportance", minImportance ?: JSONObject.NULL)
            put("groupId", groupId ?: JSONObject.NULL)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): Matcher = when (val type = json.getString("type")) {
            "Package" -> Package(json.getString("packageName"))
            "Channel" -> Channel(json.getString("channelId"))
            "EventTypes" -> {
                val arr = json.getJSONArray("types")
                EventTypes((0 until arr.length()).map { arr.getString(it) }.toSet())
            }
            "Keyword" -> {
                val fieldsArr = json.getJSONArray("fields")
                val fields = (0 until fieldsArr.length()).map {
                    KeywordField.valueOf(fieldsArr.getString(it))
                }.toSet()
                Keyword(
                    pattern = json.getString("pattern"),
                    fields = fields,
                    isRegex = json.optBoolean("isRegex", false)
                )
            }
            "ChannelProperty" -> ChannelProperty(
                minImportance = if (json.isNull("minImportance")) null else json.getInt("minImportance"),
                groupId = if (json.isNull("groupId")) null else json.getString("groupId")
            )
            else -> throw IllegalArgumentException("Unknown matcher type: $type")
        }
    }
}

/**
 * 處置行為（sealed interface）
 */
sealed interface RuleAction {

    val actionType: ActionType
    fun toJson(): JSONObject

    /** 不記錄通知（原 NOTIFICATION 黑名單） */
    data object SkipRecord : RuleAction {
        override val actionType = ActionType.SKIP_RECORD
        override fun toJson() = JSONObject().apply { put("type", "SkipRecord") }
    }

    /** 日曆匯出白名單（原 CALENDAR_EXPORT） */
    data object CalendarExport : RuleAction {
        override val actionType = ActionType.CALENDAR_EXPORT
        override fun toJson() = JSONObject().apply { put("type", "CalendarExport") }
    }

    /** 自動清除通知（原 AUTO_DISMISS） */
    data class AutoDismiss(val delayMs: Long = 0) : RuleAction {
        override val actionType = ActionType.AUTO_DISMISS
        override fun toJson() = JSONObject().apply {
            put("type", "AutoDismiss")
            put("delayMs", delayMs)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): RuleAction = when (val type = json.getString("type")) {
            "SkipRecord" -> SkipRecord
            "CalendarExport" -> CalendarExport
            "AutoDismiss" -> AutoDismiss(json.optLong("delayMs", 0))
            else -> throw IllegalArgumentException("Unknown action type: $type")
        }
    }
}

/**
 * 過濾規則 = 匹配條件列表 + 處置行為
 *
 * 所有 matcher 以 AND 邏輯組合；通過全部才算匹配。
 */
data class Rule(
    val id: String = UUID.randomUUID().toString(),
    val matchers: List<Matcher>,
    val action: RuleAction,
    val createdAt: Long = System.currentTimeMillis()
) {
    /** 所有 matcher 都通過才算匹配 */
    fun matches(context: MatchContext): Boolean =
        matchers.all { it.matches(context) }

    /** 是否為 channel 級規則（含有 Channel matcher） */
    val isChannelLevel: Boolean
        get() = matchers.any { it is Matcher.Channel }

    /** 取得 Package matcher 的 packageName */
    val packageName: String
        get() = matchers.filterIsInstance<Matcher.Package>().first().packageName

    /** 取得 Channel matcher 的 channelId（package 級規則回傳 null） */
    val channelId: String?
        get() = matchers.filterIsInstance<Matcher.Channel>().firstOrNull()?.channelId

    /** 取得 EventTypes matcher 的類型集合 */
    val eventTypes: Set<String>
        get() = matchers.filterIsInstance<Matcher.EventTypes>().firstOrNull()?.types ?: emptySet()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("matchers", JSONArray(matchers.map { it.toJson() }))
        put("action", action.toJson())
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(json: JSONObject): Rule {
            val matchersArr = json.getJSONArray("matchers")
            val matchers = (0 until matchersArr.length()).map {
                Matcher.fromJson(matchersArr.getJSONObject(it))
            }
            return Rule(
                id = json.getString("id"),
                matchers = matchers,
                action = RuleAction.fromJson(json.getJSONObject("action")),
                createdAt = json.optLong("createdAt", 0L)
            )
        }

        /**
         * 從 v1 FilterRule + FilterCategory 建構 Rule（遷移用）
         */
        fun fromV1(v1Rule: FilterRule, category: FilterCategory): Rule {
            val matchers = mutableListOf<Matcher>(Matcher.Package(v1Rule.packageName))
            if (v1Rule.channelId != null) {
                matchers.add(Matcher.Channel(v1Rule.channelId))
            }
            if (v1Rule.eventTypes.isNotEmpty()) {
                matchers.add(Matcher.EventTypes(v1Rule.eventTypes))
            }

            val action: RuleAction = when (category) {
                FilterCategory.NOTIFICATION -> RuleAction.SkipRecord
                FilterCategory.CALENDAR_EXPORT -> RuleAction.CalendarExport
                FilterCategory.AUTO_DISMISS -> RuleAction.AutoDismiss(v1Rule.dismissDelayMs)
            }

            return Rule(
                id = v1Rule.id,
                matchers = matchers,
                action = action,
                createdAt = v1Rule.createdAt
            )
        }
    }
}
