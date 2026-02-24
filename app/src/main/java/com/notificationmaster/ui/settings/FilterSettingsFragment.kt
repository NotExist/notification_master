package com.notificationmaster.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.notificationmaster.R
import com.notificationmaster.core.cache.AppLabelCache
import com.notificationmaster.core.filter.FilterCategory
import com.notificationmaster.core.filter.FilterRule
import com.notificationmaster.core.filter.FilterRuleStore
import com.notificationmaster.data.db.entity.EventType
import com.notificationmaster.databinding.FragmentFilterSettingsBinding
import com.notificationmaster.databinding.ItemFilterRuleBinding
import com.notificationmaster.ui.filter.FilterRuleDialogHelper

/**
 * 過濾規則管理頁面
 *
 * 透過 Navigation argument "category" 決定操作的 FilterCategory，
 * 同一 Fragment 可用於通知過濾黑名單和日曆匯出白名單。
 */
class FilterSettingsFragment : Fragment() {

    private var _binding: FragmentFilterSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var category: FilterCategory

    private val adapter = FilterRuleAdapter(
        onDeleteClick = { rule -> confirmDeleteRule(rule) }
    )

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

        // 從 Navigation argument 取得 category
        val categoryName = arguments?.getString("category") ?: FilterCategory.NOTIFICATION.name
        category = try {
            FilterCategory.valueOf(categoryName)
        } catch (e: IllegalArgumentException) {
            FilterCategory.NOTIFICATION
        }

        // 確保已載入
        context?.let { FilterRuleStore.load(it, category) }

        // 根據 category 設定空白提示文字
        when (category) {
            FilterCategory.NOTIFICATION -> {
                binding.textEmptyTitle.setText(R.string.filter_empty)
                binding.textEmptyHint.setText(R.string.filter_empty_hint)
            }
            FilterCategory.CALENDAR_EXPORT -> {
                binding.textEmptyTitle.setText(R.string.calendar_whitelist_empty)
                binding.textEmptyHint.setText(R.string.calendar_whitelist_empty_hint)
            }
        }

        binding.recyclerRules.adapter = adapter

        binding.fabAddRule.setOnClickListener {
            startAddRuleFlow()
        }

        refreshList()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun refreshList() {
        val rules = FilterRuleStore.getRules(category)
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
            category = category,
            onRuleAdded = { refreshList() }
        )
    }

    private fun confirmDeleteRule(rule: FilterRule) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setMessage(R.string.filter_delete_confirm)
            .setPositiveButton(R.string.ok) { _, _ ->
                FilterRuleStore.removeRule(ctx, category, rule.id)
                Toast.makeText(ctx, R.string.filter_rule_deleted, Toast.LENGTH_SHORT).show()
                refreshList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // === RecyclerView Adapter ===

    private class FilterRuleDiffCallback : DiffUtil.ItemCallback<FilterRule>() {
        override fun areItemsTheSame(oldItem: FilterRule, newItem: FilterRule) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: FilterRule, newItem: FilterRule) =
            oldItem == newItem
    }

    private class FilterRuleAdapter(
        private val onDeleteClick: (FilterRule) -> Unit
    ) : ListAdapter<FilterRule, FilterRuleAdapter.ViewHolder>(FilterRuleDiffCallback()) {

        inner class ViewHolder(
            private val binding: ItemFilterRuleBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(rule: FilterRule) {
                val ctx = binding.root.context

                // App 圖示
                binding.textAppName.text = AppLabelCache.getLabel(ctx, rule.packageName)
                try {
                    val appInfo = ctx.packageManager.getApplicationInfo(rule.packageName, 0)
                    binding.imgAppIcon.setImageDrawable(ctx.packageManager.getApplicationIcon(appInfo))
                } catch (e: Exception) {
                    binding.imgAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                }

                binding.textPackageName.text = rule.packageName

                // Channel 資訊
                if (rule.channelId != null) {
                    binding.textChannelInfo.visibility = View.VISIBLE
                    binding.textChannelInfo.text = "Channel: ${rule.channelId}"
                } else {
                    binding.textChannelInfo.visibility = View.GONE
                }

                // 事件類型
                val allEventTypes = EventType.entries.map { it.name }.toSet()
                binding.textFilterMode.text = if (rule.eventTypes == allEventTypes) {
                    ctx.getString(R.string.filter_mode_ignore_all)
                } else {
                    rule.eventTypes.joinToString()
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
}
