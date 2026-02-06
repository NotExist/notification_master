package com.notificationmaster.ui.archive

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.notificationmaster.data.db.entity.ChannelEntity
import com.notificationmaster.databinding.ItemChannelBinding
import java.text.NumberFormat

/**
 * Channel 列表 Adapter
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
            binding.textChannelName.text = item.channelName ?: item.channelId
            binding.textChannelId.text = item.channelId
            binding.textPackageName.text = item.packageName
            binding.textCount.text = NumberFormat.getNumberInstance().format(item.notificationCount)

            // 顯示重要性
            binding.textImportance.text = getImportanceText(item.importance)

            // 嘗試載入 App 圖示
            try {
                val pm = binding.root.context.packageManager
                val icon = pm.getApplicationIcon(item.packageName)
                binding.imgAppIcon.setImageDrawable(icon)
            } catch (e: Exception) {
                binding.imgAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }
        }

        private fun getImportanceText(importance: Int): String {
            return when (importance) {
                android.app.NotificationManager.IMPORTANCE_NONE -> "已封鎖"
                android.app.NotificationManager.IMPORTANCE_MIN -> "最低"
                android.app.NotificationManager.IMPORTANCE_LOW -> "低"
                android.app.NotificationManager.IMPORTANCE_DEFAULT -> "預設"
                android.app.NotificationManager.IMPORTANCE_HIGH -> "高"
                android.app.NotificationManager.IMPORTANCE_MAX -> "緊急"
                else -> "未知"
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
