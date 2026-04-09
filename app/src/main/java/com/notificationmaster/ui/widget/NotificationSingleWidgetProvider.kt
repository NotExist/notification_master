package com.notificationmaster.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.R
import com.notificationmaster.core.prefs.AppPreferences
import com.notificationmaster.ui.main.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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

        // AOSP 推薦：widget background work 用單一 executor，避免每次呼叫建立新 thread
        private val executor: ExecutorService = Executors.newSingleThreadExecutor()

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val type = AppPreferences.getWidgetType(context, appWidgetId)

            val typeLabel = when (type) {
                WidgetConfigActivity.TYPE_AUDIBLE -> context.getString(R.string.widget_type_audible)
                WidgetConfigActivity.TYPE_HEADSUP -> context.getString(R.string.widget_type_headsup)
                WidgetConfigActivity.TYPE_DISMISSED -> context.getString(R.string.widget_type_dismissed)
                else -> context.getString(R.string.widget_single_name)
            }

            // 第一階段（任何 thread 都安全）：立即 push 基本 RemoteViews
            // 含 type label + 空狀態文字，讓 widget 即時呈現靜態識別。
            // 不在這裡查 DAO：呼叫 updateWidget 的入口（WidgetConfigActivity dialog
            // click、onReceive、notifyUpdate）多半在 main thread，Room 預設禁止
            // main thread query 會 crash。
            val baseViews = RemoteViews(context.packageName, R.layout.widget_notification_single)
            baseViews.setTextViewText(R.id.widget_single_type, typeLabel)
            baseViews.setTextViewText(R.id.widget_single_title, context.getString(R.string.widget_empty))
            baseViews.setTextViewText(R.id.widget_single_time, "")
            baseViews.setTextViewText(R.id.widget_single_content, "")
            appWidgetManager.updateAppWidget(appWidgetId, baseViews)

            if (type == null) return

            // 第二階段（background thread）：查 DAO 後 push 完整 RemoteViews
            // 用 applicationContext 避免持有 Activity context 導致 leak
            val appContext = context.applicationContext
            executor.execute {
                val dao = NotificationMasterApp.getInstance().database.notificationDao()
                val notification = when (type) {
                    WidgetConfigActivity.TYPE_AUDIBLE -> dao.getRecentAudibleNotificationsSync(1)
                    WidgetConfigActivity.TYPE_HEADSUP -> dao.getRecentHeadsupNotificationsSync(1)
                    WidgetConfigActivity.TYPE_DISMISSED -> dao.getRecentDismissedNotificationsSync(1)
                    else -> emptyList()
                }.firstOrNull()

                val views = RemoteViews(appContext.packageName, R.layout.widget_notification_single)
                views.setTextViewText(R.id.widget_single_type, typeLabel)

                if (notification != null) {
                    views.setTextViewText(R.id.widget_single_title, notification.title ?: "No Title")
                    views.setTextViewText(R.id.widget_single_time, timeFormat.format(Date(notification.postTime)))
                    views.setTextViewText(R.id.widget_single_content, notification.bigText ?: notification.text ?: "")

                    // 點擊 → Detail 頁
                    val detailIntent = Intent(appContext, MainActivity::class.java).apply {
                        action = MainActivity.ACTION_SHOW_DETAIL
                        putExtra(MainActivity.EXTRA_NOTIFICATION_ID, notification.id)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    val pendingIntent = PendingIntent.getActivity(
                        appContext, appWidgetId,
                        detailIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    views.setOnClickPendingIntent(R.id.widget_single_title, pendingIntent)
                    views.setOnClickPendingIntent(R.id.widget_single_content, pendingIntent)
                    views.setOnClickPendingIntent(R.id.widget_single_type, pendingIntent)
                    views.setOnClickPendingIntent(R.id.widget_single_time, pendingIntent)
                } else {
                    views.setTextViewText(R.id.widget_single_title, appContext.getString(R.string.widget_empty))
                    views.setTextViewText(R.id.widget_single_time, "")
                    views.setTextViewText(R.id.widget_single_content, "")
                }

                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
    }
}
