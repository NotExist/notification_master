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
import com.notificationmaster.core.filter.Rule
import com.notificationmaster.core.filter.RuleAction
import com.notificationmaster.core.filter.RuleEngine
import com.notificationmaster.core.filter.RuleRepository
import com.notificationmaster.core.prefs.AppPreferences
import com.notificationmaster.ui.filter.FilterRuleDialogHelper

/**
 * Widget 設定 Activity（Plan D — 直接進 RuleEngine widgetMode 編輯器）
 *
 * 流程：
 * 1. onCreate → 直接打開 FilterRuleDialogHelper.widgetMode 編輯 matchers
 *    （重設模式：以既有 widget 綁定的 rule.matchers 為初值）
 * 2. 確認後輸入 rule 顯示名稱 → 建立 LIST_FILTER Rule → 存入 RuleEngine
 * 3. 將 rule.id 寫入 widget_rule_id_<appWidgetId> SharedPreferences
 * 4. 觸發 widget 重繪
 *
 * 透明 theme，所有 UI 走 dialog；本 Activity 不顯示自身 contentView。
 */
class WidgetConfigActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    companion object {
        /** WidgetPinner.requestPin 帶入的 preselect rule id */
        const val EXTRA_PRESELECTED_RULE_ID = "preselected_rule_id"
    }

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

        RuleRepository.load(this)

        // 預選：requestPin 帶入 → 重新設定既有綁定 → 預設 RecentAudible 內建
        val preselectedRuleId = intent?.getStringExtra(EXTRA_PRESELECTED_RULE_ID)
            ?: AppPreferences.getWidgetRuleId(this, appWidgetId)
            ?: RuleRepository.builtInRuleIdAudible()
        val preselected = RuleEngine.getRule(preselectedRuleId)
        // 若 rule 已被刪除（user 手動清過），fallback 內建
        val initialRule = preselected
            ?: RuleEngine.getRule(RuleRepository.builtInRuleIdAudible())

        openEditor(initialRule)
    }

    private fun openEditor(initialRule: Rule?) {
        FilterRuleDialogHelper.showAddRuleDialog(
            context = this,
            widgetMode = true,
            existingWidgetMatchers = initialRule?.matchers ?: emptyList(),
            onMatchersReady = { matchers ->
                handleMatchersReady(matchers, initialRule)
            },
            onWidgetCancelled = { finish() }
        )
    }

    /**
     * 編輯器確認後：
     * - 若 initialRule 是內建 rule → 建立新 user-defined rule（不能改內建）
     * - 若 initialRule 是 user rule 且 matchers 有變 → 詢問新名稱建立新 rule
     *   （避免改動既存 rule 影響其他 widget；Timeline rule chip 可另行管理）
     * - 若 initialRule 是 user rule 且 matchers 不變 → 直接綁此 rule
     */
    private fun handleMatchersReady(matchers: List<Matcher>, initialRule: Rule?) {
        val unchanged = initialRule != null && matchers == initialRule.matchers
        if (unchanged && initialRule != null) {
            bindAndFinish(initialRule)
            return
        }
        promptName(initialRule, matchers)
    }

    private fun promptName(initialRule: Rule?, matchers: List<Matcher>) {
        val defaultName = initialRule?.name ?: deriveLabel(matchers)
        val editText = TextInputEditText(this).apply {
            setText(defaultName)
            setSelection(text?.length ?: 0)
        }
        val layout = TextInputLayout(
            this, null, com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = getString(R.string.preset_save_name_hint)
            setPadding(48, 16, 48, 0)
            addView(editText)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.preset_save_title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = editText.text?.toString()?.trim()?.ifEmpty { defaultName } ?: defaultName
                val baseAction = (initialRule?.action as? RuleAction.ListFilter)
                    ?: RuleAction.ListFilter()
                val rule = Rule(
                    name = name,
                    matchers = matchers,
                    action = baseAction
                )
                RuleRepository.addRule(this, rule)
                bindAndFinish(rule)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun bindAndFinish(rule: Rule) {
        AppPreferences.setWidgetRuleIdSync(this, appWidgetId, rule.id)
        AppPreferences.setWidgetLabelSync(this, appWidgetId, rule.name ?: deriveLabel(rule.matchers))
        // 清除舊架構 keys
        AppPreferences.removeWidgetLegacyKeys(this, appWidgetId)

        val awm = AppWidgetManager.getInstance(this)
        val providerClassName = awm.getAppWidgetInfo(appWidgetId)?.provider?.className
        when (providerClassName) {
            NotificationWidgetProvider::class.java.name ->
                NotificationWidgetProvider.updateWidget(this, awm, appWidgetId)
            NotificationSingleWidgetProvider::class.java.name ->
                NotificationSingleWidgetProvider.updateWidget(this, awm, appWidgetId)
            else -> {
                NotificationWidgetProvider.updateWidget(this, awm, appWidgetId)
                NotificationSingleWidgetProvider.updateWidget(this, awm, appWidgetId)
            }
        }

        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }

    private fun deriveLabel(matchers: List<Matcher>): String {
        val derived = matchers.filterIsInstance<Matcher.DerivedProperty>().firstOrNull()
        if (derived != null) {
            if (derived.isAudible == true) return getString(R.string.preset_recent_audible)
            if (derived.likelyHeadsup == true) return getString(R.string.preset_recent_headsup)
            if (derived.isRemoved == true) return getString(R.string.preset_recent_dismissed)
        }
        val pkg = matchers.filterIsInstance<Matcher.Package>().firstOrNull()
        if (pkg != null) return AppLabelCache.getLabel(this, pkg.packageName)
        return getString(R.string.widget_label_default_custom)
    }
}
