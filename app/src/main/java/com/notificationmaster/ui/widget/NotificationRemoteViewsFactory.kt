package com.notificationmaster.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.R
import com.notificationmaster.core.cache.AppLabelCache
import com.notificationmaster.core.filter.ActionType
import com.notificationmaster.core.filter.RuleEngine
import com.notificationmaster.core.filter.RuleRepository
import com.notificationmaster.core.prefs.AppPreferences
import com.notificationmaster.data.db.dao.querySync
import com.notificationmaster.data.db.entity.NotificationEventEntity
import com.notificationmaster.data.filter.toFilterSpec
import com.notificationmaster.ui.main.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 清單式 Widget 的資料填充（Plan D — RuleEngine LIST_FILTER 路線）
 * 在 binder thread 執行，使用同步 DAO 查詢
 *
 * Plan 2 Phase 9：資料源改為 notification_events；EXTRA_NOTIFICATION_ID 傳遞 event.id（Detail 反查）。
 */
class NotificationRemoteViewsFactory(
    private val context: Context,
    intent: Intent
) : RemoteViewsService.RemoteViewsFactory {

    private val appWidgetId = intent.getIntExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID,
        AppWidgetManager.INVALID_APPWIDGET_ID
    )
    private var events: List<NotificationEventEntity> = emptyList()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate() {
        // RuleEngine 可能還沒載入（widget service process 啟動時 App.onCreate 已跑過，但保險起見）
        RuleRepository.load(context)
    }

    override fun onDataSetChanged() {
        val ruleId = AppPreferences.getWidgetRuleId(context, appWidgetId) ?: run {
            events = emptyList()
            return
        }
        val rule = RuleEngine.getRule(ruleId) ?: run {
            events = emptyList()
            return
        }
        if (rule.action.actionType != ActionType.LIST_FILTER) {
            events = emptyList()
            return
        }
        val spec = rule.toFilterSpec().let { if (it.limit == null) it.copy(limit = 20) else it }
        val dao = NotificationMasterApp.getInstance().database.notificationEventDao()
        events = try {
            dao.querySync(spec)
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun onDestroy() {
        events = emptyList()
    }

    override fun getCount(): Int = events.size

    override fun getViewAt(position: Int): RemoteViews {
        val event = events[position]
        return RemoteViews(context.packageName, R.layout.widget_notification_item).apply {
            setTextViewText(R.id.widget_item_title, event.title ?: "No Title")
            setTextViewText(R.id.widget_item_time, timeFormat.format(Date(event.postTime)))
            setTextViewText(R.id.widget_item_app, AppLabelCache.getLabel(context, event.packageName))
            // events 表只投影 title / text；big_text 需要 snapshot 解析，widget 不展開
            setTextViewText(R.id.widget_item_content, event.text ?: "")

            val fillInIntent = Intent().apply {
                putExtra(MainActivity.EXTRA_NOTIFICATION_ID, event.id)
            }
            setOnClickFillInIntent(R.id.widget_item_root, fillInIntent)
        }
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = events[position].id

    override fun hasStableIds(): Boolean = true
}
