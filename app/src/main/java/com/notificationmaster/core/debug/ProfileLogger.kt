package com.notificationmaster.core.debug

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 29：Profile log，寫到 external app-specific storage（與 MediaExtractor 同層，
 * File Manager 直接可見：`/storage/emulated/0/Android/data/<pkg>/files/profile_log/`）。
 * 不走 logcat — logcat 篩選擷取困難。
 *
 * 格式：`HH:mm:ss.SSS [TAG] message` per line
 *
 * Thread-safe append-only。Debug 用。
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
                // 對齊 MediaExtractor：優先 external app-specific（File Manager 可見），fallback 到 internal
                val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
                val dir = File(baseDir, DIR_NAME).apply { mkdirs() }
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
