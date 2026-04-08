package com.notificationmaster.ui.timeline

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
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
import com.notificationmaster.core.permission.NlsConnectionManager
import com.notificationmaster.service.NotificationCaptureService
import com.notificationmaster.ui.filter.FilterRuleDialogHelper
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
    private var isAudibleMode = false
    private var isDismissedMode = false
    private var currentFilterText = ""
    private var allNotifications: List<NotificationEntity> = emptyList()
    private var loadJob: Job? = null
    /** 目前模式對應的資料庫總數（由 count Flow 更新） */
    private var totalCount: Int = 0
    // App 名稱快取委派給 AppLabelCache（共用、帶 TTL）
    private val dateFormat = SimpleDateFormat("yyyy年M月d日 EEEE", Locale.getDefault())
    private var bubbleHideRunnable: Runnable? = null
    private var wasPermissionGranted = false

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
        wasPermissionGranted = NlsConnectionManager.isNlsEnabled(requireContext())
        Log.d(TAG, "onViewCreated: permissionGranted=$wasPermissionGranted, " +
            "serviceConnected=${NotificationCaptureService.isConnected}, " +
            "serviceInstance=${NotificationCaptureService.getInstance() != null}")
        updateEmptyStateForPermission()
        setupRankingBannerObserver()

        loadNotifications()
    }

    override fun onResume() {
        super.onResume()
        val isGranted = NlsConnectionManager.isNlsEnabled(requireContext())
        Log.d(TAG, "onResume: isGranted=$isGranted, wasGranted=$wasPermissionGranted, " +
            "serviceConnected=${NotificationCaptureService.isConnected}")
        if (isGranted != wasPermissionGranted) {
            wasPermissionGranted = isGranted
            updateEmptyStateForPermission()
            if (isGranted) loadNotifications()
        }
    }

    override fun onPause() {
        super.onPause()
        // 離開前景時取消氣泡隱藏排程並立即隱藏，避免回來時延遲消失
        bubbleHideRunnable?.let { _binding?.timeBubble?.removeCallbacks(it) }
        _binding?.timeBubble?.visibility = View.GONE
    }

    override fun onDestroyView() {
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
                    !isLoadingMore && !hasReachedEnd && !isAudibleMode && !isDismissedMode) {
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
        // 不選 = 顯示全部；選其中一個 = 對應篩選模式
        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull()
            isDeduplicatedMode = checkedId == R.id.chip_deduplicated
            isAudibleMode = checkedId == R.id.chip_audible
            isDismissedMode = checkedId == R.id.chip_dismissed
            binding.swipeRefresh.isRefreshing = true
            loadNotifications()
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            // 重新檢查權限狀態（使用者可能在不觸發 onResume 的路徑下授權）
            val isGranted = NlsConnectionManager.isNlsEnabled(requireContext())
            if (isGranted != wasPermissionGranted) {
                wasPermissionGranted = isGranted
                updateEmptyStateForPermission()
            }
            Log.d(TAG, "swipeRefresh: permissionGranted=$wasPermissionGranted, " +
                "serviceConnected=${NotificationCaptureService.isConnected}")
            if (wasPermissionGranted) {
                if (NotificationCaptureService.isConnected) {
                    // 服務已連線 — 備援擷取（冪等，跳過已存在 key）
                    NotificationCaptureService.getInstance()?.captureActiveNotifications()
                } else {
                    // 服務未連線 — 嘗試觸發重新綁定
                    NlsConnectionManager.ensureServiceConnected(
                        requireContext().applicationContext,
                        viewLifecycleOwner.lifecycleScope
                    )
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

        // 重設計數器狀態
        totalCount = 0

        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            // 訂閱目前模式的資料庫總數（獨立於漸進載入，自動響應資料變動）
            launch {
                val countFlow = when {
                    isAudibleMode -> dao.getAudibleTotalCountFlow()
                    isDismissedMode -> dao.getDismissedTotalCountFlow()
                    isDeduplicatedMode -> dao.getDeduplicatedTotalCountFlow()
                    else -> dao.getTotalKeyCountFlow()
                }
                countFlow.collectLatest { total ->
                    if (_binding == null) return@collectLatest
                    totalCount = total
                    // 觸發計數器文字更新（不改變列表資料）
                    refreshCountText()
                }
            }

            // 訂閱已移除通知 id 集合（供 adapter 套用淡化）。
            // 已移除模式本身項目全為 removed，不套用以免整片變灰。
            if (!isDismissedMode) {
                launch {
                    dao.getRemovedNotificationIdsFlow().collectLatest { ids ->
                        if (_binding == null) return@collectLatest
                        adapter?.removedIds = ids.toSet()
                    }
                }
            } else {
                adapter?.removedIds = emptySet()
            }

            if (isAudibleMode) {
                // Audible 模式（全域查詢，不分天）
                dao.getRecentAudibleNotifications().collectLatest { notifications ->
                    if (_binding == null) return@collectLatest
                    _binding?.swipeRefresh?.isRefreshing = false
                    allNotifications = notifications
                    applyFilterAndDisplay()
                }
            } else if (isDismissedMode) {
                // Dismissed 模式（全域查詢，不分天）
                dao.getRecentDismissedNotifications().collectLatest { notifications ->
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
        if (isLoadingMore || hasReachedEnd || isAudibleMode || isDismissedMode) return
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
            refreshCountText(loadedOverride = 0)
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

                // 非 Audible/Dismissed 模式加入 footer 指示器
                if (!isAudibleMode && !isDismissedMode) {
                    val footer = when {
                        isLoadingMore -> TimelineItem.LoadingMore
                        hasReachedEnd -> TimelineItem.EndOfTimeline
                        else -> null
                    }
                    if (footer != null) {
                        timelineItems = timelineItems + footer
                    }
                }

                adapter?.submitList(timelineItems)
                refreshCountText(loadedOverride = filtered.size)
            }
        }
    }

    /**
     * 更新計數器文字：已載入 / 總數。
     * 若 loadedOverride 為 null，使用目前過濾後的 allNotifications 筆數。
     */
    private fun refreshCountText(loadedOverride: Int? = null) {
        val binding = _binding ?: return
        val loaded = loadedOverride
            ?: filterNotifications(allNotifications, currentFilterText).size
        binding.textCount.text = getString(
            R.string.timeline_count_format_loaded_total,
            loaded,
            totalCount
        )
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
        val bubbleHeight = binding.timeBubble.height.toFloat()

        // 用 window 絕對座標對齊，避免 banner 等中間容器造成偏移
        val rvLocation = IntArray(2)
        val bubbleParentLocation = IntArray(2)
        rv.getLocationInWindow(rvLocation)
        (binding.timeBubble.parent as? View)?.getLocationInWindow(bubbleParentLocation)
        val rvTop = rvLocation[1] - bubbleParentLocation[1]
        val trackRange = rv.height.toFloat() - bubbleHeight

        binding.timeBubble.translationY = rvTop + trackRange * fraction
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
     * 觀察 Service 的 showRankingBanner LiveData，事件驅動顯示/隱藏橫幅。
     * 連線時若 RankingMap 為空 → 顯示；填充後或斷線 → 隱藏。無需輪詢。
     */
    private fun setupRankingBannerObserver() {
        binding.bannerRankingWarning.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ranking_map_warning_title)
                .setMessage(R.string.ranking_map_warning_detail)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        NotificationCaptureService.showRankingBanner.observe(viewLifecycleOwner) { show ->
            _binding?.bannerRankingWarning?.visibility =
                if (show) View.VISIBLE else View.GONE
        }
    }

    /**
     * 切換到 Audible 篩選模式
     */
    private fun activateAudibleMode() {
        binding.chipAudible.isChecked = true  // 觸發 setOnCheckedStateChangeListener 統一處理
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
        val action = TimelineFragmentDirections.actionTimelineHomeToTimelineDetail(notification.id)
        findNavController().navigate(action)
    }
}
