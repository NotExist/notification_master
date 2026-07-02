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
import com.notificationmaster.core.filter.Matcher
import com.notificationmaster.core.filter.OrderBy
import com.notificationmaster.data.db.dao.querySync
import com.notificationmaster.data.db.entity.NotificationEventEntity
import com.notificationmaster.data.filter.EventFilterSpec
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

    // === W23z：append 式漸進載入狀態（view-scope）===

    /** 已累積的本體 items（含 DateHeader，不含 footer）— append 不重查前面 */
    private val accumulatedItems = mutableListOf<TimelineItem>()

    /** 已納入 accumulatedItems 的 event id，防 OFFSET 期間頂部新事件造成偏移重複 */
    private val seenIds = HashSet<Long>()

    /** DateHeader 連續性游標：append 新批時不重複插入同日標頭 */
    private var lastDate: Long? = null

    /** 最近一批 query size < limit → DB 已無更多，footer 換 EndOfTimeline */
    private var endReached = false

    /** 一批載入進行中（防 scroll 高頻重複觸發 / 重入） */
    private var isLoading = false

    private val baseSpec: EventFilterSpec by lazy {
        // 對齊 timeline 預設視角：所有 event row（INITIAL/POSTED/UPDATED），不 dedup、
        // event_time DESC；REMOVED event row 由 EventFilterSqlBuilder 統一排除（W1 慣例）
        EventFilterSpec(
            matchers = buildList {
                add(Matcher.Package(args.packageName))
                if (args.channelId.isNotEmpty()) add(Matcher.Channel(args.channelId))
            },
            deduplicate = false,
            orderBy = OrderBy.EventTimeDesc
        )
    }

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

        // W23z：接近底部觸發 append 載入（footer PendingMore「繼續滾動載入更多」對齊此動作）
        binding.recyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || endReached || isLoading) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val distance = lm.itemCount - lm.findLastVisibleItemPosition()
                if (distance > LOAD_MORE_THRESHOLD) return
                loadMore()
            }
        })
    }

    /**
     * W23z：append 式載入。
     * - 首次：載第一批（PAGE_SIZE）
     * - 返回（view 重建，viewModel.loadedCount>0）：一次載回先前展開量，重建累積狀態
     * 之後滑近底部由 [loadMore] 逐批 append，不重查已載入部分。
     */
    private fun loadNotifications() {
        val mode = if (args.channelId.isNotEmpty()) "channel" else "package"
        val target = if (args.channelId.isNotEmpty()) "${args.packageName}/${args.channelId}" else args.packageName
        ProfileLogger.append("Archive", "load start mode=$mode target=$target restore=${viewModel.loadedCount}")

        // view-scope 累積狀態重置（view 重建後 accumulatedItems 為空）。
        // isLoading 必須一併重置：fetch coroutine 跑在 viewLifecycleOwner scope，
        // 進 Detail 時 view 銷毀會取消 in-flight fetch，isLoading 停在 true —
        // 不重置的話返回後 fetchBatch 重入 guard 直接 return，永遠卡載入中。
        accumulatedItems.clear()
        seenIds.clear()
        lastDate = null
        endReached = false
        isLoading = false

        val restoreCount = viewModel.loadedCount
        viewModel.loadedCount = 0
        binding.progressLoading.show()
        // restoreCount>0 → 一次載回展開量（返回場景）；否則首批
        fetchBatch(
            limit = if (restoreCount > 0) restoreCount else ArchiveDetailViewModel.PAGE_SIZE,
            isInitial = true
        )
    }

    /** 滑近底部觸發：append 下一批（offset = 已載量） */
    private fun loadMore() {
        if (isLoading || endReached) return
        adapter.submitList(accumulatedItems + TimelineItem.LoadingMore)
        fetchBatch(limit = ArchiveDetailViewModel.PAGE_SIZE, isInitial = false)
    }

    /**
     * 查一批（offset = viewModel.loadedCount）→ 去重 → enrich → append → submit。
     * query/enrich/build 都在 IO，main 只做 submitList。
     */
    private fun fetchBatch(limit: Int, isInitial: Boolean) {
        if (isLoading) return
        isLoading = true
        val offset = viewModel.loadedCount
        val tStart = System.currentTimeMillis()

        viewLifecycleOwner.lifecycleScope.launch {
            val eventDao = NotificationMasterApp.getInstance().database.notificationEventDao()
            val result = withContext(Dispatchers.IO) {
                val events = eventDao.querySync(baseSpec.copy(limit = limit, offset = offset))
                // OFFSET 期間頂部若有新事件寫入會偏移 → 過濾已納入的 id
                val fresh = events.filter { it.id !in seenIds }
                val displays = enrichEvents(fresh)
                Triple(events.size, fresh, displays)
            }
            val binding = _binding ?: return@launch
            val (rawSize, fresh, displays) = result

            endReached = rawSize < limit
            viewModel.loadedCount = offset + rawSize

            if (isInitial && accumulatedItems.isEmpty() && fresh.isEmpty()) {
                binding.progressLoading.hide()
                binding.emptyState.visibility = View.VISIBLE
                binding.recyclerView.visibility = View.GONE
                isLoading = false
                return@launch
            }

            // append：fresh.id 入集合、按 lastDate 連續性建 items
            fresh.forEach { seenIds.add(it.id) }
            accumulatedItems += buildItemsAppending(displays)

            binding.emptyState.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            val footer = if (endReached) TimelineItem.EndOfTimeline else TimelineItem.PendingMore
            adapter.submitList(accumulatedItems + footer) {
                ProfileLogger.append(
                    "Archive",
                    "batch offset=$offset raw=$rawSize fresh=${fresh.size} " +
                        "accumulated=${accumulatedItems.size} endReached=$endReached " +
                        "took=${System.currentTimeMillis() - tStart}ms"
                )
                val b = _binding ?: return@submitList
                b.progressLoading.hide()
                if (isInitial) pendingScrollRestore?.let {
                    b.recyclerView.layoutManager?.onRestoreInstanceState(it)
                    pendingScrollRestore = null
                }
            }
            isLoading = false
        }
    }

    private suspend fun enrichEvents(events: List<NotificationEventEntity>): List<NotificationDisplay> {
        if (events.isEmpty()) return emptyList()
        val database = NotificationMasterApp.getInstance().database
        return NotificationEnricher.enrich(
            events,
            database.channelDao(),
            database.rankingObservationDao(),
            database.rankingSnapshotDao(),
            database.notificationEventDao()
        )
    }

    /** 用成員 [lastDate] 維持跨批 DateHeader 連續性（append 不重複插同日標頭） */
    private fun buildItemsAppending(notifications: List<NotificationDisplay>): List<TimelineItem> {
        val items = mutableListOf<TimelineItem>()
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

    companion object {
        /** W23z：距底多少 item 內觸發 append 載入 */
        private const val LOAD_MORE_THRESHOLD = 30
    }
}
