package com.notificationmaster.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.R
import com.notificationmaster.core.filter.ActionType
import com.notificationmaster.core.filter.RuleEngine
import com.notificationmaster.core.filter.RuleRepository
import com.notificationmaster.core.prefs.AppPreferences
import com.notificationmaster.data.db.dao.querySync
import com.notificationmaster.data.filter.toFilterSpec
import com.notificationmaster.ui.main.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 單項式通知 Widget（Plan D — RuleEngine LIST_FILTER 路線）
 * 顯示符合 rule 的最新一筆通知，點擊進入 Detail 頁
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
        private val executor: ExecutorService = Executors.newSingleThreadExecutor()

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            RuleRepository.load(context)

            val label = AppPreferences.getWidgetLabel(context, appWidgetId)
            val typeLabel = label ?: context.getString(R.string.widget_single_name)

            // 第一階段：立即 push 基本 RemoteViews
            val baseViews = RemoteViews(context.packageName, R.layout.widget_notification_single)
            baseViews.setTextViewText(R.id.widget_single_type, typeLabel)
            baseViews.setTextViewText(R.id.widget_single_title, context.getString(R.string.widget_empty))
            baseViews.setTextViewText(R.id.widget_single_time, "")
            baseViews.setTextViewText(R.id.widget_single_content, "")

            // 標題點擊 → Timeline 套用此 widget 綁定的 LIST_FILTER rule
            val ruleId = AppPreferences.getWidgetRuleId(context, appWidgetId)
            val titleIntent = Intent(context, MainActivity::class.java).apply {
                if (ruleId != null) {
                    action = MainActivity.ACTION_SHOW_FILTERED_TIMELINE
                    putExtra(MainActivity.EXTRA_RULE_ID, ruleId)
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val typePendingIntent = PendingIntent.getActivity(
                context, appWidgetId,
                titleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            baseViews.setOnClickPendingIntent(R.id.widget_single_type, typePendingIntent)
            appWidgetManager.updateAppWidget(appWidgetId, baseViews)

            if (ruleId == null) return

            val rule = RuleEngine.getRule(ruleId) ?: return
            if (rule.action.actionType != ActionType.LIST_FILTER) return

            val spec = rule.toFilterSpec().copy(limit = 1)
            val appContext = context.applicationContext

            // 第二階段（background thread）：查最新一筆 push 完整 RemoteViews
            executor.execute {
                val dao = NotificationMasterApp.getInstance().database.notificationDao()
                val notification = try {
                    dao.querySync(spec).firstOrNull()
                } catch (_: Exception) { null }

                val views = RemoteViews(appContext.packageName, R.layout.widget_notification_single)
                views.setTextViewText(R.id.widget_single_type, typeLabel)
                views.setOnClickPendingIntent(R.id.widget_single_type, typePendingIntent)

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
