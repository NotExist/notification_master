package com.notificationmaster.ui.archive

import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.databinding.FragmentArchiveDetailBinding
import com.notificationmaster.ui.common.NotificationDisplay
import com.notificationmaster.core.debug.ProfileLogger
import com.notificationmaster.ui.common.NotificationEnricher
import com.notificationmaster.ui.filter.CalendarPickerLauncher
import com.notificationmaster.ui.filter.FilterRuleDialogHelper
import com.notificationmaster.ui.filter.SoundPickerLauncher
import com.notificationmaster.ui.timeline.TimelineAdapter
import com.notificationmaster.ui.timeline.TimelineItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 歸檔詳情 Fragment
 * 顯示特定 App 或 Channel 的通知列表
 *
 * Plan 2 Phase 9：資料源切到 notification_events（每個 notification_key 取最新 event 一筆），
 * Adapter 共用 [TimelineAdapter] 吃 [NotificationDisplay]。
 */
class ArchiveDetailFragment : Fragment() {

    private var _binding: FragmentArchiveDetailBinding? = null
    private val binding get() = _binding!!

    private val soundPicker = SoundPickerLauncher(this)
    private val calendarPicker = CalendarPickerLauncher(this)

    private val args: ArchiveDetailFragmentArgs by navArgs()
    private val viewModel: ArchiveDetailViewModel by viewModels()
    private lateinit var adapter: TimelineAdapter

    /** view 剛建立時要還原一次 scroll 位置；submitList 完成後消費掉 */
    private var pendingScrollRestore: Parcelable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentArchiveDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        pendingScrollRestore = viewModel.scrollState
        loadNotifications()
    }

    override fun onPause() {
        super.onPause()
        binding.recyclerView.layoutManager?.onSaveInstanceState()?.let {
            viewModel.scrollState = it
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupRecyclerView() {
        adapter = TimelineAdapter(
            onItemClick = { display ->
                val action = ArchiveDetailFragmentDirections
                    .actionArchiveSubDetailToArchiveNotificationDetail(
                        notificationKey = display.notificationKey,
                        anchorEventId = display.eventId
                    )
                findNavController().navigate(action)
            },
            onItemLongClick = { display ->
                FilterRuleDialogHelper.showAddRuleDialog(
                    context = requireContext(),
                    actionType = null,
                    prefillPackageName = display.packageName,
                    prefillChannelId = display.channelId,
                    soundPicker = soundPicker,
                    calendarPicker = calendarPicker
                )
            }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
    }

    private fun loadNotifications() {
        val database = NotificationMasterApp.getInstance().database
        val eventDao = database.notificationEventDao()

        val mode = if (args.channelId.isNotEmpty()) "channel" else "package"
        val target = if (args.channelId.isNotEmpty()) "${args.packageName}/${args.channelId}" else args.packageName
        val tStart = System.currentTimeMillis()
        ProfileLogger.append("Archive", "load start mode=$mode target=$target")

        val flow = if (args.channelId.isNotEmpty()) {
            eventDao.getLatestEventsByChannel(args.packageName, args.channelId)
        } else {
            eventDao.getLatestEventsByPackage(args.packageName)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            flow.collectLatest { events ->
                ProfileLogger.append(
                    "Archive",
                    "query emit size=${events.size} since-start=${System.currentTimeMillis() - tStart}ms"
                )
                val binding = _binding ?: return@collectLatest
                if (events.isEmpty()) {
                    binding.emptyState.visibility = View.VISIBLE
                    binding.recyclerView.visibility = View.GONE
                } else {
                    binding.emptyState.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                    val tEnrich = System.currentTimeMillis()
                    val displays = withContext(Dispatchers.IO) {
                        val database = NotificationMasterApp.getInstance().database
                        NotificationEnricher.enrich(
                            events,
                            database.channelDao(),
                            database.rankingObservationDao(),
                            database.rankingSnapshotDao(),
                            database.notificationEventDao()
                        )
                    }
                    val tSubmit = System.currentTimeMillis()
                    adapter.submitList(buildTimelineItems(displays)) {
                        ProfileLogger.append(
                            "Archive",
                            "submitList done size=${displays.size} " +
                                "enrich=${tSubmit - tEnrich}ms commit=${System.currentTimeMillis() - tSubmit}ms"
                        )
                        pendingScrollRestore?.let {
                            binding.recyclerView.layoutManager?.onRestoreInstanceState(it)
                            pendingScrollRestore = null
                        }
                    }
                }
            }
        }
    }

    private fun buildTimelineItems(notifications: List<NotificationDisplay>): List<TimelineItem> {
        val items = mutableListOf<TimelineItem>()
        var lastDate: Long? = null

        for (notification in notifications) {
            val notificationDate = getStartOfDay(notification.postTime)

            if (lastDate != notificationDate) {
                items.add(TimelineItem.DateHeader(notificationDate))
                lastDate = notificationDate
            }

            items.add(TimelineItem.NotificationItem(notification))
        }

        return items
    }

    private fun getStartOfDay(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

}
