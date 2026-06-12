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
import com.notificationmaster.data.db.dao.query
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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

    // === W23b：漸進載入狀態（view-scope，emit 時重算）===

    /** 最近一次 emit 的 query size < limit → DB 已無更多，footer 換 EndOfTimeline */
    private var endReached = false

    /** 擴張已觸發、新 query 尚未 emit（防 scroll 高頻重複 +PAGE_INCREMENT） */
    private var isExpanding = false

    /** 最近一次 submit 的本體 items（不含 footer），擴張時先換 LoadingMore footer 用 */
    private var currentItems: List<TimelineItem> = emptyList()

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

        // W23b：接近底部觸發漸進擴張（footer PendingMore「繼續滾動載入更多」對齊此動作）
        binding.recyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || endReached || isExpanding) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val distance = lm.itemCount - lm.findLastVisibleItemPosition()
                if (distance > LOAD_MORE_THRESHOLD) return
                isExpanding = true
                val target = viewModel.pageSize.value + ArchiveDetailViewModel.PAGE_INCREMENT
                ProfileLogger.append("Archive", "lazyload expand target=$target distance=$distance")
                // 擴張期間 footer 先換 LoadingMore（新 query+enrich 可能需數秒）
                adapter.submitList(currentItems + TimelineItem.LoadingMore)
                viewModel.pageSize.value = target
            }
        })
    }

    private fun loadNotifications() {
        val database = NotificationMasterApp.getInstance().database
        val eventDao = database.notificationEventDao()

        val mode = if (args.channelId.isNotEmpty()) "channel" else "package"
        val target = if (args.channelId.isNotEmpty()) "${args.packageName}/${args.channelId}" else args.packageName
        val tStart = System.currentTimeMillis()
        ProfileLogger.append("Archive", "load start mode=$mode target=$target")

        // 對齊 timeline 預設視角：所有 event row（包含 INITIAL / POSTED / UPDATED），
        // 不 dedup、不過濾 REMOVED isRemoved 屬性、event_time DESC。REMOVED event row
        // 本身由 EventFilterSqlBuilder 統一排除（W1 慣例）。
        //
        // W23b：spec 套 limit 漸進載入 — 單一 package 上萬 events 時不再一次撈全部。
        // pageSize 擴張 → flatMapLatest 重新訂閱（同 TimelineViewModel.allNotifications 模型）。
        val baseSpec = EventFilterSpec(
            matchers = buildList {
                add(Matcher.Package(args.packageName))
                if (args.channelId.isNotEmpty()) add(Matcher.Channel(args.channelId))
            },
            deduplicate = false,
            orderBy = OrderBy.EventTimeDesc
        )

        binding.progressLoading.show()
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pageSize
                .flatMapLatest { size ->
                    ProfileLogger.append("Archive", "subscribe limit=$size")
                    eventDao.query(baseSpec.copy(limit = size)).map { size to it }
                }
                .collectLatest { (limit, events) ->
                    ProfileLogger.append(
                        "Archive",
                        "query emit limit=$limit size=${events.size} " +
                            "since-start=${System.currentTimeMillis() - tStart}ms"
                    )
                    val binding = _binding ?: return@collectLatest
                    endReached = events.size < limit
                    if (events.isEmpty()) {
                        isExpanding = false
                        currentItems = emptyList()
                        binding.progressLoading.hide()
                        binding.emptyState.visibility = View.VISIBLE
                        binding.recyclerView.visibility = View.GONE
                    } else {
                        binding.emptyState.visibility = View.GONE
                        binding.recyclerView.visibility = View.VISIBLE
                        val tEnrich = System.currentTimeMillis()
                        // W23b：enrich + buildTimelineItems 都在 IO（萬筆 item 組裝不佔 main）
                        val items = withContext(Dispatchers.IO) {
                            val database = NotificationMasterApp.getInstance().database
                            val displays = NotificationEnricher.enrich(
                                events,
                                database.channelDao(),
                                database.rankingObservationDao(),
                                database.rankingSnapshotDao(),
                                database.notificationEventDao()
                            )
                            buildTimelineItems(displays)
                        }
                        val tSubmit = System.currentTimeMillis()
                        currentItems = items
                        isExpanding = false
                        val footer = if (endReached) TimelineItem.EndOfTimeline else TimelineItem.PendingMore
                        adapter.submitList(items + footer) {
                            ProfileLogger.append(
                                "Archive",
                                "submitList done items=${items.size} endReached=$endReached " +
                                    "enrich+build=${tSubmit - tEnrich}ms commit=${System.currentTimeMillis() - tSubmit}ms"
                            )
                            val b = _binding ?: return@submitList
                            b.progressLoading.hide()
                            pendingScrollRestore?.let {
                                b.recyclerView.layoutManager?.onRestoreInstanceState(it)
                                pendingScrollRestore = null
                            }
                        }
                    }
                }
        }
    }

    private fun buildTimelineItems(notifications: List<NotificationDisplay>): List<TimelineItem> {
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

    companion object {
        /** W23b：距底多少 item 內觸發漸進擴張 */
        private const val LOAD_MORE_THRESHOLD = 30
    }
}
