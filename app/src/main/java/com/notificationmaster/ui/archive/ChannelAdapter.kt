package com.notificationmaster.ui.archive

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.notificationmaster.R
import com.notificationmaster.data.db.entity.ChannelEntity
import com.notificationmaster.databinding.ItemChannelBinding
import java.text.NumberFormat

/**
 * Channel 列表 Adapter
 *
 * 佈局 Method B（三行式）：
 * Line 1: channelName (channelId) — count 在右側
 * Line 2: description — importance 原始值在右側
 * Line 3: groupId · appName (packageName)
 */
class ChannelAdapter(
    private val onItemClick: (ChannelEntity) -> Unit
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
        }

        fun bind(item: ChannelEntity) {
            val context = binding.root.context

            // Line 1: channelName (channelId) 或僅 channelId
            binding.textChannelName.text = when {
                item.channelName == null -> item.channelId
                item.channelName == item.channelId -> item.channelId
                else -> "${item.channelName} (${item.channelId})"
            }

            // Line 2 左: description 或 fallback 提示
            binding.textDescription.text = item.description
                ?: context.getString(R.string.channel_no_description)

            // Line 2 右: importance 原始值
            binding.textImportance.text = item.importance.toString()

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
