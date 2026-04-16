package com.notificationmaster.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.notificationmaster.R
import com.notificationmaster.core.cache.AppLabelCache
import com.notificationmaster.core.filter.Matcher
import com.notificationmaster.core.prefs.AppPreferences
import com.notificationmaster.ui.filter.FilterRuleDialogHelper
import org.json.JSONArray

/**
 * Widget 設定 Activity
 *
 * 新 widget：顯示 4 選項（3 模板 + 自訂篩選）
 * 重新設定（API 28+ reconfigurable）：直接開啟 matcher 編輯器
 */
class WidgetConfigActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setResult(RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        // 重新設定：已有 matchers → 直接進入編輯
        val existingMatchers = AppPreferences.getWidgetMatchers(this, appWidgetId)
        if (existingMatchers != null) {
            showMatcherEditor(existingMatchers)
        } else {
            showTemplateChooser()
        }
    }

    /**
     * 新 widget：3 模板 + 自訂篩選
     */
    private fun showTemplateChooser() {
        val options = arrayOf(
            getString(R.string.widget_type_audible),
            getString(R.string.widget_type_headsup),
            getString(R.string.widget_type_dismissed),
            getString(R.string.widget_type_custom)
        )

        // 模板對應的 matchers
        val templates: Array<List<Matcher>?> = arrayOf(
            listOf(Matcher.DerivedProperty(isAudible = true)),
            listOf(Matcher.DerivedProperty(likelyHeadsup = true)),
            listOf(Matcher.DerivedProperty(isRemoved = true)),
            null // 自訂：開 matcher 編輯器
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.widget_config_title)
            .setItems(options) { _, which ->
                val matchers = templates[which]
                if (matchers != null) {
                    // 模板：直接完成
                    val label = options[which]
                    saveAndFinish(matchers, label)
                } else {
                    // 自訂：開啟 matcher 編輯器
                    showMatcherEditor(null)
                }
            }
            .setOnCancelListener { finish() }
            .show()
    }

    /**
     * 開啟 FilterRuleDialogHelper（widgetMode）
     */
    private fun showMatcherEditor(existingMatchersJson: String?) {
        // 解析既有 matchers（重新設定時）
        val existingMatchers = existingMatchersJson?.let { json ->
            try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { Matcher.fromJson(arr.getJSONObject(it)) }
            } catch (_: Exception) { null }
        }

        FilterRuleDialogHelper.showAddRuleDialog(
            context = this,
            widgetMode = true,
            existingWidgetMatchers = existingMatchers,
            onMatchersReady = { matchers ->
                showLabelEditor(matchers)
            },
            onWidgetCancelled = {
                // 使用者取消 matcher 編輯
                if (existingMatchersJson == null) {
                    // 新 widget 取消 → 回到模板選擇
                    showTemplateChooser()
                } else {
                    // 重新設定取消 → 結束
                    finish()
                }
            }
        )
    }

    /**
     * 顯示 label 編輯 Dialog
     */
    private fun showLabelEditor(matchers: List<Matcher>) {
        val defaultLabel = deriveLabel(matchers)
        val existingLabel = AppPreferences.getWidgetLabel(this, appWidgetId)

        val inputLayout = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = getString(R.string.widget_label_dialog_title)
        }
        val editText = TextInputEditText(inputLayout.context).apply {
            setText(existingLabel ?: defaultLabel)
            selectAll()
        }
        inputLayout.addView(editText)
        inputLayout.setPadding(48, 16, 48, 0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.widget_label_dialog_title)
            .setView(inputLayout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val label = editText.text?.toString()?.trim()?.ifEmpty { defaultLabel } ?: defaultLabel
                saveAndFinish(matchers, label)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                // 取消 label → 回到 matcher 編輯（保留 matchers 狀態太複雜，直接完成用預設 label）
                saveAndFinish(matchers, defaultLabel)
            }
            .setOnCancelListener {
                saveAndFinish(matchers, defaultLabel)
            }
            .show()
    }

    /**
     * 根據 matchers 推導預設 label
     */
    private fun deriveLabel(matchers: List<Matcher>): String {
        val derived = matchers.filterIsInstance<Matcher.DerivedProperty>().firstOrNull()
        if (derived != null) {
            if (derived.isAudible == true) return getString(R.string.widget_type_audible)
            if (derived.likelyHeadsup == true) return getString(R.string.widget_type_headsup)
            if (derived.isRemoved == true) return getString(R.string.widget_type_dismissed)
        }
        val pkg = matchers.filterIsInstance<Matcher.Package>().firstOrNull()
        if (pkg != null) return AppLabelCache.getLabel(this, pkg.packageName)
        if (matchers.any { it is Matcher.Flags }) return getString(R.string.widget_label_default_flags)
        return getString(R.string.widget_label_default_custom)
    }

    /**
     * 存入設定並完成 Widget 配置
     */
    private fun saveAndFinish(matchers: List<Matcher>, label: String) {
        // 序列化 matchers
        val matchersJson = JSONArray(matchers.map { it.toJson() }).toString()
        AppPreferences.setWidgetMatchersSync(this, appWidgetId, matchersJson)
        AppPreferences.setWidgetLabelSync(this, appWidgetId, label)

        // 更新 widget
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val providerClassName = appWidgetManager.getAppWidgetInfo(appWidgetId)?.provider?.className
        when (providerClassName) {
            NotificationWidgetProvider::class.java.name ->
                NotificationWidgetProvider.updateWidget(this, appWidgetManager, appWidgetId)
            NotificationSingleWidgetProvider::class.java.name ->
                NotificationSingleWidgetProvider.updateWidget(this, appWidgetManager, appWidgetId)
            else -> {
                NotificationWidgetProvider.updateWidget(this, appWidgetManager, appWidgetId)
                NotificationSingleWidgetProvider.updateWidget(this, appWidgetManager, appWidgetId)
            }
        }

        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }
}
