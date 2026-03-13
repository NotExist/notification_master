package com.notificationmaster.core.filter

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.notificationmaster.core.prefs.AppPreferences
import com.notificationmaster.data.db.entity.EventType
import org.json.JSONArray
import org.json.JSONObject

/**
 * 規則引擎：管理過濾/處置規則的儲存、匹配與匯出匯入
 *
 * 支援多種 Matcher 組合和 Action 類型，儲存格式為 v2 JSON。
 */
object RuleEngine {

    private const val TAG = "RuleEngine"
    private const val BACKUP_FILENAME = "notification_master_filter_rules.json"
    private const val EXPORT_VERSION = 2

    private var rules = listOf<Rule>()
    private var loaded = false

    // ========== 載入 / 儲存 ==========

    /**
     * 載入規則
     */
    fun load(context: Context) {
        if (loaded) return

        val v2Json = AppPreferences.getRulesV2Json(context)
        rules = if (v2Json != null) {
            try {
                val arr = JSONArray(v2Json)
                (0 until arr.length()).map { Rule.fromJson(arr.getJSONObject(it)) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse rules", e)
                emptyList()
            }
        } else {
            // 首次啟動：建立預設規則（過濾自身通知）
            listOf(createSelfFilterRule(context))
        }

        // 首次啟動時持久化預設規則
        if (v2Json == null && rules.isNotEmpty()) {
            save(context)
        }

        loaded = true
        Log.d(TAG, "Loaded ${rules.size} rules")
    }

    /**
     * 強制重新載入（規則被外部修改時使用）
     */
    fun reload(context: Context) {
        loaded = false
        load(context)
    }

    private fun save(context: Context) {
        val arr = JSONArray(rules.map { it.toJson() })
        AppPreferences.setRulesV2Json(context, arr.toString())
        autoBackup(context)
    }

    /**
     * 建立預設的自身過濾規則
     *
     * 將 Notification Master 自身的通知加入黑名單，
     * 避免記錄 App 自己發出的通知（如持續提醒的 heads-up 通知）。
     * 同時作為使用者理解過濾規則的範例。
     */
    private fun createSelfFilterRule(context: Context): Rule {
        return Rule(
            matchers = listOf(
                Matcher.Package(context.packageName),
                Matcher.EventTypes(EventType.entries.map { it.name }.toSet())
            ),
            action = RuleAction.SkipRecord
        )
    }

    // ========== CRUD ==========

    fun addRule(context: Context, rule: Rule) {
        rules = rules + rule
        save(context)
        Log.d(TAG, "Added rule: ${rule.action.actionType} ${rule.packageName}")
    }

    fun updateRule(context: Context, rule: Rule) {
        rules = rules.map { if (it.id == rule.id) rule else it }
        save(context)
        Log.d(TAG, "Updated rule: ${rule.id}")
    }

    fun removeRule(context: Context, ruleId: String) {
        rules = rules.filter { it.id != ruleId }
        save(context)
        Log.d(TAG, "Removed rule: $ruleId")
    }

    // ========== 查詢 ==========

    fun getRules(): List<Rule> = rules.toList()

    fun getRules(actionType: ActionType): List<Rule> =
        rules.filter { it.action.actionType == actionType }

    fun hasRules(actionType: ActionType): Boolean =
        rules.any { it.action.actionType == actionType }

    fun isAllEmpty(): Boolean = rules.isEmpty()

    // ========== 匹配 ==========

    /**
     * 查詢匹配的規則（含 channel 級優先邏輯）
     *
     * 1. 精確匹配：同 packageName + 同 channelId → 優先
     * 2. 包級匹配：同 packageName + 無 Channel matcher → 次之
     * 3. 無匹配 → null
     *
     * Channel 級規則存在時會 shadow 同 package 的包級規則
     * （即使 channel 級規則因 eventType 等條件不通過也不降級）。
     */
    fun findMatchingRule(actionType: ActionType, context: MatchContext): Rule? {
        val candidates = rules.filter { it.action.actionType == actionType }
        if (candidates.isEmpty()) return null

        // 1. Channel 級優先
        if (context.channelId != null) {
            val channelCandidates = candidates.filter { rule ->
                rule.isChannelLevel
                    && rule.matchers.filterIsInstance<Matcher.Package>()
                        .any { it.packageName == context.packageName }
                    && rule.matchers.filterIsInstance<Matcher.Channel>()
                        .any { it.channelId == context.channelId }
            }
            if (channelCandidates.isNotEmpty()) {
                return channelCandidates.firstOrNull { it.matches(context) }
            }
        }

        // 2. 包級匹配
        val packageCandidates = candidates.filter { rule ->
            !rule.isChannelLevel
                && rule.matchers.filterIsInstance<Matcher.Package>()
                    .any { it.packageName == context.packageName }
        }
        return packageCandidates.firstOrNull { it.matches(context) }
    }

    /**
     * 完整匹配（Boolean 簡寫）
     */
    fun matches(actionType: ActionType, context: MatchContext): Boolean =
        findMatchingRule(actionType, context) != null

    /**
     * 僅來源匹配：packageName + channelId（不檢查 eventType / keyword 等）
     * 日曆匯出白名單用
     */
    fun matchesSource(actionType: ActionType, packageName: String, channelId: String?): Boolean {
        val candidates = rules.filter { it.action.actionType == actionType }
        if (candidates.isEmpty()) return false

        // Channel 級優先
        if (channelId != null) {
            val channelMatch = candidates.any { rule ->
                rule.isChannelLevel
                    && rule.matchers.filterIsInstance<Matcher.Package>()
                        .any { it.packageName == packageName }
                    && rule.matchers.filterIsInstance<Matcher.Channel>()
                        .any { it.channelId == channelId }
            }
            if (channelMatch) return true
        }

        // 包級匹配
        return candidates.any { rule ->
            !rule.isChannelLevel
                && rule.matchers.filterIsInstance<Matcher.Package>()
                    .any { it.packageName == packageName }
        }
    }

    // ========== 匯出 / 匯入 ==========

    /**
     * 匯出所有規則為 JSON 字串（v2 格式）
     */
    fun exportAllToJson(): String {
        val root = JSONObject()
        root.put("version", EXPORT_VERSION)
        root.put("exportTime", System.currentTimeMillis())
        root.put("rules", JSONArray(rules.map { it.toJson() }))
        return root.toString(2)
    }

    /**
     * 匯入 JSON 字串（v2 格式）
     *
     * @return 各 ActionType 匯入的規則數
     */
    fun importAllFromJson(context: Context, json: String): Map<ActionType, Int> {
        val root = JSONObject(json)
        val arr = root.getJSONArray("rules")
        val imported = (0 until arr.length()).map { Rule.fromJson(arr.getJSONObject(it)) }

        rules = imported
        save(context)

        val result = mutableMapOf<ActionType, Int>()
        for (type in ActionType.entries) {
            val count = imported.count { it.action.actionType == type }
            if (count > 0) result[type] = count
        }
        Log.d(TAG, "Imported ${imported.size} rules")
        return result
    }

    /**
     * 計算備份 JSON 中有多少規則不在目前規則集內（以 ID 判斷）
     *
     * @return 備份中尚未同步的規則數量；解析失敗回傳 0
     */
    fun countNewRulesInBackup(json: String): Int {
        return try {
            val root = JSONObject(json)
            val arr = root.getJSONArray("rules")
            val existingIds = rules.map { it.id }.toSet()
            (0 until arr.length()).count { i ->
                val id = arr.getJSONObject(i).optString("id")
                id.isNotEmpty() && id !in existingIds
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to count new rules in backup", e)
            0
        }
    }

    /**
     * 合併匯入 JSON 字串（v2 格式）
     *
     * 保留現有規則，僅加入備份中 ID 不重複的新規則。
     *
     * @return 各 ActionType 新增的規則數
     */
    fun mergeFromJson(context: Context, json: String): Map<ActionType, Int> {
        val root = JSONObject(json)
        val arr = root.getJSONArray("rules")
        val imported = (0 until arr.length()).map { Rule.fromJson(arr.getJSONObject(it)) }

        val existingIds = rules.map { it.id }.toSet()
        val newRules = imported.filter { it.id !in existingIds }

        if (newRules.isEmpty()) return emptyMap()

        rules = rules + newRules
        save(context)

        val result = mutableMapOf<ActionType, Int>()
        for (type in ActionType.entries) {
            val count = newRules.count { it.action.actionType == type }
            if (count > 0) result[type] = count
        }
        Log.d(TAG, "Merged ${newRules.size} new rules (${imported.size} total in backup)")
        return result
    }

    // ========== 自動備份 ==========

    fun autoBackup(context: Context) {
        val treeUri = AppPreferences.getBackupDirUri(context) ?: return
        try {
            val dir = DocumentFile.fromTreeUri(context, treeUri) ?: return
            val existing = dir.findFile(BACKUP_FILENAME)
            val file = existing ?: dir.createFile("application/json", BACKUP_FILENAME)
            if (file == null) {
                Log.w(TAG, "Failed to create backup file")
                return
            }
            val json = exportAllToJson()
            context.contentResolver.openOutputStream(file.uri, "wt")?.use {
                it.write(json.toByteArray())
            }
            Log.d(TAG, "Auto backup completed")
        } catch (e: Exception) {
            Log.w(TAG, "Auto backup failed", e)
        }
    }

    fun readBackupFromDir(context: Context): String? {
        val treeUri = AppPreferences.getBackupDirUri(context) ?: return null
        return try {
            val dir = DocumentFile.fromTreeUri(context, treeUri) ?: return null
            val file = dir.findFile(BACKUP_FILENAME) ?: return null
            if (!file.canRead()) return null
            context.contentResolver.openInputStream(file.uri)?.use {
                it.bufferedReader().readText()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read backup file", e)
            null
        }
    }

    /**
     * 清除所有規則（僅供測試使用）
     */
    internal fun clearForTesting() {
        rules = emptyList()
        loaded = false
    }

    /**
     * 直接設定規則（僅供測試使用，跳過 SharedPreferences）
     */
    internal fun setRulesForTesting(testRules: List<Rule>) {
        rules = testRules
        loaded = true
    }
}
