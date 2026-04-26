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
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.R
import com.notificationmaster.core.cache.AppLabelCache
import com.notificationmaster.core.permission.NlsConnectionManager
import com.notificationmaster.data.db.entity.NotificationEntity
import com.notificationmaster.core.filter.ActionType
import com.notificationmaster.core.filter.Rule
import com.notificationmaster.core.filter.RuleAction
import com.notificationmaster.core.filter.RuleEngine
import com.notificationmaster.core.filter.RuleRepository
import com.notificationmaster.data.filter.EventFilterSpec
import com.notificationmaster.data.filter.coreFilterSpecOf
import com.notificationmaster.data.filter.toFilterSpec
import com.notificationmaster.data.db.dao.count
import com.notificationmaster.data.db.dao.query
import com.notificationmaster.databinding.FragmentTimelineBinding
import com.notificationmaster.service.NotificationCaptureService
import com.notificationmaster.ui.filter.FilterRuleDialogHelper
import com.notificationmaster.ui.filter.SoundPickerLauncher
import com.notificationmaster.ui.main.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 時間軸 Fragment
 *
 * 以 [EventFilterSpec] 統一篩選：
 * - 核心 4 chip（去重／有聲／彈出／已移除）多選 AND
 * - Preset chip 單選套用整套 spec
 * - + chip 開啟自訂篩選 BottomSheet
 * - spec 為「全部」或「僅去重」時走天分頁漸進載入；其餘走全域 spec 查詢
 */
class TimelineFragment : Fragment() {

    private var _binding: FragmentTimelineBinding? = null
    private val binding get() = _binding!!

    // 系統原生鈴聲選擇器（field initializer 確保在 Fragment STARTED 前完成註冊）
    private val soundPicker = SoundPickerLauncher(this)

    private var adapter: TimelineAdapter? = null

    // === 篩選狀態 ===
    /** 當前核心 spec（由 chip 狀態組合而成；若 LIST_FILTER rule 啟用則為 rule.toFilterSpec()） */
    private var coreSpec: EventFilterSpec = EventFilterSpec.Deduplicated
    /** 當前選中的 LIST_FILTER rule id，null 表示未套用任何 rule（走核心 chip） */
    private var activeRuleId: String? = null
    /** 是否正由程式調整 chip 狀態（避免 listener re-entrancy） */
    private var suppressChipListener = false

    private var currentFilterText = ""
    private var allNotifications: List<NotificationEntity> = emptyList()
    private var loadJob: Job? = null
    /** 目前模式對應的資料庫總數（由 count Flow 更新） */
    private var totalCount: Int = 0
    private val dateFormat = SimpleDateFormat("yyyy年M月d日 EEEE", Locale.getDefault())
    private var bubbleHideRunnable: Runnable? = null
    private var wasPermissionGranted = false

    // === 天分頁漸進載入 ===
    private var todayNotifications: List<NotificationEntity> = emptyList()
    private val historicalDays = mutableListOf<Pair<Long, List<NotificationEntity>>>()
    private var nextDayToLoad: Long = 0L
    private var isLoadingMore = false
    private var hasReachedEnd = false
    private var earliestPostTime: Long? = null
    private var removedIds: Set<Long> = emptySet()

    private companion object {
        private const val TAG = "TimelineFragment"
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
        // 確保 RuleEngine 已載入（含內建 LIST_FILTER rules）
        RuleRepository.load(requireContext())

        setupRecyclerView()
        setupFilterInput()
        setupFilterChips()
        setupRuleChips()
        setupSwipeRefresh()
        setupPermissionButton()
        wasPermissionGranted = NlsConnectionManager.isNlsEnabled(requireContext())
        Log.d(TAG, "onViewCreated: permissionGranted=$wasPermissionGranted, " +
            "serviceConnected=${NotificationCaptureService.isConnected}, " +
            "serviceInstance=${NotificationCaptureService.getInstance() != null}")
        updateEmptyStateForPermission()
        setupRankingBannerObserver()

        // 優先套用 Intent 帶入的 spec / preset
        val handled = handleIncomingIntent()
        if (!handled) {
            rebuildSpecFromChips()
            loadNotifications()
        }
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
        // onResume 可能因 MainActivity.onNewIntent 而觸發，重新檢查 Intent
        if (handleIncomingIntent()) return
    }

    override fun onPause() {
        super.onPause()
        bubbleHideRunnable?.let { _binding?.timeBubble?.removeCallbacks(it) }
        _binding?.timeBubble?.visibility = View.GONE
        (activity as? MainActivity)?.setToolbarCount(null)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        (activity as? MainActivity)?.setToolbarCount(null)
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
                        prefillChannelId = notification.channelId,
                        soundPicker = soundPicker
                    )
                }
            )
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                updateTimeBubble()
                showAndScheduleHideBubble()

                // 天分頁模式才有「接近底部載入下一天」
                if (usesDayPaging()) {
                    val layoutManager = rv.layoutManager as? LinearLayoutManager ?: return
                    val totalItemCount = layoutManager.itemCount
                    val lastVisible = layoutManager.findLastVisibleItemPosition()
                    if (totalItemCount - lastVisible <= 5 && !isLoadingMore && !hasReachedEnd) {
                        loadNextDay()
                    }
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
        // 核心 chip 容器為 LinearLayout（強制單行）— 對每個 chip 個別監聽
        val onChipChange = { _: View ->
            if (!suppressChipListener) {
                activeRuleId = null
                clearRuleSelection()
                rebuildSpecFromChips()
                binding.swipeRefresh.isRefreshing = true
                loadNotifications()
            }
        }
        binding.chipDeduplicated.setOnClickListener(onChipChange)
        binding.chipAudible.setOnClickListener(onChipChange)
        binding.chipHeadsup.setOnClickListener(onChipChange)
        binding.chipDismissed.setOnClickListener(onChipChange)
    }

    private fun setupRuleChips() {
        viewLifecycleOwner.lifecycleScope.launch {
            // RuleEngine 內容變動時 triggerFlow tick；沒收到也在 onResume 重 render
            RuleEngine.triggerFlow.collectLatest {
                if (_binding == null) return@collectLatest
                renderRuleChips()
            }
        }
        renderRuleChips()
        binding.chipAddPreset.setOnClickListener {
            openListFilterEditor(initial = coreSpec, editingRuleId = null)
        }
    }

    /** 只顯示使用者命名（非內建）的 LIST_FILTER rule；內建留給 Shortcut / 4 chip 對應 */
    private fun renderRuleChips() {
        val group = binding.chipGroupPresets
        val addChip = binding.chipAddPreset
        val toRemove = (0 until group.childCount).mapNotNull { i ->
            val c = group.getChildAt(i)
            if (c.id != R.id.chip_add_preset) c else null
        }
        toRemove.forEach { group.removeView(it) }

        val rules = RuleEngine.getListFilterRules().filter { !it.isBuiltIn }
        val inflater = LayoutInflater.from(requireContext())
        for ((i, rule) in rules.withIndex()) {
            val chip = inflater.inflate(R.layout.chip_preset, group, false) as Chip
            chip.text = rule.name ?: rule.id.take(8)
            chip.tag = rule.id
            chip.isChecked = (activeRuleId == rule.id)
            chip.setOnClickListener {
                if (suppressChipListener) return@setOnClickListener
                if (chip.isChecked) {
                    applyRule(rule)
                } else {
                    activeRuleId = null
                    rebuildSpecFromChips()
                    binding.swipeRefresh.isRefreshing = true
                    loadNotifications()
                }
            }
            chip.setOnLongClickListener {
                showRuleMenu(rule)
                true
            }
            group.addView(chip, i)
        }
        group.removeView(addChip)
        group.addView(addChip)
    }

    private fun clearRuleSelection() {
        val group = binding.chipGroupPresets
        for (i in 0 until group.childCount) {
            val c = group.getChildAt(i) as? Chip ?: continue
            if (c.id != R.id.chip_add_preset) c.isChecked = false
        }
    }

    private fun applyRule(rule: Rule) {
        activeRuleId = rule.id
        coreSpec = rule.toFilterSpec()
        syncChipsFromSpec(coreSpec)
        binding.swipeRefresh.isRefreshing = true
        loadNotifications()
    }

    private fun showRuleMenu(rule: Rule) {
        val items = if (rule.isBuiltIn) {
            arrayOf(getString(R.string.preset_action_create_widget))
        } else {
            arrayOf(
                getString(R.string.preset_action_edit),
                getString(R.string.preset_action_rename),
                getString(R.string.preset_action_delete),
                getString(R.string.preset_action_create_widget)
            )
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(rule.name ?: rule.id.take(8))
            .setItems(items) { _, which ->
                if (rule.isBuiltIn) {
                    if (which == 0) requestCreateWidget(rule.id)
                } else {
                    when (which) {
                        0 -> openListFilterEditor(initial = rule.toFilterSpec(), editingRuleId = rule.id)
                        1 -> showRenameRuleDialog(rule)
                        2 -> showDeleteRuleDialog(rule)
                        3 -> requestCreateWidget(rule.id)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRenameRuleDialog(rule: Rule) {
        val editText = com.google.android.material.textfield.TextInputEditText(requireContext()).apply {
            setText(rule.name.orEmpty())
            setSelection(text?.length ?: 0)
        }
        val inputLayout = com.google.android.material.textfield.TextInputLayout(
            requireContext(), null, com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = getString(R.string.preset_save_name_hint)
            helperText = getString(R.string.preset_rename_widget_notice)
            isHelperTextEnabled = true
            setPadding(48, 16, 48, 0)
            addView(editText)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.preset_rename_title)
            .setView(inputLayout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = editText.text?.toString()?.trim().orEmpty()
                if (newName.isNotEmpty() && newName != rule.name) {
                    RuleRepository.updateRule(requireContext(), rule.copy(name = newName))
                    renderRuleChips()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeleteRuleDialog(rule: Rule) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.preset_delete_confirm_title)
            .setMessage(getString(R.string.preset_delete_confirm_message, rule.name ?: rule.id))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                RuleRepository.removeRule(requireContext(), rule.id)
                if (activeRuleId == rule.id) {
                    activeRuleId = null
                    rebuildSpecFromChips()
                    loadNotifications()
                }
                renderRuleChips()
                com.google.android.material.snackbar.Snackbar.make(
                    binding.root,
                    getString(R.string.preset_deleted, rule.name ?: rule.id),
                    com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun requestCreateWidget(ruleId: String) {
        com.notificationmaster.ui.widget.WidgetPinner.requestPin(requireContext(), ruleId)
    }

    /**
     * 開啟 LIST_FILTER rule 編輯器（統一用 FilterRuleDialogHelper widgetMode）
     *
     * editingRuleId == null：新增 rule，完成後彈出命名輸入；
     * editingRuleId != null：更新既有 rule 的 matchers
     */
    private fun openListFilterEditor(initial: EventFilterSpec, editingRuleId: String?) {
        FilterRuleDialogHelper.showAddRuleDialog(
            context = requireContext(),
            widgetMode = true,
            existingWidgetMatchers = initial.matchers,
            onMatchersReady = { matchers ->
                if (editingRuleId != null) {
                    val existing = RuleEngine.getRule(editingRuleId) ?: return@showAddRuleDialog
                    val updated = existing.copy(matchers = matchers)
                    RuleRepository.updateRule(requireContext(), updated)
                    if (activeRuleId == editingRuleId) {
                        coreSpec = updated.toFilterSpec()
                        syncChipsFromSpec(coreSpec)
                        loadNotifications()
                    }
                    renderRuleChips()
                } else {
                    promptNewRuleName(matchers, initial)
                }
            }
        )
    }

    private fun promptNewRuleName(matchers: List<com.notificationmaster.core.filter.Matcher>, base: EventFilterSpec) {
        val editText = com.google.android.material.textfield.TextInputEditText(requireContext())
        val inputLayout = com.google.android.material.textfield.TextInputLayout(
            requireContext(), null, com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = getString(R.string.preset_save_name_hint)
            setPadding(48, 16, 48, 0)
            addView(editText)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.preset_save_title)
            .setView(inputLayout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = editText.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) return@setPositiveButton
                val rule = Rule(
                    name = name,
                    matchers = matchers,
                    action = RuleAction.ListFilter(
                        orderBy = base.orderBy,
                        limit = base.limit,
                        deduplicate = base.deduplicate
                    )
                )
                RuleRepository.addRule(requireContext(), rule)
                activeRuleId = rule.id
                coreSpec = rule.toFilterSpec()
                syncChipsFromSpec(coreSpec)
                renderRuleChips()
                loadNotifications()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun rebuildSpecFromChips() {
        val dedup = binding.chipDeduplicated.isChecked
        val audible = binding.chipAudible.isChecked
        val headsup = binding.chipHeadsup.isChecked
        val dismissed = binding.chipDismissed.isChecked
        coreSpec = coreFilterSpecOf(
            isAudible = if (audible) true else null,
            likelyHeadsup = if (headsup) true else null,
            isRemoved = if (dismissed) true else null,
            deduplicate = dedup
        )
    }

    /** 將 spec 反映到核心 chip 勾選狀態（Intent / rule / editor 套用後） */
    private fun syncChipsFromSpec(spec: EventFilterSpec) {
        suppressChipListener = true
        try {
            binding.chipDeduplicated.isChecked = spec.deduplicate
            binding.chipAudible.isChecked = (spec.isAudible == true)
            binding.chipHeadsup.isChecked = (spec.likelyHeadsup == true)
            binding.chipDismissed.isChecked = (spec.isRemoved == true)

            // Rule chip 勾選：activeRuleId 有值時勾起對應 chip
            val group = binding.chipGroupPresets
            for (i in 0 until group.childCount) {
                val c = group.getChildAt(i) as? Chip ?: continue
                if (c.id == R.id.chip_add_preset) continue
                c.isChecked = (c.tag == activeRuleId)
            }
        } finally {
            suppressChipListener = false
        }
    }

    /** 是否走天分頁漸進載入：全部 / 純去重，其餘皆走全域 spec 查詢 */
    private fun usesDayPaging(): Boolean =
        coreSpec.matchers.isEmpty() &&
            coreSpec.timeFrom == null && coreSpec.timeTo == null &&
            coreSpec.limit == null

    private fun handleIncomingIntent(): Boolean {
        val act = activity ?: return false
        val intent = act.intent ?: return false
        if (intent.action != MainActivity.ACTION_SHOW_FILTERED_TIMELINE) return false

        val ruleId = intent.getStringExtra(MainActivity.EXTRA_RULE_ID) ?: run {
            intent.action = null
            return false
        }
        intent.action = null

        val rule = RuleEngine.getRule(ruleId) ?: return false
        if (rule.action.actionType != ActionType.LIST_FILTER) return false

        activeRuleId = ruleId
        coreSpec = rule.toFilterSpec()
        syncChipsFromSpec(coreSpec)
        binding.swipeRefresh.isRefreshing = true
        loadNotifications()
        return true
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            val isGranted = NlsConnectionManager.isNlsEnabled(requireContext())
            if (isGranted != wasPermissionGranted) {
                wasPermissionGranted = isGranted
                updateEmptyStateForPermission()
            }
            Log.d(TAG, "swipeRefresh: permissionGranted=$wasPermissionGranted, " +
                "serviceConnected=${NotificationCaptureService.isConnected}")
            if (wasPermissionGranted) {
                if (NotificationCaptureService.isConnected) {
                    NotificationCaptureService.getInstance()?.captureActiveNotifications()
                } else {
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
        loadJob?.cancel()

        todayNotifications = emptyList()
        historicalDays.clear()
        isLoadingMore = false
        hasReachedEnd = false
        removedIds = emptySet()

        binding.swipeRefresh.isRefreshing = true

        val database = NotificationMasterApp.getInstance().database
        val dao = database.notificationDao()

        totalCount = 0

        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            // 總數 Flow：統一走 count(spec)
            launch {
                dao.count(coreSpec).collectLatest { total ->
                    if (_binding == null) return@collectLatest
                    totalCount = total
                    refreshCountText()
                }
            }

            // 已移除 id 集合（除非 spec 本身就是 removed 專屬）
            val showRemovedOverlay = coreSpec.isRemoved != true
            if (showRemovedOverlay) {
                launch {
                    dao.getRemovedNotificationIdsFlow().collectLatest { ids ->
                        if (_binding == null) return@collectLatest
                        val newSet = ids.toSet()
                        if (removedIds != newSet) {
                            removedIds = newSet
                            applyFilterAndDisplay()
                        }
                    }
                }
            } else {
                removedIds = emptySet()
            }

            if (usesDayPaging()) {
                // 全部 / 純去重 → 天分頁漸進載入
                val todayStart = getStartOfDay(System.currentTimeMillis())
                val yesterdayStart = todayStart - ONE_DAY_MS
                nextDayToLoad = yesterdayStart - ONE_DAY_MS

                earliestPostTime = withContext(Dispatchers.IO) { dao.getEarliestPostTime() }

                // 昨天：一次 suspend 查詢（取首個 emission）
                val yesterdaySpec = coreSpec.copy(timeFrom = yesterdayStart, timeTo = todayStart)
                val yesterdayData = withContext(Dispatchers.IO) {
                    dao.query(yesterdaySpec).first()
                }
                if (yesterdayData.isNotEmpty()) {
                    historicalDays.add(yesterdayStart to yesterdayData)
                }
                checkReachedEnd()

                val todaySpec = coreSpec.copy(timeFrom = todayStart, timeTo = Long.MAX_VALUE)
                dao.query(todaySpec).collectLatest { liveNotifications ->
                    if (_binding == null) return@collectLatest
                    _binding?.swipeRefresh?.isRefreshing = false
                    todayNotifications = liveNotifications
                    combineAndDisplay()
                }
            } else {
                // 全域 spec 查詢
                dao.query(coreSpec).collectLatest { notifications ->
                    if (_binding == null) return@collectLatest
                    _binding?.swipeRefresh?.isRefreshing = false
                    allNotifications = notifications
                    applyFilterAndDisplay()
                }
            }
        }
    }

    private fun loadNextDay() {
        if (isLoadingMore || hasReachedEnd || !usesDayPaging()) return
        isLoadingMore = true
        updateFooterInList()

        val dao = NotificationMasterApp.getInstance().database.notificationDao()

        viewLifecycleOwner.lifecycleScope.launch {
            val dayStart = nextDayToLoad
            val dayEnd = dayStart + ONE_DAY_MS
            val spec = coreSpec.copy(timeFrom = dayStart, timeTo = dayEnd)

            val data = withContext(Dispatchers.IO) {
                dao.query(spec).first()
            }

            if (data.isNotEmpty()) {
                historicalDays.add(dayStart to data)
            }

            nextDayToLoad = dayStart - ONE_DAY_MS
            checkReachedEnd()
            isLoadingMore = false
            combineAndDisplay()
        }
    }

    private fun checkReachedEnd() {
        val earliest = earliestPostTime ?: run {
            hasReachedEnd = true
            return
        }
        if (nextDayToLoad + ONE_DAY_MS <= earliest) {
            hasReachedEnd = true
        }
    }

    private fun combineAndDisplay() {
        allNotifications = todayNotifications + historicalDays.flatMap { it.second }
        applyFilterAndDisplay()
    }

    private fun updateFooterInList() {
        val currentList = adapter?.currentList ?: return
        val withoutFooter = currentList.filter {
            it !is TimelineItem.LoadingMore && it !is TimelineItem.EndOfTimeline
        }
        adapter?.submitList(withoutFooter + TimelineItem.LoadingMore)
    }

    private fun applyFilterAndDisplay() {
        val binding = _binding ?: return
        val filtered = filterNotifications(allNotifications, currentFilterText)
        if (filtered.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
            refreshCountText(loadedOverride = filtered)
            updateEmptyStateForPermission()
        } else {
            binding.emptyState.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            val database = NotificationMasterApp.getInstance().database
            val notificationDao = database.notificationDao()

            viewLifecycleOwner.lifecycleScope.launch {
                var timelineItems: List<TimelineItem> = if (coreSpec.deduplicate) {
                    buildTimelineItemsWithSimilarCount(filtered, notificationDao)
                } else {
                    buildTimelineItems(filtered)
                }

                if (usesDayPaging()) {
                    val footer = when {
                        isLoadingMore -> TimelineItem.LoadingMore
                        hasReachedEnd -> TimelineItem.EndOfTimeline
                        else -> null
                    }
                    if (footer != null) timelineItems = timelineItems + footer
                }

                adapter?.submitList(timelineItems)
                refreshCountText(loadedOverride = filtered)
            }
        }
    }

    private fun loadedCountForCounter(filtered: List<NotificationEntity>): Int {
        // 統一按 count(spec) 一致的單位：
        // - deduplicate=true → count by notification_key（spec SQL 用 GROUP BY notification_key）
        // - deduplicate=false → DISTINCT id，即 filtered.size
        return if (coreSpec.deduplicate) filtered.distinctBy { it.notificationKey }.size else filtered.size
    }

    private fun refreshCountText(loadedOverride: List<NotificationEntity>? = null) {
        val source = loadedOverride
            ?: filterNotifications(allNotifications, currentFilterText)
        val loaded = loadedCountForCounter(source)
        val text = getString(R.string.timeline_count_format_loaded_total, loaded, totalCount)
        (activity as? MainActivity)?.setToolbarCount(text)
    }

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

    private fun getAppLabel(packageName: String): String {
        val ctx = context ?: return packageName
        return AppLabelCache.getLabel(ctx, packageName)
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

            items.add(TimelineItem.NotificationItem(
                notification = notification,
                isRemoved = removedIds.contains(notification.id)
            ))
        }

        return items
    }

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

            val dayStart = notificationDate
            val dayEnd = dayStart + ONE_DAY_MS
            val similarCount = withContext(Dispatchers.IO) {
                dao.getDeduplicatedCount(notification.contentHash, dayStart, dayEnd)
            }

            items.add(TimelineItem.NotificationItem(
                notification = notification,
                similarCount = similarCount,
                isRemoved = removedIds.contains(notification.id)
            ))
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

    private fun updateTimeBubble() {
        val binding = _binding ?: return
        val rv = binding.recyclerView
        val layoutManager = rv.layoutManager as? LinearLayoutManager ?: return
        val position = layoutManager.findFirstVisibleItemPosition()
        if (position == RecyclerView.NO_POSITION) return

        val item = adapter?.currentList?.getOrNull(position) ?: return
        val dateText = when (item) {
            is TimelineItem.DateHeader -> dateFormat.format(Date(item.date))
            is TimelineItem.NotificationItem -> dateFormat.format(Date(item.notification.postTime))
            is TimelineItem.LoadingMore, is TimelineItem.EndOfTimeline -> return
        }
        binding.timeBubble.text = dateText

        val scrollRange = rv.computeVerticalScrollRange()
        val scrollExtent = rv.computeVerticalScrollExtent()
        val scrollOffset = rv.computeVerticalScrollOffset()
        val maxScroll = scrollRange - scrollExtent
        if (maxScroll <= 0) return

        val fraction = scrollOffset.toFloat() / maxScroll
        val bubbleHeight = binding.timeBubble.height.toFloat()

        val rvLocation = IntArray(2)
        val bubbleParentLocation = IntArray(2)
        rv.getLocationInWindow(rvLocation)
        (binding.timeBubble.parent as? View)?.getLocationInWindow(bubbleParentLocation)
        val rvTop = rvLocation[1] - bubbleParentLocation[1]
        val trackRange = rv.height.toFloat() - bubbleHeight

        binding.timeBubble.translationY = rvTop + trackRange * fraction
    }

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
            }.toTypedArray<CharSequence>()

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
        val action = TimelineFragmentDirections.actionTimelineHomeToTimelineDetail(notification.id)
        findNavController().navigate(action)
    }
}
