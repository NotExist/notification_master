package com.notificationmaster.ui.alert

import android.app.Notification
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
import com.google.android.material.button.MaterialButton
import com.notificationmaster.R
import com.notificationmaster.core.alert.AlertData
import com.notificationmaster.core.alert.PersistentAlertManager
import com.notificationmaster.databinding.ActivityPersistentAlertBinding
import com.notificationmaster.service.NotificationCaptureService
import java.text.DateFormat
import java.util.Date

/**
 * 持續提醒全螢幕 Activity
 *
 * 鎖屏時顯示全螢幕介面，亮屏時由系統顯示持續型 heads-up。
 * 透過 fullScreenIntent 由系統決定顯示方式。
 */
class PersistentAlertActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPersistentAlertBinding

    /** 監聽停止提醒 broadcast，同步關閉 Activity */
    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == PersistentAlertManager.ACTION_STOP_ALERT) {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 鎖屏顯示 + 喚醒螢幕
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

        binding = ActivityPersistentAlertBinding.inflate(layoutInflater)
        setContentView(binding.root)

        populateFromIntent(intent)
        registerStopReceiver()

        // 返回鍵觸發停止提醒（避免 Activity 消失但鈴聲繼續）
        onBackPressedDispatcher.addCallback(this) {
            stopAlertAndFinish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        populateFromIntent(intent)
    }

    private fun populateFromIntent(intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val appName = intent.getStringExtra(EXTRA_APP_NAME) ?: ""
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
        val eventType = intent.getStringExtra(EXTRA_EVENT_TYPE) ?: ""
        val timestamp = intent.getLongExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
        val text = intent.getStringExtra(EXTRA_TEXT)
        val subText = intent.getStringExtra(EXTRA_SUB_TEXT)
        val bigText = intent.getStringExtra(EXTRA_BIG_TEXT)
        val contentIntent: PendingIntent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_CONTENT_INTENT, PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_CONTENT_INTENT)
        }
        val actions: Array<Notification.Action>? = getActionsFromIntent(intent)

        // App 圖示
        try {
            binding.imgAppIcon.setImageDrawable(packageManager.getApplicationIcon(packageName))
        } catch (_: Exception) {
            binding.imgAppIcon.setImageResource(R.mipmap.ic_launcher)
        }

        // App 名稱
        binding.textAppName.text = appName

        // 觸發事件 + 時間
        val timeStr = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestamp))
        binding.textEventInfo.text = getString(R.string.alert_event_type, eventType) + " · $timeStr"

        // 通知標題
        binding.textTitle.text = title

        // 副標題
        if (subText.isNullOrEmpty()) {
            binding.textSubText.visibility = android.view.View.GONE
        } else {
            binding.textSubText.visibility = android.view.View.VISIBLE
            binding.textSubText.text = subText
        }

        // 通知內文（bigText 優先）
        val content = bigText ?: text
        if (content.isNullOrEmpty()) {
            binding.textContent.visibility = android.view.View.GONE
        } else {
            binding.textContent.visibility = android.view.View.VISIBLE
            binding.textContent.text = content
        }

        // 原始 Action 按鈕
        binding.layoutActions.removeAllViews()
        val validActions = actions?.filter { it.remoteInputs.isNullOrEmpty() }
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
        if (contentIntent != null) {
            binding.btnOpenOriginal.visibility = android.view.View.VISIBLE
            binding.btnOpenOriginal.setOnClickListener {
                try {
                    contentIntent.send()
                    stopAlertAndFinish()
                } catch (_: PendingIntent.CanceledException) {
                    Toast.makeText(this, R.string.alert_open_failed, Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            binding.btnOpenOriginal.visibility = android.view.View.GONE
        }
    }

    private fun getActionsFromIntent(intent: Intent): Array<Notification.Action>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayExtra(EXTRA_ACTIONS, Notification.Action::class.java)
                ?.filterIsInstance<Notification.Action>()?.toTypedArray()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayExtra(EXTRA_ACTIONS)
                ?.filterIsInstance<Notification.Action>()?.toTypedArray()
        }
    }

    private fun registerStopReceiver() {
        val filter = IntentFilter(PersistentAlertManager.ACTION_STOP_ALERT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stopReceiver, filter)
        }
    }

    private fun stopAlertAndFinish() {
        NotificationCaptureService.getInstance()?.stopPersistentAlert()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) {}
    }

    companion object {
        const val EXTRA_TITLE = "alert_title"
        const val EXTRA_TEXT = "alert_text"
        const val EXTRA_APP_NAME = "alert_app_name"
        const val EXTRA_PACKAGE_NAME = "alert_package_name"
        const val EXTRA_EVENT_TYPE = "alert_event_type"
        const val EXTRA_TIMESTAMP = "alert_timestamp"
        const val EXTRA_SUB_TEXT = "alert_sub_text"
        const val EXTRA_BIG_TEXT = "alert_big_text"
        const val EXTRA_CONTENT_INTENT = "alert_content_intent"
        const val EXTRA_ACTIONS = "alert_actions"

        fun createIntent(context: Context, data: AlertData): Intent {
            return Intent(context, PersistentAlertActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_TITLE, data.title)
                putExtra(EXTRA_TEXT, data.text)
                putExtra(EXTRA_APP_NAME, data.appName)
                putExtra(EXTRA_PACKAGE_NAME, data.packageName)
                putExtra(EXTRA_EVENT_TYPE, data.eventType)
                putExtra(EXTRA_TIMESTAMP, data.timestamp)
                putExtra(EXTRA_SUB_TEXT, data.subText)
                putExtra(EXTRA_BIG_TEXT, data.bigText)
                putExtra(EXTRA_CONTENT_INTENT, data.contentIntent)
                if (data.actions != null) {
                    putExtra(EXTRA_ACTIONS, data.actions)
                }
            }
        }
    }
}
