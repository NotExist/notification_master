package com.notificationmaster.ui.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.notificationmaster.R
import com.notificationmaster.databinding.FragmentSearchBinding
import com.notificationmaster.ui.filter.FilterRuleDialogHelper

/**
 * 搜尋頁面 Fragment
 */
class SearchFragment : Fragment() {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SearchViewModel by viewModels()
    private lateinit var notificationAdapter: NotificationAdapter

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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupRecyclerView() {
        notificationAdapter = NotificationAdapter(
            onItemClick = { notification ->
                findNavController().navigate(
                    R.id.action_search_to_detail,
                    bundleOf("notificationId" to notification.id)
                )
            },
            onItemLongClick = { notification ->
                FilterRuleDialogHelper.showAddRuleDialog(
                    context = requireContext(),
                    actionType = null,
                    prefillPackageName = notification.packageName,
                    prefillChannelId = notification.channelId
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
            val _binding = _binding ?: return@observe
            notificationAdapter.submitList(results)
            _binding.textEmpty.visibility =
                if (results.isEmpty() && !viewModel.query.value.isNullOrBlank()) View.VISIBLE
                else View.GONE
        }
    }
}
