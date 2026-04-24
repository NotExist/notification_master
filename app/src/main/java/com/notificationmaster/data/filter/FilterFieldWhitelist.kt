package com.notificationmaster.data.filter

/**
 * FilterSpec extraPredicates 可放入的欄位白名單。
 *
 * 第一版對應現有 `notifications` 表的 column；Plan 2 完成後改對應
 * `notification_events` column，清單與 SqlBuilder 同步調整。
 */
object FilterFieldWhitelist {

    data class FieldDef(
        /** SQL column 名稱（`notifications` 表） */
        val column: String,
        /** UI 顯示標籤 resource id（null 時用 column 本身） */
        val labelResId: Int? = null,
        /** 欄位型別，決定允許的 Op 與 value 解析 */
        val type: Type
    )

    enum class Type { INT, LONG, BOOL, TEXT }

    /** 欄位名 → 定義；欄位名即 column（使用者看到的 key） */
    val fields: Map<String, FieldDef> = linkedMapOf(
        // 優先級 / 重要性
        "priority" to FieldDef("priority", null, Type.INT),
        "importance" to FieldDef("importance", null, Type.INT),
        "visibility" to FieldDef("visibility", null, Type.INT),
        "category" to FieldDef("category", null, Type.TEXT),

        // Bubble / intent
        "has_bubble_metadata" to FieldDef("has_bubble_metadata", null, Type.BOOL),
        "has_full_screen_intent" to FieldDef("has_full_screen_intent", null, Type.BOOL),
        "has_content_intent" to FieldDef("has_content_intent", null, Type.BOOL),

        // Flags 系列 booleans
        "is_ongoing" to FieldDef("is_ongoing", null, Type.BOOL),
        "is_foreground_service" to FieldDef("is_foreground_service", null, Type.BOOL),
        "is_high_priority" to FieldDef("is_high_priority", null, Type.BOOL),
        "is_local_only" to FieldDef("is_local_only", null, Type.BOOL),
        "is_group_summary" to FieldDef("is_group_summary", null, Type.BOOL),

        // Flags int
        "flags" to FieldDef("flags", null, Type.INT),

        // 時間（進階）
        "post_time" to FieldDef("post_time", null, Type.LONG),
        "capture_time" to FieldDef("capture_time", null, Type.LONG),
        "when_time" to FieldDef("when_time", null, Type.LONG)
    )

    fun contains(field: String): Boolean = fields.containsKey(field)

    fun require(field: String): FieldDef =
        fields[field] ?: throw IllegalArgumentException("Field not in whitelist: $field")
}
