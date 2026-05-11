package com.notificationmaster.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 通用 JSON tree diff（Plan 2 Phase 5）
 *
 * 給 detail 頁時間軸 inline 顯示「相對前一事件」全欄位變化。
 * read-time 計算（不依賴 write-time 預存的 contentDiff column），對 RawSerializer
 * 輸出的 eventRawJson 樹做 key 級別比對。
 *
 * 自動覆蓋未來新增欄位 — 只要 raw 結構新增 key，diff 就會自動列出。
 */
object EventDiffer {

    /** 噪音欄位黑名單（每次都不同但語意上不算「變化」） */
    private val NOISE_PATHS: Set<String> = setOf(
        "captureTime",
        "sbn.postTime"  // postTime 由 sbn 自身決定，與通知內容無關（同 sbn 不變）
    )

    /**
     * 比對兩個 raw JSON。prev=null 視為起始事件，回傳空 list。
     *
     * @param prev 前一事件的 eventRawJson 解析結果；null 表示無前事件
     * @param curr 當前事件的 eventRawJson 解析結果
     * @return 變化項目清單，path = 點分隔（如 "sbn.notification.extras.android.title"）
     */
    fun diff(prev: JSONObject?, curr: JSONObject): List<DiffEntry> {
        if (prev == null) return emptyList()
        val out = mutableListOf<DiffEntry>()
        diffObject(prev, curr, "", out)
        return out
    }

    private fun diffObject(a: JSONObject, b: JSONObject, prefix: String, out: MutableList<DiffEntry>) {
        val aKeys = a.keys().asSequence().toSet()
        val bKeys = b.keys().asSequence().toSet()

        // 在 a 但不在 b → 移除
        for (key in aKeys - bKeys) {
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            if (path in NOISE_PATHS) continue
            out.add(DiffEntry(path, a.opt(key), null))
        }
        // 在 b 但不在 a → 新增
        for (key in bKeys - aKeys) {
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            if (path in NOISE_PATHS) continue
            out.add(DiffEntry(path, null, b.opt(key)))
        }
        // 共同 key 比對
        for (key in aKeys intersect bKeys) {
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            if (path in NOISE_PATHS) continue
            diffValue(a.opt(key), b.opt(key), path, out)
        }
    }

    private fun diffValue(av: Any?, bv: Any?, path: String, out: MutableList<DiffEntry>) {
        when {
            av == null && bv == null -> {}
            av == JSONObject.NULL && bv == JSONObject.NULL -> {}
            av is JSONObject && bv is JSONObject -> diffObject(av, bv, path, out)
            av is JSONArray && bv is JSONArray -> diffArray(av, bv, path, out)
            else -> {
                if (!valueEquals(av, bv)) out.add(DiffEntry(path, av, bv))
            }
        }
    }

    private fun diffArray(a: JSONArray, b: JSONArray, prefix: String, out: MutableList<DiffEntry>) {
        // 簡化：長度不同視為整個陣列變動；長度相同則逐 index 比對
        // 如要更精準的 list diff（LCS）後續再優化
        if (a.length() != b.length()) {
            out.add(DiffEntry(prefix, a, b))
            return
        }
        for (i in 0 until a.length()) {
            diffValue(a.opt(i), b.opt(i), "$prefix[$i]", out)
        }
    }

    private fun valueEquals(av: Any?, bv: Any?): Boolean {
        if (av == null && bv == null) return true
        if (av == null || bv == null) return false
        if (av == JSONObject.NULL && bv == JSONObject.NULL) return true
        if (av == JSONObject.NULL || bv == JSONObject.NULL) return false
        // 數字型別 boxing 差異容忍：用 toString 比對
        return av.toString() == bv.toString()
    }

    /**
     * 一個變動項目。
     * - oldValue == null && newValue != null → 新增
     * - oldValue != null && newValue == null → 移除
     * - 兩者皆非 null → 修改
     */
    data class DiffEntry(
        val path: String,
        val oldValue: Any?,
        val newValue: Any?
    ) {
        val changeType: ChangeType get() = when {
            oldValue == null || oldValue == JSONObject.NULL -> ChangeType.ADDED
            newValue == null || newValue == JSONObject.NULL -> ChangeType.REMOVED
            else -> ChangeType.MODIFIED
        }
    }

    enum class ChangeType { ADDED, REMOVED, MODIFIED }
}
