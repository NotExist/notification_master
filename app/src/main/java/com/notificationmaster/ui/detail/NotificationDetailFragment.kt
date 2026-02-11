package com.notificationmaster.ui.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
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
import com.notificationmaster.core.media.MediaExtractor
import com.notificationmaster.core.compat.ApiVersionHelper
import com.notificationmaster.data.db.entity.EventType
import com.notificationmaster.data.db.entity.MediaAttachmentEntity
import com.notificationmaster.data.db.entity.NotificationEntity
import com.notificationmaster.data.db.entity.NotificationEventEntity
import com.notificationmaster.databinding.FragmentNotificationDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private val dateTimeFormat = SimpleDateFormat("yyyy年M月d日 HH:mm:ss", Locale.getDefault())
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

        setupRecyclerView()
        loadNotificationDetail()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupRecyclerView() {
        eventAdapter = NotificationEventAdapter { event ->
            showEventDetail(event)
        }
        binding.recyclerEvents.apply {
            adapter = eventAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun loadNotificationDetail() {
        val database = NotificationMasterApp.getInstance().database
        val notificationDao = database.notificationDao()
        val eventDao = database.notificationEventDao()
        val mediaDao = database.mediaAttachmentDao()

        viewLifecycleOwner.lifecycleScope.launch {
            val notification = withContext(Dispatchers.IO) {
                notificationDao.getById(args.notificationId)
            }

            if (notification != null) {
                displayNotification(notification)

                // 載入事件歷程
                val events = withContext(Dispatchers.IO) {
                    eventDao.getEventsByNotificationKey(notification.notificationKey)
                }
                eventAdapter.submitList(events)

                // 載入媒體附件
                val attachments = withContext(Dispatchers.IO) {
                    mediaDao.getAttachmentsByNotificationIdSync(args.notificationId)
                }
                displayMediaAttachments(attachments)
            }
        }
    }

    private fun displayNotification(notification: NotificationEntity) {
        val context = requireContext()

        // App 資訊
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(notification.packageName, 0)
            binding.textAppName.text = pm.getApplicationLabel(appInfo)
            binding.imgAppIcon.setImageDrawable(pm.getApplicationIcon(appInfo))
        } catch (e: Exception) {
            binding.textAppName.text = context.getString(R.string.unknown_app)
            binding.imgAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
        }

        binding.textPackageName.text = notification.packageName

        // 基本內容
        binding.textTitle.text = notification.title ?: context.getString(R.string.no_title)
        binding.textContent.text = notification.bigText ?: notification.text ?: context.getString(R.string.no_content)
        binding.textTime.text = dateTimeFormat.format(Date(notification.postTime))

        // 標籤
        binding.chipGroupFlags.removeAllViews()

        // 系統通知抽屜分類標籤
        if (notification.isConversation ||
            (notification.isMessagingStyle && !notification.shortcutId.isNullOrEmpty())) {
            addChip("Conversation", R.color.tag_conversation, R.string.tag_conversation_desc)
        }
        if (notification.importance in 1..2) {
            addChip("Silent", R.color.tag_silent, R.string.tag_silent_desc)
        }
        if (notification.isMessagingStyle) {
            addChip("MessagingStyle", R.color.tag_messaging_style, R.string.tag_messaging_style_desc)
        }

        // 通知屬性標籤
        if (notification.isOngoing) {
            addChip("Ongoing", R.color.event_initial, R.string.tag_ongoing_desc)
        }
        if (notification.isForegroundService) {
            addChip("Foreground Service", R.color.event_ranking, R.string.tag_fg_service_desc)
        }
        if (notification.likelyHeadsup) {
            addChip("Heads-up", R.color.status_warning, R.string.tag_headsup_desc)
        }
        if (notification.isAutoCancel) {
            addChip("AutoCancel", R.color.event_updated, R.string.tag_auto_cancel_desc)
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

        // Channel（API 26+）
        if (!notification.channelId.isNullOrEmpty()) {
            binding.layoutChannel.visibility = View.VISIBLE
            binding.textChannel.text = notification.channelId
        } else {
            binding.layoutChannel.visibility = View.GONE
        }

        // Group
        if (!notification.groupKey.isNullOrEmpty()) {
            binding.layoutGroup.visibility = View.VISIBLE
            binding.textGroup.text = notification.groupKey
        } else {
            binding.layoutGroup.visibility = View.GONE
        }

        // Key 和 Hash
        binding.textKey.text = notification.notificationKey
        binding.textHash.text = getString(R.string.format_hash_truncated, notification.contentHash.take(16))
    }

    private fun showEventDetail(event: NotificationEventEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            val notification = withContext(Dispatchers.IO) {
                NotificationMasterApp.getInstance().database
                    .notificationDao().getById(event.notificationId)
            }
            val binding = _binding ?: return@launch

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

            // RANKING 事件：排序資訊
            if (event.rankingRank != null || event.rankingImportance != null) {
                sb.appendLine()
                sb.appendLine("── Ranking 資訊 ──")
                event.rankingRank?.let { sb.appendLine("rank: $it") }
                event.rankingImportance?.let { sb.appendLine("importance: $it") }
                event.isAmbient?.let { sb.appendLine("is_ambient: $it") }
                event.isSuspended?.let { sb.appendLine("is_suspended: $it") }
                event.suppressedVisualEffects?.let { sb.appendLine("suppressed_visual_effects: $it") }
            }

            // 內容快照
            if (!event.contentSnapshot.isNullOrEmpty()) {
                sb.appendLine()
                sb.appendLine("── 內容快照 ──")
                try {
                    val json = JSONObject(event.contentSnapshot)
                    sb.appendLine(json.toString(2))
                } catch (_: Exception) {
                    sb.appendLine(event.contentSnapshot)
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

    private fun addChip(text: String, colorRes: Int, descriptionRes: Int) {
        val ctx = requireContext()
        val chip = Chip(ctx).apply {
            this.text = text
            isClickable = true
            chipBackgroundColor = ContextCompat.getColorStateList(ctx, colorRes)
            setTextColor(ContextCompat.getColor(ctx, R.color.white))
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
