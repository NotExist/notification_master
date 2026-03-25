package com.notificationmaster.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.notificationmaster.R
import com.notificationmaster.core.filter.RuleEngine
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
        checkBackupOnStartup(savedInstanceState)

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

    /**
     * App 啟動時檢查備份目錄設定
     *
     * 未設定備份目錄且未拒絕過 → 提示設定。
     * 僅在首次 create 時執行（savedInstanceState == null），
     * 避免螢幕旋轉等重建時重複提示。
     */
    private fun checkBackupOnStartup(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) return
        if (AppPreferences.isBackupDirEnabled(this)) return
        if (AppPreferences.isBackupSetupDeclined(this)) return

        RuleEngine.load(this)

        AlertDialog.Builder(this)
            .setTitle(R.string.filter_backup_setup_title)
            .setMessage(R.string.filter_backup_setup_message)
            .setPositiveButton(R.string.ok) { _, _ ->
                // 導航到設定頁，由設定頁處理目錄選擇
                val navHostFragment = supportFragmentManager
                    .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                binding.bottomNav.selectedItemId = R.id.nav_settings
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                AppPreferences.setBackupSetupDeclined(this, true)
            }
            .show()
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
