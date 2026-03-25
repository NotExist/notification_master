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

        // 設定所有頂層目的地（不顯示返回按鈕）
        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.nav_timeline,
                R.id.nav_archive,
                R.id.nav_search,
                R.id.nav_settings
            )
        )

        setupActionBarWithNavController(navController, appBarConfiguration)
        binding.bottomNav.setupWithNavController(navController)
        // setupWithNavController 預設行為：
        // - 頂層目的地 → 自動選取對應 tab
        // - 非頂層目的地 → tab 保持原狀（使用者從哪個 tab 進入就停在哪個 tab）
        // 不需要自訂 OnDestinationChangedListener
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcutIntent(intent)
    }

    private fun handleShortcutIntent(intent: Intent?) {
        if (intent?.action == ACTION_SHOW_DETAIL) {
            val notificationId = intent.getLongExtra(EXTRA_NOTIFICATION_ID, -1L)
            if (notificationId != -1L) {
                val navHostFragment = supportFragmentManager
                    .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                val navController = navHostFragment.navController
                val args = NotificationDetailFragmentArgs(notificationId).toBundle()
                navController.navigate(R.id.nav_detail, args)
            }
            intent.action = null
        }
    }

    companion object {
        const val ACTION_SHOW_DETAIL = "com.notificationmaster.action.SHOW_DETAIL"
        const val EXTRA_NOTIFICATION_ID = "notification_id"

        /** Process 級 flag，App 被殺重啟才重置 */
        private var hasRedirectedToSettings = false
    }

    override fun onSupportNavigateUp(): Boolean {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        return navHostFragment.navController.navigateUp() || super.onSupportNavigateUp()
    }
}
