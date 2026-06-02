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
 * `Documents/NotificationMaster/debug/profile_log/timeline_<sessionTs>.log`。
 *
 * W22：檔名加 process 啟動 timestamp（`yyyyMMdd_HHmmss_SSS` 精度到 ms），每個 process
 * session 獨立檔案。舊版固定 `timeline.log` 跨 session append，新舊版本 / 重灌 APK 後
 * log 黏在一起難辨識；session timestamp 後 user 看檔名即知對應哪次啟動。
 *
 * 精度到 ms 是為了避免「ANR/crash 後立即重啟」或「OEM 自動 rebind NLS」場景同秒內撞檔。
 *
 * logFile 延遲到首次 append 時 resolve（隨 isEnabled toggle 自然啟動）；resolve 後緩存
 * 直到 process 結束 — toggle 改 disable 後 reference 仍在但 append 入口已 gate。
 */
object ProfileLogger {

    /** Process 啟動時生成的 session timestamp（精度到 ms），整個 process 生命週期固定 */
    private val SESSION_TS: String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    private val FILE_NAME = "timeline_$SESSION_TS.log"
    private const val TAG = "ProfileLogger"
    @Volatile private var appContext: Context? = null
    @Volatile private var logFile: File? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun append(tag: String, message: String) {
        val ctx = appContext ?: return
        // W10：個別 tag 開關（內部 chained 檢查 isDebugDumperEnabled）
        if (!AppPreferences.isDebugTagEnabled(ctx, tag)) return
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
