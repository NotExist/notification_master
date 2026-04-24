package com.notificationmaster.data.filter

import org.json.JSONObject

enum class PresetSource { SYSTEM, USER }

/**
 * 命名篩選組合：供 Timeline chip / Widget / Shortcut 共用。
 *
 * - name：唯一 ID + 顯示名稱（系統 preset 的 name 為常數字串）
 * - source：SYSTEM 內建不可刪除／重新命名
 */
data class FilterPreset(
    val name: String,
    val spec: EventFilterSpec,
    val createdAt: Long,
    val updatedAt: Long,
    val source: PresetSource
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("spec", spec.toJson())
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("source", source.name)
    }

    companion object {
        fun fromJson(json: JSONObject): FilterPreset = FilterPreset(
            name = json.getString("name"),
            spec = EventFilterSpec.fromJson(json.getJSONObject("spec")),
            createdAt = json.optLong("createdAt", 0L),
            updatedAt = json.optLong("updatedAt", 0L),
            source = PresetSource.valueOf(json.optString("source", PresetSource.USER.name))
        )
    }
}
