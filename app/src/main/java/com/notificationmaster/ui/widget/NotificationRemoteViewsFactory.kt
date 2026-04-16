package com.notificationmaster.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.R
import com.notificationmaster.core.cache.AppLabelCache
import com.notificationmaster.core.filter.MatchContext
import com.notificationmaster.core.filter.Matcher
import com.notificationmaster.core.prefs.AppPreferences
import com.notificationmaster.data.db.entity.NotificationEntity
import com.notificationmaster.ui.main.MainActivity
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 清單式 Widget 的資料填充
 * 在 binder thread 執行，使用同步 DAO 查詢 + 記憶體內 matcher 篩選
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
        val matchersJson = AppPreferences.getWidgetMatchers(context, appWidgetId)
        if (matchersJson == null) {
            notifications = emptyList()
            return
        }

        val matchers = try {
            val arr = JSONArray(matchersJson)
            (0 until arr.length()).map { Matcher.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            notifications = emptyList()
            return
        }

        // 統一查詢：每個 key 的最新 entity
        val candidates = dao.getRecentNotificationsSync(200)

        // 記憶體內 matcher 篩選
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

        // dismissed 排序：若 matchers 含 DerivedProperty(isRemoved=true)，按 removedAt DESC
        val hasDismissedFilter = matchers.any { it is Matcher.DerivedProperty && (it as Matcher.DerivedProperty).isRemoved == true }
        notifications = if (hasDismissedFilter) {
            filtered.sortedByDescending { it.removedAt ?: 0L }.take(20)
        } else {
            filtered.take(20) // 已按 post_time DESC
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
