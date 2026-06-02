package com.notificationmaster.core.debug

import android.content.Context
import android.util.Log
import com.notificationmaster.core.prefs.AppPreferences
import java.io.File
import java.io.FileNotFoundException
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
 *
 * W22h：append 偵測 FileNotFoundException（parent dir 不存在，典型情境是 user 為了除錯
 * 把外部 profile_log 資料夾搬走）時自動 invalidate cache + 重 resolve + 重試一次；
 * DebugPaths.resolve 內 mkdirs 會重建被搬走的外部 dir，log 不中斷。
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

        val ts = timeFmt.format(Date())
        val thread = Thread.currentThread().name
        val line = "$ts [$tag/T:$thread] $message\n"

        val file = resolveLogFile(ctx) ?: return
        try {
            writeLine(file, line)
            return
        } catch (e: FileNotFoundException) {
            // W22h：parent dir 不存在（典型情境：user 把外部 profile_log 資料夾搬走除錯）
            // → invalidate logFile cache，下次 resolveLogFile 重 resolve 觸發
            // DebugPaths.resolve 內 mkdirs 重建外部 dir → 重試一次。
            Log.w(TAG, "append: parent dir missing, rebuild + retry", e)
            // fall through to retry
        } catch (e: Exception) {
            // 其他 IO 錯誤（device 滿 / 權限變動等）不重試，避免無限迴圈
            Log.e(TAG, "append failed", e)
            return
        }

        synchronized(this) { logFile = null }
        val rebuiltFile = resolveLogFile(ctx) ?: return
        try {
            writeLine(rebuiltFile, line)
        } catch (e: Exception) {
            Log.e(TAG, "append failed after rebuild", e)
        }
    }

    /**
     * W22h：寫入一行的核心邏輯抽出為 helper，供 [append] 主路徑與 FileNotFoundException
     * 重試路徑共用。同 W12 約定 — FileOutputStream + fd.sync 確保 process killed 前
     * 緩衝刷到磁碟。
     */
    private fun writeLine(file: File, line: String) {
        synchronized(this) {
            FileOutputStream(file, true).use { fos ->
                fos.write(line.toByteArray())
                fos.fd.sync()
            }
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
