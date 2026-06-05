package com.notificationmaster.ui.detail

import android.app.Notification
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.R
import com.notificationmaster.core.cache.AppLabelCache
import com.notificationmaster.core.cache.PendingIntentCache
import com.notificationmaster.core.media.MediaExtractor
import com.notificationmaster.core.compat.ApiVersionHelper
import com.notificationmaster.data.db.entity.ChannelEntity
import com.notificationmaster.data.db.entity.ActionEntity
import com.notificationmaster.data.db.entity.EventType
import com.notificationmaster.data.db.entity.MediaAttachmentEntity
import com.notificationmaster.data.db.entity.DeviceStateEntity
import com.notificationmaster.core.NotificationSnapshotParser
import com.notificationmaster.ui.common.NotificationDisplay
import com.notificationmaster.data.model.NotificationSnapshot
import com.notificationmaster.data.db.entity.SemanticAction
import com.notificationmaster.data.db.entity.NotificationEventEntity
import com.notificationmaster.service.NotificationCaptureService
import com.notificationmaster.databinding.FragmentNotificationDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 通知詳情 Fragment
 * 顯示通知的完整資訊、生命週期事件和動作按鈕
 */
class NotificationDetailFragment : Fragment() {

    private var _binding: FragmentNotificationDetailBinding? = null
    private val binding get() = _binding!!

    private val args: NotificationDetailFragmentArgs by navArgs()

    /**
     * Phase 27：list 內的 NotificationDisplay 不再持有 NotificationSnapshot reference（避免 OOM）。
     * Detail 進入時對當前 event 的 eventRawJson lazy parse 一次，cache 給所有 display* 函式共用。
     * 換 notification（如 Toolbar 切上下篇）時呼叫 [updateSnapshotCache] 重新 parse。
     */
    private var cachedSnapshot: NotificationSnapshot? = null
    private var cachedSnapshotForEventId: Long = -1L

    /** 確保 cache 對應當前 notification；若已 parse 過直接 reuse。 */
    private fun snapshotFor(notification: NotificationDisplay): NotificationSnapshot? {
        if (cachedSnapshotForEventId != notification.event.id) {
            cachedSnapshot = NotificationSnapshotParser.parse(notification.event.eventRawJson)
            cachedSnapshotForEventId = notification.event.id
        }
        return cachedSnapshot
    }

    private fun formatTime(millis: Long): String {
        return if (Build.VERSION.SDK_INT >= 26) {
            java.time.Instant.ofEpochMilli(millis)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        } else {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.getDefault())
                .format(Date(millis))
        }
    }
    private val preciseTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    private lateinit var eventAdapter: NotificationEventAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNotificationDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupEventsHelp()
        setupEventList()
        loadNotificationDetail()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupEventsHelp() {
        binding.labelEvents.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.detail_events)
                .setMessage(R.string.detail_events_help)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    private fun setupEventList() {
        eventAdapter = NotificationEventAdapter(
            onEventClick = { event -> showEventDetail(event) },
            onEventLongClick = { event -> copyToClipboard(event.eventRawJson, "event raw JSON") },
            onObservationClick = { obs -> showObservationDetail(obs) },
            onObservationLongClick = { obs ->
                val merged = com.notificationmaster.core.RankingSnapshotMerger.merge(obs.snapshot, obs.observation)
                val text = merged?.toString(2) ?: obs.snapshot.rankingJson
                copyToClipboard(text, "ranking JSON")
            }
        )
        binding.recyclerEvents.apply {
            adapter = eventAdapter
            layoutManager = LinearLayoutManager(context)
        }
    }

    /** 將文字複製到剪貼簿並 Toast 提示 */
    private fun copyToClipboard(text: String, label: String) {
        val ctx = context ?: return
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        Toast.makeText(ctx, getString(R.string.clipboard_copied_with_label, label), Toast.LENGTH_SHORT).show()
    }

    /** 點擊 ranking observation 行時顯示完整 merged ranking JSON */
    private fun showObservationDetail(row: TimelineRow.Observation) {
        val merged = com.notificationmaster.core.RankingSnapshotMerger.merge(row.snapshot, row.observation)
        val text = merged?.toString(2) ?: row.snapshot.rankingJson
        val textView = TextView(requireContext()).apply {
            this.text = text
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setPadding(48, 24, 48, 24)
            setTextIsSelectable(true)
        }
        val scrollView = ScrollView(requireContext()).apply { addView(textView) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("RANKING observation @ ${preciseTimeFormat.format(java.util.Date(row.observation.observedAt))}")
            .setView(scrollView)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun updateEventCount(count: Int) {
        val b = _binding ?: return
        if (count <= 0) {
            b.textEventCount.visibility = View.GONE
        } else {
            b.textEventCount.visibility = View.VISIBLE
            b.textEventCount.text = count.toString()
        }
    }

    private fun loadNotificationDetail() {
        val database = NotificationMasterApp.getInstance().database
        val eventDao = database.notificationEventDao()
        val mediaDao = database.mediaAttachmentDao()

        viewLifecycleOwner.lifecycleScope.launch {
            // Plan 2 Phase 9-7：Detail 入口以 notificationKey 為主，摘要 / chips / details 全部以
            // anchor event 的 NotificationDisplay（攤平 snapshot）渲染。
            val notificationKey = args.notificationKey
            val sameKeyEvents = withContext(Dispatchers.IO) {
                eventDao.getEventsByKeySync(notificationKey)
            }
            // anchor event：args 帶入優先；fallback 為最新一筆 event（依 event_time）；
            // 仍無 events（極少 race）退化為 null → 摘要區隱藏，時間軸仍可呈現空態
            val anchorEvent = when {
                args.anchorEventId > 0 -> sameKeyEvents.firstOrNull { it.id == args.anchorEventId }
                else -> null
            } ?: sameKeyEvents.maxByOrNull { it.eventTime }
            val anchorEventId = anchorEvent?.id ?: -1L

            if (anchorEvent != null) {
                // Phase 14 Q2-A/B：走 NotificationEnricher 注入 channel importance + ranking 屬性
                val display = withContext(Dispatchers.IO) {
                    com.notificationmaster.ui.common.NotificationEnricher.enrich(
                        listOf(anchorEvent),
                        database.channelDao(),
                        database.rankingObservationDao(),
                        database.rankingSnapshotDao(),
                        database.notificationEventDao()
                    ).first()
                }
                val channelEntity = withContext(Dispatchers.IO) {
                    display.channelId?.let { chId ->
                        database.channelDao().getByPackageAndChannelId(display.packageName, chId)
                    }
                }
                displayNotification(display, channelEntity)

                // Phase 31m：啟動 channel Flow collect，service 寫入新 channel 資料（如名稱）
                // → DB 變動 → Flow emit → applyChannelInfo 重整 channel 區。
                // 避免新事件進來時 detail 開著但 channel 名稱 stale，需 cold start 才更新。
                display.channelId?.let { chId ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        database.channelDao()
                            .getByPackageAndChannelIdFlow(display.packageName, chId)
                            .collect { latest ->
                                if (_binding == null) return@collect
                                applyChannelInfo(display, latest)
                            }
                    }
                }

                // 載入動作按鈕與 Intent 資訊（FK 為 event_id，用 anchor event 查）
                val actionDao = database.actionDao()
                val actions = withContext(Dispatchers.IO) {
                    actionDao.getActionsByEventIdSync(anchorEventId)
                }
                displayIntents(actions, display)

                // 載入媒體附件（FK 為 event_id）
                val attachments = withContext(Dispatchers.IO) {
                    mediaDao.getAttachmentsByEventIdSync(anchorEventId)
                }
                displayMediaAttachments(attachments)

                // W22ac：同 nkey 跨 event 附件總覽（dedup by content_hash），排除已在本
                // event 出現的 hash 避免重複呈現。供使用者在點 UPDATED event 時看到本
                // event 缺漏的附件（POSTED 時曾出現的圖等）。
                val relatedAll = withContext(Dispatchers.IO) {
                    mediaDao.getAttachmentsByNotificationKeyDedup(notificationKey)
                }
                val currentHashes = attachments.map { it.contentHash }.toSet()
                val related = relatedAll.filterNot { it.contentHash in currentHashes }
                displayMediaRelatedAttachments(related)

                // 顯示樣式資訊
                displayStyleInfo(display)

                // 條件性區塊
                displayConditionalBlocks(display)

                // 通知效果、Ranking 快照、裝置狀態
                displayEffects(display)

                // Ranking 區塊：從 RankingObservation + RankingSnapshot 合併還原
                val rankingJson = withContext(Dispatchers.IO) {
                    val obs = database.rankingObservationDao().getLatestByKey(notificationKey)
                    val snap = obs?.let { database.rankingSnapshotDao().getById(it.rankingSnapshotId) }
                    if (obs != null && snap != null) {
                        com.notificationmaster.core.RankingSnapshotMerger.merge(snap, obs)
                    } else null
                }
                displayRanking(rankingJson)

                val deviceState = withContext(Dispatchers.IO) {
                    database.deviceStateDao().getByEventId(anchorEventId)
                }
                displayDeviceState(deviceState)

                // 顯示自訂 View 資訊
                displayRemoteViewsInfo(display)
            }

            // Phase 7b-B-4：時間軸 = events ∪ ranking observations，按時間升冪排序
            val rows = withContext(Dispatchers.IO) {
                val observations = database.rankingObservationDao().getByKeySync(notificationKey)
                val snapshotById = observations.map { it.rankingSnapshotId }.toSet()
                    .mapNotNull { id -> database.rankingSnapshotDao().getById(id)?.let { id to it } }
                    .toMap()
                val eventRows = sameKeyEvents.map { TimelineRow.Event(it) }
                val obsRows = observations.mapNotNull { obs ->
                    snapshotById[obs.rankingSnapshotId]?.let { TimelineRow.Observation(obs, it) }
                }
                (eventRows + obsRows).sortedBy { it.time }
            }
            eventAdapter.setFocusedEventId(anchorEventId)
            eventAdapter.submitList(rows) {
                // 滾到 anchor event 對應的 row（若有）
                val idx = rows.indexOfFirst { it is TimelineRow.Event && it.event.id == anchorEventId }
                if (idx >= 0) {
                    (binding.recyclerEvents.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(idx, 0)
                }
            }
            updateEventCount(rows.size)
        }
    }

    private fun displayNotification(notification: NotificationDisplay, channelEntity: ChannelEntity? = null) {
        val context = requireContext()

        // App 資訊
        binding.textAppName.text = AppLabelCache.getLabel(context, notification.packageName)
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(notification.packageName, 0)
            binding.imgAppIcon.setImageDrawable(pm.getApplicationIcon(appInfo))
        } catch (e: Exception) {
            binding.imgAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
        }

        binding.textPackageName.text = notification.packageName

        // 標題：bigTitle 優先 → fallback title
        val displayTitle = notification.bigTitle ?: notification.title
        val titleField = if (notification.bigTitle != null) "bigTitle" else "title"
        binding.labelTitle.text = getString(R.string.label_with_field, getString(R.string.label_title), titleField)
        binding.textTitle.text = displayTitle ?: context.getString(R.string.no_title)
        if (notification.bigTitle != null && notification.title != null && notification.bigTitle != notification.title) {
            binding.iconTitleInfo.visibility = View.VISIBLE
            binding.iconTitleInfo.setOnClickListener {
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.label_title)
                    .setMessage(getString(R.string.desc_title_fallback, notification.title))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        } else {
            binding.iconTitleInfo.visibility = View.GONE
        }

        // 副標：subText 優先 → fallback infoText
        val displaySubtitle = notification.subText ?: notification.infoText
        if (displaySubtitle != null) {
            binding.layoutSubtitle.visibility = View.VISIBLE
            val subtitleField = if (notification.subText != null) "subText" else "infoText"
            binding.labelSubtitle.text = getString(R.string.label_with_field, getString(R.string.label_subtitle), subtitleField)
            binding.textSubtitle.text = displaySubtitle
            if (notification.subText == null && notification.infoText != null) {
                binding.iconSubtitleInfo.visibility = View.VISIBLE
                binding.iconSubtitleInfo.setOnClickListener {
                    MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.label_subtitle)
                        .setMessage(R.string.desc_subtitle_fallback)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            } else {
                binding.iconSubtitleInfo.visibility = View.GONE
            }
        } else {
            binding.layoutSubtitle.visibility = View.GONE
        }

        // 內容：bigText 優先 → fallback text
        val displayContent = notification.bigText ?: notification.text
        val contentField = if (notification.bigText != null) "bigText" else "text"
        binding.labelContent.text = getString(R.string.label_with_field, getString(R.string.label_content), contentField)
        binding.textContent.text = displayContent ?: context.getString(R.string.no_content)
        if (notification.bigText != null && notification.text != null && notification.bigText != notification.text) {
            binding.iconContentInfo.visibility = View.VISIBLE
            binding.iconContentInfo.setOnClickListener {
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.label_content)
                    .setMessage(getString(R.string.desc_content_fallback, notification.text))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        } else {
            binding.iconContentInfo.visibility = View.GONE
        }

        // 發佈時間
        binding.textTime.text = formatTime(notification.postTime)
        binding.labelPostTime.setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.label_time)
                .setMessage(R.string.desc_post_time)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        // 事件時間 (whenTime)
        val whenTime = notification.whenTime
        if (whenTime > 0) {
            binding.layoutWhenTime.visibility = View.VISIBLE
            val whenText = formatTime(whenTime)
            val showWhen = snapshotFor(notification)?.extras?.optBoolean("android.showWhen", true) ?: true
            binding.textWhenTime.text = if (showWhen) whenText
                else "$whenText ${getString(R.string.label_when_not_shown)}"
            binding.labelWhenTime.setOnClickListener {
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.label_when_time)
                    .setMessage(R.string.desc_when_time)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        } else {
            binding.layoutWhenTime.visibility = View.GONE
        }

        // 擷取時間
        binding.textCaptureTime.text = formatTime(notification.captureTime)
        binding.labelCaptureTime.setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.label_capture_time)
                .setMessage(R.string.desc_capture_time)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        // 標籤
        binding.chipGroupFlags.removeAllViews()

        // Importance / Priority chips
        if (notification.importance >= 0) {
            // API 26+：顯示 importance chip
            when (notification.importance) {
                0 -> addChip("NONE", R.color.status_disabled, R.string.tag_importance_none_desc)
                1 -> addChip("MIN", R.color.tag_silent, R.string.tag_importance_min_desc)
                2 -> addChip("LOW", R.color.tag_silent, R.string.tag_importance_low_desc)
                4 -> addChip("HIGH", R.color.status_warning, R.string.tag_importance_high_desc)
                5 -> addChip("MAX", R.color.status_warning, R.string.tag_importance_max_desc)
            }
        } else {
            // Pre-26：顯示 priority chip
            when (notification.priority) {
                -2 -> addChip("PRI:MIN", R.color.tag_silent, R.string.tag_priority_min_desc)
                -1 -> addChip("PRI:LOW", R.color.tag_silent, R.string.tag_priority_low_desc)
                1 -> addChip("PRI:HIGH", R.color.status_warning, R.string.tag_priority_high_desc)
                2 -> addChip("PRI:MAX", R.color.status_warning, R.string.tag_priority_max_desc)
            }
        }

        // 系統通知抽屜分類標籤
        if (notification.isConversation ||
            (notification.isMessagingStyle && !notification.shortcutId.isNullOrEmpty())) {
            addChip("Conversation", R.color.tag_conversation, R.string.tag_conversation_desc)
        }
        if (notification.isMessagingStyle) {
            addChip("MessagingStyle", R.color.tag_messaging_style, R.string.tag_messaging_style_desc)
        }

        // 通知屬性標籤
        if (notification.isOngoing) {
            addChip("Ongoing", R.color.event_initial, R.string.tag_ongoing_desc)
        }
        if (notification.isNoClear) {
            addChip("NoClear", R.color.event_initial, R.string.tag_no_clear_desc)
        }
        if (notification.isForegroundService) {
            addChip("Foreground Service", R.color.event_ranking, R.string.tag_fg_service_desc)
        }
        // 推斷 chips（虛線邊框）
        if (notification.likelyHeadsup) {
            addChip("Heads-up", R.color.status_warning, R.string.tag_headsup_desc, inferred = true)
        }
        if (notification.isAudible) {
            addChip("Audible", R.color.tag_audible, R.string.tag_audible_desc, inferred = true)
        }
        if (notification.isAutoCancel) {
            addChip("AutoCancel", R.color.event_updated, R.string.tag_auto_cancel_desc)
        }
        if (notification.isHighPriority) {
            addChip("HighPriority", R.color.status_warning, R.string.tag_high_priority_desc)
        }
        if (notification.isLocalOnly) {
            addChip("LocalOnly", R.color.text_secondary, R.string.tag_local_only_desc)
        }
        if (notification.isGroupSummary) {
            addChip("Group Summary", R.color.event_posted, R.string.tag_summary_desc)
        }
        if (notification.hasBubbleMetadata) {
            addChip("Bubble", R.color.status_enabled, R.string.tag_bubble_desc)
        }
        if (notification.hasCustomContentView || notification.hasCustomBigContentView || notification.hasCustomHeadsUpContentView) {
            addChip("Custom View", R.color.text_secondary, R.string.tag_custom_view_desc)
        }
        if (notification.showChronometer) {
            addChip("Chronometer", R.color.event_ranking, R.string.tag_chronometer_desc)
        }
        if (notification.isAmbient) {
            addChip("Ambient", R.color.tag_silent, R.string.tag_ambient_desc)
        }
        if (notification.isSuspended) {
            addChip("Suspended", R.color.status_disabled, R.string.tag_suspended_desc)
        }

        // Style 標籤（基於 template 尾綴匹配）
        val style = notification.template
        when {
            style == null -> { /* 無 Style，不加標籤 */ }
            style.endsWith("BigTextStyle") ->
                addChip("BigTextStyle", R.color.tag_big_text_style, R.string.tag_big_text_style_desc)
            style.endsWith("BigPictureStyle") ->
                addChip("BigPictureStyle", R.color.tag_big_picture_style, R.string.tag_big_picture_style_desc)
            style.endsWith("InboxStyle") ->
                addChip("InboxStyle", R.color.tag_inbox_style, R.string.tag_inbox_style_desc)
            style.endsWith("MediaStyle") || style.endsWith("DecoratedMediaCustomViewStyle") ->
                addChip("MediaStyle", R.color.tag_media_style, R.string.tag_media_style_desc)
            style.endsWith("CallStyle") ->
                addChip("CallStyle", R.color.tag_call_style, R.string.tag_call_style_desc)
        }

        // 詳細資訊欄位

        // 識別
        binding.textKey.text = notification.notificationKey
        binding.labelKey.setOnClickListener { showDescDialog(R.string.label_key, R.string.desc_notification_key) }

        // 頻道資訊卡片（API 26+）— Phase 31m：拆出 applyChannelInfo，可隨 Flow 重整
        if (notification.channelId != null) {
            binding.cardChannel.visibility = View.VISIBLE
            binding.textChannel.text = notification.channelId
            binding.labelChannelId.setOnClickListener { showDescDialog(R.string.label_channel, R.string.desc_channel_id) }
            binding.labelChannelName.setOnClickListener { showDescDialog(R.string.label_channel_name, R.string.desc_channel_name) }
            binding.labelImportance.setOnClickListener { showDescDialog(R.string.label_importance, R.string.desc_importance) }
            applyChannelInfo(notification, channelEntity)
        }

        // 分類 / 群組
        binding.textCategory.text = notification.category ?: "null"
        binding.labelCategory.setOnClickListener { showDescDialog(R.string.label_category, R.string.desc_category) }
        binding.textGroup.text = notification.groupKey ?: "null"
        binding.labelGroup.setOnClickListener { showDescDialog(R.string.label_group, R.string.desc_group_key) }
        val overrideGroupKey = snapshotFor(notification)?.overrideGroupKey
        if (overrideGroupKey != null) {
            binding.layoutOverrideGroupKey.visibility = View.VISIBLE
            binding.textOverrideGroupKey.text = overrideGroupKey
            binding.labelOverrideGroupKey.setOnClickListener { showDescDialog(R.string.label_group_override, R.string.desc_override_group_key) }
        }
        binding.textSortKey.text = notification.sortKey ?: "null"
        binding.labelSortKey.setOnClickListener { showDescDialog(R.string.label_sort_key, R.string.desc_sort_key) }

        // 狀態 / 行為屬性
        binding.textVisibility.text = when (notification.visibility) {
            Notification.VISIBILITY_PUBLIC -> "PUBLIC"
            Notification.VISIBILITY_PRIVATE -> "PRIVATE"
            Notification.VISIBILITY_SECRET -> "SECRET"
            else -> notification.visibility.toString()
        }
        binding.labelVisibility.setOnClickListener { showDescDialog(R.string.label_visibility, R.string.desc_visibility) }

        // priority
        val priorityName = when (notification.priority) {
            -2 -> "MIN"; -1 -> "LOW"; 0 -> "DEFAULT"; 1 -> "HIGH"; 2 -> "MAX"
            else -> notification.priority.toString()
        }
        binding.textPriority.text = "$priorityName (${notification.priority})"
        if (notification.importance >= 0) {
            binding.labelPriority.setTextColor(
                ContextCompat.getColor(context, R.color.text_tertiary))
            binding.labelPriority.setOnClickListener { showDescDialog(R.string.label_priority, R.string.desc_priority_with_channel) }
        } else {
            binding.labelPriority.setOnClickListener { showDescDialog(R.string.label_priority, R.string.desc_priority) }
        }

        binding.textShortcutId.text = notification.shortcutId ?: "null"
        binding.labelShortcutId.setOnClickListener { showDescDialog(R.string.label_shortcut_id, R.string.desc_shortcut_id) }

        // color
        val color = notification.color
        if (color != 0) {
            binding.viewColorPreview.visibility = View.VISIBLE
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 4f * resources.displayMetrics.density
                setColor(color)
            }
            binding.viewColorPreview.background = bg
            binding.textColor.text = String.format("#%06X", 0xFFFFFF and color)
        } else {
            binding.viewColorPreview.visibility = View.GONE
            binding.textColor.text = "未設定"
        }
        binding.labelColor.setOnClickListener { showDescDialog(R.string.label_color, R.string.desc_color) }

        // flags
        val flags = notification.flags
        binding.textFlags.text = String.format("0x%08X", flags)
        binding.labelFlags.setOnClickListener {
            val decoded = buildString {
                appendLine("FLAG_SHOW_LIGHTS: ${(flags and 0x01) != 0}")
                appendLine("FLAG_ONGOING_EVENT: ${(flags and 0x02) != 0}")
                appendLine("FLAG_INSISTENT: ${(flags and 0x04) != 0}")
                appendLine("FLAG_ONLY_ALERT_ONCE: ${(flags and 0x08) != 0}")
                appendLine("FLAG_AUTO_CANCEL: ${(flags and 0x10) != 0}")
                appendLine("FLAG_NO_CLEAR: ${(flags and 0x20) != 0}")
                appendLine("FLAG_FOREGROUND_SERVICE: ${(flags and 0x40) != 0}")
                appendLine("FLAG_HIGH_PRIORITY: ${(flags and 0x80) != 0}")
                appendLine("FLAG_LOCAL_ONLY: ${(flags and 0x100) != 0}")
                append("FLAG_GROUP_SUMMARY: ${(flags and 0x200) != 0}")
            }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.label_flags)
                .setMessage(getString(R.string.desc_flags, decoded))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        // contentHash
        binding.textHash.text = getString(R.string.format_hash_truncated, notification.contentHash.take(16))
        binding.labelContentHash.setOnClickListener { showDescDialog(R.string.label_content_hash, R.string.desc_content_hash) }

        // 系統通知設定按鈕
        binding.btnAppNotificationSettings.setOnClickListener {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, notification.packageName)
                }
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${notification.packageName}")
                }
            }
            startActivity(intent)
        }
    }

    /**
     * Phase 31m：channel 區渲染拆出 — 隨 channel Flow emit 重整。
     * notification 的 channelId 不會變，可 cache 第一次 displayNotification 的引用；
     * channelEntity 由 Flow 提供，可能為 null（DB 未寫入或被刪）。
     */
    private fun applyChannelInfo(notification: NotificationDisplay, channelEntity: ChannelEntity?) {
        binding.textChannelName.text = channelEntity?.channelName
            ?: if (!NotificationCaptureService.isRankingMapPopulated) getString(R.string.channel_name_pending)
            else getString(R.string.label_channel_name_unknown)

        // importance（優先 ChannelEntity，fallback display.importance — 來自 ranking observation）
        val effectiveImportance = channelEntity?.importance?.takeIf { it >= 0 }
            ?: notification.importance.takeIf { it >= 0 }
        if (effectiveImportance != null) {
            val importanceName = when (effectiveImportance) {
                0 -> "NONE"; 1 -> "MIN"; 2 -> "LOW"; 3 -> "DEFAULT"; 4 -> "HIGH"; 5 -> "MAX"
                else -> effectiveImportance.toString()
            }
            binding.textImportance.text = "$importanceName ($effectiveImportance)"
        } else {
            binding.textImportance.text = if (!NotificationCaptureService.isRankingMapPopulated)
                getString(R.string.channel_name_pending) else getString(R.string.label_channel_name_unknown)
        }

        // channel group
        if (channelEntity?.groupId != null) {
            binding.layoutChannelGroup.visibility = View.VISIBLE
            binding.textChannelGroup.text = channelEntity.groupId
            binding.labelChannelGroup.setOnClickListener { showDescDialog(R.string.label_channel_group, R.string.desc_channel_group) }
        } else {
            binding.layoutChannelGroup.visibility = View.GONE
        }
    }

    private fun showEventDetail(event: NotificationEventEntity) {
        val sb = StringBuilder()

        // 基本資訊
        sb.appendLine("notification_key: ${event.notificationKey}")
        sb.appendLine("event_type: ${event.eventType.name}")
        sb.appendLine("event_time: ${preciseTimeFormat.format(Date(event.eventTime))}")
        sb.appendLine("event_time_raw: ${event.eventTime}")
        sb.appendLine("package: ${event.packageName}")
        event.channelId?.let { sb.appendLine("channel_id: $it") }
        event.title?.let { sb.appendLine("title: $it") }
        event.text?.let { sb.appendLine("text: $it") }
        sb.appendLine("content_hash: ${event.contentHash}")
        sb.appendLine("is_audible: ${event.isAudible}")
        sb.appendLine("likely_headsup: ${event.likelyHeadsup}")

        // REMOVED 事件：移除原因（Phase 16：從 eventRawJson.removalReason 取，不再有 column）
        if (event.eventType == EventType.REMOVED) {
            val reason = com.notificationmaster.core.NotificationSnapshotParser
                .parse(event.eventRawJson)?.removalReason
            if (reason != null) {
                sb.appendLine()
                sb.appendLine("── 移除資訊 ──")
                sb.appendLine("removal_reason: $reason")
                sb.appendLine("removal_reason_category: ${ApiVersionHelper.categorizeRemovalReason(reason)}")
                sb.appendLine("removal_reason_desc: ${ApiVersionHelper.getRemovalReasonDescription(reason)}")
            }
        }

        sb.appendLine()
        sb.appendLine("── 提示 ──")
        sb.appendLine("長按時間軸事件可複製完整 event raw JSON 至剪貼簿")

        val textView = TextView(requireContext()).apply {
            text = sb.toString()
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setPadding(48, 24, 48, 24)
            setTextIsSelectable(true)
        }
        val scrollView = ScrollView(requireContext()).apply {
            addView(textView)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("${event.eventType.name} 事件詳情")
            .setView(scrollView)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.clipboard_copy_raw_json) { _, _ ->
                copyToClipboard(event.eventRawJson, "event raw JSON")
            }
            .show()
    }

    private fun displayMediaAttachments(attachments: List<MediaAttachmentEntity>) {
        if (attachments.isEmpty()) {
            binding.cardMedia.visibility = View.GONE
            return
        }
        binding.cardMedia.visibility = View.VISIBLE
        val container = binding.layoutMediaContainer
        container.removeAllViews()
        for (attachment in attachments) addAttachmentItem(container, attachment)
    }

    /**
     * W22ac：同 nkey 跨 event dedup 總覽（已在本 event 顯示的 hash 由 caller 過濾）。
     * 此區塊 query 已排除 trace state（save_failed_ / extract_failed_ / unavailable_），
     * 但實體檔案仍可能 mediaFileExists=false（被使用者清理 / migrate 失敗），共用
     * [addAttachmentItem] 的 fileExists 分支處理。
     */
    private fun displayMediaRelatedAttachments(attachments: List<MediaAttachmentEntity>) {
        if (attachments.isEmpty()) {
            binding.cardMediaRelated.visibility = View.GONE
            return
        }
        binding.cardMediaRelated.visibility = View.VISIBLE
        val container = binding.layoutMediaRelatedContainer
        container.removeAllViews()
        for (attachment in attachments) addAttachmentItem(container, attachment)
    }

    private fun addAttachmentItem(container: LinearLayout, attachment: MediaAttachmentEntity) {
        val ctx = requireContext()
        val sizePx = (80 * resources.displayMetrics.density).toInt()
        val marginPx = (8 * resources.displayMetrics.density).toInt()

        // W22ab：trace 狀態三態 — unavailable(URI 失敗)、saveFailed(IO 寫入失敗)、
        // extractFailed(drawable 解析失敗 / API 限制)。詳見 MediaExtractor 內部。
        val isUnavailable = attachment.filePath.isEmpty() && !attachment.sourceUri.isNullOrEmpty()
        val isSaveFailed = attachment.filePath.isEmpty() &&
            attachment.contentHash.startsWith("save_failed_")
        val isExtractFailed = attachment.filePath.isEmpty() &&
            attachment.contentHash.startsWith("extract_failed_")
        val fileExists = !isUnavailable && !isSaveFailed && !isExtractFailed &&
            MediaExtractor.mediaFileExists(ctx, attachment.filePath)

        val itemLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = marginPx
            }
        }

        val imageView = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = attachment.mediaType.name

            if (fileExists) {
                val bitmap = MediaExtractor.loadMediaBitmapSampled(
                    ctx, attachment.filePath, sizePx, sizePx
                )
                if (bitmap != null) {
                    setImageBitmap(bitmap)
                }
                setOnClickListener {
                    openMediaFile(attachment.filePath, attachment.mimeType)
                }
            } else {
                setImageResource(android.R.drawable.ic_menu_report_image)
                alpha = 0.3f
            }

            if (isUnavailable) {
                setOnLongClickListener {
                    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("media_uri", attachment.sourceUri))
                    Toast.makeText(ctx, R.string.media_uri_copied, Toast.LENGTH_SHORT).show()
                    true
                }
            }
        }

        val label = TextView(ctx).apply {
            text = when {
                fileExists -> attachment.mediaType.name
                isUnavailable -> ctx.getString(R.string.media_unavailable)
                isSaveFailed -> ctx.getString(R.string.media_save_failed)
                isExtractFailed -> ctx.getString(R.string.media_extract_failed)
                else -> ctx.getString(R.string.media_file_removed)
            }
            textSize = 10f
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(sizePx, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        itemLayout.addView(imageView)
        itemLayout.addView(label)
        container.addView(itemLayout)
    }

    /**
     * 使用系統檔案檢視器開啟媒體檔案
     */
    private fun openMediaFile(filePath: String, mimeType: String) {
        val ctx = requireContext()
        val uri = MediaExtractor.getShareUri(ctx, filePath)
        if (uri == null) {
            Toast.makeText(ctx, R.string.media_file_removed, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w("NotificationDetail", "Failed to open media file", e)
            Toast.makeText(ctx, R.string.error_no_viewer, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 根據通知的 Style 類型，從 snapshot.extras 解析並顯示額外的樣式資訊
     */
    private fun displayStyleInfo(notification: NotificationDisplay) {
        val _binding = _binding ?: return
        val style = notification.template ?: return
        val container = _binding.layoutStyleInfoContainer
        container.removeAllViews()

        val extras = snapshotFor(notification)?.extras

        var hasContent = false

        when {
            style.endsWith("InboxStyle") -> {
                val textLines = extras?.optJSONArray("android.textLines")
                if (textLines != null && textLines.length() > 0) {
                    hasContent = true
                    addStyleHeader(container, "InboxStyle", R.string.style_inbox_desc)
                    addStyleInfoLabel(container, "textLines")
                    for (i in 0 until textLines.length()) {
                        addStyleInfoText(container, textLines.optString(i, ""))
                    }
                }
                // summaryText（InboxStyle 也可以有）
                val summaryText = extras?.optString("android.summaryText", "")
                if (!summaryText.isNullOrEmpty()) {
                    if (!hasContent) addStyleHeader(container, "InboxStyle", R.string.style_inbox_desc)
                    hasContent = true
                    addStyleInfoLabel(container, "summaryText")
                    addStyleInfoText(container, summaryText)
                }
            }

            style.endsWith("MediaStyle") || style.endsWith("DecoratedMediaCustomViewStyle") -> {
                addStyleHeader(container, "MediaStyle", R.string.style_media_desc)
                val compactActions = extras?.optJSONArray("android.compactActions")
                if (compactActions != null) {
                    hasContent = true
                    val indices = (0 until compactActions.length())
                        .map { compactActions.optInt(it, -1) }
                        .filter { it >= 0 }
                    addStyleInfoLabel(container, "compactActions")
                    addStyleInfoText(container, indices.joinToString(", "))
                }
                val hasMediaSession = extras?.has("android.mediaSession") == true
                if (hasMediaSession) {
                    hasContent = true
                    addStyleInfoLabel(container, "mediaSession")
                    addStyleInfoText(container, "present")
                }
                if (!hasContent) container.removeAllViews() // 移除空的 header
            }

            style.endsWith("CallStyle") -> {
                addStyleHeader(container, "CallStyle", R.string.style_call_desc)
                val callType = extras?.optInt("android.callType", -1) ?: -1
                if (callType >= 0) {
                    hasContent = true
                    val typeDesc = when (callType) {
                        1 -> "incoming (來電)"
                        2 -> "ongoing (通話中)"
                        3 -> "screening (篩選中)"
                        else -> callType.toString()
                    }
                    addStyleInfoLabel(container, "callType")
                    addStyleInfoText(container, typeDesc)
                }
                val callPerson = extras?.optString("android.callPerson", "")
                if (!callPerson.isNullOrEmpty()) {
                    hasContent = true
                    addStyleInfoLabel(container, "callPerson")
                    addStyleInfoText(container, callPerson)
                }
                if (!hasContent) container.removeAllViews()
            }

            style.endsWith("MessagingStyle") -> {
                addStyleHeader(container, "MessagingStyle", R.string.style_messaging_desc)
                val conversationTitle = notification.conversationTitle
                    ?: extras?.optString("android.conversationTitle", "")
                if (!conversationTitle.isNullOrEmpty()) {
                    hasContent = true
                    addStyleInfoLabel(container, "conversationTitle")
                    addStyleInfoText(container, conversationTitle)
                }
                val isGroup = notification.isGroupConversation
                if (isGroup) {
                    hasContent = true
                    addStyleInfoLabel(container, "isGroupConversation")
                    addStyleInfoText(container, "true")
                }
                if (!hasContent) container.removeAllViews()
            }

            style.endsWith("BigTextStyle") -> {
                val summaryText = extras?.optString("android.summaryText", "")
                if (!summaryText.isNullOrEmpty()) {
                    hasContent = true
                    addStyleHeader(container, "BigTextStyle", R.string.style_big_text_desc)
                    addStyleInfoLabel(container, "summaryText")
                    addStyleInfoText(container, summaryText)
                }
            }
        }

        _binding.cardStyleInfo.visibility = if (hasContent) View.VISIBLE else View.GONE
    }

    private fun displayConditionalBlocks(notification: NotificationDisplay) {
        val _binding = _binding ?: return
        val extras = snapshotFor(notification)?.extras
        val notif = snapshotFor(notification)?.notification

        // 進度條
        val progress = extras?.optInt("android.progress", 0) ?: 0
        val progressMax = extras?.optInt("android.progressMax", 0) ?: 0
        val progressIndeterminate = extras?.optBoolean("android.progressIndeterminate", false) ?: false
        if (progress > 0 || progressIndeterminate) {
            _binding.cardProgress.visibility = View.VISIBLE
            _binding.textProgress.text = if (progressIndeterminate) "不確定"
                else "$progress / $progressMax"
        } else {
            _binding.cardProgress.visibility = View.GONE
        }

        // 計時器
        if (notification.showChronometer) {
            _binding.cardChronometer.visibility = View.VISIBLE
            val countDown = extras?.optBoolean("android.chronometerCountDown", false) ?: false
            _binding.textChronometer.text = if (countDown) "倒數計時" else "正計時"
        } else {
            _binding.cardChronometer.visibility = View.GONE
        }

        // 關聯聯絡人（extras.android.people 為 JSONArray 或字串陣列）
        val people = extras?.optJSONArray("android.people")
        if (people != null && people.length() > 0) {
            _binding.cardPeople.visibility = View.VISIBLE
            _binding.textPeople.text = (0 until people.length())
                .joinToString("\n") { people.optString(it, "") }
        } else {
            _binding.cardPeople.visibility = View.GONE
        }

        // 訊息內容（MessagingStyle extras.android.messages）
        val messages = extras?.optJSONArray("android.messages")
        if (messages != null && messages.length() > 0) {
            _binding.cardMessages.visibility = View.VISIBLE
            _binding.textMessages.text = (0 until messages.length()).joinToString("\n") { i ->
                val msg = messages.optJSONObject(i)
                val sender = msg?.optString("sender", "") ?: ""
                val text = msg?.optString("text", "") ?: ""
                if (sender.isNotEmpty()) "$sender: $text" else text
            }
        } else {
            _binding.cardMessages.visibility = View.GONE
        }

        // Bubble 詳情（snapshot.notification.bubbleMetadata）
        val bubble = notif?.optJSONObject("bubbleMetadata")
        if (bubble != null) {
            _binding.cardBubble.visibility = View.VISIBLE
            val container = _binding.layoutBubbleContainer
            container.removeAllViews()
            addStyleInfoLabel(container, "desiredHeight")
            addStyleInfoText(container, "${bubble.optInt("desiredHeight", 0)} dp")
            val heightRes = bubble.optInt("desiredHeightResId", 0)
            if (heightRes != 0) {
                addStyleInfoLabel(container, "desiredHeightResId")
                addStyleInfoText(container, heightRes.toString())
            }
            addStyleInfoLabel(container, "autoExpand")
            addStyleInfoText(container, bubble.optBoolean("autoExpand", false).toString())
            addStyleInfoLabel(container, "suppressNotification")
            addStyleInfoText(container, bubble.optBoolean("suppressNotification", false).toString())
        } else {
            _binding.cardBubble.visibility = View.GONE
        }
    }

    private fun displayEffects(notification: NotificationDisplay) {
        val _binding = _binding ?: return
        val notif = snapshotFor(notification)?.notification
        val soundUri = notif?.let {
            if (it.has("sound") && !it.isNull("sound")) it.optString("sound").takeIf { s -> s.isNotEmpty() } else null
        }
        val vibratePattern = notif?.optJSONArray("vibrate")?.takeIf { it.length() > 0 }?.toString()
        val ledArgb = notif?.optInt("ledARGB", 0) ?: 0
        val ledOnMs = notif?.optInt("ledOnMs", 0) ?: 0
        val ledOffMs = notif?.optInt("ledOffMs", 0) ?: 0

        val hasEffects = soundUri != null || vibratePattern != null || ledArgb != 0

        if (!hasEffects) {
            _binding.cardEffects.visibility = View.GONE
            return
        }

        _binding.cardEffects.visibility = View.VISIBLE
        val container = _binding.layoutEffectsContainer
        container.removeAllViews()

        if (soundUri != null) {
            addStyleInfoLabel(container, "soundUri")
            addStyleInfoText(container, soundUri)
        }
        if (vibratePattern != null) {
            addStyleInfoLabel(container, "vibratePattern")
            addStyleInfoText(container, vibratePattern)
        }
        if (ledArgb != 0) {
            addStyleInfoLabel(container, "LED")
            addStyleInfoText(container, "色彩: ${String.format("#%06X", 0xFFFFFF and ledArgb)} · 亮: ${ledOnMs}ms · 暗: ${ledOffMs}ms")
        }
    }

    /**
     * Plan 2 Phase 7b-B：ranking 區塊資料來源切到 RankingObservation + RankingSnapshot
     * 合併（[com.notificationmaster.core.RankingSnapshotMerger]）。完整 JSON 還原後，
     * 列出所有非預設欄位。
     */
    private fun displayRanking(rankingJson: JSONObject?) {
        val _binding = _binding ?: return
        if (rankingJson == null) {
            _binding.cardRanking.visibility = View.GONE
            return
        }

        val rank = if (rankingJson.has("rank") && !rankingJson.isNull("rank")) rankingJson.optInt("rank") else null
        val importance = if (rankingJson.has("importance") && !rankingJson.isNull("importance"))
            rankingJson.optInt("importance") else null
        val suppressed = rankingJson.optInt("suppressedVisualEffects", 0)
        val lastAudibly = rankingJson.optLong("lastAudiblyAlertedMillis", 0L)
        val isAmbient = rankingJson.optBoolean("isAmbient", false)
        val isSuspended = rankingJson.optBoolean("isSuspended", false)
        val isConversation = rankingJson.optBoolean("isConversation", false)
        val canBubble = rankingJson.optBoolean("canBubble", false)
        val canShowBadge = rankingJson.optBoolean("canShowBadge", false)
        val overrideGroupKey = if (rankingJson.has("overrideGroupKey") && !rankingJson.isNull("overrideGroupKey"))
            rankingJson.optString("overrideGroupKey") else null

        val anyContent = rank != null || importance != null || suppressed != 0 ||
            lastAudibly > 0 || isAmbient || isSuspended || isConversation ||
            canBubble || canShowBadge || overrideGroupKey != null
        if (!anyContent) {
            _binding.cardRanking.visibility = View.GONE
            return
        }

        _binding.cardRanking.visibility = View.VISIBLE
        val container = _binding.layoutRankingContainer
        container.removeAllViews()

        _binding.labelRankingTitle.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.label_ranking)
                .setMessage(R.string.desc_ranking)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        if (rank != null) {
            addStyleInfoLabel(container, "rank")
            addStyleInfoText(container, rank.toString())
        }
        if (importance != null) {
            addStyleInfoLabel(container, "importance")
            addStyleInfoText(container, importance.toString())
        }
        if (suppressed != 0) {
            addStyleInfoLabel(container, "suppressedVisualEffects")
            addStyleInfoText(container, String.format("0x%X", suppressed))
        }
        if (lastAudibly > 0) {
            addStyleInfoLabel(container, "lastAudiblyAlertedMillis")
            addStyleInfoText(container, formatTime(lastAudibly))
        }
        if (isAmbient) {
            addStyleInfoLabel(container, "isAmbient")
            addStyleInfoText(container, "true")
        }
        if (isSuspended) {
            addStyleInfoLabel(container, "isSuspended")
            addStyleInfoText(container, "true")
        }
        if (isConversation) {
            addStyleInfoLabel(container, "isConversation")
            addStyleInfoText(container, "true")
        }
        if (canBubble) {
            addStyleInfoLabel(container, "canBubble")
            addStyleInfoText(container, "true")
        }
        if (canShowBadge) {
            addStyleInfoLabel(container, "canShowBadge")
            addStyleInfoText(container, "true")
        }
        if (overrideGroupKey != null) {
            addStyleInfoLabel(container, "overrideGroupKey")
            addStyleInfoText(container, overrideGroupKey)
        }
    }

    private fun displayDeviceState(deviceState: DeviceStateEntity?) {
        val _binding = _binding ?: return
        if (deviceState == null) {
            _binding.cardDeviceState.visibility = View.GONE
            return
        }

        _binding.cardDeviceState.visibility = View.VISIBLE
        val container = _binding.layoutDeviceStateContainer
        container.removeAllViews()

        addStyleInfoLabel(container, "ringerMode")
        addStyleInfoText(container, when (deviceState.ringerMode) {
            0 -> "靜音"; 1 -> "振動"; 2 -> "正常"; else -> deviceState.ringerMode.toString()
        })

        addStyleInfoLabel(container, "isScreenOn")
        addStyleInfoText(container, if (deviceState.isScreenOn) "亮" else "暗")

        addStyleInfoLabel(container, "batteryLevel")
        addStyleInfoText(container, if (deviceState.batteryLevel >= 0) "${deviceState.batteryLevel}%" else "未知")

        addStyleInfoLabel(container, "batteryStatus")
        addStyleInfoText(container, when (deviceState.batteryStatus) {
            2 -> "充電中"; 3 -> "放電中"; 4 -> "未充電"; 5 -> "已充滿"; else -> "未知"
        })

        addStyleInfoLabel(container, "isConnected")
        addStyleInfoText(container, when (deviceState.isConnected) {
            true -> "已連線"; false -> "未連線"; null -> "未知"
        })

        addStyleInfoLabel(container, "connectionType")
        addStyleInfoText(container, when (deviceState.connectionType) {
            -1 -> "無"; 1 -> "WiFi"; 0 -> "行動數據"; 9 -> "乙太網路"
            else -> "類型 ${deviceState.connectionType}"
        })
    }

    private fun addStyleHeader(container: LinearLayout, styleName: String, descriptionRes: Int) {
        val ctx = container.context
        val dp = ctx.resources.displayMetrics.density
        val tv = TextView(ctx).apply {
            text = styleName
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_info_outline, 0)
            compoundDrawablePadding = (4 * dp).toInt()
            setPadding(0, 0, 0, (4 * dp).toInt())
            setOnClickListener {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(styleName)
                    .setMessage(descriptionRes)
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
        }
        container.addView(tv)
    }

    private fun addStyleInfoLabel(container: LinearLayout, label: String) {
        val tv = TextView(container.context).apply {
            text = label
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, 8, 0, 2)
        }
        container.addView(tv)
    }

    private fun addStyleInfoText(container: LinearLayout, content: String) {
        val tv = TextView(container.context).apply {
            text = content
            textSize = 14f
            setPadding(0, 0, 0, 4)
            setTextIsSelectable(true)
        }
        container.addView(tv)
    }

    /**
     * 顯示動作按鈕與 Intent 資訊（合併至同一張 Card）
     */
    private fun displayIntents(actions: List<ActionEntity>, notification: NotificationDisplay) {
        val _binding = _binding ?: return
        val hasAnyIntent = notification.hasContentIntent || notification.hasDeleteIntent || notification.hasFullScreenIntent

        if (actions.isEmpty() && !hasAnyIntent) {
            _binding.cardActions.visibility = View.GONE
            return
        }

        _binding.cardActions.visibility = View.VISIBLE
        if (actions.isNotEmpty()) {
            _binding.labelActionsTitle.text = "${getString(R.string.detail_actions)} (${actions.size})"
        }
        val container = _binding.layoutActionsContainer
        container.removeAllViews()

        val intentSet = PendingIntentCache.get(notification.notificationKey)

        // 動作按鈕區塊
        for ((index, action) in actions.withIndex()) {
            if (index > 0) {
                val divider = View(container.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply {
                        topMargin = (8 * resources.displayMetrics.density).toInt()
                        bottomMargin = (8 * resources.displayMetrics.density).toInt()
                    }
                    setBackgroundColor(ContextCompat.getColor(context, R.color.text_tertiary))
                    alpha = 0.3f
                }
                container.addView(divider)
            }

            val titleText = action.title ?: "(無標題)"
            val actionPi = intentSet?.actionIntents?.get(action.actionIndex)
            addIntentRow(container, "[${action.actionIndex}] $titleText", actionPi)

            val semanticName = semanticActionName(action.semanticAction)
            if (semanticName != null) {
                addStyleInfoText(container, "語意動作: $semanticName")
            }

            if (action.isReplyAction) {
                val replyInfo = buildString {
                    append("直接回覆")
                    if (!action.replyLabel.isNullOrEmpty()) {
                        append(" · ${action.replyLabel}")
                    }
                }
                addStyleInfoText(container, replyInfo)

                if (!action.replyChoices.isNullOrEmpty()) {
                    try {
                        val choices = JSONArray(action.replyChoices)
                        val choiceList = (0 until choices.length()).map { choices.getString(it) }
                        addStyleInfoText(container, "預設選項: ${choiceList.joinToString(", ")}")
                    } catch (_: Exception) { }
                }
            }

            val flags = mutableListOf<String>()
            if (action.isContextual) flags.add("Contextual")
            if (action.isAuthenticationRequired) flags.add("需認證")
            if (!action.allowsFreeFormInput && action.isReplyAction) flags.add("不允許自由輸入")
            if (flags.isNotEmpty()) {
                addStyleInfoText(container, flags.joinToString(" · "))
            }
        }

        // Intent 資訊區塊（contentIntent / deleteIntent / fullScreenIntent）
        if (hasAnyIntent) {
            val intentsContainer = _binding.layoutIntentsContainer
            intentsContainer.removeAllViews()

            if (actions.isNotEmpty()) {
                _binding.layoutIntentsDivider.visibility = View.VISIBLE
                _binding.labelIntents.visibility = View.VISIBLE
            }
            intentsContainer.visibility = View.VISIBLE

            val intentMeta = snapshotFor(notification)?.notification?.optJSONObject("intents")

            if (notification.hasContentIntent) {
                val desc = buildIntentDescription("contentIntent", intentMeta?.optJSONObject("contentIntent"), notification.packageName)
                addIntentRow(intentsContainer, desc, intentSet?.contentIntent)
            }
            if (notification.hasDeleteIntent) {
                val desc = buildIntentDescription("deleteIntent", intentMeta?.optJSONObject("deleteIntent"), notification.packageName)
                addIntentRow(intentsContainer, desc, intentSet?.deleteIntent)
            }
            if (notification.hasFullScreenIntent) {
                val desc = buildIntentDescription("fullScreenIntent", intentMeta?.optJSONObject("fullScreenIntent"), notification.packageName)
                addIntentRow(intentsContainer, desc, intentSet?.fullScreenIntent)
            }
        }
    }

    /**
     * 從 snapshot.notification.intents 中的元資料組裝描述文字
     */
    private fun buildIntentDescription(name: String, meta: JSONObject?, packageName: String): String {
        if (meta == null) return name

        val parts = mutableListOf<String>()

        // API 34+ type flags
        val types = mutableListOf<String>()
        if (meta.optBoolean("isActivity", false)) types.add("Activity")
        if (meta.optBoolean("isBroadcast", false)) types.add("Broadcast")
        if (meta.optBoolean("isService", false)) types.add("Service")
        if (meta.optBoolean("isForegroundService", false)) types.add("FgService")
        if (types.isNotEmpty()) parts.add(types.joinToString("/"))

        // creatorPackage（僅在與通知來源不同時顯示）
        val creator = meta.optString("creatorPackage", "")
        if (creator.isNotEmpty() && creator != packageName) {
            parts.add("from: $creator")
        }

        return if (parts.isNotEmpty()) "$name (${parts.joinToString(" · ")})" else name
    }

    /**
     * 顯示自訂 View (RemoteViews) 資訊區塊
     */
    private fun displayRemoteViewsInfo(notification: NotificationDisplay) {
        val _binding = _binding ?: return
        if (!notification.hasCustomContentView && !notification.hasCustomBigContentView && !notification.hasCustomHeadsUpContentView) {
            _binding.layoutRemoteViews.visibility = View.GONE
            return
        }

        _binding.layoutRemoteViews.visibility = View.VISIBLE
        val container = _binding.layoutRemoteViewsContainer
        container.removeAllViews()

        // 從 snapshot 取 remoteViews 元資料（contentView / bigContentView / headsUpContentView 內含 layoutId / layoutName）
        val notif = snapshotFor(notification)?.notification
        val remoteViewsMeta = JSONObject().apply {
            notif?.optJSONObject("contentView")?.let { put("contentView", it) }
            notif?.optJSONObject("bigContentView")?.let { put("bigContentView", it) }
            notif?.optJSONObject("headsUpContentView")?.let { put("headsUpContentView", it) }
        }.takeIf { it.length() > 0 }

        if (notification.hasCustomContentView) {
            val desc = buildRemoteViewDescription("contentView", remoteViewsMeta?.optJSONObject("contentView"))
            addStyleInfoText(container, desc)
        }
        if (notification.hasCustomBigContentView) {
            val desc = buildRemoteViewDescription("bigContentView", remoteViewsMeta?.optJSONObject("bigContentView"))
            addStyleInfoText(container, desc)
        }
        if (notification.hasCustomHeadsUpContentView) {
            val desc = buildRemoteViewDescription("headsUpContentView", remoteViewsMeta?.optJSONObject("headsUpContentView"))
            addStyleInfoText(container, desc)
        }
    }

    /**
     * 從 snapshot.notification.{contentView, bigContentView, headsUpContentView}
     * 元資料組裝描述文字。優先顯示 layoutName，否則 layoutId (package)
     */
    private fun buildRemoteViewDescription(name: String, meta: JSONObject?): String {
        if (meta == null) return name
        val layoutName = meta.optString("layoutName", "")
        return if (layoutName.isNotEmpty()) {
            "$name: $layoutName"
        } else {
            val layoutId = meta.optInt("layoutId", 0)
            val pkg = meta.optString("package", "")
            "$name: $layoutId ($pkg)"
        }
    }

    /**
     * 新增一行 intent 資訊：12dp 圓形狀態指示器 + 文字
     * 綠色 = 快取中（可點擊觸發），灰色 = 已過期
     */
    /**
     * 觸發第三方 PendingIntent；API 34+ 帶上 BAL（Background Activity Launch）允許
     * options 避免被靜默丟棄。`PendingIntent.CanceledException` 由呼叫端處理。
     *
     * 注意：BAL 限制是 framework 層級，OEM 自啟動 / 背景活動管理（MIUI / EMUI /
     * OneUI 等）會額外攔截，無法用程式繞過，需引導使用者放行該 App 的背景活動權限。
     */
    @Throws(PendingIntent.CanceledException::class)
    private fun sendPendingIntentWithBal(ctx: android.content.Context, pi: PendingIntent) {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            val opts = android.app.ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(
                    android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                ).toBundle()
            pi.send(ctx, 0, null, null, null, null, opts)
        } else {
            pi.send()
        }
    }

    private fun addIntentRow(container: LinearLayout, text: String, pendingIntent: PendingIntent?) {
        val ctx = container.context
        val dp = resources.displayMetrics.density
        val dotSizePx = (12 * dp).toInt()

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (4 * dp).toInt()
            }
        }

        val dot = createStatusDot(ctx, dotSizePx, pendingIntent != null)
        row.addView(dot)

        val tv = TextView(ctx).apply {
            this.text = text
            textSize = 13f
            setPadding((6 * dp).toInt(), 0, 0, 0)
        }
        row.addView(tv)

        // 存活時可點擊觸發
        if (pendingIntent != null) {
            row.isClickable = true
            row.isFocusable = true
            val typedValue = android.util.TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
            row.setBackgroundResource(typedValue.resourceId)
            row.setOnClickListener {
                try {
                    sendPendingIntentWithBal(ctx, pendingIntent)
                    Toast.makeText(ctx, R.string.intent_triggered, Toast.LENGTH_SHORT).show()
                } catch (_: PendingIntent.CanceledException) {
                    Toast.makeText(ctx, R.string.intent_send_failed, Toast.LENGTH_SHORT).show()
                    updateDotColor(dot, dotSizePx, false)
                }
            }
        }

        container.addView(row)
    }

    /**
     * 建立 12dp 圓形狀態指示器
     */
    private fun createStatusDot(ctx: Context, sizePx: Int, isAlive: Boolean): View {
        return View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(ctx,
                    if (isAlive) R.color.status_enabled else R.color.text_tertiary))
            }
        }
    }

    /**
     * 更新圓點顏色
     */
    private fun updateDotColor(dot: View, sizePx: Int, isAlive: Boolean) {
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(dot.context,
                if (isAlive) R.color.status_enabled else R.color.text_tertiary))
        }
    }

    private fun semanticActionName(value: Int): String? = when (value) {
        SemanticAction.NONE -> null
        SemanticAction.REPLY -> "Reply"
        SemanticAction.MARK_AS_READ -> "Mark as Read"
        SemanticAction.MARK_AS_UNREAD -> "Mark as Unread"
        SemanticAction.DELETE -> "Delete"
        SemanticAction.ARCHIVE -> "Archive"
        SemanticAction.MUTE -> "Mute"
        SemanticAction.UNMUTE -> "Unmute"
        SemanticAction.THUMBS_UP -> "Thumbs Up"
        SemanticAction.THUMBS_DOWN -> "Thumbs Down"
        SemanticAction.CALL -> "Call"
        else -> "Unknown ($value)"
    }

    private fun showDescDialog(titleRes: Int, messageRes: Int) {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun addChip(text: String, colorRes: Int, descriptionRes: Int) {
        addChip(text, colorRes, descriptionRes, inferred = false)
    }

    /**
     * @param inferred true 時使用虛線邊框（推斷結果），false 時使用實心背景（原始資料）
     */
    private fun addChip(text: String, colorRes: Int, descriptionRes: Int, inferred: Boolean) {
        val ctx = requireContext()
        val chip = Chip(ctx).apply {
            this.text = text
            isClickable = true
            if (inferred) {
                // 虛線邊框：透明背景 + 有色邊線
                chipBackgroundColor = ContextCompat.getColorStateList(ctx, android.R.color.transparent)
                chipStrokeWidth = 2f * ctx.resources.displayMetrics.density
                chipStrokeColor = ContextCompat.getColorStateList(ctx, colorRes)
                setTextColor(ContextCompat.getColor(ctx, colorRes))
                // 虛線效果透過 PathEffect 無法直接設定在 Chip 上，改用降低透明度區分
                alpha = 0.85f
            } else {
                chipBackgroundColor = ContextCompat.getColorStateList(ctx, colorRes)
                setTextColor(ContextCompat.getColor(ctx, R.color.white))
            }
            setOnClickListener {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(text)
                    .setMessage(descriptionRes)
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
        }
        binding.chipGroupFlags.addView(chip)
    }
}
