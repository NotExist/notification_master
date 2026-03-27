package com.notificationmaster.core.content

import android.content.Context
import com.notificationmaster.core.cache.AppLabelCache
import com.notificationmaster.data.db.entity.NotificationEntity

/**
 * 匯出精細程度
 */
enum class ExportDetailLevel {
    /** 僅標題（描述留空） */
    TITLE_ONLY,
    /** 標題 + 主要內容 */
    WITH_CONTENT,
    /** 完整：內容 + subText + metadata flags */
    FULL;

    fun displayName(): String = when (this) {
        TITLE_ONLY -> "僅標題"
        WITH_CONTENT -> "含內容"
        FULL -> "完整資訊"
    }
}

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

    /** 取得 App 顯示名稱（透過 PackageManager），需要 Context */
    fun appName(context: Context, packageName: String): String =
        AppLabelCache.getLabel(context, packageName)

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
    fun exportTitle(context: Context, notification: NotificationEntity): String {
        return "[${appName(context, notification.packageName)}] ${displayTitle(notification)}"
    }

    /** 匯出用地點：channelId / packageName */
    fun exportLocation(notification: NotificationEntity): String {
        val channelId = notification.channelId
        return if (channelId != null) "$channelId / ${notification.packageName}"
        else notification.packageName
    }

    /**
     * 匯出用描述
     */
    fun exportDescription(notification: NotificationEntity, level: ExportDetailLevel): String {
        return buildString {
            when (level) {
                ExportDetailLevel.TITLE_ONLY -> { /* 標題在 title 欄位，描述留空 */ }
                ExportDetailLevel.WITH_CONTENT -> {
                    fullContent(notification)?.let { append(it) }
                }
                ExportDetailLevel.FULL -> {
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
}
