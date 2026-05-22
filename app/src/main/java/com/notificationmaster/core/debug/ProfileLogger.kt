package com.notificationmaster.core.debug

import android.content.Context
import android.os.Environment
import android.util.Log
import com.notificationmaster.core.prefs.AppPreferences
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 29：Profile log。
 * 格式：`HH:mm:ss.SSS [TAG] message` per line。Thread-safe append-only。
 *
 * Phase 31k：對齊 DebugDumper —
 * 1. 寫入受 `AppPreferences.isDebugDumperEnabled` 共用控制（settings 啟用 debug 才寫）。
 *    disable 時不砍既有檔案（user 可事後查看），只停止寫入。
 * 2. 寫入路徑首選 Public Documents（File Manager / SMB 跨機可見），fallback App-specific
 *    External。對齊 DebugDumper 的 `dumpDir` 策略。
 *
 * logFile 延遲到首次 append 時 resolve（隨 isEnabled toggle 自然啟動）；resolve 後緩存
 * 直到 process 結束 — toggle 改 disable 後 reference 仍在但 append 入口已 gate。
 */
object ProfileLogger {

    private const val DIR_NAME = "profile_log"
    private const val FILE_NAME = "timeline.log"
    private const val PUBLIC_PARENT_DIR = "NotificationMaster"
    private const val TAG = "ProfileLogger"
    @Volatile private var appContext: Context? = null
    @Volatile private var logFile: File? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun append(tag: String, message: String) {
        val ctx = appContext ?: return
        if (!AppPreferences.isDebugDumperEnabled(ctx)) return
        val file = resolveLogFile(ctx) ?: return
        try {
            synchronized(this) {
                file.appendText("${timeFmt.format(Date())} [$tag] $message\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "append failed", e)
        }
    }

    private fun resolveLogFile(ctx: Context): File? {
        logFile?.let { return it }
        return synchronized(this) {
            logFile ?: try {
                val publicDocs = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOCUMENTS
                )
                val publicDir = File(File(publicDocs, PUBLIC_PARENT_DIR), DIR_NAME)
                val baseDir = if (publicDir.mkdirs() || publicDir.isDirectory) {
                    publicDir
                } else {
                    File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, DIR_NAME)
                        .apply { mkdirs() }
                }
                File(baseDir, FILE_NAME).also { logFile = it }
            } catch (e: Exception) {
                Log.e(TAG, "resolveLogFile failed", e)
                null
            }
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
