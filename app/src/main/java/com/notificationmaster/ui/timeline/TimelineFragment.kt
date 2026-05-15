package com.notificationmaster.ui.timeline

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
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
import com.notificationmaster.core.filter.ActionType
import com.notificationmaster.core.filter.Rule
import com.notificationmaster.core.filter.RuleAction
import com.notificationmaster.core.filter.RuleEngine
import com.notificationmaster.core.filter.RuleRepository
import com.notificationmaster.data.filter.EventFilterSpec
import com.notificationmaster.data.filter.toFilterSpec
import com.notificationmaster.databinding.FragmentTimelineBinding
import com.notificationmaster.service.NotificationCaptureService
import com.notificationmaster.ui.common.NotificationDisplay
import com.notificationmaster.ui.filter.FilterRuleDialogHelper
import com.notificationmaster.ui.filter.CalendarPickerLauncher
import com.notificationmaster.ui.filter.SoundPickerLauncher
import com.notificationmaster.ui.main.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 時間軸 Fragment
 *
 * Plan 2 §J 抖動修復後：所有篩選與載入狀態移到 [TimelineViewModel]，view 重建時直接 collect 既有
 * StateFlow，counter 不會閃 0、列表立即還原。scroll position 由 ViewModel 持久化（透過 SavedStateHandle）。
 *
 * 篩選：
 * - 核心 chip：「去重」boolean（chip_deduplicated）
 * - LIST_FILTER rule chips：單選套用整套 spec；長按開選單（rename / delete / 建立 widget）
 * - + chip 開啟 BottomSheet 自訂篩選
 *
 * spec 為「全部」或「僅去重」時走天分頁漸進載入（ViewModel.loadNextDay），其餘走全域 spec 查詢。
 *
 * Plan 2 Phase 9：list 接 [NotificationDisplay]（NotificationEventEntity + snapshot），
 * nav 進 Detail 傳 anchorEventId（events PK；Detail 內以 NotificationDisplay 攤平渲染）。
 */
class TimelineFragment : Fragment() {

    private var _binding: FragmentTimelineBinding? = null
    private val binding get() = _binding!!

    private val soundPicker = SoundPickerLauncher(this)
    private val calendarPicker = CalendarPickerLauncher(this)

    private val viewModel: TimelineViewModel by viewModels()

    private var adapter: TimelineAdapter? = null

    /** view 剛建立時要還原一次 scroll 位置；submitList 完成後消費掉 */
    private var pendingScrollRestore: Parcelable? = null

    /** 是否正由程式調整 chip 狀態（避免 listener re-entrancy） */
    private var suppressChipListener = false

    private val dateFormat = SimpleDateFormat("yyyy年M月d日 EEEE", Locale.getDefault())
    private var bubbleHideRunnable: Runnable? = null
    private var wasPermissionGranted = false

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

        // 同步 chip 視覺狀態到 ViewModel 持久化的選擇
        applyChipStateFromViewModel()
        // view 剛建立時準備一次 scroll 還原（submitList callback 內消費）
        pendingScrollRestore = viewModel.scrollState

        observeViewModel()
        handleIncomingIntent()
    }

    override fun onResume() {
        super.onResume()
        val isGranted = NlsConnectionManager.isNlsEnabled(requireContext())
        Log.d(TAG, "onResume: isGranted=$isGranted, wasGranted=$wasPermissionGranted, " +
            "serviceConnected=${NotificationCaptureService.isConnected}")
        if (isGranted != wasPermissionGranted) {
            wasPermissionGranted = isGranted
            updateEmptyStateForPermission()
            if (isGranted) viewModel.loadNotifications()
        }
        // onResume 可能因 MainActivity.onNewIntent 而觸發，重新檢查 Intent
        handleIncomingIntent()
    }

    override fun onPause() {
        super.onPause()
        // 進入 Detail / 切到其他 tab 前先保留 scroll state，回來時還原到原位
        binding.recyclerView.layoutManager?.onSaveInstanceState()?.let {
            viewModel.scrollState = it
        }
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
                onItemClick = { display -> navigateToDetail(display) },
                onSimilarClick = { display -> showSimilarNotifications(display) },
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
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                updateTimeBubble()
                showAndScheduleHideBubble()

                if (viewModel.usesDayPaging()) {
                    val layoutManager = rv.layoutManager as? LinearLayoutManager ?: return
                    val totalItemCount = layoutManager.itemCount
                    val lastVisible = layoutManager.findLastVisibleItemPosition()
                    if (totalItemCount - lastVisible <= 5 &&
                        !viewModel.isLoadingMore.value &&
                        !viewModel.hasReachedEnd.value
                    ) {
                        viewModel.loadNextDay()
                    }
                }
            }
        })
    }

    private fun setupFilterInput() {
        binding.editFilter.setText(viewModel.filterText.value)
        binding.editFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.setFilterText(s?.toString().orEmpty())
            }
        })
    }

    private fun setupFilterChips() {
        binding.chipDeduplicated.setOnClickListener {
            if (suppressChipListener) return@setOnClickListener
            // disabled 狀態下不會觸發；rule 模式由 rule chip 點擊取消
            // Phase 15：不再強制 SwipeRefresh.isRefreshing = true；progress_loading 光條
            // 由 awaitingInitialData + LoadOrigin.SYSTEM 統一控制
            viewModel.setDedupChecked(binding.chipDeduplicated.isChecked)
        }
    }

    private fun setupRuleChips() {
        viewLifecycleOwner.lifecycleScope.launch {
            RuleEngine.triggerFlow.collectLatest {
                if (_binding == null) return@collectLatest
                renderRuleChips()
            }
        }
        renderRuleChips()
        binding.chipAddPreset.setOnClickListener {
            openListFilterEditor(initial = EventFilterSpec.All, editingRuleId = null)
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

        val rules = RuleEngine.getListFilterRules()
            .sortedWith(compareByDescending<Rule> { it.isBuiltIn }.thenBy { it.createdAt })
        val inflater = LayoutInflater.from(requireContext())
        val chipSpacing = (8 * resources.displayMetrics.density).toInt()
        val activeRuleId = viewModel.activeRuleId.value
        for ((i, rule) in rules.withIndex()) {
            val chip = inflater.inflate(R.layout.chip_preset, group, false) as Chip
            (chip.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.marginStart =
                if (i == 0) 0 else chipSpacing
            chip.text = ruleDisplayName(rule)
            chip.tag = rule.id
            chip.isChecked = (activeRuleId == rule.id)
            chip.setOnClickListener {
                if (suppressChipListener) return@setOnClickListener
                // Phase 15：不再強制 SwipeRefresh.isRefreshing；走 SYSTEM origin 由光條顯示
                if (chip.isChecked) {
                    viewModel.applyRule(rule)
                } else {
                    viewModel.deactivateRule()
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
        // 重 render 後同步勾選狀態與 dedup chip enabled
        applyChipStateFromViewModel()
    }

    /** 顯示名：內建 rule 用 strings 對照；user-defined 用 rule.name；fallback id */
    private fun ruleDisplayName(rule: Rule): String = when (rule.id) {
        RuleRepository.builtInRuleIdAudible() -> getString(R.string.preset_recent_audible)
        RuleRepository.builtInRuleIdHeadsup() -> getString(R.string.preset_recent_headsup)
        RuleRepository.builtInRuleIdDismissed() -> getString(R.string.preset_recent_dismissed)
        else -> rule.name ?: rule.id.take(8)
    }

    /**
     * 從 ViewModel 狀態同步 chip 視覺：activeRuleId 對應的 chip checked；dedup chip 在 rule
     * 模式下顯示 spec 的 dedup 值並 disable。
     */
    private fun applyChipStateFromViewModel() {
        suppressChipListener = true
        try {
            val group = binding.chipGroupPresets
            val activeRuleId = viewModel.activeRuleId.value
            for (i in 0 until group.childCount) {
                val c = group.getChildAt(i) as? Chip ?: continue
                if (c.id == R.id.chip_add_preset) continue
                c.isChecked = (c.tag == activeRuleId)
            }
            binding.chipDeduplicated.isChecked = viewModel.dedupChecked.value
            val ruleMode = activeRuleId != null
            binding.chipDeduplicated.isEnabled = !ruleMode
            binding.chipDeduplicated.alpha = if (ruleMode) 0.4f else 1f
        } finally {
            suppressChipListener = false
        }
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
            .setTitle(ruleDisplayName(rule))
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
                if (viewModel.activeRuleId.value == rule.id) {
                    viewModel.deactivateRule()
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

    private fun openListFilterEditor(initial: EventFilterSpec, editingRuleId: String?) {
        val existingListFilter = editingRuleId?.let {
            (RuleEngine.getRule(it)?.action as? RuleAction.ListFilter)
        } ?: RuleAction.ListFilter(
            orderBy = initial.orderBy,
            limit = initial.limit,
            deduplicate = initial.deduplicate
        )
        FilterRuleDialogHelper.showAddRuleDialog(
            context = requireContext(),
            widgetMode = true,
            existingWidgetMatchers = initial.matchers,
            existingWidgetListFilter = existingListFilter,
            existingWidgetLabel = null,
            onListFilterReady = { matchers, listFilter, _ ->
                if (editingRuleId != null) {
                    val existing = RuleEngine.getRule(editingRuleId) ?: return@showAddRuleDialog
                    val updated = existing.copy(matchers = matchers, action = listFilter)
                    RuleRepository.updateRule(requireContext(), updated)
                    if (viewModel.activeRuleId.value == editingRuleId) viewModel.applyRule(updated)
                    renderRuleChips()
                } else {
                    promptNewRuleName(matchers, listFilter)
                }
            }
        )
    }

    private fun promptNewRuleName(
        matchers: List<com.notificationmaster.core.filter.Matcher>,
        listFilter: RuleAction.ListFilter
    ) {
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
                val rule = Rule(name = name, matchers = matchers, action = listFilter)
                RuleRepository.addRule(requireContext(), rule)
                renderRuleChips()
                viewModel.applyRule(rule)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

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
        viewModel.applyRule(rule)
        return true
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            // Phase 21：spinner 由 NotificationCaptureService.isProcessingInitial 接管
            // 若 onRefresh 沒觸發 service INITIAL 處理（NLS 沒授權 / service 未連線），
            // service signal 不會變動 → 需手動關 spinner 防卡住
            val willTriggerService = wasPermissionGranted && NotificationCaptureService.isConnected
            if (!willTriggerService) binding.swipeRefresh.isRefreshing = false

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
            viewModel.loadNotifications()
        }
    }

    private fun observeViewModel() {
        // 主資料流：displayedNotifications + removedIds + filterText + isLoadingMore + hasReachedEnd
        // Phase 22：observe displayedNotifications（client-side overlay 後的 list），不再是 base raw events。
        // 切換 chip 時 displayedNotifications 立即 re-emit、不重查 DB。
        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                combine(
                    viewModel.displayedNotifications,
                    viewModel.removedIds,
                    viewModel.filterText,
                    viewModel.coreSpec,
                    viewModel.awaitingInitialData
                ) { allList, removed, filterText, spec, awaiting ->
                    arrayOf<Any?>(allList, removed, filterText, spec, awaiting)
                },
                viewModel.isLoadingMore,
                viewModel.hasReachedEnd
            ) { core, _, _ ->
                @Suppress("UNCHECKED_CAST")
                ListRenderInput(
                    allNotifications = core[0] as List<NotificationDisplay>,
                    removedIds = core[1] as Set<String>,
                    filterText = core[2] as String,
                    spec = core[3] as EventFilterSpec,
                    awaiting = core[4] as Boolean
                )
            }.collectLatest { input ->
                if (_binding == null) return@collectLatest
                renderList(input)
            }
        }

        // counter：totalCount / awaitingInitialData / 當前篩選後筆數
        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                viewModel.displayedNotifications,
                viewModel.totalCount,
                viewModel.filterText,
                viewModel.coreSpec,
                viewModel.awaitingInitialData
            ) { all, total, text, spec, awaiting ->
                CounterInput(all, total, text, spec, awaiting)
            }.collectLatest { input ->
                if (_binding == null) return@collectLatest
                renderCounter(input)
            }
        }

        // Phase 21：兩個 indicator 對應兩種實質性不同事件
        // - DAO 載入（query DB / Flow re-emit）→ progress_loading 光條（快、< 1 秒）
        // - Service INITIAL 處理（mutex 內序列寫入）→ SwipeRefresh 圓圈（慢、5-30 秒）
        // 兩者獨立顯示；同時出現也合理（DAO 第一次 emit 後光條關，圓圈持續到 service 完成）
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.awaitingInitialData.collectLatest { awaiting ->
                if (_binding == null) return@collectLatest
                val b = _binding ?: return@collectLatest
                if (awaiting) b.progressLoading.show() else b.progressLoading.hide()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            NotificationCaptureService.isProcessingInitial.collectLatest { processing ->
                if (_binding == null) return@collectLatest
                _binding?.swipeRefresh?.isRefreshing = processing
            }
        }
    }

    private data class ListRenderInput(
        val allNotifications: List<NotificationDisplay>,
        val removedIds: Set<String>,
        val filterText: String,
        val spec: EventFilterSpec,
        val awaiting: Boolean
    )

    private data class CounterInput(
        val allNotifications: List<NotificationDisplay>,
        val totalCount: Int,
        val filterText: String,
        val spec: EventFilterSpec,
        val awaiting: Boolean
    )

    private fun renderList(input: ListRenderInput) {
        val binding = _binding ?: return
        val filtered = filterNotifications(input.allNotifications, input.filterText)
        if (filtered.isEmpty()) {
            // 載入中（首筆主資料未到）保留現狀，不切到 emptyState 避免閃爍
            if (input.awaiting) return
            binding.emptyState.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
            updateEmptyStateForPermission()
            adapter?.submitList(emptyList())
            return
        }
        binding.emptyState.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            val eventDao = NotificationMasterApp.getInstance().database.notificationEventDao()
            var timelineItems: List<TimelineItem> = if (input.spec.deduplicate) {
                buildTimelineItemsWithSimilarCount(filtered, input.removedIds, eventDao)
            } else {
                buildTimelineItems(filtered, input.removedIds)
            }
            if (viewModel.usesDayPaging()) {
                val footer = when {
                    viewModel.isLoadingMore.value -> TimelineItem.LoadingMore
                    viewModel.hasReachedEnd.value -> TimelineItem.EndOfTimeline
                    else -> null
                }
                if (footer != null) timelineItems = timelineItems + footer
            }
            adapter?.submitList(timelineItems) {
                pendingScrollRestore?.let {
                    binding.recyclerView.layoutManager?.onRestoreInstanceState(it)
                    pendingScrollRestore = null
                }
            }
        }
    }

    private fun renderCounter(input: CounterInput) {
        // Phase 22：displayedNotifications 已包含 client-side dedup + rule predicate，
        // counter 只需把 text filter 套上計算即可，不再額外 distinctBy。
        val filtered = filterNotifications(input.allNotifications, input.filterText)
        val loadedDisplay: String = if (input.awaiting) {
            getString(R.string.timeline_count_loading_placeholder)
        } else {
            filtered.size.toString()
        }
        val text = getString(
            R.string.timeline_count_format_loaded_total,
            loadedDisplay,
            input.totalCount
        )
        (activity as? MainActivity)?.setToolbarCount(text)
    }

    private fun filterNotifications(
        notifications: List<NotificationDisplay>,
        query: String
    ): List<NotificationDisplay> {
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

    private fun buildTimelineItems(
        notifications: List<NotificationDisplay>,
        removedIds: Set<String>
    ): List<TimelineItem> {
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
                isRemoved = removedIds.contains(notification.notificationKey)
            ))
        }
        return items
    }

    private suspend fun buildTimelineItemsWithSimilarCount(
        notifications: List<NotificationDisplay>,
        removedIds: Set<String>,
        eventDao: com.notificationmaster.data.db.dao.NotificationEventDao
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
                eventDao.getDeduplicatedCount(notification.contentHash, dayStart, dayEnd)
            }
            items.add(TimelineItem.NotificationItem(
                notification = notification,
                similarCount = similarCount,
                isRemoved = removedIds.contains(notification.notificationKey)
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

    private fun showSimilarNotifications(notification: NotificationDisplay) {
        val eventDao = NotificationMasterApp.getInstance().database.notificationEventDao()
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val dayStart = getStartOfDay(notification.postTime)
        val dayEnd = dayStart + ONE_DAY_MS
        viewLifecycleOwner.lifecycleScope.launch {
            val similarEvents = withContext(Dispatchers.IO) {
                eventDao.getSimilarEvents(notification.contentHash, dayStart, dayEnd)
            }
            if (_binding == null) return@launch
            if (similarEvents.size <= 1) return@launch

            val similar = withContext(Dispatchers.IO) { similarEvents.map(NotificationDisplay::from) }
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

    private fun navigateToDetail(display: NotificationDisplay) {
        val action = TimelineFragmentDirections.actionTimelineHomeToTimelineDetail(
            notificationKey = display.notificationKey,
            anchorEventId = display.eventId
        )
        findNavController().navigate(action)
    }
}
