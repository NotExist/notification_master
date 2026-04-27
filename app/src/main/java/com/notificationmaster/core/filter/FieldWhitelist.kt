package com.notificationmaster.core.filter

/**
 * Matcher.Field 可使用的 NotificationEntity column 白名單。
 *
 * 每個項目對應 `notifications` 表的一個 column；型別決定可用的 [FieldOp]
 * 與比對時的 value coercion（IsNull/IsNotNull 不需 value）。
 *
 * UI 顯示名 = `field_<key>` string 資源（fallback = key 本身）。
 */
object FieldWhitelist {

    enum class Type { INT, LONG, BOOL, TEXT }

    data class Def(val column: String, val type: Type)

    val fields: Map<String, Def> = linkedMapOf(
        // 優先級 / 重要性
        "priority" to Def("priority", Type.INT),
        "importance" to Def("importance", Type.INT),
        "visibility" to Def("visibility", Type.INT),
        "category" to Def("category", Type.TEXT),

        // Bubble / intent（無對應 NotificationFlag）
        "has_bubble_metadata" to Def("has_bubble_metadata", Type.BOOL),
        "has_full_screen_intent" to Def("has_full_screen_intent", Type.BOOL),
        "has_content_intent" to Def("has_content_intent", Type.BOOL),

        // 時間
        "post_time" to Def("post_time", Type.LONG),
        "capture_time" to Def("capture_time", Type.LONG),
        "when_time" to Def("when_time", Type.LONG)

        // 註：is_ongoing / is_foreground_service / is_high_priority /
        // is_local_only / is_group_summary 已被「通知旗標」三態選擇器覆蓋，
        // 不在此白名單重複提供。
    )

    fun contains(field: String): Boolean = fields.containsKey(field)
    fun require(field: String): Def =
        fields[field] ?: throw IllegalArgumentException("Field not in whitelist: $field")
}

/**
 * Matcher.Field 的比較運算子。
 */
enum class FieldOp {
    EQ, NEQ, LT, LTE, GT, GTE, LIKE, IS_NULL, IS_NOT_NULL;

    companion object {
        fun fromName(name: String): FieldOp =
            entries.firstOrNull { it.name == name } ?: EQ
    }
}
