package com.notificationmaster.core.prefs

import android.content.Context
import android.net.Uri

/**
 * App 偏好設定管理
 * 使用 SharedPreferences 儲存非結構化設定（如自訂媒體目錄 URI）
 */
object AppPreferences {

    private const val PREFS_NAME = "notification_master_prefs"
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
     * 取得自訂媒體目錄的 tree URI
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

    private const val KEY_REALTIME_CALENDAR_ENABLED = "realtime_calendar_enabled"
    private const val KEY_REALTIME_CALENDAR_ID = "realtime_calendar_id"
    private const val KEY_REALTIME_CALENDAR_NAME = "realtime_calendar_name"
    private const val KEY_REALTIME_CALENDAR_ACCOUNT_NAME = "realtime_calendar_account_name"
    private const val KEY_REALTIME_CALENDAR_ACCOUNT_TYPE = "realtime_calendar_account_type"
    fun isRealtimeCalendarEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REALTIME_CALENDAR_ENABLED, false)

    fun setRealtimeCalendarEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_REALTIME_CALENDAR_ENABLED, enabled)
            .apply()
    }

    fun getRealtimeCalendarId(context: Context): Long =
        prefs(context).getLong(KEY_REALTIME_CALENDAR_ID, -1L)

    fun setRealtimeCalendarTarget(
        context: Context,
        calendarId: Long,
        calendarName: String,
        accountName: String,
        accountType: String
    ) {
        prefs(context).edit()
            .putLong(KEY_REALTIME_CALENDAR_ID, calendarId)
            .putString(KEY_REALTIME_CALENDAR_NAME, calendarName)
            .putString(KEY_REALTIME_CALENDAR_ACCOUNT_NAME, accountName)
            .putString(KEY_REALTIME_CALENDAR_ACCOUNT_TYPE, accountType)
            .apply()
    }

    fun getRealtimeCalendarName(context: Context): String? =
        prefs(context).getString(KEY_REALTIME_CALENDAR_NAME, null)

    fun getRealtimeCalendarAccountName(context: Context): String? =
        prefs(context).getString(KEY_REALTIME_CALENDAR_ACCOUNT_NAME, null)

    fun getRealtimeCalendarAccountType(context: Context): String? =
        prefs(context).getString(KEY_REALTIME_CALENDAR_ACCOUNT_TYPE, null)

    fun clearRealtimeCalendar(context: Context) {
        prefs(context).edit()
            .remove(KEY_REALTIME_CALENDAR_ENABLED)
            .remove(KEY_REALTIME_CALENDAR_ID)
            .remove(KEY_REALTIME_CALENDAR_NAME)
            .remove(KEY_REALTIME_CALENDAR_ACCOUNT_NAME)
            .remove(KEY_REALTIME_CALENDAR_ACCOUNT_TYPE)
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

    // 舊版：以 core/filter/Matcher JSON 陣列儲存；保留 getter 以便既有 widget 可被識別並提示重設
    private const val KEY_WIDGET_MATCHERS_PREFIX = "widget_matchers_"
    private const val KEY_WIDGET_LABEL_PREFIX = "widget_label_"

    // 新版（Plan 1）：FilterSpec JSON + 可選 preset name
    private const val KEY_WIDGET_SPEC_PREFIX = "widget_spec_"
    private const val KEY_WIDGET_PRESET_NAME_PREFIX = "widget_preset_name_"

    fun getWidgetMatchers(context: Context, widgetId: Int): String? =
        prefs(context).getString("$KEY_WIDGET_MATCHERS_PREFIX$widgetId", null)

    /** 取得此 widget 綁定的 spec JSON（Plan 1 架構） */
    fun getWidgetSpec(context: Context, widgetId: Int): String? =
        prefs(context).getString("$KEY_WIDGET_SPEC_PREFIX$widgetId", null)

    fun setWidgetSpecSync(context: Context, widgetId: Int, specJson: String) {
        prefs(context).edit()
            .putString("$KEY_WIDGET_SPEC_PREFIX$widgetId", specJson)
            .commit()
    }

    /** 取得此 widget 綁定的命名 preset（若為系統或使用者命名 preset） */
    fun getWidgetPresetName(context: Context, widgetId: Int): String? =
        prefs(context).getString("$KEY_WIDGET_PRESET_NAME_PREFIX$widgetId", null)

    fun setWidgetPresetNameSync(context: Context, widgetId: Int, presetName: String?) {
        val edit = prefs(context).edit()
        if (presetName == null) edit.remove("$KEY_WIDGET_PRESET_NAME_PREFIX$widgetId")
        else edit.putString("$KEY_WIDGET_PRESET_NAME_PREFIX$widgetId", presetName)
        edit.commit()
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
            .remove("$KEY_WIDGET_MATCHERS_PREFIX$widgetId")
            .remove("$KEY_WIDGET_SPEC_PREFIX$widgetId")
            .remove("$KEY_WIDGET_PRESET_NAME_PREFIX$widgetId")
            .remove("$KEY_WIDGET_LABEL_PREFIX$widgetId")
            .apply()
    }

    /** 清理舊 Matcher 架構的 widget key（升級到 Plan 1 FilterSpec 後呼叫） */
    fun prefsBridgeRemoveMatchers(context: Context, widgetId: Int) {
        prefs(context).edit()
            .remove("$KEY_WIDGET_MATCHERS_PREFIX$widgetId")
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
}
