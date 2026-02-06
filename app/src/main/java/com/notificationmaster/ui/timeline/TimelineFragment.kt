package com.notificationmaster.ui.timeline

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.R
import com.notificationmaster.data.db.entity.NotificationEntity
import com.notificationmaster.databinding.FragmentTimelineBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 時間軸 Fragment
 * 顯示所有通知的時間軸視圖，支援去重顯示切換
 */
class TimelineFragment : Fragment() {

    private var _binding: FragmentTimelineBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: TimelineAdapter
    private var isDeduplicatedMode = false

    // 查詢時間範圍（預設過去 7 天）
    private val endTime: Long
        get() = System.currentTimeMillis()
    private val startTime: Long
        get() {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -7)
            return cal.timeInMillis
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTimelineBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupFilterChips()
        setupSwipeRefresh()
        loadNotifications()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupRecyclerView() {
        adapter = TimelineAdapter { notification ->
            navigateToDetail(notification)
        }
        binding.recyclerView.adapter = adapter
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun setupFilterChips() {
        binding.chipShowAll.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                isDeduplicatedMode = false
                loadNotifications()
            }
        }

        binding.chipDeduplicated.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                isDeduplicatedMode = true
                loadNotifications()
            }
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            loadNotifications()
        }
    }

    private fun loadNotifications() {
        val database = NotificationMasterApp.getInstance().database
        val notificationDao = database.notificationDao()

        viewLifecycleOwner.lifecycleScope.launch {
            val flow = if (isDeduplicatedMode) {
                notificationDao.getDeduplicatedNotifications(startTime, endTime)
            } else {
                notificationDao.getNotificationsByTimeRange(startTime, endTime)
            }

            flow.collectLatest { notifications ->
                binding.swipeRefresh.isRefreshing = false

                if (notifications.isEmpty()) {
                    showEmptyState()
                } else {
                    hideEmptyState()
                    val timelineItems = if (isDeduplicatedMode) {
                        buildTimelineItemsWithSimilarCount(notifications, notificationDao)
                    } else {
                        buildTimelineItems(notifications)
                    }
                    adapter.submitList(timelineItems)
                    updateCount(notifications.size)
                }
            }
        }
    }

    /**
     * 將通知列表轉換為時間軸項目（包含日期標題）
     */
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

    /**
     * 建立時間軸項目並計算相似通知數量（去重模式）
     */
    private suspend fun buildTimelineItemsWithSimilarCount(
        notifications: List<NotificationEntity>,
        dao: com.notificationmaster.data.db.dao.NotificationDao
    ): List<TimelineItem> {
        val items = mutableListOf<TimelineItem>()
        var lastDate: Long? = null

        for (notification in notifications) {
            val notificationDate = getStartOfDay(notification.postTime)

            if (lastDate != notificationDate) {
                items.add(TimelineItem.DateHeader(notificationDate))
                lastDate = notificationDate
            }

            // 查詢相同 hash 的通知數量
            val similarCount = withContext(Dispatchers.IO) {
                dao.getCountByHash(notification.contentHash, startTime, endTime)
            }

            items.add(TimelineItem.NotificationItem(notification, similarCount))
        }

        return items
    }

    /**
     * 取得某時間戳所在日期的開始時間（0時0分0秒）
     */
    private fun getStartOfDay(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun showEmptyState() {
        binding.emptyState.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE
        binding.textCount.text = getString(R.string.timeline_count_format, 0)
    }

    private fun hideEmptyState() {
        binding.emptyState.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE
    }

    private fun updateCount(count: Int) {
        binding.textCount.text = getString(R.string.timeline_count_format, count)
    }

    private fun navigateToDetail(notification: NotificationEntity) {
        // 導航到通知詳情頁面
        val action = TimelineFragmentDirections.actionTimelineToDetail(notification.id)
        findNavController().navigate(action)
    }
}
