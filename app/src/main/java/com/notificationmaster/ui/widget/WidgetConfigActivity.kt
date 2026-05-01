package com.notificationmaster.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
 * Widget 設定 Activity
 *
 * widget = 「呈現某個 LIST_FILTER rule」的容器；本身有獨立 label。
 *
 * 流程：
 * 1. onCreate → showRuleChooser：列出所有 LIST_FILTER rules + 「自訂篩選…」
 * 2. 選 rule → 進編輯器（matchers/listFilter 預填、widget label 預填顯示名）
 * 3. 選自訂 → 進編輯器（空 matchers、預設 ListFilter、widget label 預填「自訂篩選」）
 * 4. 編輯器確認 → 若 matchers/listFilter 沒變 → 直接 bind；變了 → 建新 rule
 *    (rule.name = widget label) → bind
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

        val preselectedRuleId = intent?.getStringExtra(EXTRA_PRESELECTED_RULE_ID)
            ?: AppPreferences.getWidgetRuleId(this, appWidgetId)
        showRuleChooser(preselectedRuleId)
    }

    private fun showRuleChooser(preselectedRuleId: String?) {
        val rules = RuleEngine.getListFilterRules()
            .sortedWith(compareByDescending<Rule> { it.isBuiltIn }.thenBy { it.createdAt })
        val labels = rules.map { ruleDisplayLabel(it) } + getString(R.string.widget_chooser_custom)
        val customIndex = rules.size
        val initialIdx = preselectedRuleId
            ?.let { id -> rules.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
            ?: 0

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.widget_chooser_title)
            .setSingleChoiceItems(labels.toTypedArray<CharSequence>(), initialIdx) { dlg, which ->
                dlg.dismiss()
                if (which == customIndex) {
                    openEditor(initialRule = null, defaultLabel = getString(R.string.widget_label_default_custom))
                } else {
                    val rule = rules[which]
                    val currentLabel = AppPreferences.getWidgetLabel(this, appWidgetId)
                    val defaultLabel = if (preselectedRuleId == rule.id && currentLabel != null)
                        currentLabel else ruleDisplayLabel(rule)
                    openEditor(initialRule = rule, defaultLabel = defaultLabel)
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun openEditor(initialRule: Rule?, defaultLabel: String) {
        val existingListFilter = initialRule?.action as? RuleAction.ListFilter
        FilterRuleDialogHelper.showAddRuleDialog(
            context = this,
            widgetMode = true,
            existingWidgetMatchers = initialRule?.matchers ?: emptyList(),
            existingWidgetListFilter = existingListFilter,
            existingWidgetLabel = defaultLabel,
            onListFilterReady = { matchers, listFilter, widgetLabel ->
                handleListFilterReady(matchers, listFilter, widgetLabel, initialRule, defaultLabel)
            },
            onWidgetCancelled = { showRuleChooser(initialRule?.id) }
        )
    }

    /**
     * 編輯器確認後：
     * - matchers + action 與 initialRule 相同 → 直接綁此 rule，套用 user 輸入的 label
     * - 否則 → 建新 user-defined rule（rule.name 用 widgetLabel；若 widgetLabel 空就 deriveLabel）
     */
    private fun handleListFilterReady(
        matchers: List<Matcher>,
        listFilter: RuleAction.ListFilter,
        widgetLabel: String,
        initialRule: Rule?,
        defaultLabel: String
    ) {
        val effectiveLabel = widgetLabel.ifEmpty { defaultLabel }
        val unchanged = initialRule != null
            && matchers == initialRule.matchers
            && listFilter == initialRule.action
        if (unchanged && initialRule != null) {
            bindAndFinish(initialRule, effectiveLabel)
            return
        }
        val ruleName = effectiveLabel.ifEmpty { deriveLabel(matchers) }
        val rule = Rule(name = ruleName, matchers = matchers, action = listFilter)
        RuleRepository.addRule(this, rule)
        bindAndFinish(rule, effectiveLabel)
    }

    private fun bindAndFinish(rule: Rule, label: String) {
        AppPreferences.setWidgetRuleIdSync(this, appWidgetId, rule.id)
        AppPreferences.setWidgetLabelSync(this, appWidgetId, label)
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

    /** 內建 rule 走 i18n 顯示名（與 Timeline chip / Shortcut 一致） */
    private fun ruleDisplayLabel(rule: Rule): String = when (rule.id) {
        RuleRepository.builtInRuleIdAudible() -> getString(R.string.preset_recent_audible)
        RuleRepository.builtInRuleIdHeadsup() -> getString(R.string.preset_recent_headsup)
        RuleRepository.builtInRuleIdDismissed() -> getString(R.string.preset_recent_dismissed)
        else -> rule.name ?: deriveLabel(rule.matchers)
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
