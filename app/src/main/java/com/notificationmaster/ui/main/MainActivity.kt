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

        // 子目的地 → 所屬 tab 的對應表
        // setupWithNavController 的 matchDestination 只匹配頂層 destination ID，
        // 非巢狀子目的地（如 nav_archive_detail）還原 back stack 後 tab 不會跟著選中。
        val childToTabMap = mapOf(
            R.id.nav_archive_detail to R.id.nav_archive,
            R.id.nav_home to R.id.nav_settings,
            R.id.nav_filter_settings to R.id.nav_settings
        )
        navController.addOnDestinationChangedListener { controller, destination, _ ->
            // 先查靜態對應表
            val tabId = childToTabMap[destination.id]
                ?: run {
                    // 未映射的共用目的地（如 nav_detail 可從 Timeline 或 Archive 進入），
                    // 從 back stack 的上一層推斷所屬 tab
                    controller.previousBackStackEntry?.destination?.id?.let { prevId ->
                        childToTabMap[prevId]
                            ?: prevId.takeIf { binding.bottomNav.menu.findItem(it) != null }
                    }
                }
            if (tabId != null && binding.bottomNav.selectedItemId != tabId) {
                binding.bottomNav.menu.findItem(tabId)?.isChecked = true
            }
        }
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
    }

    override fun onSupportNavigateUp(): Boolean {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        return navHostFragment.navController.navigateUp() || super.onSupportNavigateUp()
    }
}
