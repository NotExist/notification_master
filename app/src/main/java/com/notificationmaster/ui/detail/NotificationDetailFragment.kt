package com.notificationmaster.ui.detail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.R
import com.notificationmaster.data.db.entity.NotificationEntity
import com.notificationmaster.data.db.entity.NotificationEventEntity
import com.notificationmaster.databinding.FragmentNotificationDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        eventAdapter = NotificationEventAdapter()
        binding.recyclerEvents.apply {
            adapter = eventAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun loadNotificationDetail() {
        val database = NotificationMasterApp.getInstance().database
        val notificationDao = database.notificationDao()
        val eventDao = database.notificationEventDao()

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
        binding.textHash.text = notification.contentHash.take(16) + "..."
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
