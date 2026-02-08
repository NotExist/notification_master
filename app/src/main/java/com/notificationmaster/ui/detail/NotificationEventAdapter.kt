package com.notificationmaster.ui.detail

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.service.notification.NotificationListenerService
import com.notificationmaster.R
import com.notificationmaster.core.compat.ApiVersionHelper
import com.notificationmaster.data.db.entity.EventType
import com.notificationmaster.data.db.entity.NotificationEventEntity
import com.notificationmaster.databinding.ItemNotificationEventBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 通知事件列表 Adapter
 */
class NotificationEventAdapter(
    private val onItemClick: (NotificationEventEntity) -> Unit
) : ListAdapter<NotificationEventEntity, NotificationEventAdapter.ViewHolder>(DiffCallback()) {

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemNotificationEventBinding.inflate(
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

            // 指示器顏色
            val indicatorColor = when (event.eventType) {
                EventType.INITIAL -> R.color.event_initial
                EventType.POSTED -> R.color.event_posted
                EventType.UPDATED -> R.color.event_updated
                EventType.REMOVED -> R.color.event_removed
                EventType.RANKING -> R.color.event_ranking
            }
            binding.viewIndicator.setBackgroundColor(
                ContextCompat.getColor(context, indicatorColor)
            )

            // 時間
            binding.textEventTime.text = timeFormat.format(Date(event.eventTime))

            // 移除原因（僅 REMOVED 事件顯示）
            if (event.eventType == EventType.REMOVED && event.removalReason != null) {
                binding.textRemovalReason.visibility = View.VISIBLE
                binding.textRemovalReason.text = "(${getRemovalReasonText(context, event.removalReason)})"
            } else {
                binding.textRemovalReason.visibility = View.GONE
            }
        }

        private fun getRemovalReasonText(context: android.content.Context, reason: Int): String {
            return when (reason) {
                NotificationListenerService.REASON_CLICK ->
                    context.getString(R.string.removal_user_click)
                NotificationListenerService.REASON_CANCEL,
                NotificationListenerService.REASON_APP_CANCEL ->
                    context.getString(R.string.removal_app_cancel)
                NotificationListenerService.REASON_CANCEL_ALL,
                NotificationListenerService.REASON_APP_CANCEL_ALL ->
                    context.getString(R.string.removal_app_cancel)
                NotificationListenerService.REASON_LISTENER_CANCEL ->
                    context.getString(R.string.removal_listener_cancel)
                NotificationListenerService.REASON_SNOOZED ->
                    context.getString(R.string.removal_user_snooze)
                NotificationListenerService.REASON_TIMEOUT ->
                    context.getString(R.string.removal_timeout)
                NotificationListenerService.REASON_CHANNEL_BANNED ->
                    context.getString(R.string.removal_channel_banned)
                ApiVersionHelper.REASON_UNINSTALLED_INT ->
                    context.getString(R.string.removal_uninstalled)
                else -> context.getString(R.string.removal_other)
            }
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
