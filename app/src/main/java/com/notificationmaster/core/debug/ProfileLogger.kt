package com.notificationmaster.core.debug

import android.content.Context
import android.util.Log
import com.notificationmaster.core.prefs.AppPreferences
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 29：Profile log。
 * 格式：`HH:mm:ss.SSS [TAG] message` per line。Thread-safe append-only。
 *
 * 寫入受 `AppPreferences.isDebugDumperEnabled` 共用控制（settings 啟用 debug 才寫）。
 * disable 時不砍既有檔案（user 可事後查看），只停止寫入。
 *
 * 路徑由 [DebugPaths] 統一派生（Plan 1-zippy-thunder W4），落在
 * `Documents/NotificationMaster/debug/profile_log/timeline.log`。
 *
 * logFile 延遲到首次 append 時 resolve（隨 isEnabled toggle 自然啟動）；resolve 後緩存
 * 直到 process 結束 — toggle 改 disable 後 reference 仍在但 append 入口已 gate。
 */
object ProfileLogger {

    private const val FILE_NAME = "timeline.log"
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
            val ts = timeFmt.format(Date())
            val thread = Thread.currentThread().name
            val line = "$ts [$tag/T:$thread] $message\n"
            // W12：用 FileOutputStream + fd.sync 確保寫入即時刷到磁碟，
            // 避免 process killed 時最後幾 KB buffer 遺失（user 觀察「中斷」原因之一）。
            synchronized(this) {
                FileOutputStream(file, true).use { fos ->
                    fos.write(line.toByteArray())
                    fos.fd.sync()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "append failed", e)
        }
    }

    private fun resolveLogFile(ctx: Context): File? {
        logFile?.let { return it }
        return synchronized(this) {
            logFile ?: try {
                val baseDir = DebugPaths.resolve(ctx, DebugPaths.Sink.PROFILE_LOG)
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
