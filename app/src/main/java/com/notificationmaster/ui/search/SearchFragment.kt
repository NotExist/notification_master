package com.notificationmaster.ui.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.databinding.FragmentSearchBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 搜尋頁面 Fragment
 */
class SearchFragment : Fragment() {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private lateinit var notificationAdapter: NotificationAdapter
    private var searchJob: Job? = null

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

        // 接收從時間軸過濾帶過來的查詢文字
        val externalQuery = arguments?.getString("query")
        if (!externalQuery.isNullOrBlank()) {
            binding.editSearch.setText(externalQuery)
            performSearch(externalQuery)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupRecyclerView() {
        notificationAdapter = NotificationAdapter { _ ->
            // TODO: 導航到通知詳情
        }

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = notificationAdapter
        }
    }

    private fun setupSearchInput() {
        binding.editSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch(binding.editSearch.text?.toString() ?: "")
                true
            } else {
                false
            }
        }
    }

    private fun performSearch(query: String) {
        searchJob?.cancel()

        if (query.isBlank()) {
            notificationAdapter.submitList(emptyList())
            binding.textEmpty.visibility = View.VISIBLE
            return
        }

        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            // 防抖動
            delay(300)

            val database = NotificationMasterApp.getInstance().database
            val results = withContext(Dispatchers.IO) {
                database.notificationDao().searchNotifications(query, 100)
            }

            notificationAdapter.submitList(results)
            binding.textEmpty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
        }
    }
}
