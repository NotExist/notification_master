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
import com.google.android.material.snackbar.Snackbar
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.R
import com.notificationmaster.core.debug.ProfileLogger
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
            if (isGranted) viewModel.refresh()
        }
        // onResume 可能因 MainActivity.onNewIntent 而觸發，重新檢查 Intent
        handleIncomingIntent()
        // Phase 24 Q5：onPause 把 toolbar counter 清空後，collect 不會重新 emit
        // （StateFlow value 未變），主動讀 ViewModel value 重渲染一次
        rerenderToolbarCounter()
    }

    private fun rerenderToolbarCounter() {
        if (_binding == null) return
        renderCounter(
            CounterInput(
                allNotifications = viewModel.displayedNotifications.value,
                totalCount = viewModel.totalCount.value,
                filterText = viewModel.filterText.value,
                spec = viewModel.coreSpec.value,
                state = viewModel.state.value
            )
        )
    }

    override fun onPause() {
        super.onPause()
        // 進入 Detail / 切到其他 tab 前先保留 scroll state，回來時還原到原位
        binding.recyclerView.layoutManager?.onSaveInstanceState()?.let {
            viewModel.scrollState = it
        }
        bubbleHideRunnable?.let { _binding?.timeBubble?.removeCallbacks(it) }
        _binding?.timeBubble?.visibility = View.GONE
        (activity as? MainActivity)?.apply {
            setToolbarCount(null)
            setToolbarCountClickListener(null)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        (activity as? MainActivity)?.apply {
            setToolbarCount(null)
            setToolbarCountClickListener(null)
        }
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
                    // Phase 25：prefetch threshold 從 5 → 30，配合小 PAGE_INCREMENT (100)
                    // Phase 26：guard 改讀 viewModel.state — single source of truth，
                    // 只有 Ready(canLoadMore) 才觸發。LoadingMore / EndReached / Initial /
                    // Empty / Error 都不會 fire，杜絕 D 的反覆觸發。
                    val st = viewModel.state.value
                    if (totalItemCount - lastVisible <= 30 &&
                        st is TimelineLoadState.Ready && st.canLoadMore
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
            // Phase 22+：純 client-side overlay，不觸發任何 indicator
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
        RuleRepository.builtInRuleIdAudible() -> getString(R.string.preset_audible)
        RuleRepository.builtInRuleIdHeadsup() -> getString(R.string.preset_headsup)
        RuleRepository.builtInRuleIdDismissed() -> getString(R.string.preset_dismissed)
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
            // Phase 26：spinner 由 viewModel.state.is InitialLoading 接管（冷啟 < 1s 黑屏避免器）
            // 下拉手勢自動觸發的內建 spinner 立即關閉，避免跟 state-driven indicator 衝突。
            // 下拉觸發的視覺反饋交給 progress_loading 光條（service.isProcessingInitial）
            binding.swipeRefresh.isRefreshing = false

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
            viewModel.refresh()
        }
    }

    private fun observeViewModel() {
        // Phase 26：state 為單一 source of truth。Fragment 用一次 when 映射所有 indicator。
        // ListRenderInput 帶 state 一起傳給 renderList/renderCounter，避免在 render 內部讀 state.value
        // 跟 list 在不同時間點 emit 而錯位。
        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                viewModel.displayedNotifications,
                viewModel.removedIds,
                viewModel.filterText,
                viewModel.coreSpec,
                viewModel.state
            ) { allList, removed, filterText, spec, state ->
                ListRenderInput(
                    allNotifications = allList,
                    removedIds = removed,
                    filterText = filterText,
                    spec = spec,
                    state = state
                )
            }.collectLatest { input ->
                if (_binding == null) return@collectLatest
                try {
                    renderList(input)
                } catch (e: Exception) {
                    Log.e(TAG, "renderList failed", e)
                }
            }
        }

        // counter：用 state + totalCount + displayedNotifications
        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                viewModel.displayedNotifications,
                viewModel.totalCount,
                viewModel.filterText,
                viewModel.coreSpec,
                viewModel.state
            ) { all, total, text, spec, state ->
                CounterInput(all, total, text, spec, state)
            }.collectLatest { input ->
                if (_binding == null) return@collectLatest
                renderCounter(input)
            }
        }

        // Phase 26 indicator 對應（單一映射，無重複）：
        // - progress_loading 光條（頂部） → service.isProcessingInitial（大事件 5-30s）
        // - SwipeRefresh 圓圈 → state is InitialLoading（冷啟 < 1s 黑屏避免器）
        // - LoadingMore footer → state is LoadingMore（lazyload 中，list 內慣例）
        // - EndOfTimeline footer → state is EndReached（list 內靜態指示）
        // - emptyState → state is EmptyDb（DB 真空）
        // - Snackbar → state is Error
        viewLifecycleOwner.lifecycleScope.launch {
            NotificationCaptureService.isProcessingInitial.collectLatest { processing ->
                if (_binding == null) return@collectLatest
                val b = _binding ?: return@collectLatest
                if (processing) b.progressLoading.show() else b.progressLoading.hide()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collectLatest { st ->
                // Phase 29：state transition log，定位 cold start 為何卡 InitialLoading
                ProfileLogger.append("Fragment", "state=${st::class.simpleName}")
                if (_binding == null) return@collectLatest
                _binding?.swipeRefresh?.isRefreshing = st is TimelineLoadState.InitialLoading
                if (st is TimelineLoadState.Error) {
                    showErrorSnackbar(st.cause)
                }
            }
        }
    }

    private var lastErrorSnackbar: Snackbar? = null

    private fun showErrorSnackbar(cause: Throwable) {
        val binding = _binding ?: return
        // Phase 27：OOM 訊息友善化 — raw exception message 對 user 無意義
        val msg = if (cause is OutOfMemoryError) {
            getString(R.string.timeline_error_oom)
        } else {
            getString(R.string.timeline_error_snackbar, cause.message ?: cause.javaClass.simpleName)
        }
        lastErrorSnackbar?.dismiss()
        lastErrorSnackbar = Snackbar.make(
            binding.root,
            msg,
            Snackbar.LENGTH_LONG
        ).also { sb ->
            sb.setAction(R.string.timeline_error_dismiss) {
                viewModel.refresh()
                sb.dismiss()
            }
            sb.show()
        }
    }

    private data class ListRenderInput(
        val allNotifications: List<NotificationDisplay>,
        val removedIds: Set<String>,
        val filterText: String,
        val spec: EventFilterSpec,
        val state: TimelineLoadState
    )

    private data class CounterInput(
        val allNotifications: List<NotificationDisplay>,
        val totalCount: Int?,
        val filterText: String,
        val spec: EventFilterSpec,
        val state: TimelineLoadState
    )

    /**
     * Phase 31i：改 suspend 函式，移除內部 `viewLifecycleOwner.lifecycleScope.launch`。
     *
     * 之前 renderList 是 non-suspend，內部用 launch 啟新 coroutine 做 IO + submitList。
     * 外層 `combine(...).collectLatest { renderList(it) }` 的 collectLatest 只 cancel
     * collect block，不 cancel renderList 內 launch 出的 coroutine → lazyload 期間快速
     * 多次 emit 會啟多個並行 coroutine，後啟動但先完成的 submitList 會被先啟動但後完成
     * 的覆蓋（race），導致「新內容沒呈現 + footer 閃一下消失 + 鎖屏再亮才出現」。
     *
     * suspend 化後 IO 計算與 submitList 都在 collectLatest 控制下，最新 input 的處理
     * 自然取代舊的。
     */
    private suspend fun renderList(input: ListRenderInput) {
        val binding = _binding ?: return
        val filtered = filterNotifications(input.allNotifications, input.filterText)

        if (filtered.isEmpty()) {
            // Phase 26：emptyState 只在 state 確認 DB 空時顯示；
            // InitialLoading / LoadingMore 期間保留 list 渲染（avoid emptyState 閃爍）；
            // Error 維持現有 list（Snackbar 已給反饋，不切空頁）
            when (input.state) {
                is TimelineLoadState.EmptyDb -> {
                    binding.emptyState.visibility = View.VISIBLE
                    binding.recyclerView.visibility = View.GONE
                    updateEmptyStateForPermission()
                    adapter?.submitList(emptyList())
                }
                else -> {
                    // 不切到 emptyState，避免 InitialLoading 時閃白頁
                    return
                }
            }
            return
        }
        binding.emptyState.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE

        val eventDao = NotificationMasterApp.getInstance().database.notificationEventDao()
        // Phase 26：buildTimelineItemsWithSimilarCount 改為單一 IO 包覆（vs 之前每 item 一次
        // withContext(IO) 切換），長 list 不再 N 次 thread hop 拖累 main thread。
        // Phase 31j：similarCount 與 dedup chip 脫離連動，無論 dedup ON/OFF 都計算
        // 「跨通知同內容」筆數（不同 notification_key 但同 content_hash）。
        var timelineItems: List<TimelineItem> = withContext(Dispatchers.IO) {
            buildTimelineItemsWithSimilarCountInIo(filtered, input.removedIds, eventDao)
        }
        // Phase 26：footer 條件用 state（單一 source of truth）
        val footer = when (input.state) {
            is TimelineLoadState.LoadingMore -> TimelineItem.LoadingMore
            is TimelineLoadState.EndReached -> TimelineItem.EndOfTimeline
            else -> null
        }
        if (footer != null) timelineItems = timelineItems + footer

        ProfileLogger.append(
            "Fragment",
            "renderList submit state=${input.state::class.simpleName} " +
                "displays=${input.allNotifications.size} items=${timelineItems.size} footer=$footer"
        )
        adapter?.submitList(timelineItems) {
            pendingScrollRestore?.let {
                binding.recyclerView.layoutManager?.onRestoreInstanceState(it)
                pendingScrollRestore = null
            }
        }
    }

    private fun renderCounter(input: CounterInput) {
        // Phase 22：displayedNotifications 已包含 client-side dedup + rule predicate，
        // counter 只需把 text filter 套上計算即可，不再額外 distinctBy。
        val filtered = filterNotifications(input.allNotifications, input.filterText)
        // Phase 26：loadingPlaceholder 在 InitialLoading 顯示；其他 state（含 LoadingMore）都直接顯示數字
        val loadedDisplay: String = if (input.state is TimelineLoadState.InitialLoading) {
            getString(R.string.timeline_count_loading_placeholder)
        } else {
            filtered.size.toString()
        }
        // totalCount 可能還是 null（InitialLoading 期間 count Flow 未 emit），顯示 0 避免 format 失敗
        val text = getString(
            R.string.timeline_count_format_loaded_total,
            loadedDisplay,
            input.totalCount ?: 0
        )
        (activity as? MainActivity)?.setToolbarCount(text)
        // Phase 31f：點 toolbar counter 跳載入詳情對話框
        (activity as? MainActivity)?.setToolbarCountClickListener { showLoadDetailDialog() }
    }

    /**
     * Phase 31f：載入詳情對話框 — 2x2 表格呈現「已載入 / 總數」× 「去重 / 未去重」。
     *
     * Phase 31h：四格直接對應 ViewModel 既有的 dedup-aware 對偶資料：
     * - 已載入 × 去重 = displayedNotifications.size
     * - 已載入 × 未去重 = allNotifications.size
     * - 總數 × 去重 = totalUniqueCount
     * - 總數 × 未去重 = totalRawCount
     */
    private fun showLoadDetailDialog() {
        if (_binding == null) return
        val view = layoutInflater.inflate(R.layout.dialog_timeline_load_detail, null)
        val placeholder = getString(R.string.timeline_load_detail_value_placeholder)
        view.findViewById<android.widget.TextView>(R.id.text_loaded_dedup).text =
            viewModel.displayedNotifications.value.size.toString()
        view.findViewById<android.widget.TextView>(R.id.text_loaded_raw).text =
            viewModel.allNotifications.value.size.toString()
        view.findViewById<android.widget.TextView>(R.id.text_total_dedup).text =
            viewModel.totalUniqueCount.value?.toString() ?: placeholder
        view.findViewById<android.widget.TextView>(R.id.text_total_raw).text =
            viewModel.totalRawCount.value?.toString() ?: placeholder
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.timeline_load_detail_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()
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

    /**
     * Phase 26：呼叫端負責 `withContext(Dispatchers.IO)`，本函式內不再 per-item 切 thread。
     * 對 100 筆 list 從 N 次 dispatcher hop 變 0 次，避免 main thread 等候 IO pool 排程。
     * 仍須是 suspend — `getDeduplicatedCount` 是 DAO suspend method。
     *
     * Phase 31j：「+N 同內容」與 dedup chip 脫離連動，唯一 list builder（移除 non-similar 版本）。
     */
    private suspend fun buildTimelineItemsWithSimilarCountInIo(
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
            val similarCount = eventDao.getDeduplicatedCount(
                notification.contentHash, dayStart, dayEnd
            )
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

    /**
     * Phase 31j：dialog 改寫 — 排除自己、標示「列表內 / 列表外」。
     * 「列表內」= 對應 key 已在 ViewModel.displayedNotifications.value（user 當下可滾到）；
     * 「列表外」= 在當天但不在當前 displays（因為被 dedup 或不在 page 範圍）。
     */
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
            // 排除自己（同 notification_key），剩下才是「其他同內容通知」
            val others = similarEvents.filter { it.notificationKey != notification.notificationKey }
            if (others.isEmpty()) return@launch

            val displayedKeys = viewModel.displayedNotifications.value
                .map { it.notificationKey }
                .toSet()
            val similar = withContext(Dispatchers.IO) { others.map(NotificationDisplay::from) }
            val inListLabel = getString(R.string.similar_in_list)
            val offListLabel = getString(R.string.similar_off_list)
            val items = similar.map { n ->
                val appLabel = getAppLabel(n.packageName)
                val time = timeFormat.format(Date(n.postTime))
                val locationLabel = if (n.notificationKey in displayedKeys) inListLabel else offListLabel
                "$appLabel · $time · $locationLabel\n${n.notificationKey}"
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
