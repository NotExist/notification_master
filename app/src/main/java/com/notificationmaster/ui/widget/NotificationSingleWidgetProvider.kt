package com.notificationmaster.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.R
import com.notificationmaster.core.cache.AppLabelCache
import com.notificationmaster.core.prefs.AppPreferences
import com.notificationmaster.ui.main.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 單項式通知 Widget
 * 顯示指定類型（有聲/彈出/移除）的最新一筆通知，點擊進入 Detail 頁
 */
class NotificationSingleWidgetProvider : AppWidgetProvider() {

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
        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val type = AppPreferences.getWidgetType(context, appWidgetId)

            // 類型尚未設定（剛建立 / 設定取消 / preference 遺失）：仍 push 一份預設 RemoteViews，
            // 顯示小工具名稱作為靜態識別，避免使用者看到全黑空白。
            if (type == null) {
                val views = RemoteViews(context.packageName, R.layout.widget_notification_single)
                views.setTextViewText(R.id.widget_single_type, context.getString(R.string.widget_single_name))
                views.setTextViewText(R.id.widget_single_title, context.getString(R.string.widget_empty))
                views.setTextViewText(R.id.widget_single_time, "")
                views.setTextViewText(R.id.widget_single_content, "")
                appWidgetManager.updateAppWidget(appWidgetId, views)
                return
            }

            val dao = NotificationMasterApp.getInstance().database.notificationDao()

            val notification = when (type) {
                WidgetConfigActivity.TYPE_AUDIBLE -> dao.getRecentAudibleNotificationsSync(1)
                WidgetConfigActivity.TYPE_HEADSUP -> dao.getRecentHeadsupNotificationsSync(1)
                WidgetConfigActivity.TYPE_DISMISSED -> dao.getRecentDismissedNotificationsSync(1)
                else -> emptyList()
            }.firstOrNull()

            val typeLabel = when (type) {
                WidgetConfigActivity.TYPE_AUDIBLE -> context.getString(R.string.widget_type_audible)
                WidgetConfigActivity.TYPE_HEADSUP -> context.getString(R.string.widget_type_headsup)
                WidgetConfigActivity.TYPE_DISMISSED -> context.getString(R.string.widget_type_dismissed)
                else -> ""
            }

            val views = RemoteViews(context.packageName, R.layout.widget_notification_single)
            views.setTextViewText(R.id.widget_single_type, typeLabel)

            if (notification != null) {
                views.setTextViewText(R.id.widget_single_title, notification.title ?: "No Title")
                views.setTextViewText(R.id.widget_single_time, timeFormat.format(Date(notification.postTime)))
                views.setTextViewText(R.id.widget_single_content, notification.bigText ?: notification.text ?: "")

                // 點擊 → Detail 頁
                val detailIntent = Intent(context, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_SHOW_DETAIL
                    putExtra(MainActivity.EXTRA_NOTIFICATION_ID, notification.id)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                val pendingIntent = PendingIntent.getActivity(
                    context, appWidgetId,
                    detailIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_single_title, pendingIntent)
                views.setOnClickPendingIntent(R.id.widget_single_content, pendingIntent)
                views.setOnClickPendingIntent(R.id.widget_single_type, pendingIntent)
                views.setOnClickPendingIntent(R.id.widget_single_time, pendingIntent)
            } else {
                views.setTextViewText(R.id.widget_single_title, context.getString(R.string.widget_empty))
                views.setTextViewText(R.id.widget_single_time, "")
                views.setTextViewText(R.id.widget_single_content, "")
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
