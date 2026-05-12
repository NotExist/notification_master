package com.notificationmaster.core.content

import android.content.Context
import com.notificationmaster.core.cache.AppLabelCache
import com.notificationmaster.ui.common.NotificationDisplay

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
 * Plan 2 Phase 8：與具體 Entity 類型解耦，所有函式接原子欄位（或 [NotificationDisplay] 攤平）。
 * 呼叫端責任：從 entity / snapshot 取出欄位後傳入。
 */
object NotificationContentHelper {

    /** 取得 App 顯示名稱（透過 PackageManager） */
    fun appName(context: Context, packageName: String): String =
        AppLabelCache.getLabel(context, packageName)

    /** 取得顯示用標題（null 時回傳 fallback） */
    fun displayTitleOf(title: String?, fallback: String = "通知"): String =
        title ?: fallback

    /**
     * 取得最完整的內容文字
     *
     * bigText 是 text 的展開版（BigTextStyle），存在時 text 通常是其截斷。
     * 優先使用 bigText 避免重複。
     */
    fun fullContentOf(text: String?, bigText: String?): String? =
        bigText ?: text

    /** 組合標題和內容（用於剪貼簿等單一文字輸出） */
    fun titleAndContentOf(
        title: String?,
        text: String?,
        bigText: String?,
        fallbackTitle: String = ""
    ): String {
        val mainTitle = title ?: fallbackTitle
        val content = fullContentOf(text, bigText)
        return when {
            mainTitle.isNotEmpty() && !content.isNullOrEmpty() -> "$mainTitle\n$content"
            mainTitle.isNotEmpty() -> mainTitle
            !content.isNullOrEmpty() -> content
            else -> ""
        }
    }

    // ========== 匯出用格式化（CalendarExporter / IcsExporter 共用） ==========

    /** 匯出用事件標題：[appName] title */
    fun exportTitleOf(context: Context, packageName: String, title: String?): String {
        return "[${appName(context, packageName)}] ${displayTitleOf(title)}"
    }

    /** 匯出用地點：channelId / packageName */
    fun exportLocationOf(packageName: String, channelId: String?): String {
        return if (channelId != null) "$channelId / $packageName" else packageName
    }

    /**
     * 匯出用描述
     */
    fun exportDescriptionOf(
        text: String?,
        bigText: String?,
        subText: String?,
        isOngoing: Boolean,
        isForegroundService: Boolean,
        level: ExportDetailLevel
    ): String {
        return buildString {
            when (level) {
                ExportDetailLevel.TITLE_ONLY -> { /* 標題在 title 欄位，描述留空 */ }
                ExportDetailLevel.WITH_CONTENT -> {
                    fullContentOf(text, bigText)?.let { append(it) }
                }
                ExportDetailLevel.FULL -> {
                    fullContentOf(text, bigText)?.let { append(it) }

                    subText?.let {
                        if (isNotEmpty()) append("\n")
                        append(it)
                    }

                    val flags = mutableListOf<String>()
                    if (isOngoing) flags.add("Ongoing")
                    if (isForegroundService) flags.add("FG Service")
                    if (flags.isNotEmpty()) {
                        append("\n\n-- NotificationMaster --\n")
                        append("Flags: ${flags.joinToString(", ")}")
                        append("\n-- NotificationMaster --")
                    }
                }
            }
        }
    }

    // ========== NotificationDisplay overload（攤平模型給 Exporter 用） ==========

    fun displayTitle(display: NotificationDisplay, fallback: String = "通知"): String =
        displayTitleOf(display.title, fallback)

    fun fullContent(display: NotificationDisplay): String? =
        fullContentOf(display.text, display.bigText)

    fun titleAndContent(display: NotificationDisplay, fallbackTitle: String = ""): String =
        titleAndContentOf(display.title, display.text, display.bigText, fallbackTitle)

    fun exportTitle(context: Context, display: NotificationDisplay): String =
        exportTitleOf(context, display.packageName, display.title)

    fun exportLocation(display: NotificationDisplay): String =
        exportLocationOf(display.packageName, display.channelId)

    fun exportDescription(display: NotificationDisplay, level: ExportDetailLevel): String =
        exportDescriptionOf(
            text = display.text,
            bigText = display.bigText,
            subText = display.subText,
            isOngoing = display.isOngoing,
            isForegroundService = display.isForegroundService,
            level = level
        )
}
