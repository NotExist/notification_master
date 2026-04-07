package com.notificationmaster.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.R
import com.notificationmaster.core.cache.AppLabelCache
import com.notificationmaster.core.prefs.AppPreferences
import com.notificationmaster.data.db.entity.NotificationEntity
import com.notificationmaster.ui.main.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 清單式 Widget 的資料填充
 * 在 binder thread 執行，使用同步 DAO 查詢
 */
class NotificationRemoteViewsFactory(
    private val context: Context,
    intent: Intent
) : RemoteViewsService.RemoteViewsFactory {

    private val appWidgetId = intent.getIntExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID,
        AppWidgetManager.INVALID_APPWIDGET_ID
    )
    private var notifications: List<NotificationEntity> = emptyList()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val dao = NotificationMasterApp.getInstance().database.notificationDao()
        val type = AppPreferences.getWidgetType(context, appWidgetId)
        notifications = when (type) {
            WidgetConfigActivity.TYPE_AUDIBLE -> dao.getRecentAudibleNotificationsSync()
            WidgetConfigActivity.TYPE_HEADSUP -> dao.getRecentHeadsupNotificationsSync()
            WidgetConfigActivity.TYPE_DISMISSED -> dao.getRecentDismissedNotificationsSync()
            else -> emptyList()
        }
    }

    override fun onDestroy() {
        notifications = emptyList()
    }

    override fun getCount(): Int = notifications.size

    override fun getViewAt(position: Int): RemoteViews {
        val notification = notifications[position]
        return RemoteViews(context.packageName, R.layout.widget_notification_item).apply {
            setTextViewText(R.id.widget_item_title, notification.title ?: "No Title")
            setTextViewText(R.id.widget_item_time, timeFormat.format(Date(notification.postTime)))
            setTextViewText(R.id.widget_item_app, AppLabelCache.getLabel(context, notification.packageName))
            setTextViewText(R.id.widget_item_content, notification.bigText ?: notification.text ?: "")

            // 點擊項目 → Detail 頁
            val fillInIntent = Intent().apply {
                putExtra(MainActivity.EXTRA_NOTIFICATION_ID, notification.id)
            }
            setOnClickFillInIntent(R.id.widget_item_root, fillInIntent)
        }
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = notifications[position].id

    override fun hasStableIds(): Boolean = true
}
