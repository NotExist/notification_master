package com.notificationmaster.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.notificationmaster.R
import com.notificationmaster.core.prefs.AppPreferences
import com.notificationmaster.ui.main.MainActivity
import com.notificationmaster.ui.shortcut.AudibleShortcutActivity
import com.notificationmaster.ui.shortcut.HeadsupShortcutActivity
import com.notificationmaster.ui.shortcut.DismissedShortcutActivity

/**
 * 清單式通知 Widget
 * 顯示可捲動的通知清單，點擊項目進入 Detail、點擊標題進入完整清單
 */
class NotificationWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            AppPreferences.removeWidgetType(context, appWidgetId)
        }
    }

    companion object {
        fun notifyUpdate(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            // 清單式 Widget：直接呼叫 updateWidget 重設 title/adapter/emptyView，
            // 讓首次 config 失敗的 widget 在新通知到達時能自我修復
            // （updateWidget 內部已會 notifyAppWidgetViewDataChanged 觸發資料刷新）
            val listIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, NotificationWidgetProvider::class.java)
            )
            for (id in listIds) {
                updateWidget(context, appWidgetManager, id)
            }
            // 單項式 Widget
            val singleIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, NotificationSingleWidgetProvider::class.java)
            )
            for (id in singleIds) {
                NotificationSingleWidgetProvider.updateWidget(context, appWidgetManager, id)
            }
        }

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val type = AppPreferences.getWidgetType(context, appWidgetId)
            val views = RemoteViews(context.packageName, R.layout.widget_notification_list)

            // 標題：type null 時退回 widget 名稱作為靜態識別
            val title = when (type) {
                WidgetConfigActivity.TYPE_AUDIBLE -> context.getString(R.string.widget_type_audible)
                WidgetConfigActivity.TYPE_HEADSUP -> context.getString(R.string.widget_type_headsup)
                WidgetConfigActivity.TYPE_DISMISSED -> context.getString(R.string.widget_type_dismissed)
                else -> context.getString(R.string.widget_list_name)
            }
            views.setTextViewText(R.id.widget_title, title)

            // 無論 type 是否 null，都要綁 adapter + empty view。
            // Factory.onDataSetChanged() 在 type==null 時會回 emptyList，
            // empty view 機制就會把「尚無通知記錄」自動顯示出來，避免 widget
            // 進入「壞掉但無法自癒」的狀態。
            val serviceIntent = Intent(context, NotificationRemoteViewsService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_list_view, serviceIntent)
            views.setEmptyView(R.id.widget_list_view, R.id.widget_empty)

            // 標題點擊 → 對應 ShortcutActivity（type null 時不綁，點了也沒意義）
            val shortcutActivityClass = when (type) {
                WidgetConfigActivity.TYPE_AUDIBLE -> AudibleShortcutActivity::class.java
                WidgetConfigActivity.TYPE_HEADSUP -> HeadsupShortcutActivity::class.java
                WidgetConfigActivity.TYPE_DISMISSED -> DismissedShortcutActivity::class.java
                else -> null
            }
            if (shortcutActivityClass != null) {
                val titleIntent = PendingIntent.getActivity(
                    context, appWidgetId,
                    Intent(context, shortcutActivityClass),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_title, titleIntent)
            }

            // 項目點擊 PendingIntent template → Detail 頁
            val detailIntent = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_SHOW_DETAIL
            }
            val pendingTemplate = PendingIntent.getActivity(
                context, appWidgetId,
                detailIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.widget_list_view, pendingTemplate)

            appWidgetManager.updateAppWidget(appWidgetId, views)
            // 新綁的 adapter 不會自動拉資料，明確踢一次
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_list_view)
        }
    }
}
