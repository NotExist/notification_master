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
import com.notificationmaster.core.cache.AppLabelCache
import com.notificationmaster.databinding.ItemTimelineDateHeaderBinding
import com.notificationmaster.databinding.ItemTimelineNotificationBinding
import com.notificationmaster.ui.common.NotificationDisplay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 時間軸 Adapter
 * 支援日期分組標題和通知項目
 *
 * Plan 2 Phase 9：吃 [NotificationDisplay]（NotificationEventEntity + snapshot 合成的攤平資料）。
 * Ranking-dependent 屬性目前以預設值填入；importance / isConversation / isAmbient / isSuspended
 * 不再從列表項顯示，待 Phase 7b 由 RankingSnapshotMerger 合併最近 observation 後恢復。
 */
class TimelineAdapter(
    private val onItemClick: (NotificationDisplay) -> Unit,
    private val onSimilarClick: (NotificationDisplay) -> Unit = {},
    private val onItemLongClick: (NotificationDisplay) -> Unit = {}
) : ListAdapter<TimelineItem, RecyclerView.ViewHolder>(DiffCallback()) {

    init {
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("yyyy年M月d日 EEEE", Locale.getDefault())


    companion object {
        private const val VIEW_TYPE_DATE_HEADER = 0
        private const val VIEW_TYPE_NOTIFICATION = 1
        private const val VIEW_TYPE_LOADING = 2
        private const val VIEW_TYPE_END = 3
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is TimelineItem.DateHeader -> VIEW_TYPE_DATE_HEADER
            is TimelineItem.NotificationItem -> VIEW_TYPE_NOTIFICATION
            is TimelineItem.LoadingMore -> VIEW_TYPE_LOADING
            is TimelineItem.EndOfTimeline -> VIEW_TYPE_END
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_DATE_HEADER -> {
                val binding = ItemTimelineDateHeaderBinding.inflate(inflater, parent, false)
                DateHeaderViewHolder(binding)
            }
            VIEW_TYPE_LOADING -> {
                val view = inflater.inflate(R.layout.item_timeline_loading, parent, false)
                SimpleViewHolder(view)
            }
            VIEW_TYPE_END -> {
                val view = inflater.inflate(R.layout.item_timeline_end, parent, false)
                SimpleViewHolder(view)
            }
            else -> {
                val binding = ItemTimelineNotificationBinding.inflate(inflater, parent, false)
                NotificationViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is TimelineItem.DateHeader -> (holder as DateHeaderViewHolder).bind(item)
            is TimelineItem.NotificationItem -> (holder as NotificationViewHolder).bind(item)
            is TimelineItem.LoadingMore, is TimelineItem.EndOfTimeline -> { /* 靜態佈局，無需綁定 */ }
        }
    }

    class SimpleViewHolder(view: View) : RecyclerView.ViewHolder(view)

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
            val display = item.notification
            val context = binding.root.context

            // 已移除通知淡化（alpha=0.55）
            binding.root.alpha = if (item.isRemoved) 0.55f else 1.0f

            // 標題
            binding.textTitle.text = display.title ?: context.getString(R.string.no_title)

            // 時間（精確到秒）
            binding.textTime.text = timeFormat.format(Date(display.postTime))

            // 內容
            val content = display.bigText ?: display.text
            binding.textContent.text = content ?: context.getString(R.string.no_content)
            binding.textContent.visibility = if (content != null) View.VISIBLE else View.GONE

            // App 名稱與圖示
            binding.textAppName.text = AppLabelCache.getLabel(context, display.packageName)
            try {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(display.packageName, 0)
                binding.imgAppIcon.setImageDrawable(pm.getApplicationIcon(appInfo))
            } catch (e: Exception) {
                binding.imgAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            // 標籤
            binding.tagsContainer.removeAllViews()

            // Importance / Priority tag — Phase 14 Q2-A：channel.importance 已注入，優先用 importance；
            // 沒有（API <26 或 channel meta 未補齊）才 fallback 顯示 priority
            if (display.importance >= 0) {
                when (display.importance) {
                    0 -> addTag(binding.tagsContainer, "NONE", R.color.status_disabled, R.string.tag_importance_none_desc, "Importance: NONE")
                    1 -> addTag(binding.tagsContainer, "MIN", R.color.tag_silent, R.string.tag_importance_min_desc, "Importance: MIN")
                    2 -> addTag(binding.tagsContainer, "LOW", R.color.tag_silent, R.string.tag_importance_low_desc, "Importance: LOW")
                    4 -> addTag(binding.tagsContainer, "HIGH", R.color.status_warning, R.string.tag_importance_high_desc, "Importance: HIGH")
                    5 -> addTag(binding.tagsContainer, "MAX", R.color.status_warning, R.string.tag_importance_max_desc, "Importance: MAX")
                }
            } else {
                when (display.priority) {
                    -2 -> addTag(binding.tagsContainer, "P:MIN", R.color.tag_silent, R.string.tag_priority_min_desc, "PRI:MIN")
                    -1 -> addTag(binding.tagsContainer, "P:LOW", R.color.tag_silent, R.string.tag_priority_low_desc, "PRI:LOW")
                    1 -> addTag(binding.tagsContainer, "P:HI", R.color.status_warning, R.string.tag_priority_high_desc, "PRI:HIGH")
                    2 -> addTag(binding.tagsContainer, "P:MAX", R.color.status_warning, R.string.tag_priority_max_desc, "PRI:MAX")
                }
            }

            // 系統通知抽屜分類標籤
            if (display.isConversation) {
                addTag(binding.tagsContainer, "Conv", R.color.tag_conversation, R.string.tag_conversation_desc, "Conversation")
            }
            if (display.isMessagingStyle) {
                addTag(binding.tagsContainer, "Msg", R.color.tag_messaging_style, R.string.tag_messaging_style_desc, "MessagingStyle")
            }

            // 通知屬性標籤
            if (display.isOngoing) {
                addTag(binding.tagsContainer, "OG", R.color.event_initial, R.string.tag_ongoing_desc, "Ongoing")
            }
            if (display.isNoClear) {
                addTag(binding.tagsContainer, "NC", R.color.event_initial, R.string.tag_no_clear_desc, "NoClear")
            }
            if (display.isForegroundService) {
                addTag(binding.tagsContainer, "FGS", R.color.event_ranking, R.string.tag_fg_service_desc, "FG Service")
            }
            if (display.likelyHeadsup) {
                addTag(binding.tagsContainer, "HU", R.color.status_warning, R.string.tag_headsup_desc, "Heads-up")
            }
            if (display.isAudible) {
                addTag(binding.tagsContainer, "Audi", R.color.tag_audible, R.string.tag_audible_desc, "Audible")
            }
            if (display.isAutoCancel) {
                addTag(binding.tagsContainer, "AC", R.color.event_updated, R.string.tag_auto_cancel_desc, "AutoCancel")
            }
            if (display.isHighPriority) {
                addTag(binding.tagsContainer, "HP", R.color.status_warning, R.string.tag_high_priority_desc, "HighPriority")
            }
            if (display.isLocalOnly) {
                addTag(binding.tagsContainer, "Local", R.color.text_secondary, R.string.tag_local_only_desc, "LocalOnly")
            }
            if (display.isGroupSummary) {
                addTag(binding.tagsContainer, "Sum", R.color.event_updated, R.string.tag_summary_desc, "Summary")
            }
            if (display.hasBubbleMetadata) {
                addTag(binding.tagsContainer, "Bub", R.color.status_enabled, R.string.tag_bubble_desc, "Bubble")
            }
            if (display.hasCustomContentView || display.hasCustomBigContentView || display.hasCustomHeadsUpContentView) {
                addTag(binding.tagsContainer, "CV", R.color.text_secondary, R.string.tag_custom_view_desc, "Custom View")
            }
            if (display.showChronometer) {
                addTag(binding.tagsContainer, "Chrono", R.color.event_ranking, R.string.tag_chronometer_desc, "Chronometer")
            }
            // Phase 14 Q2-B：Ambient / Suspended 來自 RankingObservation enrichment
            if (display.isAmbient) {
                addTag(binding.tagsContainer, "Amb", R.color.tag_silent, R.string.tag_ambient_desc, "Ambient")
            }
            if (display.isSuspended) {
                addTag(binding.tagsContainer, "Susp", R.color.status_disabled, R.string.tag_suspended_desc, "Suspended")
            }

            // Style 標籤（基於 template 尾綴匹配）
            val style = display.template
            when {
                style == null -> { /* 無 Style，不加標籤 */ }
                style.endsWith("BigTextStyle") ->
                    addTag(binding.tagsContainer, "BTS", R.color.tag_big_text_style, R.string.tag_big_text_style_desc, "BigTextStyle")
                style.endsWith("BigPictureStyle") ->
                    addTag(binding.tagsContainer, "BPS", R.color.tag_big_picture_style, R.string.tag_big_picture_style_desc, "BigPictureStyle")
                style.endsWith("InboxStyle") ->
                    addTag(binding.tagsContainer, "Inbox", R.color.tag_inbox_style, R.string.tag_inbox_style_desc, "InboxStyle")
                style.endsWith("MediaStyle") || style.endsWith("DecoratedMediaCustomViewStyle") ->
                    addTag(binding.tagsContainer, "Media", R.color.tag_media_style, R.string.tag_media_style_desc, "MediaStyle")
                style.endsWith("CallStyle") ->
                    addTag(binding.tagsContainer, "Call", R.color.tag_call_style, R.string.tag_call_style_desc, "CallStyle")
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
                    onSimilarClick(display)
                }
            } else {
                binding.textSimilarCount.visibility = View.GONE
                binding.textSimilarCount.setOnClickListener(null)
            }
        }

        private fun addTag(container: ViewGroup, text: String, colorRes: Int, descriptionRes: Int, fullName: String = text) {
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
                        .setTitle(fullName)
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
                    oldItem.notification.eventId == newItem.notification.eventId
                oldItem is TimelineItem.LoadingMore && newItem is TimelineItem.LoadingMore -> true
                oldItem is TimelineItem.EndOfTimeline && newItem is TimelineItem.EndOfTimeline -> true
                else -> false
            }
        }

        /**
         * Phase 17：只比實際影響 UI 渲染的欄位，避免 data class 預設 equals 觸發
         * JSONObject reference 比較（每次 Flow emit 都會建新 snapshot 物件，reference 必不等）
         * 導致所有 row 被誤判 content changed → DefaultItemAnimator 連發 fade 動畫 → list 閃動。
         */
        override fun areContentsTheSame(oldItem: TimelineItem, newItem: TimelineItem): Boolean {
            return when {
                oldItem is TimelineItem.NotificationItem && newItem is TimelineItem.NotificationItem -> {
                    val o = oldItem.notification
                    val n = newItem.notification
                    oldItem.similarCount == newItem.similarCount &&
                        oldItem.isRemoved == newItem.isRemoved &&
                        o.contentHash == n.contentHash &&
                        o.title == n.title &&
                        o.text == n.text &&
                        o.postTime == n.postTime &&
                        o.isAudible == n.isAudible &&
                        o.likelyHeadsup == n.likelyHeadsup &&
                        o.importance == n.importance &&
                        o.priority == n.priority &&
                        o.flags == n.flags &&
                        o.isAmbient == n.isAmbient &&
                        o.isSuspended == n.isSuspended &&
                        o.isConversation == n.isConversation &&
                        o.template == n.template
                }
                oldItem is TimelineItem.DateHeader && newItem is TimelineItem.DateHeader ->
                    oldItem.date == newItem.date
                oldItem is TimelineItem.LoadingMore && newItem is TimelineItem.LoadingMore -> true
                oldItem is TimelineItem.EndOfTimeline && newItem is TimelineItem.EndOfTimeline -> true
                else -> false
            }
        }
    }
}

/**
 * 時間軸項目的密封類別
 */
sealed class TimelineItem {
    data class DateHeader(val date: Long) : TimelineItem()
    data class NotificationItem(
        val notification: NotificationDisplay,
        val similarCount: Int = 1,
        val isRemoved: Boolean = false
    ) : TimelineItem()
    object LoadingMore : TimelineItem()
    object EndOfTimeline : TimelineItem()
}
