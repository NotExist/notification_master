package com.notificationmaster.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.notificationmaster.R
import com.notificationmaster.core.prefs.AppPreferences
import com.notificationmaster.databinding.ActivityMainBinding
import com.notificationmaster.ui.detail.NotificationDetailFragmentArgs

/**
 * 主 Activity
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        setupNavigation()
        handleShortcutIntent(intent)
        handleExternalTextIntent(intent)
        handleFilteredTimelineIntent(intent)

        // 首次啟動且未設定備份目錄 → 靜默導航到設定頁
        if (savedInstanceState == null && !hasRedirectedToSettings
            && !AppPreferences.isBackupDirEnabled(this)
        ) {
            hasRedirectedToSettings = true
            binding.bottomNav.selectedItemId = R.id.nav_settings
        }

        // 根據 BottomNavigationView 實際高度動態設定 fragment 容器底部間距
        binding.bottomNav.doOnLayout { bottomNav ->
            findViewById<View>(R.id.nav_host_fragment).updatePadding(bottom = bottomNav.height)
        }
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        // 各 tab 起始頁為頂層目的地（不顯示返回按鈕）
        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.timeline_home,
                R.id.archive_home,
                R.id.search_home,
                R.id.settings_home
            )
        )

        setupActionBarWithNavController(navController, appBarConfiguration)
        binding.bottomNav.setupWithNavController(navController)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcutIntent(intent)
        handleExternalTextIntent(intent)
        handleFilteredTimelineIntent(intent)
    }

    /**
     * Widget / Shortcut 帶 spec 或 preset name 進入 Timeline。
     * 切到 Timeline tab 後 intent.action 會由 TimelineFragment 讀取並消費。
     */
    private fun handleFilteredTimelineIntent(intent: Intent?) {
        if (intent?.action != ACTION_SHOW_FILTERED_TIMELINE) return
        binding.bottomNav.selectedItemId = R.id.nav_timeline
    }

    private fun handleShortcutIntent(intent: Intent?) {
        if (intent?.action == ACTION_SHOW_DETAIL) {
            val notificationKey = intent.getStringExtra(EXTRA_NOTIFICATION_KEY)
            val anchorEventId = intent.getLongExtra(EXTRA_ANCHOR_EVENT_ID, -1L)
            if (!notificationKey.isNullOrEmpty()) {
                val navHostFragment = supportFragmentManager
                    .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                val navController = navHostFragment.navController
                val args = NotificationDetailFragmentArgs(notificationKey, anchorEventId).toBundle()
                navController.navigate(R.id.timeline_detail, args)
            }
            intent.action = null
        }
    }

    private fun handleExternalTextIntent(intent: Intent?) {
        val query = when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND ->
                intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        if (!query.isNullOrBlank()) {
            binding.bottomNav.selectedItemId = R.id.nav_search
            val navHostFragment = supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            val navController = navHostFragment.navController
            navController.navigate(R.id.search_home, Bundle().apply {
                putString("query", query)
            })
            intent?.action = null
        }
    }

    companion object {
        const val ACTION_SHOW_DETAIL = "com.notificationmaster.action.SHOW_DETAIL"
        /** 進 Detail 的通知 key（Plan 2 Phase 7b：取代舊 EXTRA_NOTIFICATION_ID(Long)）*/
        const val EXTRA_NOTIFICATION_KEY = "notification_key"
        /** Detail 進入時要 focus 的 event id（選填，預設 -1L = 用最新 event）*/
        const val EXTRA_ANCHOR_EVENT_ID = "anchor_event_id"

        /** 由 Widget / Shortcut 帶入 LIST_FILTER rule id，切到 Timeline 並套用 */
        const val ACTION_SHOW_FILTERED_TIMELINE = "com.notificationmaster.action.SHOW_FILTERED_TIMELINE"
        /** LIST_FILTER Rule id（內建或使用者命名） */
        const val EXTRA_RULE_ID = "rule_id"

        /** Process 級 flag，App 被殺重啟才重置 */
        private var hasRedirectedToSettings = false
    }

    override fun onSupportNavigateUp(): Boolean {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        return navHostFragment.navController.navigateUp() || super.onSupportNavigateUp()
    }

    /**
     * 設定 toolbar 右側計數文字。null/空字串會隱藏。
     * 供 TimelineFragment 等需要顯示總數的頁面使用。
     */
    fun setToolbarCount(text: CharSequence?) {
        binding.toolbarCount.text = text ?: ""
        binding.toolbarCount.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /** Phase 31f：點 toolbar counter 跳載入詳情對話框（含 displayed/loaded/unique/raw 四視角）。 */
    fun setToolbarCountClickListener(listener: (() -> Unit)?) {
        binding.toolbarCount.setOnClickListener(if (listener == null) null else { _ -> listener() })
    }
}
