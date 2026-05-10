package com.notificationmaster.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.notificationmaster.R
import com.notificationmaster.core.cache.AppLabelCache
import com.notificationmaster.core.filter.ActionType
import com.notificationmaster.core.filter.KeywordField
import com.notificationmaster.core.filter.Matcher
import com.notificationmaster.core.filter.Rule
import com.notificationmaster.core.filter.RuleAction
import com.notificationmaster.core.filter.RuleEngine
import com.notificationmaster.core.filter.RuleRepository
import android.content.Context
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import com.notificationmaster.data.db.entity.EventType
import com.notificationmaster.databinding.FragmentFilterSettingsBinding
import com.notificationmaster.databinding.ItemFilterRuleBinding
import com.notificationmaster.ui.filter.CalendarPickerLauncher
import com.notificationmaster.ui.filter.FilterRuleDialogHelper
import com.notificationmaster.ui.filter.SoundPickerLauncher

/**
 * 過濾規則管理頁面
 *
 * 透過 Navigation argument "actionType" 決定操作的 ActionType，
 * 同一 Fragment 可用於通知過濾黑名單、日曆匯出白名單和自動清除。
 */
class FilterSettingsFragment : Fragment() {

    private var _binding: FragmentFilterSettingsBinding? = null
    private val binding get() = _binding!!

    private val soundPicker = SoundPickerLauncher(this)
    private val calendarPicker = CalendarPickerLauncher(this)

    private lateinit var actionType: ActionType

    private val adapter by lazy {
        FilterRuleAdapter(
            actionType = actionType,
            onItemClick = { rule -> startEditRuleFlow(rule) },
            onDeleteClick = { rule -> confirmDeleteRule(rule) }
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFilterSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 從 Navigation argument 取得 actionType
        val actionTypeName = arguments?.getString("actionType") ?: ActionType.SKIP_RECORD.name
        actionType = try {
            ActionType.valueOf(actionTypeName)
        } catch (e: IllegalArgumentException) {
            ActionType.SKIP_RECORD
        }

        // 確保已載入
        context?.let { RuleRepository.load(it) }

        // 根據 actionType 設定標題和空白提示文字
        (activity as? AppCompatActivity)?.supportActionBar?.title = when (actionType) {
            ActionType.SKIP_RECORD -> getString(R.string.settings_filter_title)
            ActionType.CALENDAR_EXPORT -> getString(R.string.settings_calendar_whitelist)
            ActionType.AUTO_DISMISS -> getString(R.string.settings_auto_dismiss_title)
            ActionType.PERSISTENT_ALERT -> getString(R.string.settings_persistent_alert_title)
            ActionType.CLIPBOARD_COPY -> getString(R.string.settings_clipboard_copy_title)
            ActionType.LIST_FILTER -> "" // 不會被開啟此頁
        }

        when (actionType) {
            ActionType.SKIP_RECORD -> {
                binding.textEmptyTitle.setText(R.string.filter_empty)
                binding.textEmptyHint.setText(R.string.filter_empty_hint)
            }
            ActionType.CALENDAR_EXPORT -> {
                binding.textEmptyTitle.setText(R.string.calendar_whitelist_empty)
                binding.textEmptyHint.setText(R.string.calendar_whitelist_empty_hint)
            }
            ActionType.AUTO_DISMISS -> {
                binding.textEmptyTitle.setText(R.string.auto_dismiss_empty)
                binding.textEmptyHint.setText(R.string.auto_dismiss_empty_hint)
            }
            ActionType.PERSISTENT_ALERT -> {
                binding.textEmptyTitle.setText(R.string.persistent_alert_empty)
                binding.textEmptyHint.setText(R.string.persistent_alert_empty_hint)
            }
            ActionType.CLIPBOARD_COPY -> {
                binding.textEmptyTitle.setText(R.string.clipboard_copy_empty)
                binding.textEmptyHint.setText(R.string.clipboard_copy_empty_hint)
            }
            ActionType.LIST_FILTER -> Unit // LIST_FILTER 由 Timeline chip 管理，不會出現在此設定頁
        }

        binding.recyclerRules.adapter = adapter

        binding.fabAddRule.setOnClickListener {
            startAddRuleFlow()
        }

        refreshList()

        // 即時更新：規則觸發時刷新觸發時間顯示
        // 觸發時間不在 Rule data class 內，DiffUtil 無法偵測變化，需 notifyDataSetChanged
        viewLifecycleOwner.lifecycleScope.launch {
            RuleEngine.triggerFlow
                .drop(1)
                .collect { adapter.notifyDataSetChanged() }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun refreshList() {
        val rules = RuleEngine.getRules(actionType)
            .sortedWith(compareBy<Rule> { it.packageName ?: "" }.thenBy { it.channelId ?: "" })
        adapter.submitList(rules)

        val b = _binding ?: return
        if (rules.isEmpty()) {
            b.layoutEmpty.visibility = View.VISIBLE
            b.recyclerRules.visibility = View.GONE
        } else {
            b.layoutEmpty.visibility = View.GONE
            b.recyclerRules.visibility = View.VISIBLE
        }
    }

    // === 新增規則 ===

    private fun startAddRuleFlow() {
        val ctx = context ?: return
        FilterRuleDialogHelper.showAddRuleDialog(
            context = ctx,
            actionType = actionType,
            soundPicker = soundPicker,
            calendarPicker = calendarPicker,
            onRuleAdded = { refreshList() }
        )
    }

    private fun startEditRuleFlow(rule: Rule) {
        val ctx = context ?: return
        FilterRuleDialogHelper.showAddRuleDialog(
            context = ctx,
            actionType = actionType,
            existingRule = rule,
            soundPicker = soundPicker,
            calendarPicker = calendarPicker,
            onRuleAdded = { refreshList() }
        )
    }

    private fun confirmDeleteRule(rule: Rule) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setMessage(R.string.filter_delete_confirm)
            .setPositiveButton(R.string.ok) { _, _ ->
                RuleRepository.removeRule(ctx, rule.id)
                Toast.makeText(ctx, R.string.filter_rule_deleted, Toast.LENGTH_SHORT).show()
                refreshList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // === RecyclerView Adapter ===

    private class RuleDiffCallback : DiffUtil.ItemCallback<Rule>() {
        override fun areItemsTheSame(oldItem: Rule, newItem: Rule) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Rule, newItem: Rule) =
            oldItem == newItem
    }

    private class FilterRuleAdapter(
        private val actionType: ActionType,
        private val onItemClick: (Rule) -> Unit,
        private val onDeleteClick: (Rule) -> Unit
    ) : ListAdapter<Rule, FilterRuleAdapter.ViewHolder>(RuleDiffCallback()) {

        init {
            stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }

        inner class ViewHolder(
            private val binding: ItemFilterRuleBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(rule: Rule) {
                binding.root.setOnClickListener { onItemClick(rule) }
                val ctx = binding.root.context

                // App 圖示
                val pkgName = rule.packageName.orEmpty()
                binding.textAppName.text = AppLabelCache.getLabel(ctx, pkgName)
                try {
                    val appInfo = ctx.packageManager.getApplicationInfo(pkgName, 0)
                    binding.imgAppIcon.setImageDrawable(ctx.packageManager.getApplicationIcon(appInfo))
                } catch (e: Exception) {
                    binding.imgAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                }

                binding.textPackageName.text = pkgName

                // Channel 資訊
                if (rule.channelId != null) {
                    binding.textChannelInfo.visibility = View.VISIBLE
                    binding.textChannelInfo.text = "Channel: ${rule.channelId}"
                } else {
                    binding.textChannelInfo.visibility = View.GONE
                }

                // 事件類型（匹配條件）
                val allEventTypes = EventType.entries.map { it.name }.toSet()
                binding.textEventTypes.text = if (rule.eventTypes == allEventTypes) {
                    ctx.getString(R.string.filter_mode_ignore_all)
                } else {
                    rule.eventTypes.joinToString()
                }

                // 動作效果（獨立顯示）
                val actionInfo = when {
                    actionType == ActionType.CALENDAR_EXPORT -> {
                        val calId = (rule.action as? RuleAction.CalendarExport)?.calendarId
                        ctx.getString(
                            R.string.filter_calendar_target_display,
                            com.notificationmaster.ui.filter.CalendarPickerLauncher.resolveLabel(ctx, calId)
                        )
                    }
                    actionType == ActionType.AUTO_DISMISS -> {
                        val delayMs = (rule.action as? RuleAction.AutoDismiss)?.delayMs ?: 0L
                        if (delayMs > 0) ctx.getString(R.string.filter_dismiss_delay_format, formatDismissDelay(ctx, delayMs))
                        else null
                    }
                    actionType == ActionType.PERSISTENT_ALERT -> {
                        val alertAction = rule.action as? RuleAction.PersistentAlert
                        if (alertAction != null) {
                            val vibrateLabel = if (alertAction.vibrate) ctx.getString(R.string.filter_alert_vibrate) else ""
                            val soundLabel = if (alertAction.soundUri != null) {
                                android.media.RingtoneManager.getRingtone(ctx, android.net.Uri.parse(alertAction.soundUri))
                                    ?.getTitle(ctx) ?: ""
                            } else {
                                ctx.getString(R.string.filter_alert_sound_default)
                            }
                            listOf(soundLabel, vibrateLabel).filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { null }
                        } else null
                    }
                    else -> null
                }
                if (actionInfo != null) {
                    binding.textActionInfo.visibility = View.VISIBLE
                    binding.textActionInfo.text = actionInfo
                } else {
                    binding.textActionInfo.visibility = View.GONE
                }

                // Keyword 資訊
                val keyword = rule.matchers.filterIsInstance<Matcher.Keyword>().firstOrNull()
                if (keyword != null) {
                    binding.textKeywordInfo.visibility = View.VISIBLE
                    val fieldNames = keyword.fields.joinToString(", ") { field ->
                        when (field) {
                            KeywordField.TITLE -> ctx.getString(R.string.filter_keyword_field_title)
                            KeywordField.TEXT -> ctx.getString(R.string.filter_keyword_field_text)
                            KeywordField.BIG_TEXT -> ctx.getString(R.string.filter_keyword_field_big_text)
                            KeywordField.SUB_TEXT -> ctx.getString(R.string.filter_keyword_field_sub_text)
                        }
                    }
                    val patternDisplay = if (keyword.isRegex) "/${keyword.pattern}/" else "\"${keyword.pattern}\""
                    binding.textKeywordInfo.text = ctx.getString(R.string.filter_keyword_display, patternDisplay, fieldNames)
                } else {
                    binding.textKeywordInfo.visibility = View.GONE
                }

                // ChannelProperty 資訊
                val channelProp = rule.matchers.filterIsInstance<Matcher.ChannelProperty>().firstOrNull()
                if (channelProp != null) {
                    binding.textChannelPropertyInfo.visibility = View.VISIBLE
                    val parts = mutableListOf<String>()
                    channelProp.minImportance?.let { imp ->
                        parts.add(ctx.getString(R.string.filter_importance_display, importanceLabel(ctx, imp)))
                    }
                    channelProp.groupId?.let { parts.add("group: $it") }
                    binding.textChannelPropertyInfo.text = parts.joinToString(", ")
                } else {
                    binding.textChannelPropertyInfo.visibility = View.GONE
                }

                // 最後觸發時間
                val triggered = RuleEngine.getLastTriggered(rule.id)
                if (triggered > 0) {
                    binding.textLastTriggered.visibility = View.VISIBLE
                    binding.textLastTriggered.text = ctx.getString(
                        R.string.filter_last_triggered,
                        android.text.format.DateUtils.getRelativeTimeSpanString(
                            triggered,
                            System.currentTimeMillis(),
                            android.text.format.DateUtils.SECOND_IN_MILLIS
                        )
                    )
                } else {
                    binding.textLastTriggered.visibility = View.GONE
                }

                binding.btnDelete.setOnClickListener {
                    onDeleteClick(rule)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemFilterRuleBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }
    }

    companion object {
        /** 將 importance 值轉為人可讀標籤 */
        fun importanceLabel(ctx: Context, importance: Int): String = when (importance) {
            1 -> ctx.getString(R.string.filter_importance_min)
            2 -> ctx.getString(R.string.filter_importance_low)
            3 -> ctx.getString(R.string.filter_importance_default)
            4 -> ctx.getString(R.string.filter_importance_high)
            5 -> ctx.getString(R.string.filter_importance_max)
            else -> importance.toString()
        }

        /** 將延遲毫秒數格式化為人可讀的時間文字 */
        fun formatDismissDelay(ctx: Context, delayMs: Long): String = when (delayMs) {
            0L -> ctx.getString(R.string.filter_dismiss_delay_immediate)
            5 * 60 * 1000L -> ctx.getString(R.string.filter_dismiss_delay_5min)
            15 * 60 * 1000L -> ctx.getString(R.string.filter_dismiss_delay_15min)
            30 * 60 * 1000L -> ctx.getString(R.string.filter_dismiss_delay_30min)
            60 * 60 * 1000L -> ctx.getString(R.string.filter_dismiss_delay_1h)
            else -> "${delayMs / 60000} ${ctx.getString(R.string.filter_dismiss_delay_custom_hint)}"
        }
    }
}
