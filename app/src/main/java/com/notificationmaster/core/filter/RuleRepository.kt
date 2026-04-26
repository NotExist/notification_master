package com.notificationmaster.core.filter

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.notificationmaster.core.prefs.AppPreferences
import com.notificationmaster.data.db.entity.EventType
import org.json.JSONArray
import org.json.JSONObject

/**
 * 規則持久化層：負責 SharedPreferences 讀寫、備份 I/O
 *
 * 將 Android 依賴（Context、DocumentFile、Log）集中於此，
 * 讓 [RuleEngine] 保持為純 Kotlin 邏輯層。
 */
object RuleRepository {

    private const val TAG = "RuleRepository"
    private const val BACKUP_FILENAME = "notification_master_filter_rules.json"

    private var loaded = false

    // ========== 載入 / 儲存 ==========

    /**
     * 載入規則
     */
    fun load(context: Context) {
        if (loaded) return

        val v2Json = AppPreferences.getRulesV2Json(context)
        val parsed: List<Rule> = if (v2Json != null) {
            try {
                val arr = JSONArray(v2Json)
                (0 until arr.length()).map { Rule.fromJson(arr.getJSONObject(it)) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse rules", e)
                emptyList()
            }
        } else {
            listOf(createSelfFilterRule(context))
        }

        // 確保內建 LIST_FILTER rules（RecentAudible/Headsup/Dismissed）存在
        val rules = ensureBuiltInListFilterRules(parsed)
        val seeded = rules.size != parsed.size

        RuleEngine.setRules(rules)
        RuleEngine.setLastTriggeredTimes(AppPreferences.getRuleLastTriggered(context))

        if ((v2Json == null && rules.isNotEmpty()) || seeded) {
            save(context)
        }

        loaded = true
        Log.d(TAG, "Loaded ${rules.size} rules (seeded built-in: $seeded)")
    }

    /** 內建 LIST_FILTER rule 的固定 id（不可變） */
    private const val BUILTIN_RECENT_AUDIBLE_ID = "builtin-list-recent-audible"
    private const val BUILTIN_RECENT_HEADSUP_ID = "builtin-list-recent-headsup"
    private const val BUILTIN_RECENT_DISMISSED_ID = "builtin-list-recent-dismissed"

    /**
     * 若記憶體中缺少內建 LIST_FILTER rule，補齊。回傳補齊後的 rule list。
     */
    private fun ensureBuiltInListFilterRules(existing: List<Rule>): List<Rule> {
        val byId = existing.associateBy { it.id }
        val builtIns = builtInListFilterRules()
        val missing = builtIns.filter { it.id !in byId }
        return if (missing.isEmpty()) existing else existing + missing
    }

    private fun builtInListFilterRules(): List<Rule> = listOf(
        Rule(
            id = BUILTIN_RECENT_AUDIBLE_ID,
            name = "RecentAudible",
            matchers = listOf(Matcher.DerivedProperty(isAudible = true)),
            action = RuleAction.ListFilter(deduplicate = true, limit = 20),
            isBuiltIn = true
        ),
        Rule(
            id = BUILTIN_RECENT_HEADSUP_ID,
            name = "RecentHeadsup",
            matchers = listOf(Matcher.DerivedProperty(likelyHeadsup = true)),
            action = RuleAction.ListFilter(deduplicate = true, limit = 20),
            isBuiltIn = true
        ),
        Rule(
            id = BUILTIN_RECENT_DISMISSED_ID,
            name = "RecentDismissed",
            matchers = listOf(Matcher.DerivedProperty(isRemoved = true)),
            action = RuleAction.ListFilter(deduplicate = true, limit = 30),
            isBuiltIn = true
        )
    )

    /** 提供給 Shortcut / Widget 解析「最近有聲」等系統入口 */
    fun builtInRuleIdAudible(): String = BUILTIN_RECENT_AUDIBLE_ID
    fun builtInRuleIdHeadsup(): String = BUILTIN_RECENT_HEADSUP_ID
    fun builtInRuleIdDismissed(): String = BUILTIN_RECENT_DISMISSED_ID

    /**
     * 強制重新載入（規則被外部修改時使用）
     */
    fun reload(context: Context) {
        loaded = false
        load(context)
    }

    /**
     * 持久化目前的規則到 SharedPreferences 並觸發自動備份
     */
    fun save(context: Context) {
        val arr = JSONArray(RuleEngine.getRules().map { it.toJson() })
        AppPreferences.setRulesV2Json(context, arr.toString())
        AppPreferences.setRuleLastTriggered(context, RuleEngine.getLastTriggeredTimes())
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

    // ========== CRUD（委派 RuleEngine + 持久化）==========

    fun addRule(context: Context, rule: Rule) {
        RuleEngine.addRule(rule)
        save(context)
        Log.d(TAG, "Added rule: ${rule.action.actionType} ${rule.packageName}")
    }

    fun updateRule(context: Context, rule: Rule) {
        RuleEngine.updateRule(rule)
        save(context)
        Log.d(TAG, "Updated rule: ${rule.id}")
    }

    fun removeRule(context: Context, ruleId: String) {
        RuleEngine.removeRule(ruleId)
        save(context)
        Log.d(TAG, "Removed rule: $ruleId")
    }

    // ========== 匯入 ==========

    /**
     * 匯入 JSON 字串（v2 格式）
     *
     * @return 各 ActionType 匯入的規則數
     */
    fun importAllFromJson(context: Context, json: String): Map<ActionType, Int> {
        val result = RuleEngine.importAllFromJson(json)
        save(context)
        Log.d(TAG, "Imported ${RuleEngine.getRules().size} rules")
        return result
    }

    /**
     * 合併匯入 JSON 字串（v2 格式，以內容比對去重）
     *
     * 以備份規則為主體，保留本機中備份沒有的獨有規則。
     * 內容相同（matchers + action）但 id/createdAt 不同的規則視為重複。
     *
     * @return 各 ActionType 從備份新增的規則數
     */
    fun mergeFromJson(context: Context, json: String): Map<ActionType, Int> {
        val result = RuleEngine.mergeFromJson(json)
        if (result.isNotEmpty()) {
            save(context)
        }
        Log.d(TAG, "Merged rules: $result")
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
            val json = RuleEngine.exportAllToJson()
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
     * 清除載入狀態（僅供測試使用）
     */
    internal fun clearForTesting() {
        loaded = false
    }
}
