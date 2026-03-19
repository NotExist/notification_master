package com.notificationmaster.ui.filter

import android.content.Context
import android.media.RingtoneManager
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.R
import com.notificationmaster.core.compat.ApiVersionHelper
import com.notificationmaster.core.filter.ActionType
import com.notificationmaster.core.filter.KeywordField
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

        // Keyword 相關 views
        val btnToggleKeyword = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_toggle_keyword)
        val layoutKeywordSection = dialogView.findViewById<LinearLayout>(R.id.layout_keyword_section)
        val editKeywordPattern = dialogView.findViewById<TextInputEditText>(R.id.edit_keyword_pattern)
        val containerKeywordFields = dialogView.findViewById<LinearLayout>(R.id.container_keyword_fields)
        val switchKeywordRegex = dialogView.findViewById<MaterialSwitch>(R.id.switch_keyword_regex)

        // Keyword 區塊收合/展開
        btnToggleKeyword.setOnClickListener {
            val expanded = layoutKeywordSection.visibility == View.VISIBLE
            layoutKeywordSection.visibility = if (expanded) View.GONE else View.VISIBLE
            btnToggleKeyword.text = context.getString(
                if (expanded) R.string.filter_keyword_expand else R.string.filter_keyword_collapse
            )
        }

        // ChannelProperty 相關 views
        val labelChannelProperty = dialogView.findViewById<View>(R.id.label_channel_property)
        val layoutMinImportance = dialogView.findViewById<TextInputLayout>(R.id.layout_min_importance)
        val dropdownMinImportance = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.dropdown_min_importance)
        val layoutGroupId = dialogView.findViewById<TextInputLayout>(R.id.layout_group_id)
        val editGroupId = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.edit_group_id)

        // PersistentAlert 相關 views
        val btnChooseSound = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_choose_sound)
        val switchAlertVibrate = dialogView.findViewById<MaterialSwitch>(R.id.switch_alert_vibrate)
        var selectedSoundUri: String? = null

        // === Keyword 欄位 checkbox 動態生成 ===
        val keywordFieldLabels = mapOf(
            KeywordField.TITLE to context.getString(R.string.filter_keyword_field_title),
            KeywordField.TEXT to context.getString(R.string.filter_keyword_field_text),
            KeywordField.BIG_TEXT to context.getString(R.string.filter_keyword_field_big_text),
            KeywordField.SUB_TEXT to context.getString(R.string.filter_keyword_field_sub_text)
        )
        val keywordFieldCheckBoxes = mutableMapOf<KeywordField, MaterialCheckBox>()
        for ((field, label) in keywordFieldLabels) {
            val cb = MaterialCheckBox(context).apply {
                text = label
                isChecked = field == KeywordField.TITLE || field == KeywordField.TEXT
            }
            keywordFieldCheckBoxes[field] = cb
            containerKeywordFields.addView(cb)
        }

        // === Importance dropdown 設定 ===
        data class ImportanceOption(val label: String, val value: Int?)
        val importanceOptions = listOf(
            ImportanceOption(context.getString(R.string.filter_importance_any), null),
            ImportanceOption(context.getString(R.string.filter_importance_min), 1),
            ImportanceOption(context.getString(R.string.filter_importance_low), 2),
            ImportanceOption(context.getString(R.string.filter_importance_default), 3),
            ImportanceOption(context.getString(R.string.filter_importance_high), 4),
            ImportanceOption(context.getString(R.string.filter_importance_max), 5)
        )
        var selectedImportance: Int? = null
        val importanceAdapter = ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, importanceOptions.map { it.label })
        dropdownMinImportance.setAdapter(importanceAdapter)
        dropdownMinImportance.setText(importanceOptions[0].label, false)
        dropdownMinImportance.setOnItemClickListener { _, _, pos, _ ->
            selectedImportance = importanceOptions[pos].value
        }

        // ChannelProperty 區塊：API 26+ 才顯示
        if (!ApiVersionHelper.supportsNotificationChannel()) {
            labelChannelProperty.visibility = View.GONE
            layoutMinImportance.visibility = View.GONE
            layoutGroupId.visibility = View.GONE
        }

        // === Category 下拉選單 ===
        val categoryLabels = arrayOf(
            context.getString(R.string.filter_dialog_category_notification),
            context.getString(R.string.filter_dialog_category_calendar),
            context.getString(R.string.filter_dialog_category_auto_dismiss),
            context.getString(R.string.filter_dialog_category_persistent_alert)
        )
        val categoryValues = arrayOf(ActionType.SKIP_RECORD, ActionType.CALENDAR_EXPORT, ActionType.AUTO_DISMISS, ActionType.PERSISTENT_ALERT)
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

        /** 根據目前有效的 actionType 切換提醒設定區塊顯示 */
        fun updateAlertOptionsVisibility(effectiveType: ActionType) {
            val show = effectiveType == ActionType.PERSISTENT_ALERT
            btnChooseSound.visibility = if (show) View.VISIBLE else View.GONE
            switchAlertVibrate.visibility = if (show) View.VISIBLE else View.GONE
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

        // === EventType 可選限制 ===
        /** 根據有效的 actionType 更新 EventType checkbox 可選狀態 */
        fun updateEventTypeAvailability(effectiveType: ActionType) {
            val enabledTypes = when (effectiveType) {
                ActionType.CALENDAR_EXPORT -> setOf(EventType.POSTED, EventType.UPDATED, EventType.REMOVED)
                ActionType.PERSISTENT_ALERT -> setOf(EventType.POSTED, EventType.UPDATED)
                else -> EventType.entries.toSet()
            }
            eventTypes.forEachIndexed { i, et ->
                checkBoxes[i].isEnabled = et in enabledTypes
                if (et !in enabledTypes) checkBoxes[i].isChecked = false
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
                updateAlertOptionsVisibility(categoryValues[position])
                updateEventTypeAvailability(categoryValues[position])
            }
        }

        // 固定 actionType 或編輯模式時根據 actionType 決定延遲/提醒區塊
        val effectiveActionType = if (isEditMode) existingRule!!.action.actionType else actionType
        if (effectiveActionType != null) {
            updateDismissDelayVisibility(effectiveActionType)
            updateAlertOptionsVisibility(effectiveActionType)
            updateEventTypeAvailability(effectiveActionType)
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

        // 鈴聲選擇按鈕
        btnChooseSound.setOnClickListener {
            showSoundPicker(context, selectedSoundUri) { uri ->
                selectedSoundUri = uri
                btnChooseSound.text = if (uri != null) {
                    RingtoneManager.getRingtone(context, android.net.Uri.parse(uri))
                        ?.getTitle(context) ?: context.getString(R.string.filter_alert_choose_sound)
                } else {
                    context.getString(R.string.filter_alert_sound_default)
                }
            }
        }

        // 編輯模式：回填 PersistentAlert 設定
        (existingRule?.action as? RuleAction.PersistentAlert)?.let { alert ->
            selectedSoundUri = alert.soundUri
            switchAlertVibrate.isChecked = alert.vibrate
            if (alert.soundUri != null) {
                btnChooseSound.text = RingtoneManager.getRingtone(
                    context, android.net.Uri.parse(alert.soundUri)
                )?.getTitle(context) ?: context.getString(R.string.filter_alert_choose_sound)
            } else {
                btnChooseSound.text = context.getString(R.string.filter_alert_sound_default)
            }
        }

        // 編輯模式：回填 keyword/channelProperty
        if (isEditMode) {
            existingRule!!.matchers.filterIsInstance<Matcher.Keyword>().firstOrNull()?.let { kw ->
                editKeywordPattern.setText(kw.pattern)
                switchKeywordRegex.isChecked = kw.isRegex
                keywordFieldCheckBoxes.forEach { (field, cb) -> cb.isChecked = field in kw.fields }
                // 有 keyword matcher 時自動展開
                layoutKeywordSection.visibility = View.VISIBLE
                btnToggleKeyword.text = context.getString(R.string.filter_keyword_collapse)
            }
            existingRule.matchers.filterIsInstance<Matcher.ChannelProperty>().firstOrNull()?.let { cp ->
                cp.minImportance?.let { imp ->
                    val idx = importanceOptions.indexOfFirst { it.value == imp }
                    if (idx >= 0) {
                        selectedImportance = imp
                        dropdownMinImportance.setText(importanceOptions[idx].label, false)
                    }
                }
                cp.groupId?.let { editGroupId.setText(it) }
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

        // === 全選按鈕 ===
        btnSelectAll.setOnClickListener {
            val enabledBoxes = checkBoxes.filter { it.isEnabled }
            val allChecked = enabledBoxes.all { it.isChecked }
            enabledBoxes.forEach { it.isChecked = !allChecked }
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
                // 觸發 channelId / groupId 建議更新
                loadChannelSuggestions(scope, database, rawPackageNames[position], editChannelId)
                loadGroupIdSuggestions(scope, database, rawPackageNames[position], editGroupId)
            }
        }

        // packageName 變動時更新 channelId / groupId 建議
        editPackageName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val pkg = s?.toString()?.trim() ?: return
                loadChannelSuggestions(scope, database, pkg, editChannelId)
                loadGroupIdSuggestions(scope, database, pkg, editGroupId)
            }
        })

        // 預填 packageName 時立即載入 channel / groupId 建議
        val initPackageName = if (isEditMode) existingRule!!.packageName else prefillPackageName
        if (!initPackageName.isNullOrEmpty()) {
            loadChannelSuggestions(scope, database, initPackageName, editChannelId)
            loadGroupIdSuggestions(scope, database, initPackageName, editGroupId)
        }

        // === 預覽匹配結果 ===
        val btnPreviewMatch = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_preview_match)
        btnPreviewMatch.setOnClickListener {
            val packageName = editPackageName.text.toString().trim()
            if (packageName.isEmpty()) {
                Toast.makeText(context, R.string.filter_preview_package_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 建構臨時 matcher 列表
            val tempMatchers = mutableListOf<Matcher>(Matcher.Package(packageName))
            val channelId = editChannelId.text.toString().trim().ifEmpty { null }
            if (channelId != null) tempMatchers.add(Matcher.Channel(channelId))
            val selectedETs = eventTypes.filterIndexed { i, _ -> checkBoxes[i].isChecked && checkBoxes[i].isEnabled }
                .map { it.name }.toSet()
            if (selectedETs.isNotEmpty()) tempMatchers.add(Matcher.EventTypes(selectedETs))
            val kwPattern = editKeywordPattern.text?.toString()?.trim().orEmpty()
            if (kwPattern.isNotEmpty()) {
                val selectedFields = keywordFieldCheckBoxes.filter { it.value.isChecked }.keys
                if (selectedFields.isNotEmpty()) {
                    tempMatchers.add(Matcher.Keyword(kwPattern, selectedFields, switchKeywordRegex.isChecked))
                }
            }
            val gid = editGroupId.text?.toString()?.trim()?.ifEmpty { null }
            if (selectedImportance != null || gid != null) {
                tempMatchers.add(Matcher.ChannelProperty(minImportance = selectedImportance, groupId = gid))
            }
            val tempRule = Rule(matchers = tempMatchers, action = RuleAction.SkipRecord)

            val previewLimit = 200
            scope.launch {
                val notifications = withContext(Dispatchers.IO) {
                    database.notificationDao().getNotificationsPaged(previewLimit, 0)
                }
                val matched = notifications.filter { n ->
                    val mc = com.notificationmaster.core.filter.MatchContext(
                        packageName = n.packageName,
                        channelId = n.channelId,
                        title = n.title,
                        text = n.text,
                        bigText = n.bigText,
                        subText = n.subText
                    )
                    tempRule.matches(mc)
                }

                if (matched.isEmpty()) {
                    Toast.makeText(context, R.string.filter_preview_empty, Toast.LENGTH_SHORT).show()
                } else {
                    val items = matched.take(20).map { n ->
                        val title = n.title ?: context.getString(R.string.no_title)
                        val text = n.text ?: ""
                        if (text.isNotEmpty()) "$title — $text" else title
                    }.toTypedArray<CharSequence>()

                    AlertDialog.Builder(context)
                        .setTitle(context.getString(R.string.filter_preview_title, previewLimit))
                        .setMessage(context.getString(R.string.filter_preview_count, matched.size))
                        .setItems(items, null)
                        .setPositiveButton(R.string.ok, null)
                        .show()
                }
            }
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

                // 驗證至少選一個已啟用的 EventType
                val selectedEventTypes = eventTypes.filterIndexed { i, _ -> checkBoxes[i].isChecked && checkBoxes[i].isEnabled }
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

                // Keyword matcher（pattern 非空時才加入）
                val keywordPattern = editKeywordPattern.text?.toString()?.trim().orEmpty()
                if (keywordPattern.isNotEmpty()) {
                    val selectedFields = keywordFieldCheckBoxes.filter { it.value.isChecked }.keys
                    if (selectedFields.isEmpty()) {
                        Toast.makeText(context, R.string.filter_keyword_field_required, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    matchers.add(Matcher.Keyword(keywordPattern, selectedFields, switchKeywordRegex.isChecked))
                }

                // ChannelProperty matcher（有選擇時才加入）
                val groupId = editGroupId.text?.toString()?.trim()?.ifEmpty { null }
                if (selectedImportance != null || groupId != null) {
                    matchers.add(Matcher.ChannelProperty(minImportance = selectedImportance, groupId = groupId))
                }

                // 建構 RuleAction
                val action: RuleAction = when (resolvedActionType) {
                    ActionType.SKIP_RECORD -> RuleAction.SkipRecord
                    ActionType.CALENDAR_EXPORT -> RuleAction.CalendarExport
                    ActionType.AUTO_DISMISS -> RuleAction.AutoDismiss(delayMs = dismissDelayMs)
                    ActionType.PERSISTENT_ALERT -> RuleAction.PersistentAlert(
                        soundUri = selectedSoundUri,
                        vibrate = switchAlertVibrate.isChecked
                    )
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

    /**
     * 非同步載入指定 packageName 的 channel group ID 建議清單
     */
    private fun loadGroupIdSuggestions(
        scope: CoroutineScope,
        database: com.notificationmaster.data.db.NotificationDatabase,
        packageName: String,
        editGroupId: MaterialAutoCompleteTextView
    ) {
        scope.launch {
            val groupIds = withContext(Dispatchers.IO) {
                database.channelDao().getByPackageName(packageName)
                    .mapNotNull { it.groupId }
                    .distinct()
            }
            val adapter = ArrayAdapter(
                editGroupId.context,
                android.R.layout.simple_dropdown_item_1line,
                groupIds
            )
            editGroupId.setAdapter(adapter)
        }
    }

    /**
     * 顯示系統鈴聲選擇 Dialog（單選列表）
     *
     * 使用 RingtoneManager.cursor 手動列出鈴聲清單，
     * 避免依賴 ActivityResultLauncher（object 中不可用）。
     */
    private fun showSoundPicker(context: Context, currentUri: String?, onSelected: (String?) -> Unit) {
        val rm = RingtoneManager(context)
        rm.setType(RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_NOTIFICATION)
        val cursor = rm.cursor

        val titles = mutableListOf(context.getString(R.string.filter_alert_sound_default))
        val uris = mutableListOf<String?>(null)

        while (cursor.moveToNext()) {
            titles.add(cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX))
            uris.add(rm.getRingtoneUri(cursor.position).toString())
        }

        val checked = uris.indexOf(currentUri).coerceAtLeast(0)

        AlertDialog.Builder(context)
            .setTitle(R.string.filter_alert_choose_sound)
            .setSingleChoiceItems(titles.toTypedArray(), checked) { dialog, which ->
                onSelected(uris[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
