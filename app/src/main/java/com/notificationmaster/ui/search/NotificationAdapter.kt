package com.notificationmaster.ui.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.notificationmaster.R
import com.notificationmaster.data.db.entity.NotificationEntity
import com.notificationmaster.databinding.ItemNotificationBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 通知列表 Adapter
 */
class NotificationAdapter(
    private val onItemClick: (NotificationEntity) -> Unit
) : ListAdapter<NotificationEntity, NotificationAdapter.ViewHolder>(DiffCallback()) {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemNotificationBinding.inflate(
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
        private val binding: ItemNotificationBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(position))
                }
            }
        }

        fun bind(item: NotificationEntity) {
            val context = binding.root.context

            // 標題
            binding.textTitle.text = item.title ?: context.getString(R.string.no_title)

            // 時間
            binding.textTime.text = timeFormat.format(Date(item.postTime))

            // 內容
            val content = item.bigText ?: item.text
            binding.textContent.text = content ?: context.getString(R.string.no_content)
            binding.textContent.visibility = if (content != null) View.VISIBLE else View.GONE

            // App 名稱
            try {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(item.packageName, 0)
                binding.textAppName.text = pm.getApplicationLabel(appInfo)
                binding.imgAppIcon.setImageDrawable(pm.getApplicationIcon(appInfo))
            } catch (e: Exception) {
                binding.textAppName.text = item.packageName
                binding.imgAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            // 標籤
            binding.tagsContainer.removeAllViews()

            // Ongoing 標籤
            if (item.isOngoing) {
                addTag(binding.tagsContainer, "Ongoing", R.color.event_initial)
            }

            // Foreground Service 標籤
            if (item.isForegroundService) {
                addTag(binding.tagsContainer, "FG Service", R.color.event_ranking)
            }

            // Heads-up 標籤
            if (item.likelyHeadsup) {
                addTag(binding.tagsContainer, "Heads-up", R.color.status_warning)
            }

            // Group Summary 標籤
            if (item.isGroupSummary) {
                addTag(binding.tagsContainer, "Summary", R.color.event_updated)
            }
        }

        private fun addTag(container: ViewGroup, text: String, colorRes: Int) {
            val context = container.context
            val tag = TextView(context).apply {
                this.text = text
                textSize = 10f
                setTextColor(ContextCompat.getColor(context, R.color.white))
                setBackgroundColor(ContextCompat.getColor(context, colorRes))
                setPadding(8, 2, 8, 2)
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = 4
                }
            }
            container.addView(tag)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<NotificationEntity>() {
        override fun areItemsTheSame(oldItem: NotificationEntity, newItem: NotificationEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: NotificationEntity, newItem: NotificationEntity): Boolean {
            return oldItem == newItem
        }
    }
}
