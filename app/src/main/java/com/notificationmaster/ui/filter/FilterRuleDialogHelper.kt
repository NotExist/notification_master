package com.notificationmaster.ui.filter

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import com.google.android.material.checkbox.MaterialCheckBox
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.R
import com.notificationmaster.core.filter.ActionType
import com.notificationmaster.core.filter.Matcher
import com.notificationmaster.core.filter.Rule
import com.notificationmaster.core.filter.RuleAction
import com.notificationmaster.core.filter.RuleEngine
import com.notificationmaster.data.db.entity.EventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 新增過濾規則 Dialog 的共用 helper
 *
 * 供 FilterSettingsFragment FAB 和外部長按入口（時間軸、歸檔）共用。
 */
object FilterRuleDialogHelper {

    /** 延遲選項：label → 毫秒值（-1 代表自訂） */
    private data class DelayOption(val labelResId: Int, val delayMs: Long)

    private val DELAY_OPTIONS = listOf(
        DelayOption(R.string.filter_dismiss_delay_immediate, 0),
        DelayOption(R.string.filter_dismiss_delay_5min, 5 * 60 * 1000L),
        DelayOption(R.string.filter_dismiss_delay_15min, 15 * 60 * 1000L),
        DelayOption(R.string.filter_dismiss_delay_30min, 30 * 60 * 1000L),
        DelayOption(R.string.filter_dismiss_delay_1h, 60 * 60 * 1000L),
        DelayOption(R.string.filter_dismiss_delay_custom, -1)
    )

    /**
     * 顯示新增或編輯規則 Dialog
     *
     * @param context Activity/Fragment context
     * @param actionType 固定 actionType；null 則讓使用者在 Dialog 中選擇
     * @param prefillPackageName 預填 packageName（新增模式）
     * @param prefillChannelId 預填 channelId（新增模式）
     * @param existingRule 既有規則（編輯模式），非 null 時為編輯
     * @param onRuleAdded 規則新增/更新完成後的 callback（用於 refresh UI）
     */
    fun showAddRuleDialog(
        context: Context,
        actionType: ActionType? = null,
        prefillPackageName: String? = null,
        prefillChannelId: String? = null,
        existingRule: Rule? = null,
        onRuleAdded: (() -> Unit)? = null
    ) {
        val isEditMode = existingRule != null
        val dialogView = LayoutInflater.from(context)
            .inflate(R.layout.dialog_add_filter_rule, null)

        // === Views ===
        val layoutCategory = dialogView.findViewById<TextInputLayout>(R.id.layout_category)
        val dropdownCategory = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.dropdown_category)
        val editPackageName = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.edit_package_name)
        val layoutPackageName = dialogView.findViewById<TextInputLayout>(R.id.layout_package_name)
        val editChannelId = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.edit_channel_id)
        val btnSelectAll = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_select_all)
        val containerEventTypes = dialogView.findViewById<LinearLayout>(R.id.container_event_types)
        val layoutDismissDelay = dialogView.findViewById<TextInputLayout>(R.id.layout_dismiss_delay)
        val dropdownDismissDelay = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.dropdown_dismiss_delay)
        val layoutDismissDelayCustom = dialogView.findViewById<TextInputLayout>(R.id.layout_dismiss_delay_custom)
        val editDismissDelayCustom = dialogView.findViewById<TextInputEditText>(R.id.edit_dismiss_delay_custom)

        // === Category 下拉選單 ===
        val categoryLabels = arrayOf(
            context.getString(R.string.filter_dialog_category_notification),
            context.getString(R.string.filter_dialog_category_calendar),
            context.getString(R.string.filter_dialog_category_auto_dismiss)
        )
        val categoryValues = arrayOf(ActionType.SKIP_RECORD, ActionType.CALENDAR_EXPORT, ActionType.AUTO_DISMISS)
        var selectedCategoryIndex = 0

        // 延遲選項狀態
        val delayLabels = DELAY_OPTIONS.map { context.getString(it.labelResId) }
        var selectedDelayIndex = 0  // 預設「立即」
        var isCustomDelay = false

        /** 根據目前有效的 actionType 切換延遲區塊顯示 */
        fun updateDismissDelayVisibility(effectiveType: ActionType) {
            val show = effectiveType == ActionType.AUTO_DISMISS
            layoutDismissDelay.visibility = if (show) View.VISIBLE else View.GONE
            if (!show) {
                layoutDismissDelayCustom.visibility = View.GONE
                isCustomDelay = false
            }
        }

        if (isEditMode) {
            // 編輯模式：不顯示 category 選擇
            layoutCategory.visibility = View.GONE
        } else if (actionType == null) {
            layoutCategory.visibility = View.VISIBLE
            val categoryAdapter = ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, categoryLabels)
            dropdownCategory.setAdapter(categoryAdapter)
            dropdownCategory.setText(categoryLabels[0], false)
            dropdownCategory.setOnItemClickListener { _, _, position, _ ->
                selectedCategoryIndex = position
                updateDismissDelayVisibility(categoryValues[position])
            }
        }

        // 固定 actionType 或編輯模式時根據 actionType 決定延遲區塊
        val effectiveActionType = if (isEditMode) existingRule!!.action.actionType else actionType
        if (effectiveActionType != null) {
            updateDismissDelayVisibility(effectiveActionType)
        }

        // === 延遲 Dropdown 設定 ===
        val delayAdapter = ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, delayLabels)
        dropdownDismissDelay.setAdapter(delayAdapter)
        dropdownDismissDelay.setText(delayLabels[0], false)
        dropdownDismissDelay.setOnItemClickListener { _, _, position, _ ->
            selectedDelayIndex = position
            isCustomDelay = DELAY_OPTIONS[position].delayMs == -1L
            layoutDismissDelayCustom.visibility = if (isCustomDelay) View.VISIBLE else View.GONE
        }

        // 編輯模式：回填延遲值
        val existingDelayMs = (existingRule?.action as? RuleAction.AutoDismiss)?.delayMs ?: 0L
        if (isEditMode && existingDelayMs > 0) {
            val existingMs = existingDelayMs
            val matchIndex = DELAY_OPTIONS.indexOfFirst { it.delayMs == existingMs }
            if (matchIndex >= 0) {
                selectedDelayIndex = matchIndex
                dropdownDismissDelay.setText(delayLabels[matchIndex], false)
            } else {
                // 自訂值
                val customIndex = DELAY_OPTIONS.indexOfFirst { it.delayMs == -1L }
                selectedDelayIndex = customIndex
                isCustomDelay = true
                dropdownDismissDelay.setText(delayLabels[customIndex], false)
                layoutDismissDelayCustom.visibility = View.VISIBLE
                editDismissDelayCustom.setText((existingMs / 60000).toString())
            }
        }

        // === 預填值 ===
        if (isEditMode) {
            editPackageName.setText(existingRule!!.packageName)
            existingRule.channelId?.let { editChannelId.setText(it) }
        } else {
            prefillPackageName?.let { editPackageName.setText(it) }
            prefillChannelId?.let { editChannelId.setText(it) }
        }

        // === EventType CheckBox 動態生成 ===
        val eventTypes = EventType.entries
        val checkBoxes = mutableListOf<MaterialCheckBox>()
        for (et in eventTypes) {
            val cb = MaterialCheckBox(context).apply {
                text = et.name
                isChecked = isEditMode && et.name in existingRule!!.eventTypes
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            checkBoxes.add(cb)
            containerEventTypes.addView(cb)
        }

        // === 全選按鈕 ===
        btnSelectAll.setOnClickListener {
            val allChecked = checkBoxes.all { it.isChecked }
            checkBoxes.forEach { it.isChecked = !allChecked }
        }

        // === AutoComplete 資料載入 ===
        val database = NotificationMasterApp.getInstance().database
        val scopeJob = Job()
        val scope = CoroutineScope(scopeJob + Dispatchers.Main)

        // 載入 packageName 建議
        scope.launch {
            val apps = withContext(Dispatchers.IO) {
                database.appSourceDao().getAll()
            }
            val packageNames = apps.map { app ->
                val label = app.appName
                if (label != null) "$label (${app.packageName})" else app.packageName
            }
            val rawPackageNames = apps.map { it.packageName }

            val adapter = ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, packageNames)
            editPackageName.setAdapter(adapter)

            // 選擇項目時設為實際 packageName
            editPackageName.setOnItemClickListener { _, _, position, _ ->
                editPackageName.setText(rawPackageNames[position])
                editPackageName.setSelection(rawPackageNames[position].length)
                // 觸發 channelId 建議更新
                loadChannelSuggestions(scope, database, rawPackageNames[position], editChannelId)
            }
        }

        // packageName 變動時更新 channelId 建議
        editPackageName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val pkg = s?.toString()?.trim() ?: return
                loadChannelSuggestions(scope, database, pkg, editChannelId)
            }
        })

        // 預填 packageName 時立即載入 channel 建議
        val initPackageName = if (isEditMode) existingRule!!.packageName else prefillPackageName
        if (!initPackageName.isNullOrEmpty()) {
            loadChannelSuggestions(scope, database, initPackageName, editChannelId)
        }

        // === 建立 Dialog ===
        // 確保規則已載入
        RuleEngine.load(context)

        val dialogTitle = if (isEditMode) R.string.filter_dialog_edit_title else R.string.filter_dialog_title
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(dialogTitle)
            .setView(dialogView)
            .setPositiveButton(R.string.ok, null) // listener 在 show() 後覆寫以防自動關閉
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnDismissListener { scopeJob.cancel() }

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                // 驗證 packageName
                val packageName = editPackageName.text.toString().trim()
                if (packageName.isEmpty()) {
                    layoutPackageName.error = context.getString(R.string.filter_package_name_required)
                    return@setOnClickListener
                }
                layoutPackageName.error = null

                // 驗證至少選一個 EventType
                val selectedEventTypes = eventTypes.filterIndexed { i, _ -> checkBoxes[i].isChecked }
                    .map { it.name }
                    .toSet()
                if (selectedEventTypes.isEmpty()) {
                    Toast.makeText(context, R.string.filter_event_type_required, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val channelId = editChannelId.text.toString().trim().ifEmpty { null }

                // 決定 actionType
                val resolvedActionType = actionType ?: categoryValues[selectedCategoryIndex]

                // 計算延遲毫秒（僅 AUTO_DISMISS）
                val dismissDelayMs = if (resolvedActionType == ActionType.AUTO_DISMISS) {
                    if (isCustomDelay) {
                        val minutes = editDismissDelayCustom.text?.toString()?.toLongOrNull()
                        if (minutes == null || minutes < 0) {
                            Toast.makeText(context, R.string.filter_dismiss_delay_custom_hint, Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        minutes * 60 * 1000L
                    } else {
                        DELAY_OPTIONS[selectedDelayIndex].delayMs
                    }
                } else 0L

                // 建構 Matcher 列表
                val matchers = mutableListOf<Matcher>(Matcher.Package(packageName))
                if (channelId != null) {
                    matchers.add(Matcher.Channel(channelId))
                }
                matchers.add(Matcher.EventTypes(selectedEventTypes))

                // 建構 RuleAction
                val action: RuleAction = when (resolvedActionType) {
                    ActionType.SKIP_RECORD -> RuleAction.SkipRecord
                    ActionType.CALENDAR_EXPORT -> RuleAction.CalendarExport
                    ActionType.AUTO_DISMISS -> RuleAction.AutoDismiss(delayMs = dismissDelayMs)
                }

                if (isEditMode) {
                    val updatedRule = Rule(
                        id = existingRule!!.id,
                        matchers = matchers,
                        action = action,
                        createdAt = existingRule.createdAt
                    )
                    RuleEngine.updateRule(context, updatedRule)
                    Toast.makeText(context, R.string.filter_rule_updated, Toast.LENGTH_SHORT).show()
                } else {
                    val rule = Rule(matchers = matchers, action = action)
                    RuleEngine.addRule(context, rule)
                    Toast.makeText(context, R.string.filter_rule_added, Toast.LENGTH_SHORT).show()
                }
                onRuleAdded?.invoke()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    /**
     * 非同步載入指定 packageName 的 channel 建議清單
     */
    private fun loadChannelSuggestions(
        scope: CoroutineScope,
        database: com.notificationmaster.data.db.NotificationDatabase,
        packageName: String,
        editChannelId: MaterialAutoCompleteTextView
    ) {
        scope.launch {
            val channels = withContext(Dispatchers.IO) {
                database.channelDao().getByPackageName(packageName)
            }
            val channelIds = channels.map { ch ->
                val name = ch.channelName
                if (name != null) "$name (${ch.channelId})" else ch.channelId
            }
            val rawChannelIds = channels.map { it.channelId }

            val adapter = ArrayAdapter(
                editChannelId.context,
                android.R.layout.simple_dropdown_item_1line,
                channelIds
            )
            editChannelId.setAdapter(adapter)

            editChannelId.setOnItemClickListener { _, _, position, _ ->
                editChannelId.setText(rawChannelIds[position])
                editChannelId.setSelection(rawChannelIds[position].length)
            }
        }
    }
}
