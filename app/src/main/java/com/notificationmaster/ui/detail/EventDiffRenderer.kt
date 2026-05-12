package com.notificationmaster.ui.detail

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import com.notificationmaster.core.EventDiffer
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 [EventDiffer.DiffEntry] 列表渲染成 inline 多行文字（Plan 2 Phase 7b-B）。
 *
 * 每行格式：
 *   - 新增：`+ <path>: <newValue>`（綠色）
 *   - 移除：`- <path>: <oldValue>`（紅色）
 *   - 修改：`~ <path>: <old> → <new>`（橙色）
 *
 * 過長 value 會 truncate 至 [MAX_VALUE_LEN]，JSONObject / JSONArray 折成 `{...}` / `[N]`。
 */
object EventDiffRenderer {

    private const val MAX_VALUE_LEN = 120

    /** 預設色彩（直接以 ARGB int 提供，呼叫端不必傳 context） */
    private const val COLOR_ADDED = 0xFF2E7D32.toInt()    // green 800
    private const val COLOR_REMOVED = 0xFFC62828.toInt()  // red 800
    private const val COLOR_MODIFIED = 0xFFE65100.toInt() // orange 900

    /**
     * 渲染為 CharSequence（含 ForegroundColorSpan 著色）。
     * 空 list 回傳空字串（呼叫端可據此設 visibility）。
     */
    fun render(diff: List<EventDiffer.DiffEntry>): CharSequence {
        if (diff.isEmpty()) return ""
        val sb = SpannableStringBuilder()
        for ((idx, entry) in diff.withIndex()) {
            if (idx > 0) sb.append('\n')
            val start = sb.length
            val (prefix, color) = when (entry.changeType) {
                EventDiffer.ChangeType.ADDED -> "+ " to COLOR_ADDED
                EventDiffer.ChangeType.REMOVED -> "- " to COLOR_REMOVED
                EventDiffer.ChangeType.MODIFIED -> "~ " to COLOR_MODIFIED
            }
            sb.append(prefix)
            sb.append(entry.path)
            sb.append(": ")
            when (entry.changeType) {
                EventDiffer.ChangeType.ADDED -> sb.append(formatValue(entry.newValue))
                EventDiffer.ChangeType.REMOVED -> sb.append(formatValue(entry.oldValue))
                EventDiffer.ChangeType.MODIFIED -> {
                    sb.append(formatValue(entry.oldValue))
                    sb.append(" → ")
                    sb.append(formatValue(entry.newValue))
                }
            }
            sb.setSpan(
                ForegroundColorSpan(color),
                start,
                sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return sb
    }

    private fun formatValue(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> "{${value.length()} keys}"
        is JSONArray -> "[${value.length()}]"
        is String -> '"' + truncate(value) + '"'
        else -> truncate(value.toString())
    }

    private fun truncate(s: String): String =
        if (s.length <= MAX_VALUE_LEN) s else s.substring(0, MAX_VALUE_LEN) + "…"
}
