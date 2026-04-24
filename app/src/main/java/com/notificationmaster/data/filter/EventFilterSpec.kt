package com.notificationmaster.data.filter

import org.json.JSONArray
import org.json.JSONObject

/**
 * 通用事件篩選條件（Plan 1 — prowling-quiet-lynx）
 *
 * 表達層：與資料模型耦合度低。第一版對應 `notifications` 表；
 * Plan 2（flickering-snuggling-tiger）完成後內部翻譯改對應 `notification_events`，
 * spec 表達契約不變。
 */
data class EventFilterSpec(
    // 內容 / 識別
    val packageName: String? = null,
    val channelId: String? = null,
    val notificationKey: String? = null,

    // 行為篩選
    val isAudible: Boolean? = null,
    val likelyHeadsup: Boolean? = null,
    val isRemoved: Boolean? = null,

    // 內容搜尋（LIKE on title/text/bigText/subText + media filename JOIN）
    val keyword: String? = null,

    // 時間範圍
    val timeFrom: Long? = null,
    val timeTo: Long? = null,

    // 視圖控制
    val deduplicate: Boolean = false,
    val orderBy: OrderBy = OrderBy.PostTimeDesc,
    val limit: Int? = null,

    // 開放槽：spec 之外的欄位
    val extraPredicates: List<FieldPredicate> = emptyList()
) {

    fun toJson(): JSONObject = JSONObject().apply {
        if (packageName != null) put("packageName", packageName)
        if (channelId != null) put("channelId", channelId)
        if (notificationKey != null) put("notificationKey", notificationKey)
        if (isAudible != null) put("isAudible", isAudible)
        if (likelyHeadsup != null) put("likelyHeadsup", likelyHeadsup)
        if (isRemoved != null) put("isRemoved", isRemoved)
        if (keyword != null) put("keyword", keyword)
        if (timeFrom != null) put("timeFrom", timeFrom)
        if (timeTo != null) put("timeTo", timeTo)
        if (deduplicate) put("deduplicate", true)
        put("orderBy", orderBy.name)
        if (limit != null) put("limit", limit)
        if (extraPredicates.isNotEmpty()) {
            put("extraPredicates", JSONArray(extraPredicates.map { it.toJson() }))
        }
    }

    fun toJsonString(): String = toJson().toString()

    /** 是否為「全部」（無任何篩選），UI 用以判斷核心 chip 全部未勾選狀態 */
    fun isAll(): Boolean =
        packageName == null && channelId == null && notificationKey == null &&
            isAudible == null && likelyHeadsup == null && isRemoved == null &&
            keyword == null && timeFrom == null && timeTo == null &&
            !deduplicate && extraPredicates.isEmpty() && limit == null

    companion object {

        // === 系統 built-in preset（與現有 Shortcut 對齊） ===
        val RecentAudible = EventFilterSpec(isAudible = true, deduplicate = true, limit = 20)
        val RecentHeadsup = EventFilterSpec(likelyHeadsup = true, deduplicate = true, limit = 20)
        val RecentDismissed = EventFilterSpec(isRemoved = true, deduplicate = true, limit = 30)
        val Deduplicated = EventFilterSpec(deduplicate = true)
        val All = EventFilterSpec()

        fun fromJson(json: JSONObject): EventFilterSpec {
            val extras = if (json.has("extraPredicates") && !json.isNull("extraPredicates")) {
                val arr = json.getJSONArray("extraPredicates")
                (0 until arr.length()).map { FieldPredicate.fromJson(arr.getJSONObject(it)) }
            } else emptyList()

            return EventFilterSpec(
                packageName = json.optStringOrNull("packageName"),
                channelId = json.optStringOrNull("channelId"),
                notificationKey = json.optStringOrNull("notificationKey"),
                isAudible = json.optBooleanOrNull("isAudible"),
                likelyHeadsup = json.optBooleanOrNull("likelyHeadsup"),
                isRemoved = json.optBooleanOrNull("isRemoved"),
                keyword = json.optStringOrNull("keyword"),
                timeFrom = json.optLongOrNull("timeFrom"),
                timeTo = json.optLongOrNull("timeTo"),
                deduplicate = json.optBoolean("deduplicate", false),
                orderBy = OrderBy.fromName(json.optString("orderBy", OrderBy.PostTimeDesc.name)),
                limit = json.optIntOrNull("limit"),
                extraPredicates = extras
            )
        }

        fun fromJsonString(json: String): EventFilterSpec = fromJson(JSONObject(json))
    }
}

// === JSONObject 小工具：optString/optBoolean/opt* 對 null 的友善版本 ===

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
    if (has(key) && !isNull(key)) getBoolean(key) else null

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) getLong(key) else null

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) getInt(key) else null
