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
import androidx.viewpager2.widget.ViewPager2
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
import com.notificationmaster.data.db.entity.NotificationEntity
import com.notificationmaster.data.db.entity.SemanticAction
import com.notificationmaster.data.db.entity.NotificationEventEntity
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

    private lateinit var pagerAdapter: EventGroupPagerAdapter

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
        setupEventPager()
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

    private fun setupEventPager() {
        val database = NotificationMasterApp.getInstance().database
        val eventDao = database.notificationEventDao()

        pagerAdapter = EventGroupPagerAdapter(
            eventDao = eventDao,
            lifecycleScope = viewLifecycleOwner.lifecycleScope,
            onEventClick = { event -> showEventDetail(event) }
        )
        binding.pagerEvents.adapter = pagerAdapter

        // 頁面切換時更新分頁指示器
        binding.pagerEvents.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updatePagerIndicator(position)
            }
        })
    }

    private fun updatePagerIndicator(position: Int) {
        val binding = _binding ?: return
        val total = pagerAdapter.itemCount
        if (total <= 1) {
            binding.textEventPagerIndicator.visibility = View.GONE
        } else {
            binding.textEventPagerIndicator.visibility = View.VISIBLE
            binding.textEventPagerIndicator.text =
                getString(R.string.event_pager_indicator, position + 1, total)
        }
    }

    private fun loadNotificationDetail() {
        val database = NotificationMasterApp.getInstance().database
        val notificationDao = database.notificationDao()
        val mediaDao = database.mediaAttachmentDao()

        viewLifecycleOwner.lifecycleScope.launch {
            val notification = withContext(Dispatchers.IO) {
                notificationDao.getById(args.notificationId)
            }

            if (notification != null) {
                val channelEntity = withContext(Dispatchers.IO) {
                    notification.channelId?.let { chId ->
                        database.channelDao().getByPackageAndChannelId(notification.packageName, chId)
                    }
                }
                displayNotification(notification, channelEntity)

                // 載入動作按鈕與 Intent 資訊
                val actionDao = database.actionDao()
                val actions = withContext(Dispatchers.IO) {
                    actionDao.getActionsByNotificationIdSync(args.notificationId)
                }
                displayIntents(actions, notification)

                // 載入媒體附件
                val attachments = withContext(Dispatchers.IO) {
                    mediaDao.getAttachmentsByNotificationIdSync(args.notificationId)
                }
                displayMediaAttachments(attachments)

                // 顯示樣式資訊
                displayStyleInfo(notification)

                // 條件性區塊
                displayConditionalBlocks(notification)

                // 通知效果、Ranking 快照、裝置狀態
                displayEffects(notification)
                displayRanking(notification)

                val deviceState = withContext(Dispatchers.IO) {
                    database.deviceStateDao().getByNotificationId(args.notificationId)
                }
                displayDeviceState(deviceState)

                // 取得同 key 的所有 Entity ID
                val entityIds = withContext(Dispatchers.IO) {
                    notificationDao.getEntityIdsByKey(notification.notificationKey)
                }

                // 提交給 pager adapter
                pagerAdapter.submitEntityIds(entityIds)

                // 定位到當前瀏覽的 entity
                val currentIndex = entityIds.indexOf(args.notificationId)
                if (currentIndex >= 0) {
                    binding.pagerEvents.setCurrentItem(currentIndex, false)
                }
                updatePagerIndicator(if (currentIndex >= 0) currentIndex else 0)

                // 顯示自訂 View 資訊
                displayRemoteViewsInfo(notification)
            }
        }
    }

    private fun displayNotification(notification: NotificationEntity, channelEntity: ChannelEntity? = null) {
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
            binding.textWhenTime.text = if (notification.showWhen) whenText
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

        // 頻道資訊卡片（API 26+）
        if (notification.channelId != null) {
            binding.cardChannel.visibility = View.VISIBLE
            binding.textChannel.text = notification.channelId
            binding.labelChannelId.setOnClickListener { showDescDialog(R.string.label_channel, R.string.desc_channel_id) }
            binding.textChannelName.text = channelEntity?.channelName
                ?: getString(R.string.label_channel_name_unknown)
            binding.labelChannelName.setOnClickListener { showDescDialog(R.string.label_channel_name, R.string.desc_channel_name) }

            // importance
            if (notification.importance >= 0) {
                val importanceName = when (notification.importance) {
                    0 -> "NONE"; 1 -> "MIN"; 2 -> "LOW"; 3 -> "DEFAULT"; 4 -> "HIGH"; 5 -> "MAX"
                    else -> notification.importance.toString()
                }
                binding.textImportance.text = "$importanceName (${notification.importance})"
            } else {
                binding.textImportance.text = getString(R.string.label_channel_name_unknown)
            }
            binding.labelImportance.setOnClickListener { showDescDialog(R.string.label_importance, R.string.desc_importance) }

            // channel group
            if (channelEntity?.groupId != null) {
                binding.layoutChannelGroup.visibility = View.VISIBLE
                binding.textChannelGroup.text = channelEntity.groupId
                binding.labelChannelGroup.setOnClickListener { showDescDialog(R.string.label_channel_group, R.string.desc_channel_group) }
            }
        }

        // 分類 / 群組
        binding.textCategory.text = notification.category ?: "null"
        binding.labelCategory.setOnClickListener { showDescDialog(R.string.label_category, R.string.desc_category) }
        binding.textGroup.text = notification.groupKey ?: "null"
        binding.labelGroup.setOnClickListener { showDescDialog(R.string.label_group, R.string.desc_group_key) }
        if (notification.overrideGroupKey != null) {
            binding.layoutOverrideGroupKey.visibility = View.VISIBLE
            binding.textOverrideGroupKey.text = notification.overrideGroupKey
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

    private fun showEventDetail(event: NotificationEventEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            val notification = withContext(Dispatchers.IO) {
                NotificationMasterApp.getInstance().database
                    .notificationDao().getById(event.notificationId)
            }
            _binding ?: return@launch

            val sb = StringBuilder()

            // 基本資訊
            sb.appendLine("notification_key: ${event.notificationKey}")
            sb.appendLine("event_type: ${event.eventType.name}")
            sb.appendLine("event_time: ${preciseTimeFormat.format(Date(event.eventTime))}")
            sb.appendLine("event_time_raw: ${event.eventTime}")

            // REMOVED 事件：移除原因
            if (event.eventType == EventType.REMOVED && event.removalReason != null) {
                sb.appendLine()
                sb.appendLine("── 移除資訊 ──")
                sb.appendLine("removal_reason: ${event.removalReason}")
                sb.appendLine("removal_reason_category: ${event.removalReasonCategory}")
                sb.appendLine("removal_reason_desc: ${ApiVersionHelper.getRemovalReasonDescription(event.removalReason)}")
            }

            // 變動內容（UPDATED / RANKING 事件）
            if (event.eventType == EventType.UPDATED || event.eventType == EventType.RANKING) {
                sb.appendLine()
                sb.appendLine("── 變動內容 ──")
                if (!event.contentDiff.isNullOrEmpty()) {
                    try {
                        val json = JSONObject(event.contentDiff)
                        val keys = json.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val change = json.getJSONObject(key)
                            val oldVal = change.opt("old")?.takeIf { it != JSONObject.NULL } ?: "(無)"
                            val newVal = change.opt("new")?.takeIf { it != JSONObject.NULL } ?: "(無)"
                            sb.appendLine("$key: $oldVal → $newVal")
                        }
                    } catch (_: Exception) {
                        sb.appendLine(event.contentDiff)
                    }
                } else {
                    sb.appendLine("(無變更)")
                }
            }

            // 關聯通知記錄的完整資料
            if (notification != null) {
                // Raw Data JSON
                if (!notification.rawDataJson.isNullOrEmpty()) {
                    sb.appendLine()
                    sb.appendLine("── Raw Data ──")
                    try {
                        val json = JSONObject(notification.rawDataJson)
                        sb.appendLine(json.toString(2))
                    } catch (_: Exception) {
                        sb.appendLine(notification.rawDataJson)
                    }
                }

                // Extras JSON
                if (!notification.extrasJson.isNullOrEmpty()) {
                    sb.appendLine()
                    sb.appendLine("── Extras JSON ──")
                    try {
                        val json = JSONObject(notification.extrasJson)
                        sb.appendLine(json.toString(2))
                    } catch (_: Exception) {
                        sb.appendLine(notification.extrasJson)
                    }
                }
            }

            // 用等寬字體的 TextView 顯示
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
                .show()
        }
    }

    private fun displayMediaAttachments(attachments: List<MediaAttachmentEntity>) {
        if (attachments.isEmpty()) {
            binding.cardMedia.visibility = View.GONE
            return
        }

        binding.cardMedia.visibility = View.VISIBLE
        val container = binding.layoutMediaContainer
        container.removeAllViews()

        val ctx = requireContext()
        val sizePx = (80 * resources.displayMetrics.density).toInt()
        val marginPx = (8 * resources.displayMetrics.density).toInt()

        for (attachment in attachments) {
            val isUnavailable = attachment.filePath.isEmpty() && !attachment.sourceUri.isNullOrEmpty()
            val fileExists = !isUnavailable && MediaExtractor.mediaFileExists(ctx, attachment.filePath)

            // 每張圖的容器：圖片 + 類型標籤
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

                // 不可用媒體：長按複製原始 URI
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
     * 根據通知的 Style 類型，從 extrasJson 解析並顯示額外的樣式資訊
     */
    private fun displayStyleInfo(notification: NotificationEntity) {
        val _binding = _binding ?: return
        val style = notification.template ?: return
        val container = _binding.layoutStyleInfoContainer
        container.removeAllViews()

        val extras = try {
            if (!notification.extrasJson.isNullOrEmpty()) JSONObject(notification.extrasJson) else null
        } catch (_: Exception) { null }

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

    private fun displayConditionalBlocks(notification: NotificationEntity) {
        val _binding = _binding ?: return

        // 進度條
        if (notification.progress > 0 || notification.progressIndeterminate) {
            _binding.cardProgress.visibility = View.VISIBLE
            _binding.textProgress.text = if (notification.progressIndeterminate) "不確定"
                else "${notification.progress} / ${notification.progressMax}"
        } else {
            _binding.cardProgress.visibility = View.GONE
        }

        // 計時器
        if (notification.showChronometer) {
            _binding.cardChronometer.visibility = View.VISIBLE
            _binding.textChronometer.text = if (notification.chronometerCountDown) "倒數計時" else "正計時"
        } else {
            _binding.cardChronometer.visibility = View.GONE
        }

        // 關聯聯絡人
        if (!notification.people.isNullOrEmpty()) {
            _binding.cardPeople.visibility = View.VISIBLE
            _binding.textPeople.text = try {
                val arr = JSONArray(notification.people)
                (0 until arr.length()).joinToString("\n") { arr.optString(it, "") }
            } catch (_: Exception) { notification.people }
        } else {
            _binding.cardPeople.visibility = View.GONE
        }

        // 訊息內容
        if (!notification.messages.isNullOrEmpty()) {
            _binding.cardMessages.visibility = View.VISIBLE
            _binding.textMessages.text = try {
                val arr = JSONArray(notification.messages)
                (0 until arr.length()).joinToString("\n") { i ->
                    val msg = arr.optJSONObject(i)
                    val sender = msg?.optString("sender", "") ?: ""
                    val text = msg?.optString("text", "") ?: ""
                    if (sender.isNotEmpty()) "$sender: $text" else text
                }
            } catch (_: Exception) { notification.messages }
        } else {
            _binding.cardMessages.visibility = View.GONE
        }

        // Bubble 詳情
        if (notification.hasBubbleMetadata) {
            _binding.cardBubble.visibility = View.VISIBLE
            val container = _binding.layoutBubbleContainer
            container.removeAllViews()
            addStyleInfoLabel(container, "desiredHeight")
            addStyleInfoText(container, "${notification.bubbleDesiredHeight} dp")
            if (notification.bubbleDesiredHeightResId != 0) {
                addStyleInfoLabel(container, "desiredHeightResId")
                addStyleInfoText(container, notification.bubbleDesiredHeightResId.toString())
            }
            addStyleInfoLabel(container, "autoExpand")
            addStyleInfoText(container, notification.bubbleAutoExpand.toString())
            addStyleInfoLabel(container, "suppressNotification")
            addStyleInfoText(container, notification.bubbleSuppressNotification.toString())
        } else {
            _binding.cardBubble.visibility = View.GONE
        }
    }

    private fun displayEffects(notification: NotificationEntity) {
        val _binding = _binding ?: return
        val hasEffects = notification.soundUri != null ||
                notification.vibratePattern != null ||
                notification.ledArgb != 0

        if (!hasEffects) {
            _binding.cardEffects.visibility = View.GONE
            return
        }

        _binding.cardEffects.visibility = View.VISIBLE
        val container = _binding.layoutEffectsContainer
        container.removeAllViews()

        if (notification.soundUri != null) {
            addStyleInfoLabel(container, "soundUri")
            addStyleInfoText(container, notification.soundUri)
        }
        if (notification.vibratePattern != null) {
            addStyleInfoLabel(container, "vibratePattern")
            addStyleInfoText(container, notification.vibratePattern)
        }
        if (notification.ledArgb != 0) {
            addStyleInfoLabel(container, "LED")
            addStyleInfoText(container, "色彩: ${String.format("#%06X", 0xFFFFFF and notification.ledArgb)} · 亮: ${notification.ledOnMs}ms · 暗: ${notification.ledOffMs}ms")
        }
    }

    private fun displayRanking(notification: NotificationEntity) {
        val _binding = _binding ?: return
        val hasRanking = notification.suppressedVisualEffects != 0 ||
                notification.lastAudiblyAlertedMillis > 0

        if (!hasRanking) {
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

        if (notification.suppressedVisualEffects != 0) {
            addStyleInfoLabel(container, "suppressedVisualEffects")
            addStyleInfoText(container, String.format("0x%X", notification.suppressedVisualEffects))
        }
        if (notification.lastAudiblyAlertedMillis > 0) {
            addStyleInfoLabel(container, "lastAudiblyAlertedMillis")
            addStyleInfoText(container, formatTime(notification.lastAudiblyAlertedMillis))
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
    private fun displayIntents(actions: List<ActionEntity>, notification: NotificationEntity) {
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

            val intentMeta = try {
                notification.rawDataJson?.let { raw ->
                    JSONObject(raw).optJSONObject("notification")?.optJSONObject("intents")
                }
            } catch (_: Exception) { null }

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
     * 從 rawDataJson 中的 intent 元資料組裝描述文字
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
    private fun displayRemoteViewsInfo(notification: NotificationEntity) {
        val _binding = _binding ?: return
        if (!notification.hasCustomContentView && !notification.hasCustomBigContentView && !notification.hasCustomHeadsUpContentView) {
            _binding.layoutRemoteViews.visibility = View.GONE
            return
        }

        _binding.layoutRemoteViews.visibility = View.VISIBLE
        val container = _binding.layoutRemoteViewsContainer
        container.removeAllViews()

        // 從 rawDataJson 解析 remoteViews 元資料
        val remoteViewsMeta = try {
            notification.rawDataJson?.let { raw ->
                JSONObject(raw).optJSONObject("notification")?.optJSONObject("remoteViews")
            }
        } catch (_: Exception) { null }

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
     * 從 rawDataJson 中的 remoteViews 元資料組裝描述文字
     * 優先顯示 layoutName，否則 layoutId (package)
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
                    pendingIntent.send()
                    Toast.makeText(ctx, R.string.intent_triggered, Toast.LENGTH_SHORT).show()
                } catch (_: PendingIntent.CanceledException) {
                    Toast.makeText(ctx, R.string.intent_send_failed, Toast.LENGTH_SHORT).show()
                    // 圓點變灰
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
