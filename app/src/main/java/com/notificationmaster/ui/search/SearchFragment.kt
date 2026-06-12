package com.notificationmaster.ui.search

import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.notificationmaster.databinding.FragmentSearchBinding
import com.notificationmaster.ui.common.ListFooterAdapter
import com.notificationmaster.ui.filter.CalendarPickerLauncher
import com.notificationmaster.ui.filter.FilterRuleDialogHelper
import com.notificationmaster.ui.filter.SoundPickerLauncher

/**
 * 搜尋頁面 Fragment
 */
class SearchFragment : Fragment() {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private val soundPicker = SoundPickerLauncher(this)
    private val calendarPicker = CalendarPickerLauncher(this)

    private val viewModel: SearchViewModel by viewModels()
    private lateinit var notificationAdapter: NotificationAdapter

    /** W23p：lazyload footer（與 timeline footer 同視覺語彙），ConcatAdapter 附掛 */
    private val footerAdapter = ListFooterAdapter()

    /** view 剛建立時要還原一次 scroll 位置；submitList 完成後消費掉 */
    private var pendingScrollRestore: Parcelable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupSearchInput()
        // view 剛建立時準備 scroll 還原（observeResults 內 submitList callback 消費）
        pendingScrollRestore = viewModel.scrollState
        observeResults()

        // 首次進入：接收外部帶入的 query
        if (viewModel.query.value.isNullOrBlank()) {
            val externalQuery = arguments?.getString("query")
            if (!externalQuery.isNullOrBlank()) {
                binding.editSearch.setText(externalQuery)
                viewModel.search(externalQuery)
            }
        } else {
            // 返回時還原查詢文字
            binding.editSearch.setText(viewModel.query.value)
        }
    }

    override fun onPause() {
        super.onPause()
        // 進入 Detail / 切到其他 tab 前保留 scroll state，回來時還原到原位
        binding.recyclerView.layoutManager?.onSaveInstanceState()?.let {
            viewModel.scrollState = it
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupRecyclerView() {
        notificationAdapter = NotificationAdapter(
            onItemClick = { display ->
                val action = SearchFragmentDirections.actionSearchHomeToSearchDetail(
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

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = ConcatAdapter(notificationAdapter, footerAdapter)
            // W23p：滑近底部觸發擴增查詢（loadMore 內有 isSearching / endReached guard）
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    val lm = rv.layoutManager as? LinearLayoutManager ?: return
                    val lastVisible = lm.findLastVisibleItemPosition()
                    if (lastVisible >= notificationAdapter.itemCount - LOAD_MORE_THRESHOLD) {
                        viewModel.loadMore()
                    }
                }
            })
        }
    }

    private fun setupSearchInput() {
        binding.editSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                viewModel.search(binding.editSearch.text?.toString() ?: "")
                true
            } else {
                false
            }
        }
    }

    private fun observeResults() {
        // W23l：搜尋進行中光條（與 timeline / archive「等待資料」語彙統一）
        viewModel.isSearching.observe(viewLifecycleOwner) { searching ->
            val binding = _binding ?: return@observe
            if (searching) binding.progressLoading.show() else binding.progressLoading.hide()
            updateFooter()
        }
        viewModel.endReached.observe(viewLifecycleOwner) { updateFooter() }
        viewModel.results.observe(viewLifecycleOwner) { results ->
            val binding = _binding ?: return@observe
            notificationAdapter.submitList(results) {
                pendingScrollRestore?.let {
                    binding.recyclerView.layoutManager?.onRestoreInstanceState(it)
                    pendingScrollRestore = null
                }
                updateFooter()
            }
            binding.textEmpty.visibility =
                if (results.isEmpty() && !viewModel.query.value.isNullOrBlank()) View.VISIBLE
                else View.GONE
        }
    }

    /**
     * W23p：footer 狀態推導 — 有結果才有 footer；擴增查詢中顯示 Loading（橘圈），
     * 已撈完顯示 End，可再載顯示 Pending。
     */
    private fun updateFooter() {
        val hasResults = !viewModel.results.value.isNullOrEmpty()
        footerAdapter.state = when {
            !hasResults -> ListFooterAdapter.State.NONE
            viewModel.isSearching.value == true -> ListFooterAdapter.State.LOADING
            viewModel.endReached.value == true -> ListFooterAdapter.State.END
            else -> ListFooterAdapter.State.PENDING
        }
    }

    private companion object {
        /** 距清單底部 N 筆內觸發 loadMore（同 ArchiveDetail 的觸發視角） */
        const val LOAD_MORE_THRESHOLD = 10
    }
}
