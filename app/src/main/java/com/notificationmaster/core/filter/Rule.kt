package com.notificationmaster.core.filter

import com.notificationmaster.R
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
    AUTO_DISMISS,
    PERSISTENT_ALERT,
    CLIPBOARD_COPY,
    /** 列表篩選用（Timeline / Widget / Shortcut 顯示），不參與 Service 觸發流程 */
    LIST_FILTER;

    /** 此 ActionType 允許的事件類型（UI checkbox 與預覽匹配共用） */
    val allowedEventTypes: Set<EventType>
        get() = when (this) {
            SKIP_RECORD -> EventType.entries.toSet()
            CALENDAR_EXPORT -> setOf(EventType.POSTED, EventType.UPDATED, EventType.REMOVED)
            AUTO_DISMISS -> EventType.entries.toSet()
            PERSISTENT_ALERT -> setOf(EventType.POSTED, EventType.UPDATED)
            CLIPBOARD_COPY -> setOf(EventType.POSTED, EventType.UPDATED)
            LIST_FILTER -> EventType.entries.toSet()
        }
}

/**
 * 列表排序方式（給 [RuleAction.ListFilter] 使用）
 */
enum class OrderBy {
    PostTimeDesc, PostTimeAsc,
    CaptureTimeDesc, CaptureTimeAsc,
    EventTimeDesc, EventTimeAsc;

    companion object {
        fun fromName(name: String): OrderBy = entries.firstOrNull { it.name == name } ?: PostTimeDesc
    }
}

/**
 * Keyword 匹配欄位
 */
enum class KeywordField {
    TITLE, TEXT, BIG_TEXT, SUB_TEXT
}

/**
 * Notification.flags bit flag 定義（供 UI 顯示用）
 */
enum class NotificationFlag(val bit: Int, val labelResId: Int) {
    ONGOING(0x02, R.string.flag_ongoing),
    AUTO_CANCEL(0x10, R.string.flag_auto_cancel),
    NO_CLEAR(0x20, R.string.flag_no_clear),
    FOREGROUND_SERVICE(0x40, R.string.flag_foreground_service),
    HIGH_PRIORITY(0x80, R.string.flag_high_priority),
    LOCAL_ONLY(0x100, R.string.flag_local_only),
    GROUP_SUMMARY(0x200, R.string.flag_group_summary)
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
    val channelGroupId: String? = null,
    // Flags matcher 用
    val flags: Int? = null,
    // DerivedProperty matcher 用
    val isAudible: Boolean? = null,
    val likelyHeadsup: Boolean? = null,
    val isRemoved: Boolean? = null,
    /** Field matcher 動態欄位映射（in-memory 比對用；Service 暫不填，fail-open） */
    val fieldValues: Map<String, Any?>? = null
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
            val et = context.eventType
            if (et == null) {
                System.err.println("RuleEngine: EventTypes.matches(): eventType is null, rejecting match")
                return false
            }
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

    /**
     * 依 Notification.flags bitmask 匹配
     *
     * requiredFlags 中的 bit 必須全部被設定，excludedFlags 中的 bit 必須全部未被設定。
     * 兩者不可重疊。全為 0 = 全部不限，永遠通過。
     */
    data class Flags(
        val requiredFlags: Int = 0,
        val excludedFlags: Int = 0
    ) : Matcher {
        init {
            require(requiredFlags and excludedFlags == 0) {
                "requiredFlags and excludedFlags must not overlap"
            }
        }

        override fun matches(context: MatchContext): Boolean {
            val f = context.flags ?: return false
            if (requiredFlags != 0 && (f and requiredFlags) != requiredFlags) return false
            if (excludedFlags != 0 && (f and excludedFlags) != 0) return false
            return true
        }

        override fun toJson() = JSONObject().apply {
            put("type", "Flags")
            put("requiredFlags", requiredFlags)
            put("excludedFlags", excludedFlags)
        }
    }

    /**
     * 通用欄位匹配（NotificationEntity column）— 主要用於 LIST_FILTER rule
     * 透過 SQL 路徑執行；in-memory 路徑（RuleEngine 對 Service event）對未填值
     * 的欄位永遠通過（fail-open），避免影響其他 ActionType 流程。
     *
     * 欄位需在 [FieldWhitelist] 內。op = IS_NULL / IS_NOT_NULL 不需 value。
     */
    data class Field(
        val field: String,
        val op: FieldOp,
        val value: String? = null
    ) : Matcher {
        init { FieldWhitelist.require(field) }

        override fun matches(context: MatchContext): Boolean {
            // in-memory：Service 不維護任意欄位值；fail-open（依賴 SQL 篩選）
            val v = context.fieldValues?.get(field) ?: return true
            return when (op) {
                FieldOp.IS_NULL -> v == null
                FieldOp.IS_NOT_NULL -> v != null
                FieldOp.EQ -> v.toString() == value
                FieldOp.NEQ -> v.toString() != value
                FieldOp.LIKE -> v.toString().contains(value.orEmpty(), ignoreCase = true)
                FieldOp.LT -> compareNum(v, value) < 0
                FieldOp.LTE -> compareNum(v, value) <= 0
                FieldOp.GT -> compareNum(v, value) > 0
                FieldOp.GTE -> compareNum(v, value) >= 0
            }
        }

        private fun compareNum(ctxValue: Any?, value: String?): Int {
            val a = (ctxValue as? Number)?.toLong() ?: ctxValue.toString().toLongOrNull() ?: 0L
            val b = value?.toLongOrNull() ?: 0L
            return a.compareTo(b)
        }

        override fun toJson() = JSONObject().apply {
            put("type", "Field")
            put("field", field)
            put("op", op.name)
            if (value != null) put("value", value)
        }
    }

    /**
     * 依推斷屬性匹配（isAudible / likelyHeadsup / isRemoved）
     *
     * 每個欄位：true = 必須, false = 排除, null = 不限。
     * 全為 null = 永遠通過。
     */
    data class DerivedProperty(
        val isAudible: Boolean? = null,
        val likelyHeadsup: Boolean? = null,
        val isRemoved: Boolean? = null
    ) : Matcher {
        override fun matches(context: MatchContext): Boolean {
            if (isAudible != null && context.isAudible != isAudible) return false
            if (likelyHeadsup != null && context.likelyHeadsup != likelyHeadsup) return false
            if (isRemoved != null && context.isRemoved != isRemoved) return false
            return true
        }

        override fun toJson() = JSONObject().apply {
            put("type", "DerivedProperty")
            put("isAudible", isAudible ?: JSONObject.NULL)
            put("likelyHeadsup", likelyHeadsup ?: JSONObject.NULL)
            put("isRemoved", isRemoved ?: JSONObject.NULL)
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
            "Flags" -> Flags(
                requiredFlags = json.optInt("requiredFlags", 0),
                excludedFlags = json.optInt("excludedFlags", 0)
            )
            "DerivedProperty" -> DerivedProperty(
                isAudible = if (json.isNull("isAudible")) null else json.getBoolean("isAudible"),
                likelyHeadsup = if (json.isNull("likelyHeadsup")) null else json.getBoolean("likelyHeadsup"),
                isRemoved = if (json.isNull("isRemoved")) null else json.getBoolean("isRemoved")
            )
            "Field" -> Field(
                field = json.getString("field"),
                op = FieldOp.fromName(json.getString("op")),
                value = if (json.has("value") && !json.isNull("value")) json.getString("value") else null
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

    /**
     * 日曆匯出白名單（原 CALENDAR_EXPORT）。
     *
     * `calendarId` 為每條規則自帶的目標日曆。null 表示「未配置」（例如從舊版備份匯入），
     * service 端視為「目標消失」跳過。
     */
    data class CalendarExport(val calendarId: Long? = null) : RuleAction {
        override val actionType = ActionType.CALENDAR_EXPORT
        override fun toJson() = JSONObject().apply {
            put("type", "CalendarExport")
            put("calendarId", calendarId ?: JSONObject.NULL)
        }
    }

    /** 自動清除通知（原 AUTO_DISMISS） */
    data class AutoDismiss(val delayMs: Long = 0) : RuleAction {
        override val actionType = ActionType.AUTO_DISMISS
        override fun toJson() = JSONObject().apply {
            put("type", "AutoDismiss")
            put("delayMs", delayMs)
        }
    }

    /** 持續提醒（PERSISTENT_ALERT） */
    data class PersistentAlert(
        val soundUri: String? = null,  // null = 系統預設鬧鐘鈴聲
        val vibrate: Boolean = true,
        val audioStream: String = STREAM_ALARM  // "alarm" 或 "notification"
    ) : RuleAction {
        companion object {
            const val STREAM_ALARM = "alarm"
            const val STREAM_NOTIFICATION = "notification"
        }
        override val actionType = ActionType.PERSISTENT_ALERT
        override fun toJson() = JSONObject().apply {
            put("type", "PersistentAlert")
            put("soundUri", soundUri ?: JSONObject.NULL)
            put("vibrate", vibrate)
            put("audioStream", audioStream)
        }
    }

    /** 複製到剪貼簿（CLIPBOARD_COPY） */
    data object ClipboardCopy : RuleAction {
        override val actionType = ActionType.CLIPBOARD_COPY
        override fun toJson() = JSONObject().apply { put("type", "ClipboardCopy") }
    }

    /**
     * 列表篩選顯示控制（LIST_FILTER）— 不參與 Service 觸發。
     *
     * Timeline / Widget / Shortcut 取此 rule 的 matchers 做 SQL 篩選，
     * 並用此 action 的 orderBy/limit/deduplicate/timeRange 控制呈現。
     */
    data class ListFilter(
        val orderBy: OrderBy = OrderBy.PostTimeDesc,
        val limit: Int? = null,
        val deduplicate: Boolean = false,
        val timeFrom: Long? = null,
        val timeTo: Long? = null
    ) : RuleAction {
        override val actionType = ActionType.LIST_FILTER
        override fun toJson() = JSONObject().apply {
            put("type", "ListFilter")
            put("orderBy", orderBy.name)
            put("limit", limit ?: JSONObject.NULL)
            put("deduplicate", deduplicate)
            put("timeFrom", timeFrom ?: JSONObject.NULL)
            put("timeTo", timeTo ?: JSONObject.NULL)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): RuleAction = when (val type = json.getString("type")) {
            "SkipRecord" -> SkipRecord
            "CalendarExport" -> CalendarExport(
                calendarId = if (json.isNull("calendarId")) null else json.getLong("calendarId")
            )
            "AutoDismiss" -> AutoDismiss(json.optLong("delayMs", 0))
            "PersistentAlert" -> PersistentAlert(
                soundUri = if (json.isNull("soundUri")) null else json.getString("soundUri"),
                vibrate = json.optBoolean("vibrate", true),
                audioStream = json.optString("audioStream", PersistentAlert.STREAM_ALARM)
            )
            "ClipboardCopy" -> ClipboardCopy
            "ListFilter" -> ListFilter(
                orderBy = OrderBy.fromName(json.optString("orderBy", OrderBy.PostTimeDesc.name)),
                limit = if (json.isNull("limit")) null else json.getInt("limit"),
                deduplicate = json.optBoolean("deduplicate", false),
                timeFrom = if (json.isNull("timeFrom")) null else json.getLong("timeFrom"),
                timeTo = if (json.isNull("timeTo")) null else json.getLong("timeTo")
            )
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
    val createdAt: Long = System.currentTimeMillis(),
    /** 顯示名稱（LIST_FILTER 用於 chip 顯示；其他 ActionType 可選） */
    val name: String? = null,
    /** 內建 rule 不可刪除（系統預先安排的列表篩選用） */
    val isBuiltIn: Boolean = false
) {
    /** 所有 matcher 都通過才算匹配 */
    fun matches(context: MatchContext): Boolean =
        matchers.all { it.matches(context) }

    /** 是否為 channel 級規則（含有 Channel matcher） */
    val isChannelLevel: Boolean
        get() = matchers.any { it is Matcher.Channel }

    /**
     * 取得 Package matcher 的 packageName。
     *
     * 既有 ActionType（SkipRecord 等）一定有 Package matcher → 呼叫端可期待非 null；
     * LIST_FILTER 規則可能無 Package matcher → 對該類別請改用 firstOrNull pattern。
     */
    val packageName: String?
        get() = matchers.filterIsInstance<Matcher.Package>().firstOrNull()?.packageName

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
        if (name != null) put("name", name)
        if (isBuiltIn) put("isBuiltIn", true)
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
                createdAt = json.optLong("createdAt", 0L),
                name = if (json.has("name") && !json.isNull("name")) json.getString("name") else null,
                isBuiltIn = json.optBoolean("isBuiltIn", false)
            )
        }
    }
}
