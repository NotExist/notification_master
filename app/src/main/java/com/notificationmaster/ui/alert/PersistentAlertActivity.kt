package com.notificationmaster.ui.alert

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.notificationmaster.R
import com.notificationmaster.core.alert.PersistentAlertService
import com.notificationmaster.databinding.ActivityPersistentAlertBinding
import java.text.DateFormat
import java.util.Date

/**
 * 持續提醒全螢幕 Activity
 *
 * 鎖屏時顯示全螢幕介面，亮屏時由系統顯示持續型 heads-up。
 * 透過 fullScreenIntent 由系統決定顯示方式。
 * 從 [PersistentAlertService.currentAlertData] 靜態欄位讀取資料（避免 Intent 過大）。
 */
class PersistentAlertActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPersistentAlertBinding

    /** 監聽停止提醒，同步關閉 Activity */
    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == PersistentAlertService.ACTION_STOP) {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 鎖屏顯示 + 喚醒螢幕 + 保持螢幕常亮
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityPersistentAlertBinding.inflate(layoutInflater)
        setContentView(binding.root)

        populateFromAlertData()
        registerStopReceiver()

        // 返回鍵觸發停止提醒（避免 Activity 消失但鈴聲繼續）
        onBackPressedDispatcher.addCallback(this) {
            stopAlertAndFinish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        populateFromAlertData()
    }

    private fun populateFromAlertData() {
        val data = PersistentAlertService.currentAlertData
        if (data == null) {
            finish()
            return
        }

        // App 圖示
        try {
            binding.imgAppIcon.setImageDrawable(packageManager.getApplicationIcon(data.packageName))
        } catch (_: Exception) {
            binding.imgAppIcon.setImageResource(R.mipmap.ic_launcher)
        }

        // App 名稱
        binding.textAppName.text = data.appName

        // 觸發事件 + 時間
        val timeStr = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(data.timestamp))
        binding.textEventInfo.text = getString(R.string.alert_event_type, data.eventType, timeStr)

        // 通知標題
        binding.textTitle.text = data.title

        // 副標題
        if (data.subText.isNullOrEmpty()) {
            binding.textSubText.visibility = android.view.View.GONE
        } else {
            binding.textSubText.visibility = android.view.View.VISIBLE
            binding.textSubText.text = data.subText
        }

        // 通知內文（bigText 優先）
        val content = data.bigText ?: data.text
        if (content.isNullOrEmpty()) {
            binding.textContent.visibility = android.view.View.GONE
        } else {
            binding.textContent.visibility = android.view.View.VISIBLE
            binding.textContent.text = content
        }

        // 原始 Action 按鈕
        binding.layoutActions.removeAllViews()
        val validActions = data.actions?.filter { it.remoteInputs.isNullOrEmpty() }
        if (validActions.isNullOrEmpty()) {
            binding.layoutActions.visibility = android.view.View.GONE
        } else {
            binding.layoutActions.visibility = android.view.View.VISIBLE
            for (action in validActions) {
                val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    this.text = action.title?.toString()
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (4 * resources.displayMetrics.density).toInt() }
                    setOnClickListener {
                        try {
                            action.actionIntent.send()
                        } catch (_: PendingIntent.CanceledException) {
                            Toast.makeText(this@PersistentAlertActivity, R.string.alert_action_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                binding.layoutActions.addView(btn)
            }
        }

        // 停止按鈕
        binding.btnStop.setOnClickListener {
            stopAlertAndFinish()
        }

        // 開啟原始通知按鈕
        if (data.contentIntent != null) {
            binding.btnOpenOriginal.visibility = android.view.View.VISIBLE
            binding.btnOpenOriginal.setOnClickListener {
                try {
                    data.contentIntent.send()
                    stopAlertAndFinish()
                } catch (_: PendingIntent.CanceledException) {
                    Toast.makeText(this, R.string.alert_open_failed, Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            binding.btnOpenOriginal.visibility = android.view.View.GONE
        }
    }

    private fun registerStopReceiver() {
        val filter = IntentFilter(PersistentAlertService.ACTION_STOP)
        ContextCompat.registerReceiver(this, stopReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun stopAlertAndFinish() {
        PersistentAlertService.stop(this, PersistentAlertService.STOP_REASON_ACTIVITY)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) {}
    }

    companion object {
        /** 建立輕量 Intent（不含資料，Activity 從靜態欄位讀取） */
        fun createIntent(context: Context): Intent {
            return Intent(context, PersistentAlertActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}
