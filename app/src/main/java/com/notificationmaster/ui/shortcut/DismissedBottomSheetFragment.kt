package com.notificationmaster.ui.shortcut

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.R
import com.notificationmaster.data.db.dao.query
import com.notificationmaster.databinding.FragmentShortcutDismissedBinding
import com.notificationmaster.ui.main.MainActivity
import com.notificationmaster.ui.search.NotificationAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dismissed Shortcut BottomSheet — 從底部滑入顯示已移除的通知列表
 */
class DismissedBottomSheetFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentShortcutDismissedBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentShortcutDismissedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.textTitle.setText(R.string.shortcut_dismissed_title)

        val adapter = NotificationAdapter(onItemClick = { display ->
            startActivity(Intent(MainActivity.ACTION_SHOW_DETAIL).apply {
                setClass(requireContext(), MainActivity::class.java)
                putExtra(MainActivity.EXTRA_NOTIFICATION_KEY, display.notificationKey)
                putExtra(MainActivity.EXTRA_ANCHOR_EVENT_ID, display.eventId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            dismiss()
        })
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        // Plan 2 W1.d：Dismissed shortcut 改用 view-level filter 直接 query「已移除」row。
        // 不再透過 builtin rule chip（已從 RuleEngine 移除）。
        // 條件 = NOT (row 是同 nkey 最新非-REMOVED row 且 nkey 最終非-REMOVED) — 廣義 isRemoved=true。
        // SQL：撈所有 events，client 端用 enricher 算 isRemoved 篩。
        val dao = NotificationMasterApp.getInstance().database.notificationEventDao()
        val db = NotificationMasterApp.getInstance().database
        viewLifecycleOwner.lifecycleScope.launch {
            dao.query(com.notificationmaster.data.filter.EventFilterSpec.All.copy(limit = 200))
                .collectLatest { events ->
                    if (_binding == null) return@collectLatest
                    val displays = withContext(Dispatchers.IO) {
                        com.notificationmaster.ui.common.NotificationEnricher.enrich(
                            events,
                            db.channelDao(),
                            db.rankingObservationDao(),
                            db.rankingSnapshotDao(),
                            dao
                        ).filter { it.isRemoved }.take(30)
                    }
                    adapter.submitList(displays)
                    if (displays.isEmpty()) {
                        binding.recyclerView.visibility = View.GONE
                        binding.textEmpty.visibility = View.VISIBLE
                        binding.textEmpty.setText(R.string.shortcut_empty_dismissed)
                    } else {
                        binding.recyclerView.visibility = View.VISIBLE
                        binding.textEmpty.visibility = View.GONE
                    }
                }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as? DismissedShortcutActivity)?.finishFromBottomSheet()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
