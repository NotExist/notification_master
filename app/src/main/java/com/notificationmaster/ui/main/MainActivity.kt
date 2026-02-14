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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcutIntent(intent)
    }

    private fun handleShortcutIntent(intent: Intent?) {
        // shortcuts.xml 的 <extra android:value="true"> 傳入的是 String，不是 boolean
        if (intent?.hasShowAudible() == true) {
            // 確保導航到 timeline
            val navHostFragment = supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            navHostFragment.navController.navigate(R.id.nav_timeline)
            // TimelineFragment 會在 onViewCreated 中讀取 activity intent extra
        }
    }

    companion object {
        /** 檢查 intent 是否帶有 show_audible extra（相容 String 和 Boolean 兩種型別） */
        fun Intent.hasShowAudible(): Boolean =
            getStringExtra("show_audible") == "true" ||
            getBooleanExtra("show_audible", false)
    }

    override fun onSupportNavigateUp(): Boolean {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        return navHostFragment.navController.navigateUp() || super.onSupportNavigateUp()
    }
}
