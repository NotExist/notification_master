package com.notificationmaster.export.ical

import android.content.Context
import com.notificationmaster.core.content.ExportDetailLevel
import com.notificationmaster.core.content.NotificationContentHelper
import com.notificationmaster.ui.common.NotificationDisplay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * RFC 5545 iCalendar (.ics) 檔案產生器
 *
 * 純 Kotlin 手工建構，不依賴第三方套件。
 * 時間格式使用 UTC（yyyyMMdd'T'HHmmss'Z'）。
 *
 * Plan 2 Phase 8：改吃 [NotificationDisplay]（NotificationEventEntity + snapshot 攤平結果），
 * 對應「每個 notification_key 取最新一筆 event」之後的呈現。
 */
class IcsExporter(private val context: Context) {

    private val utcDateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * 將 NotificationDisplay 清單轉為 iCalendar 格式字串
     */
    fun export(
        displays: List<NotificationDisplay>,
        detailLevel: ExportDetailLevel = ExportDetailLevel.FULL
    ): String {
        return buildString {
            appendLine("BEGIN:VCALENDAR")
            appendLine("VERSION:2.0")
            appendLine("PRODID:-//Notification Master//NONSGML v1.0//EN")
            appendLine("CALSCALE:GREGORIAN")
            appendLine("METHOD:PUBLISH")

            for (display in displays) {
                appendVEvent(this, display, detailLevel)
            }

            appendLine("END:VCALENDAR")
        }
    }

    private fun appendVEvent(
        sb: StringBuilder,
        display: NotificationDisplay,
        detailLevel: ExportDetailLevel
    ) {
        val uid = "${display.eventId}@notificationmaster"
        val dtStart = formatIcsDateTime(display.postTime)
        val dtEnd = formatIcsDateTime(display.postTime + 60_000)
        val title = NotificationContentHelper.exportTitle(context, display)
        val description = NotificationContentHelper.exportDescription(display, detailLevel)

        sb.appendLine("BEGIN:VEVENT")
        sb.appendLine("UID:$uid")
        sb.appendLine("DTSTAMP:${formatIcsDateTime(System.currentTimeMillis())}")
        sb.appendLine("DTSTART:$dtStart")
        sb.appendLine("DTEND:$dtEnd")
        sb.appendLine("SUMMARY:${escapeIcsText(title)}")
        sb.appendLine("LOCATION:${escapeIcsText(NotificationContentHelper.exportLocation(display))}")
        if (description.isNotEmpty()) {
            sb.appendLine("DESCRIPTION:${escapeIcsText(description)}")
        }
        sb.appendLine("STATUS:TENTATIVE")
        sb.appendLine("TRANSP:TRANSPARENT")
        sb.appendLine("END:VEVENT")
    }

    /** 格式: 20260309T123456Z (UTC) */
    private fun formatIcsDateTime(millis: Long): String {
        return utcDateFormat.format(Date(millis))
    }

    /** RFC 5545 文字跳脫：反斜線、分號、逗號、換行 */
    private fun escapeIcsText(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\n", "\\n")
    }
}
