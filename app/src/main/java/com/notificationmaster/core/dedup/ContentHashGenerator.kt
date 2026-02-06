package com.notificationmaster.core.dedup

import java.security.MessageDigest

/**
 * 內容 Hash 產生器
 * 用於去重檢視（非儲存層去重）
 */
object ContentHashGenerator {

    /**
     * 產生內容 Hash
     * 組合 packageName + title + text + bigText
     */
    fun generateHash(
        packageName: String,
        title: String?,
        text: String?,
        bigText: String?
    ): String {
        val content = buildString {
            append(packageName)
            append("|")
            append(title ?: "")
            append("|")
            append(text ?: "")
            append("|")
            append(bigText ?: "")
        }
        return sha256(content)
    }

    /**
     * 產生完整內容 Hash（包含更多欄位，用於偵測更新）
     */
    fun generateFullHash(
        packageName: String,
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        progress: Int,
        progressMax: Int
    ): String {
        val content = buildString {
            append(packageName)
            append("|")
            append(title ?: "")
            append("|")
            append(text ?: "")
            append("|")
            append(bigText ?: "")
            append("|")
            append(subText ?: "")
            append("|")
            append(progress)
            append("|")
            append(progressMax)
        }
        return sha256(content)
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
