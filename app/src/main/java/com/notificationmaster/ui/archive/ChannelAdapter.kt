package com.notificationmaster.ui.archive

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.core.content.ContextCompat
import com.notificationmaster.R
import com.notificationmaster.data.db.entity.ChannelEntity
import com.notificationmaster.databinding.ItemChannelBinding
import java.text.NumberFormat

/**
 * Channel 列表 Adapter
 *
 * Line 1: channelName (description) — count 在右側
 * Line 2: channelId — importance（依等級著色）在右側
 * Line 3: groupId · appName (packageName)
 */
class ChannelAdapter(
    private val onItemClick: (ChannelEntity) -> Unit,
    private val onItemLongClick: (ChannelEntity) -> Unit = {}
) : ListAdapter<ChannelEntity, ChannelAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChannelBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemChannelBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(position))
                }
            }
            binding.root.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemLongClick(getItem(position))
                }
                true
            }
        }

        fun bind(item: ChannelEntity) {
            val context = binding.root.context

            // Line 1: channelName (description)，description 有才附加
            binding.textChannelName.text = when {
                item.channelName == null -> item.channelId
                item.description.isNullOrEmpty() -> item.channelName
                else -> "${item.channelName} (${item.description})"
            }

            // Line 2 左: channelId
            binding.textChannelId.text = item.channelId

            // Line 2 右: importance 原始值 + 依等級著色
            binding.textImportance.text = item.importance.toString()
            binding.textImportance.setTextColor(ContextCompat.getColor(context, importanceColor(item.importance)))

            // Line 3: groupId · appName (packageName)
            val appLabel = resolveAppName(item.packageName)
            val appPart = if (appLabel != null && appLabel != item.packageName) {
                "$appLabel (${item.packageName})"
            } else {
                item.packageName
            }
            binding.textSourceInfo.text = if (item.groupId != null) {
                "${item.groupId} \u00B7 $appPart"
            } else {
                appPart
            }

            // Count
            binding.textCount.text = NumberFormat.getNumberInstance().format(item.notificationCount)

            // App 圖示（妥善處理 App 已移除的情況）
            try {
                val pm = context.packageManager
                binding.imgAppIcon.setImageDrawable(pm.getApplicationIcon(item.packageName))
            } catch (_: Exception) {
                binding.imgAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }
        }

        /**
         * 解析 App 顯示名稱，App 已移除時回傳 null
         */
        private fun resolveAppName(packageName: String): String? {
            return try {
                val pm = binding.root.context.packageManager
                val appInfo = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                null
            }
        }

        private fun importanceColor(importance: Int): Int = when (importance) {
            0 -> R.color.importance_none
            1 -> R.color.importance_min
            2 -> R.color.importance_low
            3 -> R.color.importance_default
            4 -> R.color.importance_high
            5 -> R.color.importance_max
            else -> R.color.importance_default
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ChannelEntity>() {
        override fun areItemsTheSame(oldItem: ChannelEntity, newItem: ChannelEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChannelEntity, newItem: ChannelEntity): Boolean {
            return oldItem == newItem
        }
    }
}
