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
 * Widget 設定 Activity（Plan D + 後續調整）
 *
 * widget 是「呈現某個 LIST_FILTER rule」的容器，本身有獨立 label。
 *
 * 流程（新建）：
 * 1. onCreate → showRuleChooser：列出所有 LIST_FILTER rules（內建 + user-defined）+
 *    「自訂篩選」選項
 * 2. 選 rule → showLabelDialog（預填 rule 顯示名）→ bindAndFinish
 * 3. 選自訂 → openEditor widgetMode → 編輯確認 → 詢問 rule 名稱 → 建 rule →
 *    showLabelDialog → bindAndFinish
 *
 * 重設既有 widget：showRuleChooser 預選當前綁定 rule（同樣流程）
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
                    openEditor(initialRule = null)
                } else {
                    val rule = rules[which]
                    showLabelDialog(rule, defaultLabel = ruleDisplayLabel(rule))
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun openEditor(initialRule: Rule?) {
        val existingListFilter = initialRule?.action as? RuleAction.ListFilter
        FilterRuleDialogHelper.showAddRuleDialog(
            context = this,
            widgetMode = true,
            existingWidgetMatchers = initialRule?.matchers ?: emptyList(),
            existingWidgetListFilter = existingListFilter,
            onListFilterReady = { matchers, listFilter ->
                handleListFilterReady(matchers, listFilter, initialRule)
            },
            onWidgetCancelled = { showRuleChooser(initialRule?.id) }
        )
    }

    /**
     * 編輯器確認後：
     * - matchers + action 與 initialRule 相同 → 直接綁此 rule
     * - 否則 → 詢問新 rule 名稱建立 user-defined rule（避免改動既存 rule 影響其他 widget）
     */
    private fun handleListFilterReady(
        matchers: List<Matcher>,
        listFilter: RuleAction.ListFilter,
        initialRule: Rule?
    ) {
        val unchanged = initialRule != null
            && matchers == initialRule.matchers
            && listFilter == initialRule.action
        if (unchanged && initialRule != null) {
            showLabelDialog(initialRule, defaultLabel = ruleDisplayLabel(initialRule))
            return
        }
        promptRuleName(initialRule, matchers, listFilter)
    }

    /** 建立新 rule 流程：先詢問 rule 名稱，建好後再進 widget label 設定 */
    private fun promptRuleName(initialRule: Rule?, matchers: List<Matcher>, listFilter: RuleAction.ListFilter) {
        val defaultName = initialRule?.let { ruleDisplayLabel(it) } ?: deriveLabel(matchers)
        showInputDialog(
            titleRes = R.string.preset_save_title,
            hintRes = R.string.preset_save_name_hint,
            defaultText = defaultName
        ) { name ->
            val rule = Rule(name = name.ifEmpty { defaultName }, matchers = matchers, action = listFilter)
            RuleRepository.addRule(this, rule)
            showLabelDialog(rule, defaultLabel = name.ifEmpty { defaultName })
        }
    }

    /** Widget 標題輸入；標題與 rule.name 解耦 */
    private fun showLabelDialog(rule: Rule, defaultLabel: String) {
        showInputDialog(
            titleRes = R.string.widget_label_dialog_title,
            hintRes = R.string.widget_label_hint,
            defaultText = defaultLabel
        ) { label ->
            bindAndFinish(rule, label.ifEmpty { defaultLabel })
        }
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

    private fun showInputDialog(
        titleRes: Int,
        hintRes: Int,
        defaultText: String,
        onConfirm: (String) -> Unit
    ) {
        val editText = TextInputEditText(this).apply {
            setText(defaultText)
            setSelection(text?.length ?: 0)
        }
        val layout = TextInputLayout(
            this, null, com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = getString(hintRes)
            setPadding(48, 16, 48, 0)
            addView(editText)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onConfirm(editText.text?.toString()?.trim().orEmpty())
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
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
