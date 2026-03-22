package com.notificationmaster.core.content

import com.notificationmaster.data.db.entity.NotificationEntity

/**
 * 通知內容提取的共用 helper
 *
 * 統一各處對 NotificationEntity 欄位的提取邏輯，
 * 避免 `bigText ?: text`、`title ?: "通知"` 等模式散佈各處。
 */
object NotificationContentHelper {

    /** 取得顯示用標題（null 時回傳 fallback） */
    fun displayTitle(notification: NotificationEntity, fallback: String = "通知"): String =
        notification.title ?: fallback

    /**
     * 取得最完整的內容文字
     *
     * bigText 是 text 的展開版（BigTextStyle），存在時 text 通常是其截斷。
     * 優先使用 bigText 避免重複。
     */
    fun fullContent(notification: NotificationEntity): String? =
        notification.bigText ?: notification.text

    /** 從 packageName 提取簡短 App 名稱（最後一段） */
    fun appName(packageName: String): String =
        packageName.substringAfterLast('.')

    /** 組合標題和內容（用於剪貼簿等單一文字輸出） */
    fun titleAndContent(notification: NotificationEntity, fallbackTitle: String = ""): String {
        val title = notification.title ?: fallbackTitle
        val content = fullContent(notification)
        return when {
            title.isNotEmpty() && !content.isNullOrEmpty() -> "$title\n$content"
            title.isNotEmpty() -> title
            !content.isNullOrEmpty() -> content
            else -> ""
        }
    }

    // ========== 匯出用格式化（CalendarExporter / IcsExporter 共用） ==========

    /** 匯出用事件標題：[appName] title */
    fun exportTitle(notification: NotificationEntity): String {
        return "[${appName(notification.packageName)}] ${displayTitle(notification)}"
    }

    /** 匯出用地點：channelId / packageName */
    fun exportLocation(notification: NotificationEntity): String {
        val channelId = notification.channelId
        return if (channelId != null) "$channelId / ${notification.packageName}"
        else notification.packageName
    }

    /**
     * 匯出用描述（三個精細等級）
     *
     * @param detailLevel 0=僅標題, 1=含內容, 2=完整
     */
    fun exportDescription(notification: NotificationEntity, detailLevel: Int): String {
        return buildString {
            when (detailLevel) {
                DETAIL_TITLE_ONLY -> { /* 標題在 title 欄位，描述留空 */ }
                DETAIL_WITH_CONTENT -> {
                    fullContent(notification)?.let { append(it) }
                }
                DETAIL_FULL -> {
                    fullContent(notification)?.let { append(it) }

                    notification.subText?.let {
                        if (isNotEmpty()) append("\n")
                        append(it)
                    }

                    val flags = mutableListOf<String>()
                    if (notification.isOngoing) flags.add("Ongoing")
                    if (notification.isForegroundService) flags.add("FG Service")
                    if (flags.isNotEmpty()) {
                        append("\n\n-- NotificationMaster --\n")
                        append("Flags: ${flags.joinToString(", ")}")
                        append("\n-- NotificationMaster --")
                    }
                }
            }
        }
    }

    /** 精細等級常數 */
    const val DETAIL_TITLE_ONLY = 0
    const val DETAIL_WITH_CONTENT = 1
    const val DETAIL_FULL = 2
}
