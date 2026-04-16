package com.notificationmaster.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.R
import com.notificationmaster.core.filter.MatchContext
import com.notificationmaster.core.filter.Matcher
import com.notificationmaster.core.prefs.AppPreferences
import com.notificationmaster.ui.main.MainActivity
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 單項式通知 Widget
 * 顯示篩選條件匹配的最新一筆通知，點擊進入 Detail 頁
 */
class NotificationSingleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            AppPreferences.removeWidgetConfig(context, appWidgetId)
        }
    }

    companion object {
        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        // AOSP 推薦：widget background work 用單一 executor，避免每次呼叫建立新 thread
        private val executor: ExecutorService = Executors.newSingleThreadExecutor()

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val label = AppPreferences.getWidgetLabel(context, appWidgetId)
            val typeLabel = label ?: context.getString(R.string.widget_single_name)

            // 第一階段（任何 thread 都安全）：立即 push 基本 RemoteViews
            val baseViews = RemoteViews(context.packageName, R.layout.widget_notification_single)
            baseViews.setTextViewText(R.id.widget_single_type, typeLabel)
            baseViews.setTextViewText(R.id.widget_single_title, context.getString(R.string.widget_empty))
            baseViews.setTextViewText(R.id.widget_single_time, "")
            baseViews.setTextViewText(R.id.widget_single_content, "")
            appWidgetManager.updateAppWidget(appWidgetId, baseViews)

            val matchersJson = AppPreferences.getWidgetMatchers(context, appWidgetId) ?: return

            // 第二階段（background thread）：查 DAO + matcher 篩選後 push 完整 RemoteViews
            val appContext = context.applicationContext
            executor.execute {
                val matchers = try {
                    val arr = JSONArray(matchersJson)
                    (0 until arr.length()).map { Matcher.fromJson(arr.getJSONObject(it)) }
                } catch (_: Exception) { return@execute }

                val dao = NotificationMasterApp.getInstance().database.notificationDao()
                val candidates = dao.getRecentNotificationsSync(200)

                val filtered = candidates.filter { entity ->
                    val mc = MatchContext(
                        packageName = entity.packageName,
                        channelId = entity.channelId,
                        title = entity.title,
                        text = entity.text,
                        bigText = entity.bigText,
                        subText = entity.subText,
                        channelImportance = entity.importance.takeIf { it >= 0 },
                        flags = entity.flags,
                        isAudible = entity.isAudible,
                        likelyHeadsup = entity.likelyHeadsup,
                        isRemoved = entity.removedAt != null
                    )
                    matchers.all { it.matches(mc) }
                }

                // dismissed 排序
                val hasDismissedFilter = matchers.any { it is Matcher.DerivedProperty && (it as Matcher.DerivedProperty).isRemoved == true }
                val notification = if (hasDismissedFilter) {
                    filtered.maxByOrNull { it.removedAt ?: 0L }
                } else {
                    filtered.firstOrNull()
                }

                val views = RemoteViews(appContext.packageName, R.layout.widget_notification_single)
                views.setTextViewText(R.id.widget_single_type, typeLabel)

                if (notification != null) {
                    views.setTextViewText(R.id.widget_single_title, notification.title ?: "No Title")
                    views.setTextViewText(R.id.widget_single_time, timeFormat.format(Date(notification.postTime)))
                    views.setTextViewText(R.id.widget_single_content, notification.bigText ?: notification.text ?: "")

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
