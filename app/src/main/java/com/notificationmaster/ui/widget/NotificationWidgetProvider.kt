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
            // 清單式 Widget
            val listIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, NotificationWidgetProvider::class.java)
            )
            if (listIds.isNotEmpty()) {
                appWidgetManager.notifyAppWidgetViewDataChanged(listIds, R.id.widget_list_view)
            }
            // 單項式 Widget
            val singleIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, NotificationSingleWidgetProvider::class.java)
            )
            for (id in singleIds) {
                NotificationSingleWidgetProvider.updateWidget(context, appWidgetManager, id)
            }
        }

        private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val type = AppPreferences.getWidgetType(context, appWidgetId) ?: return
            val title = when (type) {
                WidgetConfigActivity.TYPE_AUDIBLE -> context.getString(R.string.widget_type_audible)
                WidgetConfigActivity.TYPE_HEADSUP -> context.getString(R.string.widget_type_headsup)
                WidgetConfigActivity.TYPE_DISMISSED -> context.getString(R.string.widget_type_dismissed)
                else -> ""
            }

            val views = RemoteViews(context.packageName, R.layout.widget_notification_list)
            views.setTextViewText(R.id.widget_title, title)

            // 標題點擊 → 對應 ShortcutActivity
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

            // 綁定 RemoteViewsService（清單資料來源）
            val serviceIntent = Intent(context, NotificationRemoteViewsService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_list_view, serviceIntent)
            views.setEmptyView(R.id.widget_list_view, R.id.widget_empty)

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
        }
    }
}
