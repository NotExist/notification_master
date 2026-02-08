package com.notificationmaster.ui.timeline

import android.os.Bundle
import android.os.Parcelable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.R
import com.notificationmaster.data.db.entity.NotificationEntity
import com.notificationmaster.databinding.FragmentTimelineBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    private var adapter: TimelineAdapter? = null
    private var isDeduplicatedMode = false
    private var layoutManagerState: Parcelable? = null
    private var currentFilterText = ""
    private var allNotifications: List<NotificationEntity> = emptyList()
    private var loadJob: Job? = null
    // 快取 App 名稱：packageName → appLabel
    private val appLabelCache = mutableMapOf<String, String>()

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
        setupFilterInput()
        setupFilterChips()
        setupSwipeRefresh()
        loadNotifications()
    }

    override fun onDestroyView() {
        layoutManagerState = binding.recyclerView.layoutManager?.onSaveInstanceState()
        super.onDestroyView()
        _binding = null
    }

    private fun setupRecyclerView() {
        if (adapter == null) {
            adapter = TimelineAdapter { notification ->
                navigateToDetail(notification)
            }
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
    }

    private fun setupFilterInput() {
        binding.editFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentFilterText = s?.toString()?.trim() ?: ""
                applyFilterAndDisplay()
            }
        })

        // 搜尋圖示點擊 → 帶查詢文字導航到搜尋頁面
        binding.layoutFilter.setEndIconOnClickListener {
            val query = binding.editFilter.text?.toString()?.trim() ?: ""
            if (query.isNotEmpty()) {
                findNavController().navigate(
                    R.id.nav_search,
                    bundleOf("query" to query)
                )
            }
        }
    }

    private fun setupFilterChips() {
        binding.chipShowAll.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                isDeduplicatedMode = false
                binding.swipeRefresh.isRefreshing = true
                loadNotifications()
            }
        }

        binding.chipDeduplicated.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                isDeduplicatedMode = true
                binding.swipeRefresh.isRefreshing = true
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
        // 取消先前的 Flow 收集，避免同時存在多個 collector
        loadJob?.cancel()

        val database = NotificationMasterApp.getInstance().database
        val notificationDao = database.notificationDao()

        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val flow = if (isDeduplicatedMode) {
                notificationDao.getDeduplicatedNotifications(startTime, endTime)
            } else {
                notificationDao.getNotificationsByTimeRange(startTime, endTime)
            }

            flow.collectLatest { notifications ->
                _binding?.swipeRefresh?.isRefreshing = false
                allNotifications = notifications
                applyFilterAndDisplay()
            }
        }
    }

    /**
     * 根據目前過濾文字和去重模式顯示結果
     */
    private fun applyFilterAndDisplay() {
        val filtered = filterNotifications(allNotifications, currentFilterText)
        if (filtered.isEmpty()) {
            showEmptyState()
        } else {
            hideEmptyState()
            val database = NotificationMasterApp.getInstance().database
            val notificationDao = database.notificationDao()

            viewLifecycleOwner.lifecycleScope.launch {
                val timelineItems = if (isDeduplicatedMode) {
                    buildTimelineItemsWithSimilarCount(filtered, notificationDao)
                } else {
                    buildTimelineItems(filtered)
                }
                adapter?.submitList(timelineItems) {
                    layoutManagerState?.let { state ->
                        _binding?.recyclerView?.layoutManager?.onRestoreInstanceState(state)
                        layoutManagerState = null
                    }
                }
                updateCount(filtered.size)
            }
        }
    }

    /**
     * 根據關鍵字過濾通知（標題、內容、bigText、packageName、App 名稱）
     */
    private fun filterNotifications(
        notifications: List<NotificationEntity>,
        query: String
    ): List<NotificationEntity> {
        if (query.isEmpty()) return notifications
        val lowerQuery = query.lowercase()
        return notifications.filter { n ->
            n.title?.lowercase()?.contains(lowerQuery) == true ||
            n.text?.lowercase()?.contains(lowerQuery) == true ||
            n.bigText?.lowercase()?.contains(lowerQuery) == true ||
            n.packageName.lowercase().contains(lowerQuery) ||
            getAppLabel(n.packageName).lowercase().contains(lowerQuery)
        }
    }

    /**
     * 取得 App 顯示名稱（帶快取）
     */
    private fun getAppLabel(packageName: String): String {
        return appLabelCache.getOrPut(packageName) {
            try {
                val pm = requireContext().packageManager
                val appInfo = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                packageName
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
