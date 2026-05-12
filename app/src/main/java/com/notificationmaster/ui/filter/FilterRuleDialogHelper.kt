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
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.R
import com.notificationmaster.core.action.ClipboardCopyHelper
import com.notificationmaster.core.content.ExportDetailLevel
import com.notificationmaster.export.calendar.CalendarExporter
import com.notificationmaster.core.compat.ApiVersionHelper
import com.notificationmaster.core.filter.ActionType
import com.notificationmaster.core.filter.KeywordField
import com.notificationmaster.core.filter.MatchContext
import com.notificationmaster.core.filter.Matcher
import com.notificationmaster.core.filter.NotificationFlag
import com.notificationmaster.core.filter.Rule
import com.notificationmaster.core.filter.RuleAction
import com.notificationmaster.core.filter.RuleRepository
import com.notificationmaster.core.filter.FieldOp
import com.notificationmaster.core.filter.FieldWhitelist
import com.notificationmaster.core.filter.OrderBy
import com.notificationmaster.core.NotificationSnapshotParser
import com.notificationmaster.data.db.dao.countSync
import com.notificationmaster.data.db.entity.EventType
import com.notificationmaster.data.db.entity.NotificationEventEntity
import com.notificationmaster.data.filter.EventFilterSpec
import com.notificationmaster.ui.common.NotificationDisplay
import com.notificationmaster.ui.search.NotificationAdapter
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
     * @param soundPicker 持續提醒鈴聲系統選擇器；由呼叫端 Fragment 在 field initializer
     *                    建立並傳入。為 null 時會 fallback 到 cursor-based 選單
     * @param onRuleAdded 規則新增/更新完成後的 callback（用於 refresh UI）
     * @param widgetMode Widget 模式：隱藏 ActionType/EventType/Action 區塊，Package 非必填
     * @param existingWidgetMatchers Widget 重新設定時的既有 matchers（widgetMode 專用）
     * @param onMatchersReady Widget 模式確認後回傳 matchers（widgetMode 專用）
     * @param onWidgetCancelled Widget 模式取消回呼（widgetMode 專用）
     */
    fun showAddRuleDialog(
        context: Context,
        actionType: ActionType? = null,
        prefillPackageName: String? = null,
        prefillChannelId: String? = null,
        existingRule: Rule? = null,
        soundPicker: SoundPickerLauncher? = null,
        calendarPicker: CalendarPickerLauncher? = null,
        onRuleAdded: (() -> Unit)? = null,
        widgetMode: Boolean = false,
        existingWidgetMatchers: List<Matcher>? = null,
        existingWidgetListFilter: RuleAction.ListFilter? = null,
        existingWidgetLabel: String? = null,
        onMatchersReady: ((List<Matcher>) -> Unit)? = null,
        onListFilterReady: ((List<Matcher>, RuleAction.ListFilter, String) -> Unit)? = null,
        onWidgetCancelled: (() -> Unit)? = null
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
        val dialogScrollView = dialogView as android.widget.ScrollView
        btnToggleKeyword.setOnClickListener {
            val expanded = layoutKeywordSection.visibility == View.VISIBLE
            layoutKeywordSection.visibility = if (expanded) View.GONE else View.VISIBLE
            btnToggleKeyword.text = context.getString(
                if (expanded) R.string.filter_keyword_expand else R.string.filter_keyword_collapse
            )
            // 展開時清除焦點並捲動到按鈕位置，避免 ScrollView 跳回先前焦點
            if (!expanded) {
                btnToggleKeyword.post {
                    dialogScrollView.smoothScrollTo(0, btnToggleKeyword.top)
                }
            }
        }

        // ChannelProperty 相關 views
        // section_header_channel_property 區塊已拆除（importance 移到「加入條件」、
        // group_id 搬到 ChannelID 之後）
        // (importance 下拉已移除，由「加入條件」importance >= N 取代)
        val layoutGroupId = dialogView.findViewById<TextInputLayout>(R.id.layout_group_id)
        val editGroupId = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.edit_group_id)

        // Flags / DerivedProperty 相關 views
        val btnToggleFlags = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_toggle_flags)
        val layoutFlagsSection = dialogView.findViewById<LinearLayout>(R.id.layout_flags_section)
        val containerFlags = dialogView.findViewById<LinearLayout>(R.id.container_flags)
        val containerDerived = dialogView.findViewById<LinearLayout>(R.id.container_derived)

        // Flags 區塊收合/展開
        btnToggleFlags.setOnClickListener {
            val expanded = layoutFlagsSection.visibility == View.VISIBLE
            layoutFlagsSection.visibility = if (expanded) View.GONE else View.VISIBLE
            btnToggleFlags.text = context.getString(
                if (expanded) R.string.filter_flags_expand else R.string.filter_flags_collapse
            )
            if (!expanded) {
                btnToggleFlags.post {
                    dialogScrollView.smoothScrollTo(0, btnToggleFlags.top)
                }
            }
        }

        // === Flags 三態列動態生成 ===
        data class FlagToggle(val id: Int, val group: com.google.android.material.button.MaterialButtonToggleGroup)

        fun createTriStateRow(parent: LinearLayout, label: String, subtitle: String? = null): com.google.android.material.button.MaterialButtonToggleGroup {
            val density = context.resources.displayMetrics.density
            val buttonHeight = (36 * density).toInt()
            val hPadding = (16 * density).toInt()
            val rowBottomMargin = (4 * density).toInt()

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = rowBottomMargin }
            }
            // 標籤區塊：主標籤 + 可選副標籤（垂直堆疊）
            val labelContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvLabel = TextView(context).apply {
                text = label
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            labelContainer.addView(tvLabel)
            if (subtitle != null) {
                val tvSubtitle = TextView(context).apply {
                    text = subtitle
                    textSize = 11f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(com.google.android.material.color.MaterialColors.getColor(
                        context, android.R.attr.textColorSecondary, 0
                    ))
                }
                labelContainer.addView(tvSubtitle)
            }
            val toggleGroup = com.google.android.material.button.MaterialButtonToggleGroup(context).apply {
                isSingleSelection = true
                isSelectionRequired = true
            }
            fun createToggleButton(textResId: Int) = com.google.android.material.button.MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId()
                text = context.getString(textResId)
                textSize = 11f
                minWidth = 0
                minimumWidth = 0
                setPadding(hPadding, 0, hPadding, 0)
                minHeight = 0
                minimumHeight = 0
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, buttonHeight)
            }
            val btnRequire = createToggleButton(R.string.flag_state_require)
            val btnExclude = createToggleButton(R.string.flag_state_exclude)
            val btnIgnore = createToggleButton(R.string.flag_state_ignore)
            toggleGroup.addView(btnRequire)
            toggleGroup.addView(btnExclude)
            toggleGroup.addView(btnIgnore)
            toggleGroup.check(btnIgnore.id) // 預設：不限
            // tag 存放 3 個 button id 供讀取用
            toggleGroup.tag = Triple(btnRequire.id, btnExclude.id, btnIgnore.id)

            row.addView(labelContainer)
            row.addView(toggleGroup)
            parent.addView(row)
            return toggleGroup
        }

        // 建立 7 個 bit flag 列
        val flagToggleGroups = mutableMapOf<com.notificationmaster.core.filter.NotificationFlag, com.google.android.material.button.MaterialButtonToggleGroup>()
        for (flag in com.notificationmaster.core.filter.NotificationFlag.entries) {
            flagToggleGroups[flag] = createTriStateRow(
                containerFlags,
                flag.name,
                context.getString(flag.labelResId)
            )
        }

        // 建立 3 個 derived property 列
        data class DerivedDef(val key: String, val labelResId: Int)
        val derivedDefs = listOf(
            DerivedDef("isAudible", R.string.derived_audible),
            DerivedDef("likelyHeadsup", R.string.derived_headsup),
            DerivedDef("isRemoved", R.string.derived_removed)
        )
        val derivedToggleGroups = mutableMapOf<String, com.google.android.material.button.MaterialButtonToggleGroup>()
        for (def in derivedDefs) {
            derivedToggleGroups[def.key] = createTriStateRow(containerDerived, context.getString(def.labelResId))
        }

        // 讀取 toggle group 的三態值
        fun readTriState(group: com.google.android.material.button.MaterialButtonToggleGroup): Int {
            // 回傳 1=require, -1=exclude, 0=ignore
            @Suppress("UNCHECKED_CAST")
            val tag = group.tag as Triple<Int, Int, Int>
            val (reqId, exclId, _) = tag
            return when (group.checkedButtonId) {
                reqId -> 1
                exclId -> -1
                else -> 0
            }
        }

        // 回填 Flags / DerivedProperty matcher 到 toggle groups；若有值則展開 flags 區塊
        // Widget 編輯模式和 Rule 編輯模式共用此邏輯
        fun prefillFlagsAndDerived(matchers: List<Matcher>) {
            var hasFlags = false
            matchers.filterIsInstance<Matcher.Flags>().firstOrNull()?.let { fm ->
                for ((flag, group) in flagToggleGroups) {
                    @Suppress("UNCHECKED_CAST")
                    val tag = group.tag as Triple<Int, Int, Int>
                    val (reqId, exclId, ignId) = tag
                    when {
                        fm.requiredFlags and flag.bit != 0 -> { group.check(reqId); hasFlags = true }
                        fm.excludedFlags and flag.bit != 0 -> { group.check(exclId); hasFlags = true }
                        else -> group.check(ignId)
                    }
                }
            }
            matchers.filterIsInstance<Matcher.DerivedProperty>().firstOrNull()?.let { dp ->
                fun fillDerived(key: String, value: Boolean?) {
                    val group = derivedToggleGroups[key] ?: return
                    @Suppress("UNCHECKED_CAST")
                    val tag = group.tag as Triple<Int, Int, Int>
                    val (reqId, exclId, ignId) = tag
                    when (value) {
                        true -> { group.check(reqId); hasFlags = true }
                        false -> { group.check(exclId); hasFlags = true }
                        null -> group.check(ignId)
                    }
                }
                fillDerived("isAudible", dp.isAudible)
                fillDerived("likelyHeadsup", dp.likelyHeadsup)
                fillDerived("isRemoved", dp.isRemoved)
            }
            if (hasFlags) {
                layoutFlagsSection.visibility = View.VISIBLE
                btnToggleFlags.text = context.getString(R.string.filter_flags_collapse)
            }
        }

        // 動作設定容器
        val layoutActionSettings = dialogView.findViewById<LinearLayout>(R.id.layout_action_settings)

        // PersistentAlert 相關 views
        val btnChooseSound = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_choose_sound)
        val switchAlertVibrate = dialogView.findViewById<MaterialSwitch>(R.id.switch_alert_vibrate)
        val switchAlarmStream = dialogView.findViewById<MaterialSwitch>(R.id.switch_alarm_stream)
        var selectedSoundUri: String? = null

        // CalendarExport 相關 views
        val btnChooseCalendar = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_choose_calendar)
        var selectedCalendarId: Long? = (existingRule?.action as? RuleAction.CalendarExport)?.calendarId
        fun refreshCalendarButtonText() {
            btnChooseCalendar.text = context.getString(
                R.string.filter_calendar_choose,
                CalendarPickerLauncher.resolveLabel(context, selectedCalendarId)
            )
        }
        refreshCalendarButtonText()
        btnChooseCalendar.setOnClickListener {
            calendarPicker?.pick(onSelected = { cal ->
                selectedCalendarId = cal.id
                refreshCalendarButtonText()
            }) ?: run {
                Toast.makeText(context, R.string.filter_preview_calendar_no_permission, Toast.LENGTH_SHORT).show()
            }
        }

        // === Keyword 欄位 checkbox 動態生成（兩行 × 兩欄） ===
        val keywordFieldLabels = listOf(
            KeywordField.TITLE to context.getString(R.string.filter_keyword_field_title),
            KeywordField.TEXT to context.getString(R.string.filter_keyword_field_text),
            KeywordField.BIG_TEXT to context.getString(R.string.filter_keyword_field_big_text),
            KeywordField.SUB_TEXT to context.getString(R.string.filter_keyword_field_sub_text)
        )
        val keywordFieldCheckBoxes = mutableMapOf<KeywordField, MaterialCheckBox>()
        val row1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, pair) in keywordFieldLabels.withIndex()) {
            val (field, label) = pair
            val cb = MaterialCheckBox(context).apply {
                text = label
                isChecked = field == KeywordField.TITLE || field == KeywordField.TEXT
            }
            keywordFieldCheckBoxes[field] = cb
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (i < 2) row1.addView(cb, params) else row2.addView(cb, params)
        }
        containerKeywordFields.addView(row1)
        containerKeywordFields.addView(row2)

        // ChannelGroupID：API 26+ 才顯示
        if (!ApiVersionHelper.supportsNotificationChannel()) {
            layoutGroupId.visibility = View.GONE
        }

        // === Category 下拉選單 ===
        val categoryLabels = arrayOf(
            context.getString(R.string.filter_dialog_category_notification),
            context.getString(R.string.filter_dialog_category_calendar),
            context.getString(R.string.filter_dialog_category_auto_dismiss),
            context.getString(R.string.filter_dialog_category_persistent_alert),
            context.getString(R.string.filter_dialog_category_clipboard_copy)
        )
        val categoryValues = arrayOf(ActionType.SKIP_RECORD, ActionType.CALENDAR_EXPORT, ActionType.AUTO_DISMISS, ActionType.PERSISTENT_ALERT, ActionType.CLIPBOARD_COPY)
        var selectedCategoryIndex = 0

        // 延遲選項狀態
        val delayLabels = DELAY_OPTIONS.map { context.getString(it.labelResId) }
        var selectedDelayIndex = 0  // 預設「立即」
        var isCustomDelay = false

        /** 根據目前有效的 actionType 切換動作設定區塊顯示 */
        fun updateActionSettingsVisibility(effectiveType: ActionType) {
            val showDismiss = effectiveType == ActionType.AUTO_DISMISS
            val showAlert = effectiveType == ActionType.PERSISTENT_ALERT
            val showCalendar = effectiveType == ActionType.CALENDAR_EXPORT

            // 容器整體顯示/隱藏
            layoutActionSettings.visibility = if (showDismiss || showAlert || showCalendar) View.VISIBLE else View.GONE

            // AUTO_DISMISS 子元件
            layoutDismissDelay.visibility = if (showDismiss) View.VISIBLE else View.GONE
            if (!showDismiss) {
                layoutDismissDelayCustom.visibility = View.GONE
                isCustomDelay = false
            }

            // PERSISTENT_ALERT 子元件
            btnChooseSound.visibility = if (showAlert) View.VISIBLE else View.GONE
            switchAlertVibrate.visibility = if (showAlert) View.VISIBLE else View.GONE
            switchAlarmStream.visibility = if (showAlert) View.VISIBLE else View.GONE

            // CALENDAR_EXPORT 子元件
            btnChooseCalendar.visibility = if (showCalendar) View.VISIBLE else View.GONE
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
            val enabledTypes = effectiveType.allowedEventTypes
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
                updateActionSettingsVisibility(categoryValues[position])
                updateEventTypeAvailability(categoryValues[position])
            }
        }

        // 固定 actionType 或編輯模式時根據 actionType 決定延遲/提醒區塊
        val effectiveActionType = if (isEditMode) existingRule!!.action.actionType else actionType
        if (effectiveActionType != null) {
            updateActionSettingsVisibility(effectiveActionType)
            updateEventTypeAvailability(effectiveActionType)
        }

        // === Widget 標題（widgetMode 用） ===
        val layoutWidgetLabel = dialogView.findViewById<TextInputLayout>(R.id.layout_widget_label)
        val editWidgetLabel = dialogView.findViewById<TextInputEditText>(R.id.edit_widget_label)

        // === ListFilter 顯示控制（widgetMode 用） ===
        val layoutListFilterSettings = dialogView.findViewById<LinearLayout>(R.id.layout_list_filter_settings)
        val dropdownListOrder = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.dropdown_list_order)
        val switchListDedup = dialogView.findViewById<MaterialSwitch>(R.id.switch_list_dedup)
        val editListLimit = dialogView.findViewById<TextInputEditText>(R.id.edit_list_limit)

        // === Field predicates（任意欄位條件） ===
        val containerFieldPredicates = dialogView.findViewById<LinearLayout>(R.id.container_field_predicates)
        val btnAddFieldPredicate = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_add_field_predicate)
        val fieldPredicateRows = mutableListOf<FieldPredicateRow>()
        btnAddFieldPredicate.setOnClickListener {
            addFieldPredicateRow(context, containerFieldPredicates, fieldPredicateRows, null)
        }

        // === 即時預覽計數 ===
        val textPreviewCount = dialogView.findViewById<com.google.android.material.textview.MaterialTextView>(R.id.text_preview_count)

        // === Widget 模式：隱藏 ActionType/EventType/Action 區塊 ===
        if (widgetMode) {
            layoutCategory.visibility = View.GONE
            dialogView.findViewById<View>(R.id.section_header_event_types)?.visibility = View.GONE
            containerEventTypes.visibility = View.GONE
            layoutActionSettings.visibility = View.GONE
            layoutListFilterSettings.visibility = View.VISIBLE
            // widget label 欄位只在 widget 設定情境顯示（Timeline 編 rule 時呼叫端傳 null）
            if (existingWidgetLabel != null) {
                layoutWidgetLabel.visibility = View.VISIBLE
                editWidgetLabel.setText(existingWidgetLabel)
            }

            // OrderBy dropdown（發佈/擷取/事件時間 × 正反）
            val orderLabels = listOf(
                context.getString(R.string.filter_list_order_post_desc),
                context.getString(R.string.filter_list_order_post_asc),
                context.getString(R.string.filter_list_order_capture_desc),
                context.getString(R.string.filter_list_order_capture_asc),
                context.getString(R.string.filter_list_order_event_desc),
                context.getString(R.string.filter_list_order_event_asc)
            )
            val orderValues = listOf(
                OrderBy.PostTimeDesc, OrderBy.PostTimeAsc,
                OrderBy.CaptureTimeDesc, OrderBy.CaptureTimeAsc,
                OrderBy.EventTimeDesc, OrderBy.EventTimeAsc
            )
            dropdownListOrder.setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, orderLabels))
            val initOrderIdx = orderValues.indexOf(existingWidgetListFilter?.orderBy ?: OrderBy.PostTimeDesc).coerceAtLeast(0)
            dropdownListOrder.setText(orderLabels[initOrderIdx], false)

            switchListDedup.isChecked = existingWidgetListFilter?.deduplicate == true
            existingWidgetListFilter?.limit?.let { editListLimit.setText(it.toString()) }

            // Widget 編輯模式：回填既有 matchers
            if (existingWidgetMatchers != null) {
                existingWidgetMatchers.filterIsInstance<Matcher.Package>().firstOrNull()?.let {
                    editPackageName.setText(it.packageName)
                }
                existingWidgetMatchers.filterIsInstance<Matcher.Channel>().firstOrNull()?.let {
                    editChannelId.setText(it.channelId)
                }
                existingWidgetMatchers.filterIsInstance<Matcher.Keyword>().firstOrNull()?.let { kw ->
                    editKeywordPattern.setText(kw.pattern)
                    switchKeywordRegex.isChecked = kw.isRegex
                    keywordFieldCheckBoxes.forEach { (field, cb) -> cb.isChecked = field in kw.fields }
                    layoutKeywordSection.visibility = View.VISIBLE
                    btnToggleKeyword.text = context.getString(R.string.filter_keyword_collapse)
                }
                existingWidgetMatchers.filterIsInstance<Matcher.ChannelProperty>().firstOrNull()?.let { cp ->
                    // 既有 minImportance 升級轉成 Field("importance", GTE, value) 條件
                    cp.minImportance?.let { imp ->
                        addFieldPredicateRow(
                            context, containerFieldPredicates, fieldPredicateRows,
                            Matcher.Field("importance", FieldOp.GTE, imp.toString())
                        )
                    }
                    cp.groupId?.let { editGroupId.setText(it) }
                }
                // Flags + DerivedProperty 回填
                prefillFlagsAndDerived(existingWidgetMatchers)
                // Field 條件回填
                existingWidgetMatchers.filterIsInstance<Matcher.Field>().forEach { fm ->
                    addFieldPredicateRow(context, containerFieldPredicates, fieldPredicateRows, fm)
                }
            }
        }
        // 編輯既有 rule（非 widgetMode）也回填 Field
        if (existingRule != null) {
            existingRule.matchers.filterIsInstance<Matcher.Field>().forEach { fm ->
                addFieldPredicateRow(context, containerFieldPredicates, fieldPredicateRows, fm)
            }
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
            showSoundPicker(context, selectedSoundUri, soundPicker) { uri ->
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
            switchAlarmStream.isChecked = alert.audioStream == RuleAction.PersistentAlert.STREAM_ALARM
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
                    addFieldPredicateRow(
                        context, containerFieldPredicates, fieldPredicateRows,
                        Matcher.Field("importance", FieldOp.GTE, imp.toString())
                    )
                }
                cp.groupId?.let { editGroupId.setText(it) }
            }

            // 回填 Flags + DerivedProperty
            prefillFlagsAndDerived(existingRule.matchers)
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

        // === 共用：從 Dialog 表單驗證並建構 Matcher 列表 ===
        /**
         * 驗證表單欄位並建構 Matcher 列表。
         * @param silent true 時不彈 Toast / 不設 input layout error；給即時預覽 polling 用
         */
        fun buildMatchersFromDialog(silent: Boolean = false): List<Matcher>? {
            val packageName = editPackageName.text.toString().trim()
            if (!widgetMode && packageName.isEmpty()) {
                if (!silent) layoutPackageName.error = context.getString(R.string.filter_package_name_required)
                return null
            }
            if (!silent) layoutPackageName.error = null

            if (!widgetMode) {
                val selectedEventTypes = eventTypes.filterIndexed { i, _ -> checkBoxes[i].isChecked && checkBoxes[i].isEnabled }
                    .map { it.name }.toSet()
                if (selectedEventTypes.isEmpty()) {
                    if (!silent) Toast.makeText(context, R.string.filter_event_type_required, Toast.LENGTH_SHORT).show()
                    return null
                }
            }

            val matchers = mutableListOf<Matcher>()
            if (packageName.isNotEmpty()) matchers.add(Matcher.Package(packageName))
            val channelId = editChannelId.text.toString().trim().ifEmpty { null }
            if (channelId != null) matchers.add(Matcher.Channel(channelId))
            if (!widgetMode) {
                val selectedEventTypes = eventTypes.filterIndexed { i, _ -> checkBoxes[i].isChecked && checkBoxes[i].isEnabled }
                    .map { it.name }.toSet()
                matchers.add(Matcher.EventTypes(selectedEventTypes))
            }

            val kwPattern = editKeywordPattern.text?.toString()?.trim().orEmpty()
            if (kwPattern.isNotEmpty()) {
                val selectedFields = keywordFieldCheckBoxes.filter { it.value.isChecked }.keys
                if (selectedFields.isEmpty()) {
                    if (!silent) Toast.makeText(context, R.string.filter_keyword_field_required, Toast.LENGTH_SHORT).show()
                    return null
                }
                matchers.add(Matcher.Keyword(kwPattern, selectedFields, switchKeywordRegex.isChecked))
            }

            val gid = editGroupId.text?.toString()?.trim()?.ifEmpty { null }
            if (gid != null) {
                matchers.add(Matcher.ChannelProperty(minImportance = null, groupId = gid))
            }

            // Flags matcher
            var requiredFlags = 0
            var excludedFlags = 0
            for ((flag, group) in flagToggleGroups) {
                when (readTriState(group)) {
                    1 -> requiredFlags = requiredFlags or flag.bit
                    -1 -> excludedFlags = excludedFlags or flag.bit
                }
            }
            if (requiredFlags != 0 || excludedFlags != 0) {
                matchers.add(Matcher.Flags(requiredFlags, excludedFlags))
            }

            // DerivedProperty matcher
            val audible = when (readTriState(derivedToggleGroups["isAudible"]!!)) { 1 -> true; -1 -> false; else -> null }
            val headsup = when (readTriState(derivedToggleGroups["likelyHeadsup"]!!)) { 1 -> true; -1 -> false; else -> null }
            val removed = when (readTriState(derivedToggleGroups["isRemoved"]!!)) { 1 -> true; -1 -> false; else -> null }
            if (audible != null || headsup != null || removed != null) {
                matchers.add(Matcher.DerivedProperty(audible, headsup, removed))
            }

            // Field 條件 matchers
            for (row in fieldPredicateRows) {
                row.toMatcher()?.let { matchers.add(it) }
            }

            return matchers
        }

        // === 即時預覽計數（每 500ms 重算 SQL count，dialog scope cancel 時停止） ===
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(500)
                val matchers = try { buildMatchersFromDialog(silent = true) } catch (_: Exception) { null }
                if (matchers == null) {
                    textPreviewCount.text = ""
                    continue
                }
                val spec = EventFilterSpec(matchers = matchers)
                val count = try {
                    withContext(Dispatchers.IO) {
                        database.notificationEventDao().countSync(spec)
                    }
                } catch (_: Exception) { -1 }
                textPreviewCount.text = if (count >= 0)
                    context.getString(R.string.filter_preview_count_format, count)
                else ""
            }
        }

        // === 預覽匹配結果 ===
        val btnPreviewMatch = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_preview_match)
        btnPreviewMatch.setOnClickListener {
            val tempMatchers = buildMatchersFromDialog() ?: return@setOnClickListener
            val resolvedType = actionType ?: categoryValues[selectedCategoryIndex]
            val tempRule = Rule(matchers = tempMatchers, action = RuleAction.SkipRecord)

            val previewLimit = 200
            scope.launch {
                val (events, channelGroupMap) = withContext(Dispatchers.IO) {
                    val ev = database.notificationEventDao().getRecentEvents(previewLimit)
                    val groupMap = database.channelDao().getAllChannelsSync()
                        .associate { (it.packageName to it.channelId) to it.groupId }
                    ev to groupMap
                }
                // 同 notification_key 的多個 event 共構一個邏輯通知，任一 event 匹配即納入
                val matched = events
                    .groupBy { it.notificationKey }
                    .values
                    .filter { evGroup ->
                        evGroup.any { e ->
                            val mc = buildMatchContext(e, channelGroupMap)
                            tempRule.matches(mc)
                        }
                    }
                    .map { evGroup ->
                        // 顯示用「該 group 內最新 event」一筆
                        evGroup.maxBy { it.eventTime }
                    }
                    .map(NotificationDisplay::from)

                if (matched.isEmpty()) {
                    // 檢查是否有 INITIAL 事件符合（忽略 EventType 條件）
                    val hasEventTypeMatcher = tempMatchers.any { it is Matcher.EventTypes }
                    val initialCount = if (hasEventTypeMatcher) {
                        val relaxedRule = Rule(
                            matchers = tempMatchers.filter { it !is Matcher.EventTypes },
                            action = RuleAction.SkipRecord
                        )
                        events
                            .filter { it.eventType == EventType.INITIAL }
                            .groupBy { it.notificationKey }
                            .values
                            .count { evGroup ->
                                evGroup.any { e ->
                                    relaxedRule.matches(buildMatchContext(e, channelGroupMap))
                                }
                            }
                    } else 0

                    if (initialCount > 0) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.filter_preview_empty_initial_hint, initialCount),
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(context, R.string.filter_preview_empty, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    showPreviewResultDialog(context, matched, previewLimit, resolvedType, tempRule)
                }
            }
        }

        // === 建立 Dialog ===
        // 確保規則已載入
        RuleRepository.load(context)

        val dialogTitle = when {
            widgetMode -> R.string.widget_config_title_edit
            isEditMode -> R.string.filter_dialog_edit_title
            else -> R.string.filter_dialog_title
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(dialogTitle)
            .setView(dialogView)
            .setPositiveButton(R.string.ok, null) // listener 在 show() 後覆寫以防自動關閉
            .setNegativeButton(R.string.cancel, null)
            .create()

        // 追蹤 widgetMode 是否成功確認，否則 dismiss 時呼叫 onWidgetCancelled
        var widgetConfirmed = false

        dialog.setOnDismissListener {
            scopeJob.cancel()
            if (widgetMode && !widgetConfirmed) {
                onWidgetCancelled?.invoke()
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val matchers = buildMatchersFromDialog() ?: return@setOnClickListener

                // Widget 模式：回傳 matchers + ListFilter action（若呼叫端要）
                if (widgetMode) {
                    widgetConfirmed = true
                    if (onListFilterReady != null) {
                        val orderLabels = listOf(
                            context.getString(R.string.filter_list_order_post_desc),
                            context.getString(R.string.filter_list_order_post_asc),
                            context.getString(R.string.filter_list_order_capture_desc),
                            context.getString(R.string.filter_list_order_capture_asc),
                            context.getString(R.string.filter_list_order_event_desc),
                            context.getString(R.string.filter_list_order_event_asc)
                        )
                        val orderValues = listOf(
                            OrderBy.PostTimeDesc, OrderBy.PostTimeAsc,
                            OrderBy.CaptureTimeDesc, OrderBy.CaptureTimeAsc,
                            OrderBy.EventTimeDesc, OrderBy.EventTimeAsc
                        )
                        val orderIdx = orderLabels.indexOf(dropdownListOrder.text?.toString()).coerceAtLeast(0)
                        val limit = editListLimit.text?.toString()?.trim()?.toIntOrNull()
                        val listFilter = RuleAction.ListFilter(
                            orderBy = orderValues[orderIdx],
                            limit = limit,
                            deduplicate = switchListDedup.isChecked
                        )
                        val widgetLabel = editWidgetLabel.text?.toString()?.trim().orEmpty()
                        onListFilterReady.invoke(matchers, listFilter, widgetLabel)
                    } else {
                        onMatchersReady?.invoke(matchers)
                    }
                    dialog.dismiss()
                    return@setOnClickListener
                }

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

                // CALENDAR_EXPORT 必填驗證
                if (resolvedActionType == ActionType.CALENDAR_EXPORT && selectedCalendarId == null) {
                    Toast.makeText(context, R.string.filter_calendar_required, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // 建構 RuleAction
                val action: RuleAction = when (resolvedActionType) {
                    ActionType.SKIP_RECORD -> RuleAction.SkipRecord
                    ActionType.CALENDAR_EXPORT -> RuleAction.CalendarExport(calendarId = selectedCalendarId)
                    ActionType.AUTO_DISMISS -> RuleAction.AutoDismiss(delayMs = dismissDelayMs)
                    ActionType.PERSISTENT_ALERT -> RuleAction.PersistentAlert(
                        soundUri = selectedSoundUri,
                        vibrate = switchAlertVibrate.isChecked,
                        audioStream = if (switchAlarmStream.isChecked)
                            RuleAction.PersistentAlert.STREAM_ALARM
                        else
                            RuleAction.PersistentAlert.STREAM_NOTIFICATION
                    )
                    ActionType.CLIPBOARD_COPY -> RuleAction.ClipboardCopy
                    ActionType.LIST_FILTER -> {
                        // FilterRuleDialogHelper 主流程不處理 LIST_FILTER（呼叫端走 widgetMode 路徑）
                        Toast.makeText(context, R.string.error_list_filter_not_supported_here, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                }

                if (isEditMode) {
                    val updatedRule = Rule(
                        id = existingRule!!.id,
                        matchers = matchers,
                        action = action,
                        createdAt = existingRule.createdAt
                    )
                    RuleRepository.updateRule(context, updatedRule)
                    Toast.makeText(context, R.string.filter_rule_updated, Toast.LENGTH_SHORT).show()
                } else {
                    val rule = Rule(matchers = matchers, action = action)
                    RuleRepository.addRule(context, rule)
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
     * 顯示鈴聲選擇器。
     *
     * 優先使用系統原生 RingtonePicker（透過呼叫端 Fragment 提供的 SoundPickerLauncher），
     * 有預聽、分類、捲動等完整體驗。當未傳入 launcher 時 fallback 到自訂 cursor 列表。
     */
    private fun showSoundPicker(
        context: Context,
        currentUri: String?,
        soundPicker: SoundPickerLauncher?,
        onSelected: (String?) -> Unit
    ) {
        if (soundPicker != null) {
            soundPicker.pick(
                currentUri = currentUri,
                title = context.getString(R.string.filter_alert_choose_sound),
                onSelected = onSelected
            )
        } else {
            showSoundPickerFallback(context, currentUri, onSelected)
        }
    }

    /**
     * Fallback 鈴聲選擇器（cursor + AlertDialog 單選）。
     *
     * 用於沒有 SoundPickerLauncher 的呼叫端（例如未來可能從非 Fragment context 呼叫時）。
     */
    private fun showSoundPickerFallback(
        context: Context,
        currentUri: String?,
        onSelected: (String?) -> Unit
    ) {
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
            .setSingleChoiceItems(titles.toTypedArray<CharSequence>(), checked) { dialog, which ->
                onSelected(uris[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 從 NotificationEventEntity + snapshot 構造 in-memory matcher 用的 MatchContext。
     *
     * events 表不投影 channel importance / channelGroup 等 channel-meta 屬性，
     * 這部份對應 SQL 路徑的 ALWAYS_TRUE 退化（Phase 9-1 決策）；in-memory 路徑
     * 對 ChannelProperty.minImportance 仍會 fail-close（返 null → fail），這是
     * 已知 UX 預覽偏保守的退化，等 Phase 7b RankingMerger 補上 importance 再對齊。
     */
    private fun buildMatchContext(
        event: NotificationEventEntity,
        channelGroupMap: Map<Pair<String, String>, String?>
    ): MatchContext {
        val snap = NotificationSnapshotParser.parse(event.eventRawJson)
        return MatchContext(
            packageName = event.packageName,
            channelId = event.channelId,
            eventType = event.eventType,
            title = event.title,
            text = event.text,
            bigText = snap?.bigText,
            subText = snap?.subText,
            channelImportance = null,
            channelGroupId = event.channelId?.let { chId ->
                channelGroupMap[event.packageName to chId]
            },
            flags = snap?.flags,
            isAudible = event.isAudible,
            likelyHeadsup = event.likelyHeadsup,
            isRemoved = event.eventType == EventType.REMOVED
        )
    }

    /**
     * 顯示預覽結果 Dialog（RecyclerView + 套用按鈕）
     */
    private fun showPreviewResultDialog(
        context: Context,
        matched: List<NotificationDisplay>,
        previewLimit: Int,
        actionType: ActionType,
        tempRule: Rule
    ) {
        val dialogView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
        }

        // 摘要文字
        val summary = TextView(context).apply {
            text = context.getString(R.string.filter_preview_count, matched.size) +
                "（最近 $previewLimit 筆中）"
            setPadding(48, 0, 48, 16)
        }
        dialogView.addView(summary)

        // RecyclerView 顯示匹配通知
        val recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            val previewAdapter = NotificationAdapter(onItemClick = { /* 點擊不做事 */ })
            adapter = previewAdapter
            previewAdapter.submitList(matched.take(50))
        }
        dialogView.addView(recyclerView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val canApply = actionType in setOf(ActionType.CLIPBOARD_COPY, ActionType.CALENDAR_EXPORT)

        val builder = AlertDialog.Builder(context)
            .setTitle(R.string.filter_preview_result_title)
            .setView(dialogView)

        if (canApply) {
            builder.setPositiveButton(R.string.filter_preview_apply) { _, _ ->
                applyActionToResults(context, matched, actionType, tempRule)
            }
            builder.setNegativeButton(R.string.cancel, null)
        } else {
            builder.setPositiveButton(R.string.ok, null)
        }

        builder.show()
    }

    /**
     * 對預覽結果套用實際動作
     *
     * Plan 2 Phase 8：matched 為 NotificationDisplay 列表；ClipboardCopyHelper /
     * CalendarExporter 已對齊新 schema，直接傳遞 display。
     */
    private fun applyActionToResults(
        context: Context,
        notifications: List<NotificationDisplay>,
        actionType: ActionType,
        rule: Rule
    ) {
        when (actionType) {
            ActionType.CLIPBOARD_COPY -> {
                val keywordMatcher = rule.matchers.filterIsInstance<Matcher.Keyword>().firstOrNull()
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    var copyCount = 0
                    for (display in notifications) {
                        copyCount += withContext(Dispatchers.Main) {
                            ClipboardCopyHelper.copyToClipboard(context, display, keywordMatcher)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context,
                            context.getString(R.string.filter_preview_applied_clipboard, copyCount),
                            Toast.LENGTH_SHORT).show()
                    }
                }
            }
            ActionType.CALENDAR_EXPORT -> {
                val exporter = CalendarExporter(context)
                if (!exporter.hasCalendarPermission()) {
                    Toast.makeText(context, R.string.filter_preview_calendar_no_permission, Toast.LENGTH_SHORT).show()
                    return
                }
                // 預覽套用一律走 rule 自帶 calendarId（與正式匯出行為一致）
                val targetId = (rule.action as? RuleAction.CalendarExport)?.calendarId
                if (targetId == null) {
                    Toast.makeText(context, R.string.filter_calendar_required, Toast.LENGTH_SHORT).show()
                    return
                }
                val targetCal = exporter.getAvailableCalendars().firstOrNull { it.id == targetId }
                if (targetCal == null) {
                    Toast.makeText(context,
                        CalendarPickerLauncher.resolveLabel(context, targetId),
                        Toast.LENGTH_SHORT).show()
                    return
                }
                exporter.setTargetAccount(targetCal.accountName, targetCal.accountType)
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    val result = exporter.exportToCalendar(notifications, targetId, ExportDetailLevel.FULL)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context,
                            context.getString(R.string.filter_preview_applied_calendar, result.successCount),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            else -> {
                Toast.makeText(context, R.string.filter_preview_apply_unsupported, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // === Field 條件 row（任意 NotificationEntity column predicate） ===

    private class FieldPredicateRow(
        val rootView: View,
        private val fieldDropdown: MaterialAutoCompleteTextView,
        private val opDropdown: MaterialAutoCompleteTextView,
        private val valueEdit: TextInputEditText,
        private val fieldKeys: List<String>,
        private val fieldLabels: List<String>
    ) {
        fun toMatcher(): Matcher.Field? {
            val fieldLabel = fieldDropdown.text?.toString().orEmpty()
            val idx = fieldLabels.indexOf(fieldLabel)
            if (idx < 0) return null
            val key = fieldKeys[idx]
            val opName = opDropdown.text?.toString().orEmpty()
            val op = runCatching { FieldOp.fromName(opName) }.getOrNull() ?: return null
            val value = valueEdit.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            return runCatching { Matcher.Field(key, op, value) }.getOrNull()
        }
    }

    /**
     * 加入一筆條件 row，使用 MaterialCardView 兩列佈局：
     * - 第 1 列：欄位 dropdown + 移除按鈕
     * - 第 2 列：運算子 dropdown + 值 input（weight 1:1.5）
     */
    private fun addFieldPredicateRow(
        context: Context,
        container: LinearLayout,
        rows: MutableList<FieldPredicateRow>,
        prefill: Matcher.Field?
    ) {
        val density = context.resources.displayMetrics.density
        val pad = (8 * density).toInt()
        val cardPad = (12 * density).toInt()

        val card = com.google.android.material.card.MaterialCardView(context).apply {
            cardElevation = 0f
            strokeWidth = (1 * density).toInt()
            strokeColor = com.google.android.material.color.MaterialColors.getColor(
                this, com.google.android.material.R.attr.colorOutlineVariant, 0
            )
            setContentPadding(cardPad, cardPad, cardPad, cardPad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad }
        }

        val outer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        card.addView(outer)

        // === 第 1 列：欄位 + 移除按鈕 ===
        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val fieldKeys = FieldWhitelist.fields.keys.toList()
        val fieldLabels = fieldKeys.map { fieldDisplayName(context, it) }
        val fieldLayout = TextInputLayout(
            context, null, com.google.android.material.R.attr.textInputOutlinedExposedDropdownMenuStyle
        ).apply {
            hint = context.getString(R.string.filter_field_label)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val fieldDropdown = MaterialAutoCompleteTextView(context).apply {
            inputType = android.text.InputType.TYPE_NULL
            isFocusable = false
            setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, fieldLabels))
            val initIdx = prefill?.field?.let { fieldKeys.indexOf(it) }?.coerceAtLeast(0) ?: 0
            setText(fieldLabels[initIdx], false)
        }
        fieldLayout.addView(fieldDropdown)

        val removeBtn = android.widget.ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
            layoutParams = LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt()).apply {
                marginStart = pad
            }
            contentDescription = context.getString(R.string.filter_add_field_predicate)
        }
        row1.addView(fieldLayout)
        row1.addView(removeBtn)

        // === 第 2 列：運算子 + 值 ===
        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )).also {
                it.topMargin = pad
                layoutParams = it
            }
        }
        val opLayout = TextInputLayout(
            context, null, com.google.android.material.R.attr.textInputOutlinedExposedDropdownMenuStyle
        ).apply {
            hint = context.getString(R.string.filter_op_label)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val opLabels = FieldOp.entries.map { it.name }
        val opDropdown = MaterialAutoCompleteTextView(context).apply {
            inputType = android.text.InputType.TYPE_NULL
            isFocusable = false
            setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, opLabels))
            setText(prefill?.op?.name ?: FieldOp.EQ.name, false)
        }
        opLayout.addView(opDropdown)
        val valueLayout = TextInputLayout(
            context, null, com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = context.getString(R.string.filter_value_label)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f).apply {
                marginStart = pad
            }
        }
        val valueEdit = TextInputEditText(context).apply {
            setText(prefill?.value.orEmpty())
            maxLines = 1
        }
        valueLayout.addView(valueEdit)
        row2.addView(opLayout)
        row2.addView(valueLayout)

        outer.addView(row1)
        outer.addView(row2)

        val rowObj = FieldPredicateRow(card, fieldDropdown, opDropdown, valueEdit, fieldKeys, fieldLabels)
        rows.add(rowObj)
        container.addView(card)

        removeBtn.setOnClickListener {
            container.removeView(card)
            rows.remove(rowObj)
        }
    }

    /** 顯示名來自 string `field_<key>`；fallback = key 本身 */
    private fun fieldDisplayName(context: Context, key: String): String {
        val resId = context.resources.getIdentifier("field_$key", "string", context.packageName)
        return if (resId != 0) context.getString(resId) else key
    }
}
