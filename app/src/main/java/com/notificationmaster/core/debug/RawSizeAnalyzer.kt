package com.notificationmaster.core.debug

import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase 31a：對 eventRawJson 做 size breakdown 分析。
 *
 * 遞迴 walk JSON tree，計算每個 path 的 `value.toString().length`（chars）。
 * 排序後輸出 top N 大 paths + 各 `extras` key 小計，純文字格式給人類閱讀。
 *
 * 使用情境：DebugDumper.dumpEvent 寫入 raw .json 時，同時寫 sibling `*_breakdown.txt`，
 * user 開檔即可看實際 key 大小分佈，驗證 130KB raw 內容主體。
 */
object RawSizeAnalyzer {

    /**
     * 分析 eventRawJson 字串並回傳純文字摘要。
     *
     * @param jsonString 完整 raw JSON 字串
     * @param topN top N 大 paths 列出（預設 30）
     */
    fun analyze(jsonString: String, topN: Int = 30): String {
        val total = jsonString.length
        return try {
            val root = JSONObject(jsonString)
            buildReport(root, total, topN)
        } catch (e: Exception) {
            "Analyze failed: ${e.message}\nTotal size: $total chars\n"
        }
    }

    private fun buildReport(root: JSONObject, total: Int, topN: Int): String {
        val sizes = mutableListOf<Pair<String, Int>>()
        walk(root, "$", sizes)

        val sb = StringBuilder()
        sb.append("Total: $total chars\n\n")

        // Top N paths
        sb.append("Top $topN paths by size (chars):\n")
        sizes.sortedByDescending { it.second }
            .take(topN)
            .forEach { (path, size) ->
                sb.append(String.format("%10d   %s%n", size, path))
            }

        // Per-extras-key 小計（notification.extras 子層獨立列出）
        val extras = root
            .optJSONObject("sbn")
            ?.optJSONObject("notification")
            ?.optJSONObject("extras")
        if (extras != null) {
            sb.append("\nPer-extras-key:\n")
            val extrasSizes = mutableListOf<Pair<String, Int>>()
            for (key in extras.keys()) {
                val value = extras.opt(key)
                val len = value?.toString()?.length ?: 0
                extrasSizes += key to len
            }
            extrasSizes.sortedByDescending { it.second }
                .forEach { (key, size) ->
                    sb.append(String.format("%10d   %s%n", size, key))
                }
        }

        return sb.toString()
    }

    /** 遞迴 walk：對每個 path 記錄 toString().length。 */
    private fun walk(node: Any?, path: String, out: MutableList<Pair<String, Int>>) {
        if (node == null) return
        when (node) {
            is JSONObject -> {
                out += path to node.toString().length
                for (key in node.keys()) {
                    walk(node.opt(key), "$path.$key", out)
                }
            }
            is JSONArray -> {
                out += path to node.toString().length
                for (i in 0 until node.length()) {
                    walk(node.opt(i), "$path[$i]", out)
                }
            }
            else -> {
                // 葉節點（String / Number / Boolean）
                out += path to node.toString().length
            }
        }
    }
}
