package com.notificationmaster.ui.detail

import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
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
import com.notificationmaster.core.cache.PendingIntentCache
import com.notificationmaster.core.media.MediaExtractor
import com.notificationmaster.core.compat.ApiVersionHelper
import com.notificationmaster.data.db.entity.ActionEntity
import com.notificationmaster.data.db.entity.EventType
import com.notificationmaster.data.db.entity.MediaAttachmentEntity
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

                // 顯示樣式資訊
                displayStyleInfo(notification)

                // 載入動作按鈕
                val actionDao = database.actionDao()
                val actions = withContext(Dispatchers.IO) {
                    actionDao.getActionsByNotificationIdSync(args.notificationId)
                }
                displayActions(actions, notification.notificationKey)

                // 顯示 Intent 資訊
                displayIntentInfo(notification)

                // 顯示自訂 View 資訊
                displayRemoteViewsInfo(notification)
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
            // MessagingStyle 和 DecoratedCustomViewStyle 已被其他標籤涵蓋
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

            // 變動內容
            if (!event.contentDiff.isNullOrEmpty()) {
                sb.appendLine()
                sb.appendLine("── 變動內容 ──")
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
                // 解析 android.textLines 陣列
                val textLines = extras?.optJSONArray("android.textLines")
                if (textLines != null && textLines.length() > 0) {
                    hasContent = true
                    addStyleInfoLabel(container, "textLines")
                    for (i in 0 until textLines.length()) {
                        addStyleInfoText(container, textLines.optString(i, ""))
                    }
                }
            }

            style.endsWith("MediaStyle") || style.endsWith("DecoratedMediaCustomViewStyle") -> {
                // compactActions 索引陣列
                val compactActions = extras?.optJSONArray("android.compactActions")
                if (compactActions != null) {
                    hasContent = true
                    val indices = (0 until compactActions.length())
                        .map { compactActions.optInt(it, -1) }
                        .filter { it >= 0 }
                    addStyleInfoLabel(container, "compactActions")
                    addStyleInfoText(container, indices.joinToString(", "))
                }
                // mediaSession 存在與否
                val hasMediaSession = extras?.has("android.mediaSession") == true
                if (hasMediaSession) {
                    hasContent = true
                    addStyleInfoLabel(container, "mediaSession")
                    addStyleInfoText(container, "present")
                }
            }

            style.endsWith("CallStyle") -> {
                // callType
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
                // callPerson
                val callPerson = extras?.optString("android.callPerson", "")
                if (!callPerson.isNullOrEmpty()) {
                    hasContent = true
                    addStyleInfoLabel(container, "callPerson")
                    addStyleInfoText(container, callPerson)
                }
            }

            style.endsWith("MessagingStyle") -> {
                // conversationTitle 和 isGroupConversation（補充顯示）
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
            }

            style.endsWith("BigTextStyle") -> {
                // summaryText（若主內容區未顯示）
                val summaryText = extras?.optString("android.summaryText", "")
                if (!summaryText.isNullOrEmpty()) {
                    hasContent = true
                    addStyleInfoLabel(container, "summaryText")
                    addStyleInfoText(container, summaryText)
                }
            }
        }

        _binding.cardStyleInfo.visibility = if (hasContent) View.VISIBLE else View.GONE
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

    private fun displayActions(actions: List<ActionEntity>, notificationKey: String) {
        val _binding = _binding ?: return
        if (actions.isEmpty()) {
            _binding.cardActions.visibility = View.GONE
            return
        }

        _binding.cardActions.visibility = View.VISIBLE
        val container = _binding.layoutActionsContainer
        container.removeAllViews()

        val intentSet = PendingIntentCache.get(notificationKey)

        for ((index, action) in actions.withIndex()) {
            // 動作間分隔線
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

            // 動作標題行（含狀態圓點）
            val titleText = action.title ?: "(無標題)"
            val actionPi = intentSet?.actionIntents?.get(action.actionIndex)
            addIntentRow(container, "[${action.actionIndex}] $titleText", actionPi)

            // 語意動作（Semantic Action, API 28+）
            val semanticName = semanticActionName(action.semanticAction)
            if (semanticName != null) {
                addStyleInfoText(container, "語意動作: $semanticName")
            }

            // 回覆資訊
            if (action.isReplyAction) {
                val replyInfo = buildString {
                    append("直接回覆")
                    if (!action.replyLabel.isNullOrEmpty()) {
                        append(" · ${action.replyLabel}")
                    }
                }
                addStyleInfoText(container, replyInfo)

                // 預設回覆選項
                if (!action.replyChoices.isNullOrEmpty()) {
                    try {
                        val choices = JSONArray(action.replyChoices)
                        val choiceList = (0 until choices.length()).map { choices.getString(it) }
                        addStyleInfoText(container, "預設選項: ${choiceList.joinToString(", ")}")
                    } catch (_: Exception) { }
                }
            }

            // 標記
            val flags = mutableListOf<String>()
            if (action.isContextual) flags.add("Contextual")
            if (action.isAuthenticationRequired) flags.add("需認證")
            if (!action.allowsFreeFormInput && action.isReplyAction) flags.add("不允許自由輸入")
            if (flags.isNotEmpty()) {
                addStyleInfoText(container, flags.joinToString(" · "))
            }
        }
    }

    /**
     * 顯示 Intent 資訊區塊（contentIntent / deleteIntent / fullScreenIntent）
     */
    private fun displayIntentInfo(notification: NotificationEntity) {
        val _binding = _binding ?: return
        if (!notification.hasContentIntent && !notification.hasDeleteIntent && !notification.hasFullScreenIntent) {
            _binding.layoutIntents.visibility = View.GONE
            return
        }

        _binding.layoutIntents.visibility = View.VISIBLE
        val container = _binding.layoutIntentsContainer
        container.removeAllViews()

        val intentSet = PendingIntentCache.get(notification.notificationKey)

        // 從 rawDataJson 解析 intent 元資料
        val intentMeta = try {
            notification.rawDataJson?.let { raw ->
                JSONObject(raw).optJSONObject("notification")?.optJSONObject("intents")
            }
        } catch (_: Exception) { null }

        // 依序顯示各 intent
        if (notification.hasContentIntent) {
            val desc = buildIntentDescription("contentIntent", intentMeta?.optJSONObject("contentIntent"))
            addIntentRow(container, desc, intentSet?.contentIntent)
        }
        if (notification.hasDeleteIntent) {
            val desc = buildIntentDescription("deleteIntent", intentMeta?.optJSONObject("deleteIntent"))
            addIntentRow(container, desc, intentSet?.deleteIntent)
        }
        if (notification.hasFullScreenIntent) {
            val desc = buildIntentDescription("fullScreenIntent", intentMeta?.optJSONObject("fullScreenIntent"))
            addIntentRow(container, desc, intentSet?.fullScreenIntent)
        }
    }

    /**
     * 從 rawDataJson 中的 intent 元資料組裝描述文字
     */
    private fun buildIntentDescription(name: String, meta: JSONObject?): String {
        if (meta == null) return name

        // API 34+ type flags
        val types = mutableListOf<String>()
        if (meta.optBoolean("isActivity", false)) types.add("Activity")
        if (meta.optBoolean("isBroadcast", false)) types.add("Broadcast")
        if (meta.optBoolean("isService", false)) types.add("Service")
        if (meta.optBoolean("isForegroundService", false)) types.add("FgService")

        return if (types.isNotEmpty()) "$name (${types.joinToString("/")})" else name
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
