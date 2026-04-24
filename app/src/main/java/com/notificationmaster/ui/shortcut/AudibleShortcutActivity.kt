package com.notificationmaster.ui.shortcut

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.R
import com.notificationmaster.data.db.dao.query
import com.notificationmaster.data.filter.EventFilterSpec
import com.notificationmaster.databinding.ActivityShortcutListBinding
import com.notificationmaster.ui.main.MainActivity
import com.notificationmaster.ui.search.NotificationAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Audible Shortcut Activity — Dialog 風格置中浮動視窗
 * 顯示最近有聲通知列表，點擊項目跳轉到主 App 詳情頁
 */
class AudibleShortcutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShortcutListBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(true)

        binding = ActivityShortcutListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.textTitle.setText(R.string.shortcut_audible_title)

        val adapter = NotificationAdapter(onItemClick = { notification ->
            startActivity(Intent(MainActivity.ACTION_SHOW_DETAIL).apply {
                setClass(this@AudibleShortcutActivity, MainActivity::class.java)
                putExtra(MainActivity.EXTRA_NOTIFICATION_ID, notification.id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            finish()
        })
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        val dao = NotificationMasterApp.getInstance().database.notificationDao()
        lifecycleScope.launch {
            dao.query(EventFilterSpec.RecentAudible).collectLatest { notifications ->
                adapter.submitList(notifications)
                if (notifications.isEmpty()) {
                    binding.recyclerView.visibility = View.GONE
                    binding.textEmpty.visibility = View.VISIBLE
                    binding.textEmpty.setText(R.string.shortcut_empty_audible)
                } else {
                    binding.recyclerView.visibility = View.VISIBLE
                    binding.textEmpty.visibility = View.GONE
                }
            }
        }
    }

    companion object {
        const val ACTION_SHORTCUT_AUDIBLE = "com.notificationmaster.action.SHORTCUT_AUDIBLE"
    }
}
