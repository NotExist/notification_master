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
import com.notificationmaster.databinding.ItemEventGroupHeaderBinding
import com.notificationmaster.databinding.ItemNotificationEventBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 事件列表項目 sealed class：分組標題 / 事件項目
 */
sealed class EventListItem {
    /** 分組標題 */
    data class GroupHeader(
        val notificationId: Long,
        val groupIndex: Int,
        val groupTotal: Int,
        val firstEventTime: Long,
        val lastEventTime: Long,
        val isCurrent: Boolean
    ) : EventListItem()

    /** 事件項目 */
    data class EventItem(
        val event: NotificationEventEntity
    ) : EventListItem()
}

/**
 * 通知事件列表 Adapter
 * 支援分組標題和事件項目兩種 ViewType
 */
class NotificationEventAdapter(
    private val onItemClick: (NotificationEventEntity) -> Unit
) : ListAdapter<EventListItem, RecyclerView.ViewHolder>(DiffCallback()) {

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val shortTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    companion object {
        private const val VIEW_TYPE_GROUP_HEADER = 0
        private const val VIEW_TYPE_EVENT = 1
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is EventListItem.GroupHeader -> VIEW_TYPE_GROUP_HEADER
            is EventListItem.EventItem -> VIEW_TYPE_EVENT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_GROUP_HEADER -> {
                val binding = ItemEventGroupHeaderBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
                GroupHeaderViewHolder(binding)
            }
            else -> {
                val binding = ItemNotificationEventBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
                EventViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is EventListItem.GroupHeader -> (holder as GroupHeaderViewHolder).bind(item)
            is EventListItem.EventItem -> (holder as EventViewHolder).bind(item.event)
        }
    }

    inner class GroupHeaderViewHolder(
        private val binding: ItemEventGroupHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(header: EventListItem.GroupHeader) {
            val context = binding.root.context

            // 標籤文字：「第 N 次」
            binding.textGroupLabel.text = context.getString(
                R.string.event_group_header, header.groupIndex
            )

            // 時間範圍
            val timeRange = context.getString(
                R.string.event_group_time_range,
                shortTimeFormat.format(Date(header.firstEventTime)),
                shortTimeFormat.format(Date(header.lastEventTime))
            )
            binding.textGroupTimeRange.text = timeRange

            // 靜態顯示，不可點擊
            binding.root.isClickable = false
            binding.root.isFocusable = false
            binding.textGroupLabel.setTextColor(
                ContextCompat.getColor(context, R.color.text_secondary)
            )
        }
    }

    inner class EventViewHolder(
        private val binding: ItemNotificationEventBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val item = getItem(position)
                    if (item is EventListItem.EventItem) {
                        onItemClick(item.event)
                    }
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
                binding.textRemovalReason.text = context.getString(
                    R.string.format_removal_reason,
                    getRemovalReasonText(context, event.removalReason),
                    event.removalReason
                )
            } else {
                binding.textRemovalReason.visibility = View.GONE
            }
        }

        private fun getRemovalReasonText(context: android.content.Context, reason: Int): String {
            return when (reason) {
                // 使用者操作
                NotificationListenerService.REASON_CLICK ->
                    context.getString(R.string.removal_user_click)
                NotificationListenerService.REASON_CANCEL ->
                    context.getString(R.string.removal_user_dismiss)
                NotificationListenerService.REASON_CANCEL_ALL ->
                    context.getString(R.string.removal_user_clear_all)
                NotificationListenerService.REASON_USER_STOPPED ->
                    context.getString(R.string.removal_user_stopped)
                NotificationListenerService.REASON_SNOOZED ->
                    context.getString(R.string.removal_user_snooze)
                ApiVersionHelper.REASON_CLEAR_DATA_INT ->
                    context.getString(R.string.removal_clear_data)

                // App 操作
                NotificationListenerService.REASON_APP_CANCEL ->
                    context.getString(R.string.removal_app_cancel)
                NotificationListenerService.REASON_APP_CANCEL_ALL ->
                    context.getString(R.string.removal_app_cancel_all)

                // 監聽器操作
                NotificationListenerService.REASON_LISTENER_CANCEL ->
                    context.getString(R.string.removal_listener_cancel)
                NotificationListenerService.REASON_LISTENER_CANCEL_ALL ->
                    context.getString(R.string.removal_listener_cancel_all)
                ApiVersionHelper.REASON_ASSISTANT_CANCEL_INT ->
                    context.getString(R.string.removal_assistant_cancel)

                // 系統操作
                NotificationListenerService.REASON_ERROR ->
                    context.getString(R.string.removal_error)
                ApiVersionHelper.REASON_PACKAGE_CHANGED_INT ->
                    context.getString(R.string.removal_package_changed)
                NotificationListenerService.REASON_PACKAGE_BANNED ->
                    context.getString(R.string.removal_package_banned)
                NotificationListenerService.REASON_GROUP_SUMMARY_CANCELED ->
                    context.getString(R.string.removal_group_summary_canceled)
                NotificationListenerService.REASON_GROUP_OPTIMIZATION ->
                    context.getString(R.string.removal_group_optimization)
                NotificationListenerService.REASON_PACKAGE_SUSPENDED ->
                    context.getString(R.string.removal_package_suspended)
                NotificationListenerService.REASON_PROFILE_TURNED_OFF ->
                    context.getString(R.string.removal_profile_turned_off)
                NotificationListenerService.REASON_UNINSTALLED ->
                    context.getString(R.string.removal_uninstalled)
                NotificationListenerService.REASON_CHANNEL_BANNED ->
                    context.getString(R.string.removal_channel_banned)
                NotificationListenerService.REASON_TIMEOUT ->
                    context.getString(R.string.removal_timeout)
                ApiVersionHelper.REASON_CHANNEL_REMOVED_INT ->
                    context.getString(R.string.removal_channel_removed)

                else -> context.getString(R.string.removal_other, reason)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<EventListItem>() {
        override fun areItemsTheSame(oldItem: EventListItem, newItem: EventListItem): Boolean {
            return when {
                oldItem is EventListItem.GroupHeader && newItem is EventListItem.GroupHeader ->
                    oldItem.notificationId == newItem.notificationId
                oldItem is EventListItem.EventItem && newItem is EventListItem.EventItem ->
                    oldItem.event.id == newItem.event.id
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: EventListItem, newItem: EventListItem): Boolean {
            return oldItem == newItem
        }
    }
}
