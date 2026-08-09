package com.notificationmaster.core.alert

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 持續提醒診斷記錄
 *
 * 提醒管線各階段的成功/跳過/失敗記錄，SharedPreferences 環形緩衝
 * （跨 process 終止與重啟保留，供事後追查「規則有觸發但提醒沒出現」）。
 * 同時輸出統一 logcat tag [TAG]，`adb logcat -s NMAlert` 可過濾完整管線。
 *
 * stage 一覽（依管線順序）：
 *  - trigger     NLS 規則匹配成功（checkPersistentAlert）
 *  - guard       Manager 前置檢查（如 POST_NOTIFICATIONS 未授權跳過）
 *  - fgs_start   startForegroundService 呼叫（失敗＝背景啟動受限的主要嫌疑點）
 *  - service     Service onStartCommand 交接/節流/覆蓋判定
 *  - foreground  startForeground 前景通知
 *  - ringtone    鈴聲播放（含 fallback 至系統預設鬧鐘）
 *  - vibrate     振動啟動
 *  - stop        停止（detail 帶 reason：notification_button / notification_dismissed /
 *                activity / nls_destroy / no_data / foreground_failed …）
 */
object AlertDiagnostics {

    /** 統一 logcat tag：`adb logcat -s NMAlert` */
    const val TAG = "NMAlert"

    const val OUTCOME_OK = "ok"
    const val OUTCOME_SKIP = "skip"
    const val OUTCOME_FAIL = "fail"

    private const val PREFS_NAME = "alert_diagnostics"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_SIZE = 100

    data class Entry(
        val timestamp: Long,
        val stage: String,
        val outcome: String,
        val detail: String?
    )

    /**
     * 記錄一筆診斷。同步寫 logcat（fail→E、skip→W、ok→I）與 prefs 環形緩衝。
     */
    @Synchronized
    fun log(
        context: Context,
        stage: String,
        outcome: String,
        detail: String? = null,
        error: Throwable? = null
    ) {
        val fullDetail = buildString {
            if (detail != null) append(detail)
            if (error != null) {
                if (isNotEmpty()) append(" | ")
                append(error.javaClass.simpleName)
                error.message?.let { append(": ").append(it) }
            }
        }.ifEmpty { null }

        val msg = "[$stage] $outcome${fullDetail?.let { " — $it" } ?: ""}"
        when (outcome) {
            OUTCOME_FAIL -> Log.e(TAG, msg, error)
            OUTCOME_SKIP -> Log.w(TAG, msg)
            else -> Log.i(TAG, msg)
        }

        try {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(KEY_ENTRIES, "[]"))
            val trimmed = JSONArray()
            val start = maxOf(0, arr.length() - (MAX_SIZE - 1))
            for (i in start until arr.length()) trimmed.put(arr.getJSONObject(i))
            trimmed.put(JSONObject().apply {
                put("t", System.currentTimeMillis())
                put("s", stage)
                put("o", outcome)
                if (fullDetail != null) put("d", fullDetail)
            })
            prefs.edit().putString(KEY_ENTRIES, trimmed.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "診斷記錄寫入失敗", e)
        }
    }

    @Synchronized
    fun getEntries(context: Context): List<Entry> = try {
        val arr = JSONArray(
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_ENTRIES, "[]")
        )
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(
                timestamp = o.getLong("t"),
                stage = o.getString("s"),
                outcome = o.getString("o"),
                detail = o.optString("d").ifEmpty { null }
            )
        }
    } catch (e: Exception) {
        Log.e(TAG, "診斷記錄讀取失敗", e)
        emptyList()
    }

    /** 最近一筆非 ok 的記錄（設定頁摘要用）；無異常回傳 null */
    fun lastProblem(context: Context): Entry? =
        getEntries(context).lastOrNull { it.outcome != OUTCOME_OK }

    @Synchronized
    fun clear(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_ENTRIES).apply()
    }

    /** 可讀純文字（設定頁 Dialog / 分享用），最新在最下 */
    fun exportAsText(context: Context): String = buildString {
        val entries = getEntries(context)
        if (entries.isEmpty()) {
            append("（無記錄）")
            return@buildString
        }
        for (e in entries) {
            append(formatTime(e.timestamp))
            append("  [").append(e.stage).append("] ").append(e.outcome)
            e.detail?.let { append("\n    ").append(it) }
            appendLine()
        }
    }

    fun formatTime(epochMillis: Long): String {
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
