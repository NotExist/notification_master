package com.notificationmaster.export.ical

import com.notificationmaster.core.content.ExportDetailLevel
import com.notificationmaster.core.content.NotificationContentHelper
import com.notificationmaster.data.db.entity.NotificationEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * RFC 5545 iCalendar (.ics) 檔案產生器
 *
 * 純 Kotlin 手工建構，不依賴第三方套件。
 * 時間格式使用 UTC（yyyyMMdd'T'HHmmss'Z'）。
 */
class IcsExporter {

    private val utcDateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * 將通知清單轉為 iCalendar 格式字串
     */
    fun export(
        notifications: List<NotificationEntity>,
        detailLevel: ExportDetailLevel = ExportDetailLevel.FULL
    ): String {
        return buildString {
            appendLine("BEGIN:VCALENDAR")
            appendLine("VERSION:2.0")
            appendLine("PRODID:-//Notification Master//NONSGML v1.0//EN")
            appendLine("CALSCALE:GREGORIAN")
            appendLine("METHOD:PUBLISH")

            for (notification in notifications) {
                appendVEvent(this, notification, detailLevel)
            }

            appendLine("END:VCALENDAR")
        }
    }

    private fun appendVEvent(
        sb: StringBuilder,
        notification: NotificationEntity,
        detailLevel: ExportDetailLevel
    ) {
        val uid = "${notification.id}@notificationmaster"
        val dtStart = formatIcsDateTime(notification.postTime)
        val dtEnd = formatIcsDateTime(notification.postTime + 60_000)
        val title = buildEventTitle(notification)
        val description = buildEventDescription(notification, detailLevel)

        sb.appendLine("BEGIN:VEVENT")
        sb.appendLine("UID:$uid")
        sb.appendLine("DTSTAMP:${formatIcsDateTime(System.currentTimeMillis())}")
        sb.appendLine("DTSTART:$dtStart")
        sb.appendLine("DTEND:$dtEnd")
        sb.appendLine("SUMMARY:${escapeIcsText(title)}")
        sb.appendLine("LOCATION:${escapeIcsText(NotificationContentHelper.exportLocation(notification))}")
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

    private fun buildEventTitle(notification: NotificationEntity): String =
        NotificationContentHelper.exportTitle(notification)

    private fun buildEventDescription(notification: NotificationEntity, detailLevel: ExportDetailLevel): String =
        NotificationContentHelper.exportDescription(notification, detailLevel)
}
