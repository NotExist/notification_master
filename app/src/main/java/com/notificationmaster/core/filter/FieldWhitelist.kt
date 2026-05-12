package com.notificationmaster.core.filter

/**
 * Matcher.Field 可使用的 column 白名單（Plan 2 Phase 9-7：對齊 notification_events 表投影）。
 *
 * 每個項目對應 `notification_events` 表的一個實際 column；型別決定可用的 [FieldOp]
 * 與比對時的 value coercion（IsNull/IsNotNull 不需 value）。
 *
 * UI 顯示名 = `field_<key>` string 資源（fallback = key 本身）。
 *
 * 註：priority / importance / visibility / category / has_* 等舊欄位已不在 events 表投影
 * （改由 eventRawJson 內 snapshot 解析），目前 SqlBuilder 對 SQL 路徑直接退化 ALWAYS_TRUE，
 * 因此這裡只列出 events 表實際存在的 column。Phase 10 視需要再用 read-time filter 補強。
 */
object FieldWhitelist {

    enum class Type { INT, LONG, BOOL, TEXT }

    data class Def(val column: String, val type: Type)

    val fields: Map<String, Def> = linkedMapOf(
        // 時間（events 表投影）
        "post_time" to Def("post_time", Type.LONG),
        "capture_time" to Def("capture_time", Type.LONG),
        "event_time" to Def("event_time", Type.LONG)
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
