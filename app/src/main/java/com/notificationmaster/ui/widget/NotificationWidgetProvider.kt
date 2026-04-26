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

/**
 * 清單式通知 Widget
 * 顯示可捲動的通知清單，點擊項目進入 Detail、點擊標題進入主頁
 */
class NotificationWidgetProvider : AppWidgetProvider() {

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
            val label = AppPreferences.getWidgetLabel(context, appWidgetId)
            val views = RemoteViews(context.packageName, R.layout.widget_notification_list)

            // 標題：label null 時退回 widget 名稱作為靜態識別
            views.setTextViewText(R.id.widget_title, label ?: context.getString(R.string.widget_list_name))

            // 綁 adapter + empty view。Factory 讀不到 spec 時回 emptyList，empty view 會自動顯示。
            val serviceIntent = Intent(context, NotificationRemoteViewsService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_list_view, serviceIntent)
            views.setEmptyView(R.id.widget_list_view, R.id.widget_empty)

            // 標題點擊 → Timeline 套用此 widget 綁定的 LIST_FILTER rule
            val ruleId = AppPreferences.getWidgetRuleId(context, appWidgetId)
            val titleIntent = Intent(context, MainActivity::class.java).apply {
                if (ruleId != null) {
                    action = MainActivity.ACTION_SHOW_FILTERED_TIMELINE
                    putExtra(MainActivity.EXTRA_RULE_ID, ruleId)
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val mainIntent = PendingIntent.getActivity(
                context, appWidgetId,
                titleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_title, mainIntent)

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
