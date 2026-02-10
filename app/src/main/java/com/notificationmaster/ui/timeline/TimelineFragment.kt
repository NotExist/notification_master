package com.notificationmaster.ui.timeline

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.R
import com.notificationmaster.data.db.entity.NotificationEntity
import com.notificationmaster.databinding.FragmentTimelineBinding
import com.notificationmaster.service.NotificationCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 時間軸 Fragment
 * 顯示所有通知的時間軸視圖，支援去重顯示切換
 */
class TimelineFragment : Fragment() {

    private var _binding: FragmentTimelineBinding? = null
    private val binding get() = _binding!!

    private var adapter: TimelineAdapter? = null
    private var isDeduplicatedMode = true
    private var layoutManagerState: Parcelable? = null
    private var currentFilterText = ""
    private var allNotifications: List<NotificationEntity> = emptyList()
    private var loadJob: Job? = null
    // 快取 App 名稱：packageName → appLabel
    private val appLabelCache = mutableMapOf<String, String>()
    private val dateFormat = SimpleDateFormat("yyyy年M月d日 EEEE", Locale.getDefault())
    private var bubbleHideRunnable: Runnable? = null
    private var wasPermissionGranted = false

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
        setupPermissionButton()
        wasPermissionGranted = isNotificationListenerEnabled()
        updateEmptyStateForPermission()
        loadNotifications()
    }

    override fun onResume() {
        super.onResume()
        val isGranted = isNotificationListenerEnabled()
        if (isGranted && !wasPermissionGranted) {
            // 權限剛授予，要求系統重新綁定服務以觸發 onListenerConnected 初次收集
            wasPermissionGranted = true
            updateEmptyStateForPermission()
            requestServiceRebind()
            loadNotifications()
        } else if (!isGranted && wasPermissionGranted) {
            wasPermissionGranted = false
            updateEmptyStateForPermission()
        }
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

        // 捲動時更新時間索引氣泡
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                updateTimeBubble()
            }

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING -> showTimeBubble()
                    RecyclerView.SCROLL_STATE_IDLE -> scheduleHideTimeBubble()
                }
            }
        })
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
                if (_binding == null) return@collectLatest
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
        val binding = _binding ?: return
        val filtered = filterNotifications(allNotifications, currentFilterText)
        if (filtered.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
            binding.textCount.text = getString(R.string.timeline_count_format, 0)
            updateEmptyStateForPermission()
        } else {
            binding.emptyState.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
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
                _binding?.textCount?.text = getString(R.string.timeline_count_format, filtered.size)
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
                val ctx = context ?: return@getOrPut packageName
                val pm = ctx.packageManager
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

    private fun updateTimeBubble() {
        val binding = _binding ?: return
        val rv = binding.recyclerView
        val layoutManager = rv.layoutManager as? LinearLayoutManager ?: return
        val position = layoutManager.findFirstVisibleItemPosition()
        if (position == RecyclerView.NO_POSITION) return

        // 更新氣泡文字
        val item = adapter?.currentList?.getOrNull(position) ?: return
        val dateText = when (item) {
            is TimelineItem.DateHeader -> dateFormat.format(Date(item.date))
            is TimelineItem.NotificationItem -> dateFormat.format(Date(item.notification.postTime))
        }
        binding.timeBubble.text = dateText

        // 計算捲動比例，讓氣泡跟隨 fast scroll thumb 垂直位置
        val scrollRange = rv.computeVerticalScrollRange()
        val scrollExtent = rv.computeVerticalScrollExtent()
        val scrollOffset = rv.computeVerticalScrollOffset()
        val maxScroll = scrollRange - scrollExtent
        if (maxScroll <= 0) return

        val fraction = scrollOffset.toFloat() / maxScroll
        val swipeRefresh = binding.swipeRefresh
        val bubbleHeight = binding.timeBubble.height.toFloat()
        val trackRange = (swipeRefresh.bottom - swipeRefresh.top).toFloat() - bubbleHeight

        binding.timeBubble.translationY = swipeRefresh.top + trackRange * fraction
    }

    private fun showTimeBubble() {
        val binding = _binding ?: return
        bubbleHideRunnable?.let { binding.timeBubble.removeCallbacks(it) }
        binding.timeBubble.visibility = View.VISIBLE
    }

    private fun scheduleHideTimeBubble() {
        val binding = _binding ?: return
        bubbleHideRunnable?.let { binding.timeBubble.removeCallbacks(it) }
        val runnable = Runnable { _binding?.timeBubble?.visibility = View.GONE }
        bubbleHideRunnable = runnable
        binding.timeBubble.postDelayed(runnable, 1500L)
    }

    /**
     * 要求系統重新綁定 NotificationListenerService
     * 解決授權後系統未立即綁定服務、不觸發 onListenerConnected 的問題
     *
     * 策略：先切換元件啟用狀態（全版本通用、最可靠），
     * 再呼叫 requestRebind（API 24+ 官方 API，作為補充）。
     * 雙重觸發確保各家 OEM ROM 都能正確重新綁定。
     */
    private fun requestServiceRebind() {
        val ctx = context ?: return
        val componentName = ComponentName(ctx, NotificationCaptureService::class.java)

        // 方法 1：切換元件啟用狀態，強制 PackageManager 通知系統重新評估綁定
        try {
            ctx.packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            ctx.packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.i("TimelineFragment", "Toggled component to force rebind")
        } catch (e: Exception) {
            Log.w("TimelineFragment", "Component toggle rebind failed", e)
        }

        // 方法 2（API 24+）：官方 requestRebind 作為補充
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                NotificationListenerService.requestRebind(componentName)
                Log.i("TimelineFragment", "Requested service rebind (API 24+)")
            } catch (e: Exception) {
                Log.w("TimelineFragment", "requestRebind failed", e)
            }
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val ctx = context ?: return false
        val componentName = ComponentName(ctx, NotificationCaptureService::class.java)
        val flat = Settings.Secure.getString(
            ctx.contentResolver,
            "enabled_notification_listeners"
        )
        return flat?.contains(componentName.flattenToString()) == true
    }

    private fun setupPermissionButton() {
        binding.btnGrantPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    /**
     * 根據權限狀態更新空白提示的文字和按鈕
     */
    private fun updateEmptyStateForPermission() {
        val binding = _binding ?: return
        if (!wasPermissionGranted) {
            binding.textEmptyHint.setText(R.string.timeline_permission_hint)
            binding.btnGrantPermission.visibility = View.VISIBLE
        } else {
            binding.textEmptyHint.setText(R.string.timeline_empty_hint)
            binding.btnGrantPermission.visibility = View.GONE
        }
    }

    private fun navigateToDetail(notification: NotificationEntity) {
        // 導航到通知詳情頁面
        val action = TimelineFragmentDirections.actionTimelineToDetail(notification.id)
        findNavController().navigate(action)
    }
}
