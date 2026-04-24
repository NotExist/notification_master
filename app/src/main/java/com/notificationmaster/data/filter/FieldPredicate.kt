package com.notificationmaster.data.filter

import org.json.JSONArray
import org.json.JSONObject

/**
 * extraPredicates 的單一條件。
 *
 * - field：必須在 [FilterFieldWhitelist] 內
 * - op：比對運算子
 * - value：EQ/NEQ/LT/LTE/GT/GTE/LIKE 用；IS_NULL/IS_NOT_NULL 不用
 * - values：IN 用
 */
data class FieldPredicate(
    val field: String,
    val op: Op,
    val value: Any? = null,
    val values: List<Any>? = null
) {
    init {
        FilterFieldWhitelist.require(field)
        when (op) {
            Op.IN -> require(!values.isNullOrEmpty()) { "IN op requires non-empty values" }
            Op.IS_NULL, Op.IS_NOT_NULL -> { /* value/values 可為 null */ }
            else -> require(value != null) { "$op requires value" }
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("field", field)
        put("op", op.name)
        if (value != null) put("value", value)
        if (values != null) put("values", JSONArray(values))
    }

    companion object {
        fun fromJson(json: JSONObject): FieldPredicate {
            val op = Op.fromName(json.getString("op"))
            val value = if (json.has("value") && !json.isNull("value")) json.get("value") else null
            val values = if (json.has("values") && !json.isNull("values")) {
                val arr = json.getJSONArray("values")
                (0 until arr.length()).map { arr.get(it) }
            } else null
            return FieldPredicate(
                field = json.getString("field"),
                op = op,
                value = value,
                values = values
            )
        }
    }
}
