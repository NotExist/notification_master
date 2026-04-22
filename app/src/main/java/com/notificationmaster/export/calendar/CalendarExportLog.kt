package com.notificationmaster.export.calendar

import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 即時日曆匯出歷程：記憶體環形緩衝
 *
 * 記錄每次 checkRealtimeCalendarExport() 的結果（成功/跳過/失敗），
 * 供設定頁的歷程檢視器即時顯示。Process 終止後自動清除。
 *
 * 預設關閉，需由使用者在 Debug 區段手動開啟。
 */
object CalendarExportLog {

    data class Entry(
        val timestamp: Long,
        val packageName: String,
        val outcome: String,
        val detail: String?
    )

    private const val MAX_SIZE = 100
    private val buffer = ArrayDeque<Entry>(MAX_SIZE)

    /** 是否啟用記錄 */
    var enabled = false

    /** 記錄起點（開啟或清除時重設） */
    var startedAt: Long = System.currentTimeMillis()
        private set

    /** 每次 log() 遞增，供 UI collect 即時刷新 */
    val logFlow = MutableStateFlow(0L)

    fun log(packageName: String, outcome: String, detail: String? = null) {
        if (!enabled) return
        synchronized(buffer) {
            if (buffer.size >= MAX_SIZE) buffer.removeFirst()
            buffer.addLast(Entry(System.currentTimeMillis(), packageName, outcome, detail))
        }
        logFlow.value++
    }

    fun getEntries(): List<Entry> = synchronized(buffer) { buffer.toList() }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
        startedAt = System.currentTimeMillis()
        logFlow.value++
    }

    /**
     * 匯出為可讀純文字（分享用）
     */
    fun exportAsText(): String = buildString {
        appendLine("=== 日曆即時匯出歷程 ===")
        appendLine("記錄自 ${formatRfc3339(startedAt)}")
        appendLine()
        val entries = getEntries()
        if (entries.isEmpty()) {
            appendLine("（無記錄）")
        } else {
            for (entry in entries) {
                append(formatRfc3339(entry.timestamp))
                append("  [${entry.outcome}]")
                append("  ${entry.packageName}")
                entry.detail?.let { append("  $it") }
                appendLine()
            }
        }
    }

    fun formatRfc3339(epochMillis: Long): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            java.time.Instant.ofEpochMilli(epochMillis)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        } else {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.getDefault())
                .format(Date(epochMillis))
        }
    }
}
