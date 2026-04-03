package com.notificationmaster.ui.archive

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.R
import com.notificationmaster.core.compat.ApiVersionHelper
import com.notificationmaster.databinding.FragmentArchiveBinding
import com.notificationmaster.ui.filter.FilterRuleDialogHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 歸檔頁面 Fragment
 * 按 App 或 Channel 分類顯示通知
 */
class ArchiveFragment : Fragment() {

    private var _binding: FragmentArchiveBinding? = null
    private val binding get() = _binding!!

    private lateinit var appSourceAdapter: AppSourceAdapter
    private lateinit var channelAdapter: ChannelAdapter
    private var currentTab = Tab.BY_APP

    /** Channel 功能是否可用 (API 26+) */
    private val isChannelSupported: Boolean
        get() = ApiVersionHelper.supportsNotificationChannel()

    enum class Tab { BY_APP, BY_CHANNEL }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            currentTab = Tab.values()[savedInstanceState.getInt(KEY_CURRENT_TAB, 0)]
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentArchiveBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupTabs()

        // 恢復 tab 選中狀態（view 重建後 TabLayout 預設選 tab 0）
        val tabIndex = if (currentTab == Tab.BY_CHANNEL) 1 else 0
        if (binding.tabLayout.selectedTabPosition != tabIndex) {
            binding.tabLayout.selectTab(binding.tabLayout.getTabAt(tabIndex))
            // listener 會觸發 loadData()
        } else {
            loadData()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT_TAB, currentTab.ordinal)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupRecyclerView() {
        appSourceAdapter = AppSourceAdapter(
            onItemClick = { appSource ->
                val action = ArchiveFragmentDirections.actionArchiveHomeToArchiveSubDetail(
                    packageName = appSource.packageName,
                    title = appSource.appName ?: appSource.packageName
                )
                findNavController().navigate(action)
            },
            onItemLongClick = { appSource ->
                FilterRuleDialogHelper.showAddRuleDialog(
                    context = requireContext(),
                    actionType = null,
                    prefillPackageName = appSource.packageName
                )
            }
        )

        channelAdapter = ChannelAdapter(
            onItemClick = { channel ->
                val action = ArchiveFragmentDirections.actionArchiveHomeToArchiveSubDetail(
                    packageName = channel.packageName,
                    channelId = channel.channelId,
                    title = channel.channelName ?: channel.channelId
                )
                findNavController().navigate(action)
            },
            onItemLongClick = { channel ->
                FilterRuleDialogHelper.showAddRuleDialog(
                    context = requireContext(),
                    actionType = null,
                    prefillPackageName = channel.packageName,
                    prefillChannelId = channel.channelId
                )
            }
        )

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = appSourceAdapter
        }
    }

    private fun setupTabs() {
        // 如果不支援 Channel，隱藏或停用 Channel tab
        if (!isChannelSupported) {
            val channelTab = binding.tabLayout.getTabAt(1)
            channelTab?.view?.isEnabled = false
            channelTab?.view?.alpha = 0.5f
        }

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val selectedTab = when (tab?.position) {
                    0 -> Tab.BY_APP
                    1 -> Tab.BY_CHANNEL
                    else -> Tab.BY_APP
                }

                // 如果選擇了 Channel tab 但不支援，顯示提示並切回 App tab
                if (selectedTab == Tab.BY_CHANNEL && !isChannelSupported) {
                    showChannelNotSupportedMessage()
                    binding.tabLayout.selectTab(binding.tabLayout.getTabAt(0))
                    return
                }

                currentTab = selectedTab
                loadData()
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun showChannelNotSupportedMessage() {
        binding.textEmpty.text = getString(R.string.channel_not_supported)
        binding.textEmpty.visibility = View.VISIBLE
    }

    private fun loadData() {
        val database = NotificationMasterApp.getInstance().database

        viewLifecycleOwner.lifecycleScope.launch {
            when (currentTab) {
                Tab.BY_APP -> {
                    binding.recyclerView.adapter = appSourceAdapter
                    database.appSourceDao().getAllAppSources().collectLatest { apps ->
                        appSourceAdapter.submitList(apps)
                        _binding?.textEmpty?.text = getString(R.string.timeline_empty)
                        _binding?.textEmpty?.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                Tab.BY_CHANNEL -> {
                    if (!isChannelSupported) {
                        showChannelNotSupportedMessage()
                        return@launch
                    }

                    binding.recyclerView.adapter = channelAdapter
                    database.channelDao().getAllChannels().collectLatest { channels ->
                        channelAdapter.submitList(channels)
                        _binding?.textEmpty?.text = getString(R.string.timeline_empty)
                        _binding?.textEmpty?.visibility = if (channels.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    companion object {
        private const val KEY_CURRENT_TAB = "current_tab"
    }
}
