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
import com.notificationmaster.databinding.FragmentShortcutHeadsupBinding
import com.notificationmaster.ui.main.MainActivity
import com.notificationmaster.ui.search.NotificationAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Heads-up Shortcut BottomSheet — 從底部滑入顯示最近 Heads-up 通知列表
 */
class HeadsupBottomSheetFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentShortcutHeadsupBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentShortcutHeadsupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.textTitle.setText(R.string.shortcut_headsup_title)

        val adapter = NotificationAdapter(onItemClick = { notification ->
            startActivity(Intent(MainActivity.ACTION_SHOW_DETAIL).apply {
                setClass(requireContext(), MainActivity::class.java)
                putExtra(MainActivity.EXTRA_NOTIFICATION_ID, notification.id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            dismiss()
        })
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        val dao = NotificationMasterApp.getInstance().database.notificationDao()
        viewLifecycleOwner.lifecycleScope.launch {
            dao.getRecentHeadsupNotifications().collectLatest { notifications ->
                if (_binding == null) return@collectLatest
                adapter.submitList(notifications)
                if (notifications.isEmpty()) {
                    binding.recyclerView.visibility = View.GONE
                    binding.textEmpty.visibility = View.VISIBLE
                    binding.textEmpty.setText(R.string.shortcut_empty_headsup)
                } else {
                    binding.recyclerView.visibility = View.VISIBLE
                    binding.textEmpty.visibility = View.GONE
                }
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as? HeadsupShortcutActivity)?.finishFromBottomSheet()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
