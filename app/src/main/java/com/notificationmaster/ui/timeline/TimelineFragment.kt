package com.notificationmaster.ui.timeline

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.core.cache.AppLabelCache
import com.notificationmaster.R
import com.notificationmaster.data.db.entity.NotificationEntity
import com.notificationmaster.databinding.FragmentTimelineBinding
import com.notificationmaster.service.NotificationCaptureService
import com.notificationmaster.ui.filter.FilterRuleDialogHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private var isAudibleMode = false
    private var layoutManagerState: Parcelable? = null
    private var currentFilterText = ""
    private var allNotifications: List<NotificationEntity> = emptyList()
    private var loadJob: Job? = null
    // App 名稱快取委派給 AppLabelCache（共用、帶 TTL）
    private val dateFormat = SimpleDateFormat("yyyy年M月d日 EEEE", Locale.getDefault())
    private var bubbleHideRunnable: Runnable? = null
    private var wasPermissionGranted = false
    private var rebindJob: Job? = null

    // === 天分頁漸進載入 ===
    /** 今天的資料（Flow 即時更新） */
    private var todayNotifications: List<NotificationEntity> = emptyList()
    /** 歷史天資料，按日期倒序排列（dayStart to data） */
    private val historicalDays = mutableListOf<Pair<Long, List<NotificationEntity>>>()
    /** 往回載入的下一個日期（dayStart timestamp） */
    private var nextDayToLoad: Long = 0L
    /** 正在載入更多 */
    private var isLoadingMore = false
    /** 已到達最早記錄 */
    private var hasReachedEnd = false
    /** 資料庫最早記錄時間（一次性查詢） */
    private var earliestPostTime: Long? = null


    private companion object {
        private const val TAG = "TimelineFragment"
        /** 首次檢查間隔 (ms) */
        private const val REBIND_INITIAL_DELAY_MS = 1000L
        /** 最大間隔 (ms) */
        private const val REBIND_MAX_DELAY_MS = 16000L
        /** 最多嘗試幾次 */
        private const val MAX_REBIND_ATTEMPTS = 6
        /** 一天的毫秒數 */
        private const val ONE_DAY_MS = 24 * 60 * 60 * 1000L
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
        Log.d(TAG, "onViewCreated: permissionGranted=$wasPermissionGranted, " +
            "serviceConnected=${NotificationCaptureService.isConnected}, " +
            "serviceInstance=${NotificationCaptureService.getInstance() != null}")
        updateEmptyStateForPermission()

        loadNotifications()
    }

    override fun onResume() {
        super.onResume()
        val isGranted = isNotificationListenerEnabled()
        Log.d(TAG, "onResume: isGranted=$isGranted, wasGranted=$wasPermissionGranted, " +
            "serviceConnected=${NotificationCaptureService.isConnected}")
        if (isGranted && !wasPermissionGranted) {
            // 權限剛授予 — 先更新 UI 並啟動 Flow 監聽
            // 不立即觸發 rebind，讓系統有時間自然綁定服務
            wasPermissionGranted = true
            updateEmptyStateForPermission()
            loadNotifications()
            ensureServiceConnected()
        } else if (!isGranted && wasPermissionGranted) {
            wasPermissionGranted = false
            rebindJob?.cancel()
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
            adapter = TimelineAdapter(
                onItemClick = { notification -> navigateToDetail(notification) },
                onSimilarClick = { notification -> showSimilarNotifications(notification) },
                onItemLongClick = { notification ->
                    FilterRuleDialogHelper.showAddRuleDialog(
                        context = requireContext(),
                        actionType = null,
                        prefillPackageName = notification.packageName,
                        prefillChannelId = notification.channelId
                    )
                }
            )
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        // 捲動時更新時間索引氣泡 + load-more 觸發
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                updateTimeBubble()
                showAndScheduleHideBubble()

                // 接近底部時觸發載入下一天
                val layoutManager = rv.layoutManager as? LinearLayoutManager ?: return
                val totalItemCount = layoutManager.itemCount
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                if (totalItemCount - lastVisible <= 5 &&
                    !isLoadingMore && !hasReachedEnd && !isAudibleMode) {
                    loadNextDay()
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
                isAudibleMode = false
                binding.swipeRefresh.isRefreshing = true
                loadNotifications()
            }
        }

        binding.chipDeduplicated.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                isDeduplicatedMode = true
                isAudibleMode = false
                binding.swipeRefresh.isRefreshing = true
                loadNotifications()
            }
        }

        binding.chipAudible.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                isDeduplicatedMode = false
                isAudibleMode = true
                binding.swipeRefresh.isRefreshing = true
                loadNotifications()
            }
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            val serviceConnected = NotificationCaptureService.isConnected
            Log.d(TAG, "swipeRefresh: permissionGranted=$wasPermissionGranted, " +
                "serviceConnected=$serviceConnected")
            if (wasPermissionGranted) {
                if (serviceConnected) {
                    // 服務已連線 — 手動擷取當前活躍通知（不重複插入）
                    NotificationCaptureService.getInstance()?.captureActiveNotifications()
                } else {
                    // 服務未連線 — 嘗試觸發重新綁定
                    ensureServiceConnected()
                }
            }
            loadNotifications()
        }
    }

    private fun loadNotifications() {
        // 取消先前的 Flow 收集，避免同時存在多個 collector
        loadJob?.cancel()

        // 重設天分頁狀態
        todayNotifications = emptyList()
        historicalDays.clear()
        isLoadingMore = false
        hasReachedEnd = false

        // 顯示載入指示器
        binding.swipeRefresh.isRefreshing = true

        val database = NotificationMasterApp.getInstance().database
        val dao = database.notificationDao()

        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            if (isAudibleMode) {
                // Audible / Heads-up 模式維持現有邏輯（全域查詢，不分天）
                dao.getRecentAudibleNotifications().collectLatest { notifications ->
                    if (_binding == null) return@collectLatest
                    _binding?.swipeRefresh?.isRefreshing = false
                    allNotifications = notifications
                    applyFilterAndDisplay()
                }
            } else {
                // === 天分頁漸進載入 ===
                val todayStart = getStartOfDay(System.currentTimeMillis())
                val yesterdayStart = todayStart - ONE_DAY_MS
                nextDayToLoad = yesterdayStart - ONE_DAY_MS

                // 一次性查詢最早記錄時間
                earliestPostTime = withContext(Dispatchers.IO) { dao.getEarliestPostTime() }

                // 載入昨天（suspend 查詢）
                val yesterdayData = withContext(Dispatchers.IO) {
                    if (isDeduplicatedMode)
                        dao.getDeduplicatedByDayRange(yesterdayStart, todayStart)
                    else
                        dao.getNotificationsByDayRange(yesterdayStart, todayStart)
                }
                if (yesterdayData.isNotEmpty()) {
                    historicalDays.add(yesterdayStart to yesterdayData)
                }

                // 判斷昨天後是否已到底
                checkReachedEnd()

                // 訂閱今天的 Flow（即時更新）
                val todayFlow = if (isDeduplicatedMode)
                    dao.getDeduplicatedNotifications(todayStart, Long.MAX_VALUE)
                else
                    dao.getNotificationsByTimeRange(todayStart, Long.MAX_VALUE)

                todayFlow.collectLatest { liveNotifications ->
                    if (_binding == null) return@collectLatest
                    _binding?.swipeRefresh?.isRefreshing = false
                    todayNotifications = liveNotifications
                    combineAndDisplay()
                }
            }
        }
    }

    /**
     * 載入下一天的歷史資料
     */
    private fun loadNextDay() {
        if (isLoadingMore || hasReachedEnd || isAudibleMode) return
        isLoadingMore = true
        updateFooterInList()

        val dao = NotificationMasterApp.getInstance().database.notificationDao()

        viewLifecycleOwner.lifecycleScope.launch {
            val dayStart = nextDayToLoad
            val dayEnd = dayStart + ONE_DAY_MS

            val data = withContext(Dispatchers.IO) {
                if (isDeduplicatedMode)
                    dao.getDeduplicatedByDayRange(dayStart, dayEnd)
                else
                    dao.getNotificationsByDayRange(dayStart, dayEnd)
            }

            if (data.isNotEmpty()) {
                historicalDays.add(dayStart to data)
            }

            // 前移到上一天
            nextDayToLoad = dayStart - ONE_DAY_MS

            // 判斷到底
            checkReachedEnd()

            isLoadingMore = false
            combineAndDisplay()
        }
    }

    /**
     * 檢查是否已到達資料庫最早記錄
     */
    private fun checkReachedEnd() {
        val earliest = earliestPostTime ?: run {
            hasReachedEnd = true
            return
        }
        // 下一天的結尾已早於最早記錄 → 到底
        if (nextDayToLoad + ONE_DAY_MS <= earliest) {
            hasReachedEnd = true
        }
    }

    /**
     * 合併今天 + 歷史天資料
     */
    private fun combineAndDisplay() {
        allNotifications = todayNotifications + historicalDays.flatMap { it.second }
        applyFilterAndDisplay()
    }

    /**
     * 更新列表末尾的 footer 項目（LoadingMore 指示器）
     */
    private fun updateFooterInList() {
        val currentList = adapter?.currentList ?: return
        // 移除舊 footer，加入新 footer
        val withoutFooter = currentList.filter {
            it !is TimelineItem.LoadingMore && it !is TimelineItem.EndOfTimeline
        }
        adapter?.submitList(withoutFooter + TimelineItem.LoadingMore)
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
                var timelineItems: List<TimelineItem> = if (isDeduplicatedMode) {
                    buildTimelineItemsWithSimilarCount(filtered, notificationDao)
                } else {
                    buildTimelineItems(filtered)
                }

                // 非 Audible 模式加入 footer 指示器
                if (!isAudibleMode) {
                    val footer = when {
                        isLoadingMore -> TimelineItem.LoadingMore
                        hasReachedEnd -> TimelineItem.EndOfTimeline
                        else -> null
                    }
                    if (footer != null) {
                        timelineItems = timelineItems + footer
                    }
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
        val ctx = context ?: return packageName
        return AppLabelCache.getLabel(ctx, packageName)
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
     * 每天獨立計算去重數量
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

            // 統計被合併的不同通知數（以該天為範圍）
            val dayStart = notificationDate
            val dayEnd = dayStart + ONE_DAY_MS
            val similarCount = withContext(Dispatchers.IO) {
                dao.getDeduplicatedCount(notification.contentHash, dayStart, dayEnd)
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
            is TimelineItem.LoadingMore, is TimelineItem.EndOfTimeline -> return
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

    /**
     * 顯示時間氣泡並排程自動隱藏
     * 每次捲動事件觸發，確保 fast scroll thumb 拖曳期間氣泡持續可見
     */
    private fun showAndScheduleHideBubble() {
        val binding = _binding ?: return
        bubbleHideRunnable?.let { binding.timeBubble.removeCallbacks(it) }
        binding.timeBubble.visibility = View.VISIBLE
        val runnable = Runnable { _binding?.timeBubble?.visibility = View.GONE }
        bubbleHideRunnable = runnable
        binding.timeBubble.postDelayed(runnable, 1500L)
    }

    /**
     * 確保 NotificationListenerService 在授權後成功連線
     *
     * 系統授權後通常會自動綁定服務，但部分裝置/ROM 可能延遲或不觸發。
     * 此方法定期檢查服務狀態，在系統未自動綁定時透過 requestRebind (API 24+) 觸發。
     *
     * 注意：不使用 setComponentEnabledSetting 元件切換，
     * 因為 disable 元件會導致系統從 enabled_notification_listeners 移除，撤銷授權。
     */
    private fun ensureServiceConnected() {
        rebindJob?.cancel()
        Log.d(TAG, "ensureServiceConnected: starting checks (exponential backoff, " +
            "max=$MAX_REBIND_ATTEMPTS)")
        rebindJob = viewLifecycleOwner.lifecycleScope.launch {
            var delayMs = REBIND_INITIAL_DELAY_MS
            for (attempt in 0 until MAX_REBIND_ATTEMPTS) {
                delay(delayMs)
                if (_binding == null) return@launch

                val connected = NotificationCaptureService.isConnected
                val instanceExists = NotificationCaptureService.getInstance() != null
                Log.d(TAG, "ensureServiceConnected check #${attempt + 1} " +
                    "(delay=${delayMs}ms): connected=$connected, instance=$instanceExists")

                if (connected) {
                    // 服務已連線，手動觸發一次擷取確保有資料
                    NotificationCaptureService.getInstance()?.captureActiveNotifications()
                    return@launch
                }

                if (Build.VERSION.SDK_INT >= 24) {
                    try {
                        val ctx = context ?: return@launch
                        val cn = ComponentName(ctx, NotificationCaptureService::class.java)
                        NotificationListenerService.requestRebind(cn)
                        Log.d(TAG, "requestRebind sent (attempt ${attempt + 1})")
                    } catch (e: Exception) {
                        Log.w(TAG, "requestRebind failed", e)
                    }
                }

                // Exponential backoff: 1s → 2s → 4s → 8s → 16s → 16s
                delayMs = (delayMs * 2).coerceAtMost(REBIND_MAX_DELAY_MS)
            }

            Log.w(TAG, "Service not connected after $MAX_REBIND_ATTEMPTS attempts. " +
                "User can pull-to-refresh to retry.")
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

    @SuppressLint("InlinedApi")
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

    /**
     * 切換到 Audible 篩選模式
     */
    private fun activateAudibleMode() {
        isAudibleMode = true
        isDeduplicatedMode = false
        binding.chipAudible.isChecked = true
    }

    private fun showSimilarNotifications(notification: NotificationEntity) {
        val dao = NotificationMasterApp.getInstance().database.notificationDao()
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val dayStart = getStartOfDay(notification.postTime)
        val dayEnd = dayStart + ONE_DAY_MS
        viewLifecycleOwner.lifecycleScope.launch {
            val similar = withContext(Dispatchers.IO) {
                dao.getSimilarNotifications(notification.contentHash, dayStart, dayEnd)
            }
            if (_binding == null) return@launch
            if (similar.size <= 1) return@launch

            val items = similar.map { n ->
                val appLabel = getAppLabel(n.packageName)
                val time = timeFormat.format(Date(n.postTime))
                "$appLabel · $time\n${n.notificationKey}"
            }.toTypedArray()

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.similar_notifications_title)
                .setItems(items) { _, which ->
                    navigateToDetail(similar[which])
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun navigateToDetail(notification: NotificationEntity) {
        // 導航到通知詳情頁面
        val action = TimelineFragmentDirections.actionTimelineToDetail(notification.id)
        findNavController().navigate(action)
    }
}
