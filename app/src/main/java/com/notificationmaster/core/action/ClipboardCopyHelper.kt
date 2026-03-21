package com.notificationmaster.core.action

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.notificationmaster.core.content.NotificationContentHelper
import com.notificationmaster.core.filter.KeywordField
import com.notificationmaster.core.filter.Matcher
import com.notificationmaster.data.db.entity.NotificationEntity

/**
 * 剪貼簿複製共用邏輯
 *
 * 供 NotificationCaptureService 即時觸發和 FilterRuleDialogHelper 預覽套用共用。
 *
 * 兩種模式：
 * - 無 regex：複製 title + 最完整內容（bigText ?: text）
 * - 有 regex：對每個匹配欄位提取 capture groups，各自獨立複製
 */
object ClipboardCopyHelper {

    /**
     * 複製單筆通知到剪貼簿
     *
     * @param context Context
     * @param notification 通知 entity
     * @param keywordMatcher 規則中的 Keyword matcher（null 時為非 regex 模式）
     * @return 複製的條目數
     */
    fun copyToClipboard(
        context: Context,
        notification: NotificationEntity,
        keywordMatcher: Matcher.Keyword? = null
    ): Int {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        var count = 0

        if (keywordMatcher != null && keywordMatcher.isRegex) {
            val regex = try { Regex(keywordMatcher.pattern) } catch (_: Exception) { return 0 }
            val fieldTexts = keywordMatcher.fields.mapNotNull { field ->
                when (field) {
                    KeywordField.TITLE -> notification.title
                    KeywordField.TEXT -> notification.text
                    KeywordField.BIG_TEXT -> notification.bigText
                    KeywordField.SUB_TEXT -> notification.subText
                }?.let { field to it }
            }
            for ((field, text) in fieldTexts) {
                val allMatches = regex.findAll(text).toList()
                if (allMatches.isEmpty()) continue
                val groups = allMatches.flatMap { it.groupValues }
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("NM:${field.name}", groups.joinToString(" "))
                )
                count++
            }
        } else {
            val clipText = NotificationContentHelper.titleAndContent(notification)
            if (clipText.isNotEmpty()) {
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("NotificationMaster", clipText)
                )
                count++
            }
        }
        return count
    }

    /**
     * 從 MatchContext 複製到剪貼簿（Service 即時觸發用）
     *
     * 與 copyToClipboard 的差異在於資料來源是 MatchContext 而非 NotificationEntity，
     * 因為 Service 層的 regex 匹配需要用 MatchContext 的欄位。
     */
    fun copyFromMatchContext(
        context: Context,
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        keywordMatcher: Matcher.Keyword? = null
    ): Int {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        var count = 0

        if (keywordMatcher != null && keywordMatcher.isRegex) {
            val regex = try { Regex(keywordMatcher.pattern) } catch (_: Exception) { return 0 }
            val fieldTexts = keywordMatcher.fields.mapNotNull { field ->
                when (field) {
                    KeywordField.TITLE -> title
                    KeywordField.TEXT -> text
                    KeywordField.BIG_TEXT -> bigText
                    KeywordField.SUB_TEXT -> subText
                }?.let { field to it }
            }
            for ((field, fieldText) in fieldTexts) {
                val allMatches = regex.findAll(fieldText).toList()
                if (allMatches.isEmpty()) continue
                val groups = allMatches.flatMap { it.groupValues }
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("NM:${field.name}", groups.joinToString(" "))
                )
                count++
            }
        } else {
            val mainTitle = title ?: ""
            val content = bigText ?: text ?: ""
            val clipText = when {
                mainTitle.isNotEmpty() && content.isNotEmpty() -> "$mainTitle\n$content"
                mainTitle.isNotEmpty() -> mainTitle
                content.isNotEmpty() -> content
                else -> ""
            }
            if (clipText.isNotEmpty()) {
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("NotificationMaster", clipText)
                )
                count++
            }
        }
        return count
    }
}
