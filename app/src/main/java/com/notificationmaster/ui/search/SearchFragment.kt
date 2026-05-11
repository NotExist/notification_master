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
import androidx.recyclerview.widget.LinearLayoutManager
import com.notificationmaster.databinding.FragmentSearchBinding
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
                val action = SearchFragmentDirections.actionSearchHomeToSearchDetail(display.eventId)
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
            adapter = notificationAdapter
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
        viewModel.results.observe(viewLifecycleOwner) { results ->
            val binding = _binding ?: return@observe
            notificationAdapter.submitList(results) {
                pendingScrollRestore?.let {
                    binding.recyclerView.layoutManager?.onRestoreInstanceState(it)
                    pendingScrollRestore = null
                }
            }
            binding.textEmpty.visibility =
                if (results.isEmpty() && !viewModel.query.value.isNullOrBlank()) View.VISIBLE
                else View.GONE
        }
    }
}
