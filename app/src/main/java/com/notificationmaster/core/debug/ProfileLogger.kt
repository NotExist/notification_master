package com.notificationmaster.core.debug

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 29：Profile log，寫到 app data folder（不走 logcat — logcat 篩選擷取困難）。
 *
 * 位置：`<context.filesDir>/profile_log/timeline.log`
 * 格式：`HH:mm:ss.SSS [TAG] message` per line
 *
 * Thread-safe append-only。Debug 用。
 *
 * 取出 log：
 * ```
 * adb shell run-as com.notificationmaster cat files/profile_log/timeline.log > timeline.log
 * ```
 */
object ProfileLogger {

    private const val DIR_NAME = "profile_log"
    private const val FILE_NAME = "timeline.log"
    private const val TAG = "ProfileLogger"
    private val initLock = AtomicBoolean(false)
    @Volatile private var logFile: File? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        if (initLock.compareAndSet(false, true)) {
            try {
                val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
                logFile = File(dir, FILE_NAME)
            } catch (e: Exception) {
                Log.e(TAG, "init failed", e)
            }
        }
    }

    fun append(tag: String, message: String) {
        val file = logFile ?: return
        try {
            synchronized(this) {
                file.appendText("${timeFmt.format(Date())} [$tag] $message\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "append failed", e)
        }
    }

    @Suppress("unused")
    fun clear() {
        val file = logFile ?: return
        try {
            synchronized(this) { file.writeText("") }
        } catch (_: Exception) {}
    }
}
