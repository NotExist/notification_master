package com.notificationmaster.export.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
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

        /** 匯出精細程度 */
        const val DETAIL_TITLE_ONLY = 0
        const val DETAIL_WITH_CONTENT = 1
        const val DETAIL_FULL = 2

        /** Local Calendar 常數 */
        const val LOCAL_CALENDAR_NAME = "Notification Master"
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
                insertCalendarEvent(notification, calendarId, detailLevel)
                successCount++
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
     * @return 成功 true / 失敗 false
     */
    fun exportSingleNotification(
        notification: NotificationEntity,
        calendarId: Long,
        detailLevel: Int = DETAIL_WITH_CONTENT
    ): Boolean {
        if (!hasCalendarPermission()) return false
        return try {
            insertCalendarEvent(notification, calendarId, detailLevel)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to export notification to calendar", e)
            false
        }
    }

    /**
     * 插入單筆日曆事件
     */
    private fun insertCalendarEvent(
        notification: NotificationEntity,
        calendarId: Long,
        detailLevel: Int
    ): Uri? {
        val title = buildEventTitle(notification, detailLevel)
        val description = buildEventDescription(notification, detailLevel)

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.DTSTART, notification.postTime)
            put(CalendarContract.Events.DTEND, notification.postTime + 60_000) // 1 分鐘
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.HAS_ALARM, 0)
            put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
        }

        return context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
    }

    private fun buildEventTitle(notification: NotificationEntity, @Suppress("UNUSED_PARAMETER") detailLevel: Int): String {
        val appName = notification.packageName.substringAfterLast('.')
        val title = notification.title ?: "通知"
        return "[$appName] $title"
    }

    private fun buildEventDescription(notification: NotificationEntity, detailLevel: Int): String {
        return buildString {
            append("來源: ${notification.packageName}\n")

            when (detailLevel) {
                DETAIL_TITLE_ONLY -> {
                    // 只有標題，不含內容
                }
                DETAIL_WITH_CONTENT -> {
                    notification.text?.let { append("內容: $it\n") }
                }
                DETAIL_FULL -> {
                    notification.text?.let { append("內容: $it\n") }
                    notification.bigText?.let { append("展開: $it\n") }
                    notification.subText?.let { append("副文: $it\n") }
                    append("Channel: ${notification.channelId ?: "N/A"}\n")
                    append("Key: ${notification.notificationKey}\n")
                    if (notification.isOngoing) append("標記: Ongoing\n")
                    if (notification.isForegroundService) append("標記: Foreground Service\n")
                }
            }

            append("\n-- Notification Master --")
        }
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
