package com.notificationmaster.export.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
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
        /** 匯出精細程度 */
        const val DETAIL_TITLE_ONLY = 0
        const val DETAIL_WITH_CONTENT = 1
        const val DETAIL_FULL = 2
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
                calendars.add(
                    CalendarInfo(
                        id = cursor.getLong(idIndex),
                        displayName = cursor.getString(nameIndex) ?: "",
                        accountName = cursor.getString(accountNameIndex) ?: "",
                        accountType = cursor.getString(accountTypeIndex) ?: "",
                        isGoogleCalendar = cursor.getString(accountTypeIndex) == "com.google"
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
    val isGoogleCalendar: Boolean
)

/**
 * 匯出結果
 */
data class ExportResult(
    val successCount: Int,
    val failCount: Int,
    val errorMessage: String?
)
