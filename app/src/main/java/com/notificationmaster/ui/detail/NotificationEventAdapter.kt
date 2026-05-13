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
import com.notificationmaster.core.EventDiffer
import com.notificationmaster.core.NotificationSnapshotParser
import com.notificationmaster.core.RankingSnapshotMerger
import com.notificationmaster.core.compat.ApiVersionHelper
import com.notificationmaster.data.db.entity.EventType
import com.notificationmaster.data.db.entity.NotificationEventEntity
import com.notificationmaster.databinding.ItemNotificationEventBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 通知事件 + ranking observation 時間軸 Adapter（Plan 2 Phase 7b-B-4）
 *
 * 從 ViewPager2 每頁一個 Entity 的設計改為單一 RecyclerView。資料源 = 同 notificationKey
 * 的 events ∪ observations，按時間升冪排序。inline diff 預設展開：
 * - Event row：與前一筆 Event 的 eventRawJson 全欄位 diff
 * - Observation row：與前一筆 Observation 的 merged ranking JSON diff
 *
 * 非 anchor 行以 alpha 0.6 dim 區隔（[setFocusedEventId]）。
 */
class NotificationEventAdapter(
    private val onEventClick: (NotificationEventEntity) -> Unit,
    private val onEventLongClick: (NotificationEventEntity) -> Unit = {},
    private val onObservationClick: (TimelineRow.Observation) -> Unit = {},
    private val onObservationLongClick: (TimelineRow.Observation) -> Unit = {}
) : ListAdapter<TimelineRow, NotificationEventAdapter.RowViewHolder>(DiffCallback()) {

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    /** 由 Fragment 設定當前 focus 的 event id；非 focus 行以 dim alpha 呈現 */
    private var focusedEventId: Long = -1L

    fun setFocusedEventId(eventId: Long) {
        if (focusedEventId == eventId) return
        focusedEventId = eventId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val binding = ItemNotificationEventBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return RowViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        val current = getItem(position)
        // 取「同類型前一筆」用於 inline diff
        val prevSameType = (0 until position).reversed().asSequence()
            .map { getItem(it) }
            .firstOrNull { it::class == current::class }
        holder.bind(current, prevSameType)
    }

    inner class RowViewHolder(
        private val binding: ItemNotificationEventBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    when (val row = getItem(position)) {
                        is TimelineRow.Event -> onEventClick(row.event)
                        is TimelineRow.Observation -> onObservationClick(row)
                    }
                }
            }
            binding.root.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    when (val row = getItem(position)) {
                        is TimelineRow.Event -> onEventLongClick(row.event)
                        is TimelineRow.Observation -> onObservationLongClick(row)
                    }
                    true
                } else false
            }
        }

        fun bind(row: TimelineRow, prevSameType: TimelineRow?) {
            when (row) {
                is TimelineRow.Event -> bindEvent(row, prevSameType as? TimelineRow.Event)
                is TimelineRow.Observation -> bindObservation(row, prevSameType as? TimelineRow.Observation)
            }
        }

        private fun bindEvent(row: TimelineRow.Event, prev: TimelineRow.Event?) {
            val context = binding.root.context
            val event = row.event

            binding.textEventType.text = event.eventType.name

            val indicatorColor = when (event.eventType) {
                EventType.INITIAL -> R.color.event_initial
                EventType.POSTED -> R.color.event_posted
                EventType.UPDATED -> R.color.event_updated
                EventType.REMOVED -> R.color.event_removed
            }
            binding.viewIndicator.setBackgroundColor(
                ContextCompat.getColor(context, indicatorColor)
            )

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

            // Inline diff（與前一筆 Event 比對 eventRawJson）
            val diff = if (prev != null) {
                val a = NotificationSnapshotParser.parse(prev.event.eventRawJson)?.raw
                val b = NotificationSnapshotParser.parse(event.eventRawJson)?.raw
                if (a != null && b != null) EventDiffer.diff(a, b) else emptyList()
            } else emptyList()
            applyDiffOrHide(diff)

            // 非 focus event dim
            binding.root.alpha = if (focusedEventId <= 0 || focusedEventId == event.id) 1.0f else 0.6f
        }

        private fun bindObservation(
            row: TimelineRow.Observation,
            prev: TimelineRow.Observation?
        ) {
            val context = binding.root.context

            binding.textEventType.text = "RANKING"
            binding.viewIndicator.setBackgroundColor(
                ContextCompat.getColor(context, R.color.event_ranking)
            )
            binding.textEventTime.text = timeFormat.format(Date(row.observation.observedAt))

            // 噪音欄位摺疊在小字（rank / lastAudibly）
            val rank = row.observation.rank
            val lastAudibly = row.observation.lastAudiblyAlertedMillis
            if (rank != null || (lastAudibly != null && lastAudibly > 0)) {
                binding.textRemovalReason.visibility = View.VISIBLE
                binding.textRemovalReason.text = buildString {
                    if (rank != null) append("rank=$rank")
                    if (lastAudibly != null && lastAudibly > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("lastAudibly=$lastAudibly")
                    }
                }
                binding.textRemovalReason.setOnClickListener(null)
            } else {
                binding.textRemovalReason.visibility = View.GONE
            }

            // Inline diff（與前一筆 Observation 比對 merged ranking JSON）
            val diff = if (prev != null) {
                val a = RankingSnapshotMerger.merge(prev.snapshot, prev.observation)
                val b = RankingSnapshotMerger.merge(row.snapshot, row.observation)
                if (a != null && b != null) EventDiffer.diff(a, b) else emptyList()
            } else emptyList()
            applyDiffOrHide(diff)

            // observation 不參與 anchor focus，恆 1.0f
            binding.root.alpha = 1.0f
        }

        private fun applyDiffOrHide(diff: List<EventDiffer.DiffEntry>) {
            if (diff.isNotEmpty()) {
                binding.textEventDiff.visibility = View.VISIBLE
                binding.textEventDiff.text = EventDiffRenderer.render(diff)
            } else {
                binding.textEventDiff.visibility = View.GONE
                binding.textEventDiff.text = ""
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

    class DiffCallback : DiffUtil.ItemCallback<TimelineRow>() {
        override fun areItemsTheSame(oldItem: TimelineRow, newItem: TimelineRow): Boolean {
            return oldItem.stableId == newItem.stableId
        }

        override fun areContentsTheSame(oldItem: TimelineRow, newItem: TimelineRow): Boolean {
            return oldItem == newItem
        }
    }
}
