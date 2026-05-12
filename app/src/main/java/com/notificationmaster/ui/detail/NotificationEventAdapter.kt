package com.notificationmaster.ui.detail

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.notificationmaster.R
import com.notificationmaster.core.compat.ApiVersionHelper
import com.notificationmaster.data.db.entity.EventType
import com.notificationmaster.data.db.entity.NotificationEventEntity
import com.notificationmaster.databinding.ItemNotificationEventBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 通知事件列表 Adapter（Plan 2 Phase 7b）
 *
 * 從 ViewPager2 每頁一個 Entity 的設計改為單一 RecyclerView，直接顯示「同 notificationKey
 * 的所有 events」一覽（按 event_time ASC）。原 EventListItem.GroupHeader 於單頁設計下
 * 不再需要，整個 sealed class 一併簡化為 List<NotificationEventEntity>。
 */
class NotificationEventAdapter(
    private val onItemClick: (NotificationEventEntity) -> Unit
) : ListAdapter<NotificationEventEntity, NotificationEventAdapter.EventViewHolder>(DiffCallback()) {

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
        val binding = ItemNotificationEventBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return EventViewHolder(binding)
    }

    override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class EventViewHolder(
        private val binding: ItemNotificationEventBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(position))
                }
            }
        }

        fun bind(event: NotificationEventEntity) {
            val context = binding.root.context

            // 事件類型
            binding.textEventType.text = event.eventType.name

            // 指示器顏色（Plan 2：RANKING 已從 EventType 移除，改寫到 RankingObservation 軌道）
            val indicatorColor = when (event.eventType) {
                EventType.INITIAL -> R.color.event_initial
                EventType.POSTED -> R.color.event_posted
                EventType.UPDATED -> R.color.event_updated
                EventType.REMOVED -> R.color.event_removed
            }
            binding.viewIndicator.setBackgroundColor(
                ContextCompat.getColor(context, indicatorColor)
            )

            // 時間
            binding.textEventTime.text = timeFormat.format(Date(event.eventTime))

            // 移除原因（僅 REMOVED 事件顯示）
            if (event.eventType == EventType.REMOVED && event.removalReason != null) {
                binding.textRemovalReason.visibility = View.VISIBLE
                binding.textRemovalReason.text = context.getString(
                    R.string.format_removal_reason,
                    ApiVersionHelper.getRemovalReasonText(context, event.removalReason),
                    event.removalReason
                )
                binding.textRemovalReason.setOnClickListener {
                    showAllRemovalReasons(context, event.removalReason)
                }
            } else {
                binding.textRemovalReason.visibility = View.GONE
                binding.textRemovalReason.setOnClickListener(null)
            }
        }

        private fun showAllRemovalReasons(context: android.content.Context, currentReason: Int) {
            val text = buildString {
                for (code in ApiVersionHelper.allRemovalReasonCodes) {
                    val desc = ApiVersionHelper.getRemovalReasonText(context, code)
                    val marker = if (code == currentReason) "  ◀" else ""
                    appendLine("#$code — $desc$marker")
                }
            }.trimEnd()

            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.removal_reason_list_title)
                .setMessage(text)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<NotificationEventEntity>() {
        override fun areItemsTheSame(oldItem: NotificationEventEntity, newItem: NotificationEventEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: NotificationEventEntity, newItem: NotificationEventEntity): Boolean {
            return oldItem == newItem
        }
    }
}
