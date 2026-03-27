package com.notificationmaster.ui.archive

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.data.db.entity.NotificationEntity
import com.notificationmaster.databinding.FragmentArchiveDetailBinding
import com.notificationmaster.ui.filter.FilterRuleDialogHelper
import com.notificationmaster.ui.timeline.TimelineAdapter
import com.notificationmaster.ui.timeline.TimelineItem
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 歸檔詳情 Fragment
 * 顯示特定 App 或 Channel 的通知列表
 */
class ArchiveDetailFragment : Fragment() {

    private var _binding: FragmentArchiveDetailBinding? = null
    private val binding get() = _binding!!

    private val args: ArchiveDetailFragmentArgs by navArgs()
    private lateinit var adapter: TimelineAdapter

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
        loadNotifications()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupRecyclerView() {
        adapter = TimelineAdapter(
            onItemClick = { notification ->
                val action = ArchiveDetailFragmentDirections.actionArchiveDetailToDetail(notification.id)
                findNavController().navigate(action)
            },
            onItemLongClick = { notification ->
                FilterRuleDialogHelper.showAddRuleDialog(
                    context = requireContext(),
                    actionType = null,
                    prefillPackageName = notification.packageName,
                    prefillChannelId = notification.channelId
                )
            }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
    }

    private fun loadNotifications() {
        val database = NotificationMasterApp.getInstance().database
        val notificationDao = database.notificationDao()

        val flow = if (args.channelId.isNotEmpty()) {
            notificationDao.getLatestNotificationsByChannel(args.packageName, args.channelId)
        } else {
            notificationDao.getLatestNotificationsByPackage(args.packageName)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            flow.collectLatest { notifications ->
                if (notifications.isEmpty()) {
                    binding.emptyState.visibility = View.VISIBLE
                    binding.recyclerView.visibility = View.GONE
                } else {
                    binding.emptyState.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                    adapter.submitList(buildTimelineItems(notifications))
                }
            }
        }
    }

    private fun buildTimelineItems(notifications: List<NotificationEntity>): List<TimelineItem> {
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
