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
}
