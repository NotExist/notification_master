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

        // Bubble / intent
        "has_bubble_metadata" to Def("has_bubble_metadata", Type.BOOL),
        "has_full_screen_intent" to Def("has_full_screen_intent", Type.BOOL),
        "has_content_intent" to Def("has_content_intent", Type.BOOL),

        // Flags 系列 booleans
        "is_ongoing" to Def("is_ongoing", Type.BOOL),
        "is_foreground_service" to Def("is_foreground_service", Type.BOOL),
        "is_high_priority" to Def("is_high_priority", Type.BOOL),
        "is_local_only" to Def("is_local_only", Type.BOOL),
        "is_group_summary" to Def("is_group_summary", Type.BOOL),

        // Flags int
        "flags" to Def("flags", Type.INT),

        // 時間
        "post_time" to Def("post_time", Type.LONG),
        "capture_time" to Def("capture_time", Type.LONG),
        "when_time" to Def("when_time", Type.LONG)
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
