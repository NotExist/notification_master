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
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.R
import com.notificationmaster.core.compat.ApiVersionHelper
import com.notificationmaster.core.debug.ProfileLogger
import com.notificationmaster.databinding.FragmentArchiveBinding
import com.notificationmaster.ui.filter.CalendarPickerLauncher
import com.notificationmaster.ui.filter.FilterRuleDialogHelper
import com.notificationmaster.ui.filter.SoundPickerLauncher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 歸檔頁面 Fragment
 *
 * 按 App 或 Channel 分類顯示通知。currentTab 與 scroll 位置都在 [ArchiveViewModel]，跨 view 重建
 * 保留（含從 Detail 返回時直接回原位）。
 */
class ArchiveFragment : Fragment() {

    private var _binding: FragmentArchiveBinding? = null
    private val binding get() = _binding!!

    private val soundPicker = SoundPickerLauncher(this)
    private val calendarPicker = CalendarPickerLauncher(this)

    private val viewModel: ArchiveViewModel by viewModels()

    private lateinit var appSourceAdapter: AppSourceAdapter
    private lateinit var channelAdapter: ChannelAdapter

    /** view 剛建立時要還原一次當前 tab 的 scroll 位置；submitList 完成後消費掉 */
    private var pendingScrollRestore: Parcelable? = null

    /** 目前 tab 的資料 collect job，切 tab 時取消舊的 */
    private var dataJob: Job? = null

    /** Channel 功能是否可用 (API 26+) */
    private val isChannelSupported: Boolean
        get() = ApiVersionHelper.supportsNotificationChannel()

    enum class Tab { BY_APP, BY_CHANNEL }

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

        // 從 ViewModel 還原當前 tab；TabLayout 預設選 tab 0，selectTab 會觸發 listener 啟動 loadData()
        val tabIndex = if (viewModel.currentTab == Tab.BY_CHANNEL) 1 else 0
        if (binding.tabLayout.selectedTabPosition != tabIndex) {
            binding.tabLayout.selectTab(binding.tabLayout.getTabAt(tabIndex))
        } else {
            loadData()
        }
    }

    override fun onPause() {
        super.onPause()
        // 進 Detail / 切離 tab 前先把當前 tab 的 scroll state 收進 ViewModel
        val state = binding.recyclerView.layoutManager?.onSaveInstanceState() ?: return
        when (viewModel.currentTab) {
            Tab.BY_APP -> viewModel.appTabScrollState = state
            Tab.BY_CHANNEL -> viewModel.channelTabScrollState = state
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        dataJob = null
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
                    prefillPackageName = appSource.packageName,
                    soundPicker = soundPicker,
                    calendarPicker = calendarPicker
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
                    prefillChannelId = channel.channelId,
                    soundPicker = soundPicker,
                    calendarPicker = calendarPicker
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

                if (selectedTab == Tab.BY_CHANNEL && !isChannelSupported) {
                    showChannelNotSupportedMessage()
                    binding.tabLayout.selectTab(binding.tabLayout.getTabAt(0))
                    return
                }

                viewModel.currentTab = selectedTab
                loadData()
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {
                // tab 切換前保留該 tab 的 scroll state
                val state = _binding?.recyclerView?.layoutManager?.onSaveInstanceState() ?: return
                when (tab?.position) {
                    0 -> viewModel.appTabScrollState = state
                    1 -> viewModel.channelTabScrollState = state
                }
            }

            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun showChannelNotSupportedMessage() {
        binding.textEmpty.text = getString(R.string.channel_not_supported)
        binding.textEmpty.visibility = View.VISIBLE
    }

    private fun loadData() {
        val database = NotificationMasterApp.getInstance().database
        dataJob?.cancel()

        // 切到該 tab 對應的 scroll state；下一次 submitList 完成後還原
        pendingScrollRestore = when (viewModel.currentTab) {
            Tab.BY_APP -> viewModel.appTabScrollState
            Tab.BY_CHANNEL -> viewModel.channelTabScrollState
        }

        // W23c-instrument：byApp 入口「黑屏許久」嫌疑盤查 — query emit / submit commit 各段時間
        val tStart = System.currentTimeMillis()
        ProfileLogger.append("ArchiveHome", "load start tab=${viewModel.currentTab}")
        dataJob = viewLifecycleOwner.lifecycleScope.launch {
            when (viewModel.currentTab) {
                Tab.BY_APP -> {
                    binding.recyclerView.adapter = appSourceAdapter
                    database.appSourceDao().getAllAppSources().collectLatest { apps ->
                        ProfileLogger.append(
                            "ArchiveHome",
                            "byApp emit size=${apps.size} since-start=${System.currentTimeMillis() - tStart}ms"
                        )
                        val binding = _binding ?: return@collectLatest
                        val tSubmit = System.currentTimeMillis()
                        appSourceAdapter.submitList(apps) {
                            ProfileLogger.append(
                                "ArchiveHome",
                                "byApp submit done size=${apps.size} commit=${System.currentTimeMillis() - tSubmit}ms"
                            )
                            pendingScrollRestore?.let {
                                _binding?.recyclerView?.layoutManager?.onRestoreInstanceState(it)
                                pendingScrollRestore = null
                            }
                        }
                        binding.textEmpty.text = getString(R.string.timeline_empty)
                        binding.textEmpty.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                Tab.BY_CHANNEL -> {
                    if (!isChannelSupported) {
                        showChannelNotSupportedMessage()
                        return@launch
                    }
                    binding.recyclerView.adapter = channelAdapter
                    database.channelDao().getAllChannels().collectLatest { channels ->
                        ProfileLogger.append(
                            "ArchiveHome",
                            "byChannel emit size=${channels.size} since-start=${System.currentTimeMillis() - tStart}ms"
                        )
                        val binding = _binding ?: return@collectLatest
                        channelAdapter.submitList(channels) {
                            pendingScrollRestore?.let {
                                _binding?.recyclerView?.layoutManager?.onRestoreInstanceState(it)
                                pendingScrollRestore = null
                            }
                        }
                        binding.textEmpty.text = getString(R.string.timeline_empty)
                        binding.textEmpty.visibility = if (channels.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }
}
