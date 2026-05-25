package com.notificationmaster.core.debug

import android.content.Context
import android.os.Environment
import com.notificationmaster.core.prefs.AppPreferences
import java.io.File

/**
 * Debug 輸出路徑單一 provider。
 *
 * 所有 debug 相關 sink（DebugDumper / ProfileLogger / channel ranking dump）共用一個根目錄
 * 並依 [Sink] 派生子資料夾，避免各 sink 各算各路徑導致風格分歧。
 *
 * 結構：
 * ```
 * Documents/NotificationMaster/debug/   ← 既有 root（沿用 isDebugDumperEnabled gate）
 *   ├── event_dump/                     ← DebugDumper + channel ranking dump 互參放一起
 *   └── profile_log/                    ← ProfileLogger timeline.log
 * ```
 *
 * 三層 fallback：Public Documents → App-specific external → App-specific files。
 */
object DebugPaths {

    private const val PUBLIC_PARENT = "NotificationMaster"
    private const val DEBUG_ROOT = "debug"

    enum class Sink(val subdir: String) {
        EVENT_DUMP("event_dump"),
        PROFILE_LOG("profile_log"),
    }

    fun resolve(context: Context, sink: Sink): File {
        val relative = "$DEBUG_ROOT/${sink.subdir}"

        val publicDocs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val publicDir = File(File(publicDocs, PUBLIC_PARENT), relative)
        if (publicDir.mkdirs() || publicDir.isDirectory) return publicDir

        val external = context.getExternalFilesDir(null)
        if (external != null) {
            val externalDir = File(external, relative)
            if (externalDir.mkdirs() || externalDir.isDirectory) return externalDir
        }

        return File(context.filesDir, relative).apply { mkdirs() }
    }

    fun isEnabled(context: Context): Boolean = AppPreferences.isDebugDumperEnabled(context)
}
