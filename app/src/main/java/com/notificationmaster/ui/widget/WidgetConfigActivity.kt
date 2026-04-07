package com.notificationmaster.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.notificationmaster.R
import com.notificationmaster.core.prefs.AppPreferences

/**
 * Widget 設定 Activity
 * 加入桌面時彈出，讓使用者選擇通知類型（Audible / Headsup / Dismissed）
 */
class WidgetConfigActivity : AppCompatActivity() {

    companion object {
        const val TYPE_AUDIBLE = "audible"
        const val TYPE_HEADSUP = "headsup"
        const val TYPE_DISMISSED = "dismissed"
    }

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 預設回傳 CANCELED（使用者取消時不建立 Widget）
        setResult(RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val types = arrayOf(
            getString(R.string.widget_type_audible),
            getString(R.string.widget_type_headsup),
            getString(R.string.widget_type_dismissed)
        )
        val typeValues = arrayOf(TYPE_AUDIBLE, TYPE_HEADSUP, TYPE_DISMISSED)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.widget_config_title)
            .setItems(types) { _, which ->
                val selectedType = typeValues[which]
                AppPreferences.setWidgetType(this, appWidgetId, selectedType)

                // 觸發 Widget 初始更新
                val updateIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
                }
                sendBroadcast(updateIntent)

                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }
}
