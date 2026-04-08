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

                // (1) 同步寫盤，避免 process 立刻被殺造成設定遺失
                AppPreferences.setWidgetTypeSync(this, appWidgetId, selectedType)

                // (2) 直接呼叫對應 Provider 的 updateWidget。
                //     兩款 widget 共用此 configure activity，需先查 provider class。
                //     不依賴 sendBroadcast，避免隱式 broadcast 在不同 ROM 上的路由
                //     差異與時序問題。
                val appWidgetManager = AppWidgetManager.getInstance(this)
                val providerClassName = appWidgetManager.getAppWidgetInfo(appWidgetId)?.provider?.className
                when (providerClassName) {
                    NotificationWidgetProvider::class.java.name ->
                        NotificationWidgetProvider.updateWidget(this, appWidgetManager, appWidgetId)
                    NotificationSingleWidgetProvider::class.java.name ->
                        NotificationSingleWidgetProvider.updateWidget(this, appWidgetManager, appWidgetId)
                    else -> {
                        // 保險：providerInfo 取不到時兩個都呼叫一次
                        // （updateAppWidget 對非擁有者是 no-op）
                        NotificationWidgetProvider.updateWidget(this, appWidgetManager, appWidgetId)
                        NotificationSingleWidgetProvider.updateWidget(this, appWidgetManager, appWidgetId)
                    }
                }

                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }
}
