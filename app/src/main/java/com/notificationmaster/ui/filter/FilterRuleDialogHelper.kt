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
import com.google.android.material.textfield.TextInputLayout
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.R
import com.notificationmaster.core.filter.FilterCategory
import com.notificationmaster.core.filter.FilterRule
import com.notificationmaster.core.filter.FilterRuleStore
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

    /**
     * 顯示新增或編輯規則 Dialog
     *
     * @param context Activity/Fragment context
     * @param category 固定 category；null 則讓使用者在 Dialog 中選擇
     * @param prefillPackageName 預填 packageName（新增模式）
     * @param prefillChannelId 預填 channelId（新增模式）
     * @param existingRule 既有規則（編輯模式），非 null 時為編輯
     * @param onRuleAdded 規則新增/更新完成後的 callback（用於 refresh UI）
     */
    fun showAddRuleDialog(
        context: Context,
        category: FilterCategory? = null,
        prefillPackageName: String? = null,
        prefillChannelId: String? = null,
        existingRule: FilterRule? = null,
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

        // === Category 下拉選單 ===
        val categoryLabels = arrayOf(
            context.getString(R.string.filter_dialog_category_notification),
            context.getString(R.string.filter_dialog_category_calendar)
        )
        val categoryValues = arrayOf(FilterCategory.NOTIFICATION, FilterCategory.CALENDAR_EXPORT)
        var selectedCategoryIndex = 0

        if (isEditMode) {
            // 編輯模式：不顯示 category 選擇
            layoutCategory.visibility = View.GONE
        } else if (category == null) {
            layoutCategory.visibility = View.VISIBLE
            val categoryAdapter = ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, categoryLabels)
            dropdownCategory.setAdapter(categoryAdapter)
            dropdownCategory.setText(categoryLabels[0], false)
            dropdownCategory.setOnItemClickListener { _, _, position, _ ->
                selectedCategoryIndex = position
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
        // 確保 category 已載入
        if (category != null) {
            FilterRuleStore.load(context, category)
        } else {
            FilterRuleStore.load(context, FilterCategory.NOTIFICATION)
            FilterRuleStore.load(context, FilterCategory.CALENDAR_EXPORT)
        }

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

                // 決定 category
                val resolvedCategory = category ?: categoryValues[selectedCategoryIndex]

                if (isEditMode) {
                    val updatedRule = existingRule!!.copy(
                        packageName = packageName,
                        channelId = channelId,
                        eventTypes = selectedEventTypes
                    )
                    FilterRuleStore.updateRule(context, resolvedCategory, updatedRule)
                    Toast.makeText(context, R.string.filter_rule_updated, Toast.LENGTH_SHORT).show()
                } else {
                    val rule = FilterRule(
                        packageName = packageName,
                        channelId = channelId,
                        eventTypes = selectedEventTypes
                    )
                    FilterRuleStore.addRule(context, resolvedCategory, rule)
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
