package com.notificationmaster.ui.archive

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.notificationmaster.data.db.entity.AppSourceEntity
import com.notificationmaster.databinding.ItemAppSourceBinding
import java.text.NumberFormat

/**
 * App 來源列表 Adapter
 */
class AppSourceAdapter(
    private val onItemClick: (AppSourceEntity) -> Unit,
    private val onItemLongClick: (AppSourceEntity) -> Unit = {}
) : ListAdapter<AppSourceEntity, AppSourceAdapter.ViewHolder>(DiffCallback()) {

    init {
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppSourceBinding.inflate(
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
        private val binding: ItemAppSourceBinding
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

        fun bind(item: AppSourceEntity) {
            binding.textAppName.text = item.appName ?: item.packageName
            binding.textPackageName.text = item.packageName
            binding.textCount.text = NumberFormat.getNumberInstance().format(item.notificationCount)

            // 嘗試載入 App 圖示
            try {
                val pm = binding.root.context.packageManager
                val icon = pm.getApplicationIcon(item.packageName)
                binding.imgAppIcon.setImageDrawable(icon)
            } catch (e: Exception) {
                // 使用預設圖示
                binding.imgAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<AppSourceEntity>() {
        override fun areItemsTheSame(oldItem: AppSourceEntity, newItem: AppSourceEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: AppSourceEntity, newItem: AppSourceEntity): Boolean {
            return oldItem == newItem
        }
    }
}
