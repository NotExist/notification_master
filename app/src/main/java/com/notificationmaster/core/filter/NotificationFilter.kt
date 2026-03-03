package com.notificationmaster.core.filter

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.notificationmaster.core.prefs.AppPreferences
import com.notificationmaster.data.db.entity.EventType
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 過濾規則類別
 */
enum class FilterCategory {
    /** 通知過濾黑名單 */
    NOTIFICATION,
    /** 日曆匯出白名單 */
    CALENDAR_EXPORT,
    /** 通知自動清除黑名單 */
    AUTO_DISMISS
}

/**
 * 過濾規則
 *
 * eventTypes 為該規則涵蓋的事件類型集合：
 * - 全選所有 EventType = 等同原 IGNORE_ALL
 * - 選部分 = 等同原 EXCLUDE_EVENTS
 */
data class FilterRule(
    val id: String = UUID.randomUUID().toString(),
    val packageName: String,
    val channelId: String?,
    val eventTypes: Set<String>,
    val createdAt: Long = System.currentTimeMillis(),
    /** 自動清除延遲（毫秒），僅 AUTO_DISMISS 使用，0 = 立即清除 */
    val dismissDelayMs: Long = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("packageName", packageName)
        put("channelId", channelId ?: JSONObject.NULL)
        put("eventTypes", JSONArray(eventTypes.toList()))
        put("createdAt", createdAt)
        put("dismissDelayMs", dismissDelayMs)
    }

    companion object {
        fun fromJson(json: JSONObject): FilterRule {
            val eventTypes = mutableSetOf<String>()
            // 相容舊格式 "excludedEventTypes" 與新格式 "eventTypes"
            val arr = json.optJSONArray("eventTypes")
                ?: json.optJSONArray("excludedEventTypes")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    eventTypes.add(arr.getString(i))
                }
            }

            // 相容舊格式：mode=IGNORE_ALL 時 eventTypes 為空 → 轉為全選
            val mode = json.optString("mode", "")
            val resolvedEventTypes = if (mode == "IGNORE_ALL" && eventTypes.isEmpty()) {
                EventType.entries.map { it.name }.toSet()
            } else {
                eventTypes
            }

            val packageName = json.getString("packageName")
            require(packageName.isNotBlank()) { "FilterRule packageName must not be blank" }

            return FilterRule(
                id = json.getString("id"),
                packageName = packageName,
                channelId = json.optString("channelId").takeIf { it.isNotEmpty() && it != "null" },
                eventTypes = resolvedEventTypes,
                createdAt = json.optLong("createdAt", 0L),
                dismissDelayMs = json.optLong("dismissDelayMs", 0L)
            )
        }
    }
}

/**
 * 過濾規則儲存與匹配
 *
 * 記憶體快取 + SharedPreferences 持久化。
 * 支援多個 FilterCategory，各自獨立儲存。
 * 規則數通常 < 20 條，無需更複雜的索引。
 */
object FilterRuleStore {

    private const val TAG = "FilterRuleStore"
    private const val BACKUP_FILENAME = "notification_master_filter_rules.json"

    private val rulesMap = mutableMapOf<FilterCategory, List<FilterRule>>()
    private val loadedCategories = mutableSetOf<FilterCategory>()

    /**
     * 從 SharedPreferences 載入指定類別（Service onCreate 或 Fragment 使用前呼叫）
     */
    fun load(context: Context, category: FilterCategory) {
        val json = AppPreferences.getFilterRulesJson(context, category)
        val rules = if (json != null) {
            try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { FilterRule.fromJson(arr.getJSONObject(it)) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse filter rules for $category", e)
                emptyList()
            }
        } else {
            emptyList()
        }
        rulesMap[category] = rules
        loadedCategories.add(category)
        Log.d(TAG, "Loaded ${rules.size} filter rules for $category")
    }

    /**
     * 儲存到 SharedPreferences，並觸發自動備份
     */
    private fun save(context: Context, category: FilterCategory) {
        val rules = rulesMap[category] ?: emptyList()
        val arr = JSONArray()
        for (rule in rules) {
            arr.put(rule.toJson())
        }
        AppPreferences.setFilterRulesJson(context, category, arr.toString())
        autoBackup(context)
    }

    /**
     * 取得匹配的規則，供需要規則詳細資訊的場景（如 AUTO_DISMISS 需要 dismissDelayMs）
     *
     * 查詢邏輯：
     * 1. 精確匹配：同 packageName + 同 channelId（非 null）→ 優先
     * 2. 包級匹配：同 packageName + rule.channelId == null → 次之
     * 3. 無匹配 → null
     */
    fun findMatchingRule(category: FilterCategory, packageName: String, channelId: String?, eventType: EventType): FilterRule? {
        val rules = rulesMap[category] ?: return null
        if (rules.isEmpty()) return null

        // 1. 精確匹配（channel 級）
        if (channelId != null) {
            val channelRule = rules.firstOrNull {
                it.packageName == packageName && it.channelId == channelId
            }
            if (channelRule != null) {
                return if (eventType.name in channelRule.eventTypes) channelRule else null
            }
        }

        // 2. 包級匹配
        val packageRule = rules.firstOrNull {
            it.packageName == packageName && it.channelId == null
        }
        if (packageRule != null) {
            return if (eventType.name in packageRule.eventTypes) packageRule else null
        }

        // 3. 無匹配
        return null
    }

    /**
     * 完整匹配：packageName + channelId + eventType
     * 委派給 findMatchingRule()
     */
    fun matches(category: FilterCategory, packageName: String, channelId: String?, eventType: EventType): Boolean =
        findMatchingRule(category, packageName, channelId, eventType) != null

    /**
     * 僅來源匹配：packageName + channelId（不檢查 eventType）
     * 日曆匯出白名單用
     */
    fun matchesSource(category: FilterCategory, packageName: String, channelId: String?): Boolean {
        val rules = rulesMap[category] ?: return false
        if (rules.isEmpty()) return false

        // 1. 精確匹配（channel 級）
        if (channelId != null) {
            val channelRule = rules.firstOrNull {
                it.packageName == packageName && it.channelId == channelId
            }
            if (channelRule != null) return true
        }

        // 2. 包級匹配
        val packageRule = rules.firstOrNull {
            it.packageName == packageName && it.channelId == null
        }
        if (packageRule != null) return true

        return false
    }

    /**
     * 是否有規則
     */
    fun hasRules(category: FilterCategory): Boolean =
        (rulesMap[category] ?: emptyList()).isNotEmpty()

    /**
     * 新增規則
     */
    fun addRule(context: Context, category: FilterCategory, rule: FilterRule) {
        val current = rulesMap[category] ?: emptyList()
        rulesMap[category] = current + rule
        save(context, category)
        Log.d(TAG, "Added rule to $category: ${rule.packageName}/${rule.channelId}")
    }

    /**
     * 更新規則（依 ID 取代）
     */
    fun updateRule(context: Context, category: FilterCategory, rule: FilterRule) {
        val current = rulesMap[category] ?: emptyList()
        rulesMap[category] = current.map { if (it.id == rule.id) rule else it }
        save(context, category)
        Log.d(TAG, "Updated rule in $category: ${rule.packageName}/${rule.channelId}")
    }

    /**
     * 移除規則
     */
    fun removeRule(context: Context, category: FilterCategory, ruleId: String) {
        val current = rulesMap[category] ?: emptyList()
        rulesMap[category] = current.filter { it.id != ruleId }
        save(context, category)
        Log.d(TAG, "Removed rule from $category: $ruleId")
    }

    /**
     * 取得所有規則（唯讀）
     */
    fun getRules(category: FilterCategory): List<FilterRule> =
        (rulesMap[category] ?: emptyList()).toList()

    /**
     * 匯出所有類別的規則為 JSON 字串
     */
    fun exportAllToJson(): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("exportTime", System.currentTimeMillis())

        val categories = JSONObject()
        for (category in FilterCategory.entries) {
            val rules = rulesMap[category] ?: emptyList()
            val arr = JSONArray()
            for (rule in rules) {
                arr.put(rule.toJson())
            }
            categories.put(category.name, arr)
        }
        root.put("categories", categories)

        return root.toString(2)
    }

    /**
     * 從 JSON 字串匯入所有類別的規則（取代現有規則）
     *
     * @return 各類別匯入的規則數
     */
    fun importAllFromJson(context: Context, json: String): Map<FilterCategory, Int> {
        val root = JSONObject(json)
        val categories = root.getJSONObject("categories")
        val result = mutableMapOf<FilterCategory, Int>()

        for (category in FilterCategory.entries) {
            val arr = categories.optJSONArray(category.name)
            if (arr != null) {
                val rules = (0 until arr.length()).map {
                    FilterRule.fromJson(arr.getJSONObject(it))
                }
                rulesMap[category] = rules
                save(context, category)
                result[category] = rules.size
                Log.d(TAG, "Imported ${rules.size} filter rules for $category")
            }
        }

        return result
    }

    /**
     * 自動備份所有規則到已設定的 SAF 備份目錄
     * 備份目錄未設定時靜默不執行
     */
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

    /**
     * 從備份目錄讀取備份檔案內容
     * @return JSON 字串，或 null（備份目錄未設定、檔案不存在、無法讀取）
     */
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
     * 所有類別的規則是否都為空
     */
    fun isAllEmpty(): Boolean =
        FilterCategory.entries.all { (rulesMap[it] ?: emptyList()).isEmpty() }
}
