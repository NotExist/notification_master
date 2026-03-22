package com.notificationmaster.export.calendar

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.notificationmaster.R
import com.notificationmaster.core.content.NotificationContentHelper
import com.notificationmaster.data.db.entity.NotificationEntity
import java.util.TimeZone

/**
 * Calendar Provider 整合
 * 透過 ContentProvider 將通知記錄寫入系統日曆
 *
 * 所需權限：READ_CALENDAR + WRITE_CALENDAR（危險權限，需 runtime 請求）
 * 預設關閉
 */
class CalendarExporter(private val context: Context) {

    companion object {
        private const val TAG = "CalendarExporter"

        /** 匯出精細程度（委派至 NotificationContentHelper） */
        const val DETAIL_TITLE_ONLY = NotificationContentHelper.DETAIL_TITLE_ONLY
        const val DETAIL_WITH_CONTENT = NotificationContentHelper.DETAIL_WITH_CONTENT
        const val DETAIL_FULL = NotificationContentHelper.DETAIL_FULL

        /** Local Calendar 常數 */
        const val LOCAL_CALENDAR_NAME = "Local SyncAdapter"
        const val LOCAL_ACCOUNT_TYPE = CalendarContract.ACCOUNT_TYPE_LOCAL
        const val LOCAL_ACCOUNT_NAME = "Notification Master"
    }

    /**
     * 檢查日曆權限
     */
    fun hasCalendarPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 取得或建立 Local Calendar
     * @return CalendarInfo，或 null（權限不足）
     */
    fun getOrCreateLocalCalendar(): CalendarInfo? {
        if (!hasCalendarPermission()) return null

        // 先查詢是否已存在
        val existing = findLocalCalendar()
        if (existing != null) return existing

        // 建立新的 local calendar
        // CALLER_IS_SYNCADAPTER 必須用於設定 ACCOUNT_TYPE
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT_NAME)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, LOCAL_ACCOUNT_TYPE)
            .build()

        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT_NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, LOCAL_ACCOUNT_TYPE)
            put(CalendarContract.Calendars.NAME, LOCAL_CALENDAR_NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, LOCAL_CALENDAR_NAME)
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF4CAF50.toInt()) // Material Green
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, LOCAL_ACCOUNT_NAME)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }

        return try {
            val resultUri = context.contentResolver.insert(uri, values)
            val calId = ContentUris.parseId(resultUri ?: return null)

            Log.d(TAG, "Created local calendar with id=$calId")

            CalendarInfo(
                id = calId,
                displayName = LOCAL_CALENDAR_NAME,
                accountName = LOCAL_ACCOUNT_NAME,
                accountType = LOCAL_ACCOUNT_TYPE,
                isLocal = true
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create local calendar", e)
            null
        }
    }

    /**
     * 查詢已存在的 Local Calendar
     */
    private fun findLocalCalendar(): CalendarInfo? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME
        )
        val selection = "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND " +
                "${CalendarContract.Calendars.ACCOUNT_TYPE} = ?"
        val selectionArgs = arrayOf(LOCAL_ACCOUNT_NAME, LOCAL_ACCOUNT_TYPE)

        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idIndex = cursor.getColumnIndex(CalendarContract.Calendars._ID)
                val nameIndex = cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                return CalendarInfo(
                    id = cursor.getLong(idIndex),
                    displayName = cursor.getString(nameIndex) ?: LOCAL_CALENDAR_NAME,
                    accountName = LOCAL_ACCOUNT_NAME,
                    accountType = LOCAL_ACCOUNT_TYPE,
                    isLocal = true
                )
            }
        }
        return null
    }

    /**
     * 取得可用的日曆清單
     *
     * @return 日曆 ID 與名稱的配對清單
     */
    fun getAvailableCalendars(): List<CalendarInfo> {
        if (!hasCalendarPermission()) return emptyList()

        val calendars = mutableListOf<CalendarInfo>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )

        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val selectionArgs = arrayOf(
            CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()
        )

        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(CalendarContract.Calendars._ID)
            val nameIndex = cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val accountNameIndex = cursor.getColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME)
            val accountTypeIndex = cursor.getColumnIndex(CalendarContract.Calendars.ACCOUNT_TYPE)

            while (cursor.moveToNext()) {
                val accountType = cursor.getString(accountTypeIndex) ?: ""
                calendars.add(
                    CalendarInfo(
                        id = cursor.getLong(idIndex),
                        displayName = cursor.getString(nameIndex) ?: "",
                        accountName = cursor.getString(accountNameIndex) ?: "",
                        accountType = accountType,
                        isLocal = accountType == CalendarContract.ACCOUNT_TYPE_LOCAL
                    )
                )
            }
        }

        return calendars
    }

    /**
     * 匯出通知到指定日曆
     *
     * @param notifications 要匯出的通知清單
     * @param calendarId 目標日曆 ID
     * @param detailLevel 精細程度
     * @return 成功匯出的數量
     */
    fun exportToCalendar(
        notifications: List<NotificationEntity>,
        calendarId: Long,
        detailLevel: Int = DETAIL_WITH_CONTENT
    ): ExportResult {
        if (!hasCalendarPermission()) {
            return ExportResult(0, notifications.size, "缺少日曆權限")
        }

        var successCount = 0
        var failCount = 0
        val errors = mutableListOf<String>()

        for (notification in notifications) {
            try {
                val eventId = insertCalendarEvent(notification, calendarId, detailLevel)
                if (eventId > 0) successCount++ else failCount++
            } catch (e: Exception) {
                failCount++
                if (errors.size < 5) {
                    errors.add("${notification.packageName}: ${e.message}")
                }
            }
        }

        val errorMsg = if (errors.isNotEmpty()) {
            errors.joinToString("\n")
        } else null

        return ExportResult(successCount, failCount, errorMsg)
    }

    /**
     * 匯出單筆通知到日曆（即時匯出用）
     * @return 事件 ID（>0 表示成功），-1L 表示失敗
     */
    fun exportSingleNotification(
        notification: NotificationEntity,
        calendarId: Long,
        detailLevel: Int = DETAIL_WITH_CONTENT
    ): Long {
        if (!hasCalendarPermission()) return -1L
        return try {
            insertCalendarEvent(notification, calendarId, detailLevel)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to export notification to calendar", e)
            -1L
        }
    }

    /**
     * 插入單筆日曆事件
     * @return 事件 ID（>0 表示成功），-1L 表示失敗
     */
    private fun insertCalendarEvent(
        notification: NotificationEntity,
        calendarId: Long,
        detailLevel: Int
    ): Long {
        val title = buildEventTitle(notification, detailLevel)
        val description = buildEventDescription(notification, detailLevel)

        val ops = arrayListOf(
            // Op 0: 插入事件
            ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
                .withValue(CalendarContract.Events.CALENDAR_ID, calendarId)
                .withValue(CalendarContract.Events.TITLE, title)
                .withValue(CalendarContract.Events.DESCRIPTION, description)
                .withValue(CalendarContract.Events.EVENT_LOCATION, buildEventLocation(notification))
                .withValue(CalendarContract.Events.DTSTART, notification.postTime)
                .withValue(CalendarContract.Events.DTEND, notification.postTime)
                .withValue(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                .withValue(CalendarContract.Events.HAS_ALARM, 0)
                .withValue(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_TENTATIVE)
                .withValue(CalendarContract.Events.CUSTOM_APP_URI, notification.notificationKey)
                .build(),
            // Op 1: 附加 URL 擴充屬性，EVENT_ID 回參 Op 0
            ContentProviderOperation.newInsert(CalendarContract.ExtendedProperties.CONTENT_URI)
                .withValueBackReference(CalendarContract.ExtendedProperties.EVENT_ID, 0)
                .withValue(CalendarContract.ExtendedProperties.NAME, "URL")
                .withValue(CalendarContract.ExtendedProperties.VALUE, notification.notificationKey)
                .build()
        )

        val results = context.contentResolver.applyBatch(CalendarContract.AUTHORITY, ops)
        val eventUri = results[0].uri
        return if (eventUri != null) ContentUris.parseId(eventUri) else -1L
    }

    /**
     * 更新日曆事件的結束時間
     * @return 是否成功
     */
    fun updateCalendarEventEndTime(eventId: Long, endTime: Long): Boolean {
        if (!hasCalendarPermission()) return false
        return try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.DTEND, endTime)
            }
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            context.contentResolver.update(uri, values, null, null) > 0
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update calendar event end time", e)
            false
        }
    }

    /**
     * 更新日曆事件的標題和描述（UPDATED 通知用）
     * @return 是否成功
     */
    fun updateCalendarEventContent(eventId: Long, notification: NotificationEntity, detailLevel: Int): Boolean {
        if (!hasCalendarPermission()) return false
        return try {
            val title = buildEventTitle(notification, detailLevel)
            val description = buildEventDescription(notification, detailLevel)
            val values = ContentValues().apply {
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.EVENT_LOCATION, buildEventLocation(notification))
            }
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            context.contentResolver.update(uri, values, null, null) > 0
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update calendar event content", e)
            false
        }
    }

    /**
     * 透過 CUSTOM_APP_URI（notification_key）回找日曆事件
     * @return 事件 ID（>0 表示找到），-1L 表示未找到
     */
    fun findEventByNotificationKey(calendarId: Long, notificationKey: String): Long {
        if (!hasCalendarPermission()) return -1L
        return try {
            val projection = arrayOf(CalendarContract.Events._ID)
            val selection = "${CalendarContract.Events.CALENDAR_ID} = ? AND " +
                    "${CalendarContract.Events.CUSTOM_APP_URI} = ?"
            val selectionArgs = arrayOf(calendarId.toString(), notificationKey)

            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getLong(0)
                } else -1L
            } ?: -1L
        } catch (e: Exception) {
            Log.w(TAG, "Failed to find calendar event by notification key", e)
            -1L
        }
    }

    private fun buildEventTitle(notification: NotificationEntity, @Suppress("UNUSED_PARAMETER") detailLevel: Int): String =
        NotificationContentHelper.exportTitle(notification)

    private fun buildEventLocation(notification: NotificationEntity): String =
        NotificationContentHelper.exportLocation(notification)

    private fun buildEventDescription(notification: NotificationEntity, detailLevel: Int): String =
        NotificationContentHelper.exportDescription(notification, detailLevel)

    // ========== 日曆選擇器 UI ==========

    /**
     * 顯示日曆選擇 Dialog（雙行佈局：displayName + accountName · accountType）
     *
     * 共用日曆選擇器，供 SettingsFragment 和 FilterRuleDialogHelper 等處呼叫。
     * 自動確保 Local Calendar 存在並列出所有可寫入日曆。
     *
     * @param title Dialog 標題資源 ID
     * @param onCancel 取消時的 callback
     * @param onSelected 選擇日曆後的 callback
     */
    fun showPickerDialog(
        title: Int = R.string.calendar_picker_title,
        onCancel: (() -> Unit)? = null,
        onSelected: (CalendarInfo) -> Unit
    ) {
        getOrCreateLocalCalendar()
        val calendars = getAvailableCalendars()

        if (calendars.isEmpty()) {
            Toast.makeText(context, R.string.filter_preview_calendar_no_calendar, Toast.LENGTH_SHORT).show()
            onCancel?.invoke()
            return
        }

        val adapter = object : ArrayAdapter<CalendarInfo>(
            context,
            android.R.layout.simple_list_item_2,
            android.R.id.text1,
            calendars
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val cal = getItem(position) ?: return view
                val text1 = view.findViewById<TextView>(android.R.id.text1)
                val text2 = view.findViewById<TextView>(android.R.id.text2)

                val isOwnCalendar = cal.accountName == LOCAL_ACCOUNT_NAME
                    && cal.accountType == LOCAL_ACCOUNT_TYPE
                text1.text = if (isOwnCalendar) {
                    context.getString(R.string.calendar_picker_own_label, cal.displayName)
                } else {
                    cal.displayName
                }
                text2.text = context.getString(R.string.calendar_picker_detail, cal.accountName, cal.accountType)

                return view
            }
        }

        AlertDialog.Builder(context)
            .setTitle(title)
            .setAdapter(adapter) { _, which -> onSelected(calendars[which]) }
            .setNegativeButton(R.string.cancel) { _, _ -> onCancel?.invoke() }
            .show()
    }
}

/**
 * 日曆資訊
 */
data class CalendarInfo(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val accountType: String,
    val isLocal: Boolean = false
)

/**
 * 匯出結果
 */
data class ExportResult(
    val successCount: Int,
    val failCount: Int,
    val errorMessage: String?
)
