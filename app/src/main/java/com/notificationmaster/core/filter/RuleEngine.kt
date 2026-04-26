package com.notificationmaster.core.filter

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 規則引擎：純邏輯層，負責記憶體內規則管理、匹配與 JSON 序列化
 *
 * 不依賴 Android API，可在 JVM 單元測試中直接使用。
 * 持久化（SharedPreferences、檔案備份）由 [RuleRepository] 負責。
 */
object RuleEngine {

    private const val EXPORT_VERSION = 2

    private var rules = listOf<Rule>()

    // ========== 觸發時間（獨立於 Rule 物件，不影響 contentKey/序列化/備份合併） ==========

    private val lastTriggered = mutableMapOf<String, Long>()

    /** 每次觸發遞增，供 UI collect 即時刷新規則清單 */
    val triggerFlow = MutableStateFlow(0L)

    fun getLastTriggered(ruleId: String): Long = lastTriggered[ruleId] ?: 0L

    fun setLastTriggeredTimes(times: Map<String, Long>) {
        lastTriggered.putAll(times)
    }

    fun getLastTriggeredTimes(): Map<String, Long> = lastTriggered.toMap()

    // ========== 記憶體規則管理 ==========

    /**
     * 設定規則（供 [RuleRepository] 載入後呼叫）
     */
    fun setRules(newRules: List<Rule>) {
        rules = newRules
    }

    fun addRule(rule: Rule) {
        rules = rules + rule
    }

    fun updateRule(rule: Rule) {
        rules = rules.map { if (it.id == rule.id) rule else it }
    }

    fun removeRule(ruleId: String) {
        rules = rules.filter { it.id != ruleId || it.isBuiltIn /* 內建 rule 不可刪除 */ }
        lastTriggered.remove(ruleId)
    }

    // ========== 查詢 ==========

    fun getRules(): List<Rule> = rules.toList()

    fun getRules(actionType: ActionType): List<Rule> =
        rules.filter { it.action.actionType == actionType }

    fun getRule(ruleId: String): Rule? = rules.firstOrNull { it.id == ruleId }

    fun hasRules(actionType: ActionType): Boolean =
        rules.any { it.action.actionType == actionType }

    fun isAllEmpty(): Boolean = rules.isEmpty()

    /** 列表篩選用 rules（LIST_FILTER），含內建與使用者命名 */
    fun getListFilterRules(): List<Rule> = getRules(ActionType.LIST_FILTER)

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
        val result: Rule?
        if (context.channelId != null) {
            val channelCandidates = candidates.filter { rule ->
                rule.isChannelLevel
                    && rule.matchers.filterIsInstance<Matcher.Package>()
                        .any { it.packageName == context.packageName }
                    && rule.matchers.filterIsInstance<Matcher.Channel>()
                        .any { it.channelId == context.channelId }
            }
            if (channelCandidates.isNotEmpty()) {
                result = channelCandidates.firstOrNull { it.matches(context) }
            } else {
                // 2. 包級匹配
                val packageCandidates = candidates.filter { rule ->
                    !rule.isChannelLevel
                        && rule.matchers.filterIsInstance<Matcher.Package>()
                            .any { it.packageName == context.packageName }
                }
                result = packageCandidates.firstOrNull { it.matches(context) }
            }
        } else {
            // 2. 包級匹配
            val packageCandidates = candidates.filter { rule ->
                !rule.isChannelLevel
                    && rule.matchers.filterIsInstance<Matcher.Package>()
                        .any { it.packageName == context.packageName }
            }
            result = packageCandidates.firstOrNull { it.matches(context) }
        }

        // 記錄觸發時間
        result?.let {
            lastTriggered[it.id] = System.currentTimeMillis()
            triggerFlow.value++
        }
        return result
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
     * 匯入 JSON 字串（v2 格式），替換記憶體中的規則
     *
     * @return 各 ActionType 匯入的規則數
     */
    fun importAllFromJson(json: String): Map<ActionType, Int> {
        val root = JSONObject(json)
        val arr = root.getJSONArray("rules")
        val imported = (0 until arr.length()).map { Rule.fromJson(arr.getJSONObject(it)) }

        rules = imported

        val result = mutableMapOf<ActionType, Int>()
        for (type in ActionType.entries) {
            val count = imported.count { it.action.actionType == type }
            if (count > 0) result[type] = count
        }
        return result
    }

    /**
     * 取得規則的內容鍵（排除 id 和 createdAt，僅比對 matchers + action）
     *
     * Rule 是 data class，Matcher/RuleAction 子類也都是 data class，
     * copy 後的 equals/hashCode 可正確進行內容比對。
     */
    private fun Rule.contentKey(): Rule = copy(id = "", createdAt = 0, name = null, isBuiltIn = false)

    /**
     * 計算備份 JSON 中有多少規則不在目前規則集內（以內容比對）
     *
     * @return 備份中尚未同步的規則數量；解析失敗回傳 0
     */
    fun countNewRulesInBackup(json: String): Int {
        return try {
            val root = JSONObject(json)
            val arr = root.getJSONArray("rules")
            val backupRules = (0 until arr.length()).map { Rule.fromJson(arr.getJSONObject(it)) }
            val localContentKeys = rules.map { it.contentKey() }.toSet()
            backupRules.count { it.contentKey() !in localContentKeys }
        } catch (_: Exception) {
            0
        }
    }

    /**
     * 合併匯入 JSON 字串（v2 格式，以內容比對去重）
     *
     * 以備份規則為主體，保留本機中備份沒有的獨有規則。
     * 內容相同（matchers + action）但 id/createdAt 不同的規則視為重複。
     *
     * @return 各 ActionType 從備份新增的規則數；無變更時回傳空 Map
     */
    fun mergeFromJson(json: String): Map<ActionType, Int> {
        val root = JSONObject(json)
        val arr = root.getJSONArray("rules")
        val backupRules = (0 until arr.length()).map { Rule.fromJson(arr.getJSONObject(it)) }

        val backupContentKeys = backupRules.map { it.contentKey() }.toSet()
        val localOnlyRules = rules.filter { it.contentKey() !in backupContentKeys }

        val localContentKeys = rules.map { it.contentKey() }.toSet()
        val newFromBackup = backupRules.filter { it.contentKey() !in localContentKeys }

        if (newFromBackup.isEmpty() && localOnlyRules.size == rules.size) return emptyMap()

        // 備份規則為主 + 本機獨有規則
        rules = backupRules + localOnlyRules

        val result = mutableMapOf<ActionType, Int>()
        for (type in ActionType.entries) {
            val count = newFromBackup.count { it.action.actionType == type }
            if (count > 0) result[type] = count
        }
        return result
    }

    // ========== 測試輔助 ==========

    /**
     * 清除所有規則（僅供測試使用）
     */
    internal fun clearForTesting() {
        rules = emptyList()
    }

    /**
     * 直接設定規則（僅供測試使用，跳過持久化）
     */
    internal fun setRulesForTesting(testRules: List<Rule>) {
        rules = testRules
    }
}
