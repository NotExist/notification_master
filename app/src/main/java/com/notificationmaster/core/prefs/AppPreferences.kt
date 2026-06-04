package com.notificationmaster.core.prefs

import android.content.Context
import android.net.Uri

/**
 * App 偏好設定管理
 * 使用 SharedPreferences 儲存非結構化設定（如自訂媒體目錄 URI）
 */
object AppPreferences {

    private const val PREFS_NAME = "notification_master_prefs"
    private const val KEY_MEDIA_STORAGE_TYPE = "media_storage_type"
    private const val KEY_CUSTOM_MEDIA_DIR_URI = "custom_media_dir_uri"
    private const val KEY_CUSTOM_MEDIA_DIR_DISPLAY = "custom_media_dir_display"
    private const val KEY_BACKUP_DIR_URI = "backup_dir_uri"
    private const val KEY_BACKUP_DIR_DISPLAY = "backup_dir_display"
    private const val KEY_BACKUP_SETUP_DECLINED = "backup_setup_declined"
    private const val KEY_RULES_V2 = "filter_rules_v2"
    private const val KEY_RULE_LAST_TRIGGERED = "rule_last_triggered"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Phase 31l：媒體儲存類型三選一。
     * - INTERNAL：context.filesDir/media/（App 私有，無法 File Manager 看）
     * - APP_EXTERNAL：context.getExternalFilesDir(null)/media/（既有預設，File Manager 可見但 App 移除即清空）
     * - PUBLIC_EXTERNAL：使用者 SAF 選擇的 tree URI（沿用 KEY_CUSTOM_MEDIA_DIR_URI）
     *
     * 預設 APP_EXTERNAL 維持向後相容（既有部署升級不會自動觸發搬運）。
     */
    enum class MediaStorageType { INTERNAL, APP_EXTERNAL, PUBLIC_EXTERNAL }

    fun getMediaStorageType(context: Context): MediaStorageType {
        val name = prefs(context).getString(KEY_MEDIA_STORAGE_TYPE, null)
            ?: return MediaStorageType.APP_EXTERNAL
        return try {
            MediaStorageType.valueOf(name)
        } catch (_: IllegalArgumentException) {
            MediaStorageType.APP_EXTERNAL
        }
    }

    fun setMediaStorageType(context: Context, type: MediaStorageType) {
        prefs(context).edit()
            .putString(KEY_MEDIA_STORAGE_TYPE, type.name)
            .apply()
    }

    /**
     * 取得自訂媒體目錄的 tree URI（PUBLIC_EXTERNAL 對應的具體 SAF 目錄）
     */
    fun getCustomMediaDirUri(context: Context): Uri? =
        prefs(context).getString(KEY_CUSTOM_MEDIA_DIR_URI, null)?.let { Uri.parse(it) }

    /**
     * 設定自訂媒體目錄
     * @param uri tree URI，null 表示清除
     * @param displayName 使用者可讀的目錄名稱
     */
    fun setCustomMediaDir(context: Context, uri: Uri?, displayName: String?) {
        prefs(context).edit()
            .putString(KEY_CUSTOM_MEDIA_DIR_URI, uri?.toString())
            .putString(KEY_CUSTOM_MEDIA_DIR_DISPLAY, displayName)
            .apply()
    }

    /**
     * 清除自訂媒體目錄設定
     */
    fun clearCustomMediaDir(context: Context) {
        prefs(context).edit()
            .remove(KEY_CUSTOM_MEDIA_DIR_URI)
            .remove(KEY_CUSTOM_MEDIA_DIR_DISPLAY)
            .apply()
    }

    /**
     * 是否已設定自訂媒體目錄
     */
    fun isCustomMediaDirEnabled(context: Context): Boolean =
        prefs(context).getString(KEY_CUSTOM_MEDIA_DIR_URI, null) != null

    /**
     * 取得自訂目錄的顯示名稱
     */
    fun getCustomMediaDirDisplay(context: Context): String? =
        prefs(context).getString(KEY_CUSTOM_MEDIA_DIR_DISPLAY, null)

    // === 過濾規則備份目錄 ===

    fun getBackupDirUri(context: Context): Uri? =
        prefs(context).getString(KEY_BACKUP_DIR_URI, null)?.let { Uri.parse(it) }

    fun setBackupDir(context: Context, uri: Uri, displayName: String) {
        prefs(context).edit()
            .putString(KEY_BACKUP_DIR_URI, uri.toString())
            .putString(KEY_BACKUP_DIR_DISPLAY, displayName)
            .apply()
    }

    fun clearBackupDir(context: Context) {
        prefs(context).edit()
            .remove(KEY_BACKUP_DIR_URI)
            .remove(KEY_BACKUP_DIR_DISPLAY)
            .apply()
    }

    fun isBackupDirEnabled(context: Context): Boolean =
        prefs(context).getString(KEY_BACKUP_DIR_URI, null) != null

    fun getBackupDirDisplay(context: Context): String? =
        prefs(context).getString(KEY_BACKUP_DIR_DISPLAY, null)

    fun isBackupSetupDeclined(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BACKUP_SETUP_DECLINED, false)

    fun setBackupSetupDeclined(context: Context, declined: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_BACKUP_SETUP_DECLINED, declined)
            .apply()
    }

    // === 即時日曆匯出 ===
    //
    // per-rule calendar 後，目標日曆改由各 CALENDAR_EXPORT rule 自帶；此處只剩
    // 「自動匯出總開關」一個設定，calendarId/Name/AccountName/AccountType 不再需要。

    private const val KEY_REALTIME_CALENDAR_ENABLED = "realtime_calendar_enabled"

    fun isRealtimeCalendarEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REALTIME_CALENDAR_ENABLED, false)

    fun setRealtimeCalendarEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_REALTIME_CALENDAR_ENABLED, enabled)
            // 一次性清理已棄用的舊 key（per-rule calendar migration）
            .remove("realtime_calendar_id")
            .remove("realtime_calendar_name")
            .remove("realtime_calendar_account_name")
            .remove("realtime_calendar_account_type")
            .apply()
    }

    // === NLS 保活 ===

    private const val KEY_NLS_KEEPALIVE_ENABLED = "nls_keepalive_enabled"

    fun isNlsKeepaliveEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NLS_KEEPALIVE_ENABLED, false)

    fun setNlsKeepaliveEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_NLS_KEEPALIVE_ENABLED, enabled)
            .apply()
    }

    // === Widget ===

    // 現用（Plan D）：widget 綁定一個 LIST_FILTER Rule id
    private const val KEY_WIDGET_RULE_ID_PREFIX = "widget_rule_id_"
    private const val KEY_WIDGET_LABEL_PREFIX = "widget_label_"

    // Legacy（Plan 1 / 更早），讀取時 fallback；新寫入不再使用
    private const val KEY_WIDGET_MATCHERS_PREFIX = "widget_matchers_"
    private const val KEY_WIDGET_SPEC_PREFIX = "widget_spec_"
    private const val KEY_WIDGET_PRESET_NAME_PREFIX = "widget_preset_name_"

    fun getWidgetRuleId(context: Context, widgetId: Int): String? =
        prefs(context).getString("$KEY_WIDGET_RULE_ID_PREFIX$widgetId", null)

    fun setWidgetRuleIdSync(context: Context, widgetId: Int, ruleId: String) {
        prefs(context).edit()
            .putString("$KEY_WIDGET_RULE_ID_PREFIX$widgetId", ruleId)
            .commit()
    }

    fun getWidgetLabel(context: Context, widgetId: Int): String? =
        prefs(context).getString("$KEY_WIDGET_LABEL_PREFIX$widgetId", null)

    fun setWidgetLabelSync(context: Context, widgetId: Int, label: String) {
        prefs(context).edit()
            .putString("$KEY_WIDGET_LABEL_PREFIX$widgetId", label)
            .commit()
    }

    fun removeWidgetConfig(context: Context, widgetId: Int) {
        prefs(context).edit()
            .remove("$KEY_WIDGET_RULE_ID_PREFIX$widgetId")
            .remove("$KEY_WIDGET_LABEL_PREFIX$widgetId")
            .remove("$KEY_WIDGET_MATCHERS_PREFIX$widgetId")
            .remove("$KEY_WIDGET_SPEC_PREFIX$widgetId")
            .remove("$KEY_WIDGET_PRESET_NAME_PREFIX$widgetId")
            .apply()
    }

    /** 移除所有舊架構 keys（Plan 1 / Matcher JSON / Spec JSON），保留 widget_rule_id_ 與 widget_label_ */
    fun removeWidgetLegacyKeys(context: Context, widgetId: Int) {
        prefs(context).edit()
            .remove("$KEY_WIDGET_MATCHERS_PREFIX$widgetId")
            .remove("$KEY_WIDGET_SPEC_PREFIX$widgetId")
            .remove("$KEY_WIDGET_PRESET_NAME_PREFIX$widgetId")
            .apply()
    }

    // === 規則引擎 ===

    fun getRulesV2Json(context: Context): String? =
        prefs(context).getString(KEY_RULES_V2, null)

    fun setRulesV2Json(context: Context, json: String) {
        prefs(context).edit()
            .putString(KEY_RULES_V2, json)
            .apply()
    }

    // ========== 規則觸發時間 ==========

    fun getRuleLastTriggered(context: Context): Map<String, Long> {
        val json = prefs(context).getString(KEY_RULE_LAST_TRIGGERED, null) ?: return emptyMap()
        return try {
            val obj = org.json.JSONObject(json)
            obj.keys().asSequence().associateWith { obj.getLong(it) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun setRuleLastTriggered(context: Context, times: Map<String, Long>) {
        val obj = org.json.JSONObject()
        for ((id, ts) in times) obj.put(id, ts)
        prefs(context).edit()
            .putString(KEY_RULE_LAST_TRIGGERED, obj.toString())
            .apply()
    }

    // === Debug mode (phase 31a fixup) ===

    private const val KEY_DEBUG_DUMPER_ENABLED = "debug_dumper_enabled"

    /**
     * Phase 31a fixup：DebugDumper 是 instance（settings 一個、service 一個），
     * isEnabled var 不同步 → 用 SharedPreferences 跨 instance 共享。
     */
    fun isDebugDumperEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEBUG_DUMPER_ENABLED, false)

    fun setDebugDumperEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DEBUG_DUMPER_ENABLED, enabled).apply()
    }

    // === Plan 1-zippy-thunder W10：ProfileLogger per-tag 開關 ===

    private const val DEBUG_TAG_PREFIX = "debug_tag_"

    /**
     * 已知 ProfileLogger tag 清單，供 settings UI 列舉用（運行時 ProfileLogger.append
     * 不依賴此清單 — 新 tag 自動沿用預設值 true）。
     */
    val KNOWN_PROFILE_LOG_TAGS: List<String> = listOf(
        "App", "UncaughtExn", "Watchdog", "Archive",
        "Removed", "Chip", "Enricher", "Display",
        "State", "Displays", "Fragment", "Timeline",
        // W22-instrument：counter/scrollbar/list timing 釐清
        "Counter", "Scroll", "Adapter"
    )

    /**
     * 檢查 debug 總開關 ON 且該 tag 個別未被關閉。
     * 設計原則：debug 開啟時所有 tag 預設 ON（避免遺漏資訊），user 雜訊過多再個別關閉。
     */
    fun isDebugTagEnabled(context: Context, tag: String): Boolean {
        if (!isDebugDumperEnabled(context)) return false
        return isDebugTagPrefEnabled(context, tag)
    }

    /** 純讀 per-tag SharedPreferences，不檢查 debug 總開關（供 settings UI 顯示用） */
    fun isDebugTagPrefEnabled(context: Context, tag: String): Boolean {
        return prefs(context).getBoolean(
            "$DEBUG_TAG_PREFIX${tag.lowercase()}_enabled",
            true
        )
    }

    fun setDebugTagEnabled(context: Context, tag: String, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean("$DEBUG_TAG_PREFIX${tag.lowercase()}_enabled", enabled)
            .apply()
    }

    // === Plan 1-zippy-thunder W14：DebugDumper per-type 開關 ===

    private const val DUMP_TYPE_PREFIX = "debug_dump_"

    /**
     * DebugDumper / channel_dump 分類，每個分類可在 debug 總開關 ON 前提下個別 toggle。
     *
     * - [ENV]：啟動環境 / 權限快照（dumpSystemInfo）
     * - [EVENT]：個別通知事件 JSON（dumpEvent — POSTED/REMOVED/UPDATED）
     * - [INITIAL]：NLS 連線時 active notification 全表（dumpActiveNotifications）
     * - [RANKING]：onNotificationRankingUpdate 觸發的 ranking 快照（dumpRankingUpdate）
     * - [CHANNEL]：NLS ranking probe + 各觸發源 ranking dump（NotificationCaptureService）
     */
    enum class DumpType { ENV, EVENT, INITIAL, RANKING, CHANNEL }

    /** 檢查 debug 總開關 ON 且該類型個別未被關閉。預設全 ON（避免遺漏）。 */
    fun isDumpTypeEnabled(context: Context, type: DumpType): Boolean {
        if (!isDebugDumperEnabled(context)) return false
        return isDumpTypePrefEnabled(context, type)
    }

    /** 純讀 per-type SharedPreferences，不檢查 debug 總開關（供 settings UI 顯示用） */
    fun isDumpTypePrefEnabled(context: Context, type: DumpType): Boolean {
        return prefs(context).getBoolean(
            "$DUMP_TYPE_PREFIX${type.name.lowercase()}_enabled",
            true
        )
    }

    fun setDumpTypeEnabled(context: Context, type: DumpType, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean("$DUMP_TYPE_PREFIX${type.name.lowercase()}_enabled", enabled)
            .apply()
    }

    // === Plan 1-zippy-thunder：Timeline lazyload runtime tunables ===

    private const val KEY_LAZYLOAD_FOOTER_MIN_MS = "timeline_lazyload_footer_min_ms"
    private const val KEY_LAZYLOAD_AUTO_THRESHOLD = "timeline_lazyload_auto_threshold"
    private const val KEY_LAZYLOAD_INITIAL_PAGE_SIZE = "timeline_lazyload_initial_page_size"

    /**
     * Timeline cold start 初始載入的 events 數量（W22 debug 觀察用）。
     *
     * 預設 30：cold start query 30 events，list 顯示後 distance threshold (30) 立即觸發
     * auto-fill 第一次 lazyload (30→80)。user 視角看是「視距外連發兩次擴展」（cold start
     * + auto-fill）。
     *
     * 調大（如 80）可避免 cold start auto-fill：cold start 一次 query 給足 viewport
     * 撐滿 + 多一頁緩衝，user 一打開即是「auto-fill 完成」狀態，之後 user 主動滑動才
     * 觸發第一次 lazyload，footer Loading 一定在 viewport 內出現。
     */
    fun getLazyloadInitialPageSize(context: Context): Int =
        prefs(context).getInt(KEY_LAZYLOAD_INITIAL_PAGE_SIZE, 30)

    fun setLazyloadInitialPageSize(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_LAZYLOAD_INITIAL_PAGE_SIZE, value).apply()
    }

    /**
     * LoadingMore footer 最少可見時間（ms）— **debug 觀察用阻擋機制**。
     *
     * 預設 0：生產行為不延長，跟沒這層邏輯一樣（lazyload done 立即解除阻擋）。
     * 設大時：cooldown delay 期間 isLoadingMore 維持 true 阻擋下一輪 lazyload，user
     * 能看清楚每輪 lazyload 完整顯示 LoadingMore footer N ms 的邊界（適合除錯觀察）。
     */
    fun getLazyloadFooterMinMs(context: Context): Long =
        prefs(context).getLong(KEY_LAZYLOAD_FOOTER_MIN_MS, 0L)

    fun setLazyloadFooterMinMs(context: Context, value: Long) {
        prefs(context).edit().putLong(KEY_LAZYLOAD_FOOTER_MIN_MS, value).apply()
    }

    /**
     * 末尾自動 lazyload 觸發閾值：`displays.size < N` 時 renderList 末尾自動觸發 loadNextDay。
     * 預設 30（沿用 INITIAL_PAGE_SIZE 同視角）。設為 0 可關閉「自動湊滿」行為，只剩 onScrolled
     * 手動滑到底觸發。
     */
    fun getLazyloadAutoThreshold(context: Context): Int =
        prefs(context).getInt(KEY_LAZYLOAD_AUTO_THRESHOLD, 30)

    fun setLazyloadAutoThreshold(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_LAZYLOAD_AUTO_THRESHOLD, value).apply()
    }
}
