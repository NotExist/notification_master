package com.notificationmaster.ui.timeline

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.notificationmaster.R
import com.notificationmaster.data.db.entity.NotificationEntity
import com.notificationmaster.databinding.ItemTimelineDateHeaderBinding
import com.notificationmaster.databinding.ItemTimelineNotificationBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 時間軸 Adapter
 * 支援日期分組標題和通知項目
 */
class TimelineAdapter(
    private val onItemClick: (NotificationEntity) -> Unit,
    private val onSimilarClick: (NotificationEntity) -> Unit = {},
    private val onItemLongClick: (NotificationEntity) -> Unit = {}
) : ListAdapter<TimelineItem, RecyclerView.ViewHolder>(DiffCallback()) {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("yyyy年M月d日 EEEE", Locale.getDefault())

    companion object {
        private const val VIEW_TYPE_DATE_HEADER = 0
        private const val VIEW_TYPE_NOTIFICATION = 1
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is TimelineItem.DateHeader -> VIEW_TYPE_DATE_HEADER
            is TimelineItem.NotificationItem -> VIEW_TYPE_NOTIFICATION
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_DATE_HEADER -> {
                val binding = ItemTimelineDateHeaderBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
                DateHeaderViewHolder(binding)
            }
            else -> {
                val binding = ItemTimelineNotificationBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
                NotificationViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is TimelineItem.DateHeader -> (holder as DateHeaderViewHolder).bind(item)
            is TimelineItem.NotificationItem -> (holder as NotificationViewHolder).bind(item)
        }
    }

    inner class DateHeaderViewHolder(
        private val binding: ItemTimelineDateHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: TimelineItem.DateHeader) {
            val context = binding.root.context
            val today = Calendar.getInstance()
            val itemDate = Calendar.getInstance().apply { timeInMillis = item.date }

            binding.textDate.text = when {
                isSameDay(today, itemDate) -> context.getString(R.string.timeline_today)
                isYesterday(today, itemDate) -> context.getString(R.string.timeline_yesterday)
                else -> dateFormat.format(Date(item.date))
            }
        }

        private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
            return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                    cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
        }

        private fun isYesterday(today: Calendar, other: Calendar): Boolean {
            val yesterday = Calendar.getInstance().apply {
                timeInMillis = today.timeInMillis
                add(Calendar.DAY_OF_YEAR, -1)
            }
            return isSameDay(yesterday, other)
        }
    }

    inner class NotificationViewHolder(
        private val binding: ItemTimelineNotificationBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val item = getItem(position)
                    if (item is TimelineItem.NotificationItem) {
                        onItemClick(item.notification)
                    }
                }
            }
            binding.root.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val item = getItem(position)
                    if (item is TimelineItem.NotificationItem) {
                        onItemLongClick(item.notification)
                    }
                }
                true
            }
        }

        fun bind(item: TimelineItem.NotificationItem) {
            val notification = item.notification
            val context = binding.root.context

            // 標題
            binding.textTitle.text = notification.title ?: context.getString(R.string.no_title)

            // 時間（精確到秒）
            binding.textTime.text = timeFormat.format(Date(notification.postTime))

            // 內容
            val content = notification.bigText ?: notification.text
            binding.textContent.text = content ?: context.getString(R.string.no_content)
            binding.textContent.visibility = if (content != null) View.VISIBLE else View.GONE

            // App 名稱與圖示
            try {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(notification.packageName, 0)
                binding.textAppName.text = pm.getApplicationLabel(appInfo)
                binding.imgAppIcon.setImageDrawable(pm.getApplicationIcon(appInfo))
            } catch (e: Exception) {
                binding.textAppName.text = notification.packageName
                binding.imgAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            // 標籤
            binding.tagsContainer.removeAllViews()

            // 系統通知抽屜分類標籤
            if (notification.isConversation ||
                (notification.isMessagingStyle && !notification.shortcutId.isNullOrEmpty())) {
                addTag(binding.tagsContainer, "Conversation", R.color.tag_conversation, R.string.tag_conversation_desc)
            }

            if (notification.importance in 1..2) {
                addTag(binding.tagsContainer, "Silent", R.color.tag_silent, R.string.tag_silent_desc)
            }
            if (notification.isMessagingStyle) {
                addTag(binding.tagsContainer, "MessagingStyle", R.color.tag_messaging_style, R.string.tag_messaging_style_desc)
            }

            // 通知屬性標籤
            if (notification.isOngoing) {
                addTag(binding.tagsContainer, "Ongoing", R.color.event_initial, R.string.tag_ongoing_desc)
            }

            if (notification.isForegroundService) {
                addTag(binding.tagsContainer, "FG Service", R.color.event_ranking, R.string.tag_fg_service_desc)
            }

            if (notification.likelyHeadsup) {
                addTag(binding.tagsContainer, "Heads-up", R.color.status_warning, R.string.tag_headsup_desc)
            }

            if (notification.isAudible) {
                addTag(binding.tagsContainer, "Audible", R.color.tag_audible, R.string.tag_audible_desc)
            }

            if (notification.isAutoCancel) {
                addTag(binding.tagsContainer, "AutoCancel", R.color.event_updated, R.string.tag_auto_cancel_desc)
            }

            if (notification.isGroupSummary) {
                addTag(binding.tagsContainer, "Summary", R.color.event_updated, R.string.tag_summary_desc)
            }

            if (notification.hasBubbleMetadata) {
                addTag(binding.tagsContainer, "Bubble", R.color.status_enabled, R.string.tag_bubble_desc)
            }

            if (notification.hasCustomContentView || notification.hasCustomBigContentView || notification.hasCustomHeadsUpContentView) {
                addTag(binding.tagsContainer, "Custom View", R.color.text_secondary, R.string.tag_custom_view_desc)
            }

            // Style 標籤（基於 template 尾綴匹配）
            val style = notification.template
            when {
                style == null -> { /* 無 Style，不加標籤 */ }
                style.endsWith("BigTextStyle") ->
                    addTag(binding.tagsContainer, "BigTextStyle", R.color.tag_big_text_style, R.string.tag_big_text_style_desc)
                style.endsWith("BigPictureStyle") ->
                    addTag(binding.tagsContainer, "BigPictureStyle", R.color.tag_big_picture_style, R.string.tag_big_picture_style_desc)
                style.endsWith("InboxStyle") ->
                    addTag(binding.tagsContainer, "InboxStyle", R.color.tag_inbox_style, R.string.tag_inbox_style_desc)
                style.endsWith("MediaStyle") || style.endsWith("DecoratedMediaCustomViewStyle") ->
                    addTag(binding.tagsContainer, "MediaStyle", R.color.tag_media_style, R.string.tag_media_style_desc)
                style.endsWith("CallStyle") ->
                    addTag(binding.tagsContainer, "CallStyle", R.color.tag_call_style, R.string.tag_call_style_desc)
                // MessagingStyle 和 DecoratedCustomViewStyle 已被其他標籤涵蓋
            }

            // 相似通知數量
            if (item.similarCount > 1) {
                binding.textSimilarCount.visibility = View.VISIBLE
                binding.textSimilarCount.text = context.getString(
                    R.string.timeline_similar_count,
                    item.similarCount - 1
                )
                binding.textSimilarCount.setOnClickListener {
                    onSimilarClick(notification)
                }
            } else {
                binding.textSimilarCount.visibility = View.GONE
                binding.textSimilarCount.setOnClickListener(null)
            }
        }

        private fun addTag(container: ViewGroup, text: String, colorRes: Int, descriptionRes: Int) {
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
                setOnClickListener {
                    MaterialAlertDialogBuilder(context)
                        .setTitle(text)
                        .setMessage(descriptionRes)
                        .setPositiveButton(R.string.ok, null)
                        .show()
                }
            }
            container.addView(tag)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<TimelineItem>() {
        override fun areItemsTheSame(oldItem: TimelineItem, newItem: TimelineItem): Boolean {
            return when {
                oldItem is TimelineItem.DateHeader && newItem is TimelineItem.DateHeader ->
                    oldItem.date == newItem.date
                oldItem is TimelineItem.NotificationItem && newItem is TimelineItem.NotificationItem ->
                    oldItem.notification.id == newItem.notification.id
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: TimelineItem, newItem: TimelineItem): Boolean {
            return oldItem == newItem
        }
    }
}

/**
 * 時間軸項目的密封類別
 */
sealed class TimelineItem {
    data class DateHeader(val date: Long) : TimelineItem()
    data class NotificationItem(
        val notification: NotificationEntity,
        val similarCount: Int = 1
    ) : TimelineItem()
}
