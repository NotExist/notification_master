package com.notificationmaster.core.media

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.notificationmaster.core.prefs.AppPreferences
import com.notificationmaster.core.prefs.AppPreferences.MediaStorageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Phase 31l：媒體目錄搬運器。
 *
 * 切換 [MediaStorageType] 時呼叫 [migrate]，把舊位置實體檔案 copy 到新位置 + 成功後
 * delete source。失敗的檔案保留在 source — 不動 DB（`file_path` 只存檔名），
 * [MediaExtractor.resolveMediaFile] 內含 cross-type fallback 仍能找到留在舊位置的檔案。
 */
object MediaStorageMigrator {

    private const val TAG = "MediaStorageMigrator"
    private const val BUFFER_SIZE = 16 * 1024

    data class Result(val copied: Int, val skipped: Int, val failed: Int) {
        val total: Int get() = copied + skipped + failed
    }

    /**
     * 從 [from] 搬到 [to]。若兩者相同直接回零結果。
     *
     * suspend：呼叫端負責顯示 progress（搬運可能耗時數秒到數十秒，視檔案數量）。
     */
    suspend fun migrate(
        context: Context,
        from: MediaStorageType,
        to: MediaStorageType
    ): Result = withContext(Dispatchers.IO) {
        if (from == to) return@withContext Result(0, 0, 0)

        var copied = 0
        var skipped = 0
        var failed = 0

        val sourceFiles = listSourceFiles(context, from)
        for (entry in sourceFiles) {
            val result = copyEntry(context, entry, to)
            when (result) {
                CopyOutcome.Copied -> {
                    copied++
                    deleteSource(entry)
                }
                CopyOutcome.Skipped -> {
                    skipped++
                    deleteSource(entry)
                }
                CopyOutcome.Failed -> failed++
            }
        }

        Result(copied, skipped, failed)
    }

    private enum class CopyOutcome { Copied, Skipped, Failed }

    /** 描述一個 source 檔案（File 或 SAF DocumentFile），延後實際讀寫到 copyEntry */
    private sealed class SourceEntry {
        abstract val fileName: String
        data class FileEntry(val file: File) : SourceEntry() {
            override val fileName: String get() = file.name
        }
        data class DocEntry(val context: Context, val doc: DocumentFile) : SourceEntry() {
            override val fileName: String get() = doc.name ?: ""
        }
    }

    private fun listSourceFiles(context: Context, type: MediaStorageType): List<SourceEntry> {
        return when (type) {
            MediaStorageType.INTERNAL, MediaStorageType.APP_EXTERNAL -> {
                val dir = File(MediaExtractor.getMediaBaseDir(context, type), MediaExtractor.MEDIA_DIR)
                if (!dir.isDirectory) return emptyList()
                dir.listFiles()
                    ?.filter { it.isFile }
                    ?.map { SourceEntry.FileEntry(it) }
                    ?: emptyList()
            }
            MediaStorageType.PUBLIC_EXTERNAL -> {
                val uri = AppPreferences.getCustomMediaDirUri(context) ?: return emptyList()
                val tree = try {
                    DocumentFile.fromTreeUri(context, uri)?.takeIf { it.canRead() }
                } catch (e: Exception) {
                    Log.w(TAG, "PUBLIC_EXTERNAL listSourceFiles failed", e)
                    null
                } ?: return emptyList()
                tree.listFiles()
                    .filter { it.isFile && !it.name.isNullOrEmpty() }
                    .map { SourceEntry.DocEntry(context, it) }
            }
        }
    }

    /**
     * 把 source 寫入 target type。target 已有同檔名 → Skipped（dedup hash 保證內容相同）。
     */
    private fun copyEntry(
        context: Context,
        entry: SourceEntry,
        to: MediaStorageType
    ): CopyOutcome {
        return try {
            when (to) {
                MediaStorageType.INTERNAL, MediaStorageType.APP_EXTERNAL ->
                    copyToFileTarget(context, entry, to)
                MediaStorageType.PUBLIC_EXTERNAL ->
                    copyToSafTarget(context, entry)
            }
        } catch (e: Exception) {
            Log.w(TAG, "copyEntry failed: ${entry.fileName}", e)
            CopyOutcome.Failed
        }
    }

    private fun copyToFileTarget(
        context: Context,
        entry: SourceEntry,
        to: MediaStorageType
    ): CopyOutcome {
        val targetDir = File(MediaExtractor.getMediaBaseDir(context, to), MediaExtractor.MEDIA_DIR)
            .apply { mkdirs() }
        val targetFile = File(targetDir, entry.fileName)
        if (targetFile.exists() && targetFile.length() > 0) return CopyOutcome.Skipped

        when (entry) {
            is SourceEntry.FileEntry -> {
                entry.file.inputStream().use { input ->
                    targetFile.outputStream().use { input.copyTo(it, BUFFER_SIZE) }
                }
            }
            is SourceEntry.DocEntry -> {
                context.contentResolver.openInputStream(entry.doc.uri)?.use { input ->
                    targetFile.outputStream().use { input.copyTo(it, BUFFER_SIZE) }
                } ?: return CopyOutcome.Failed
            }
        }
        return CopyOutcome.Copied
    }

    private fun copyToSafTarget(context: Context, entry: SourceEntry): CopyOutcome {
        val uri = AppPreferences.getCustomMediaDirUri(context) ?: return CopyOutcome.Failed
        val tree = DocumentFile.fromTreeUri(context, uri)?.takeIf { it.canWrite() }
            ?: return CopyOutcome.Failed

        val existing = tree.findFile(entry.fileName)
        if (existing != null && existing.exists() && existing.length() > 0) return CopyOutcome.Skipped

        val mimeType = guessMimeFromName(entry.fileName)
        // SAF createFile 自動加副檔名，把 entry.fileName 去副檔名後再傳
        val baseName = entry.fileName.substringBeforeLast('.', entry.fileName)
        val created = tree.createFile(mimeType, baseName) ?: return CopyOutcome.Failed

        val input = when (entry) {
            is SourceEntry.FileEntry -> entry.file.inputStream()
            is SourceEntry.DocEntry -> context.contentResolver.openInputStream(entry.doc.uri)
                ?: return CopyOutcome.Failed.also { created.delete() }
        }
        try {
            input.use { ins ->
                context.contentResolver.openOutputStream(created.uri)?.use { out ->
                    ins.copyTo(out, BUFFER_SIZE)
                } ?: return CopyOutcome.Failed.also { created.delete() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SAF write failed for ${entry.fileName}", e)
            try { created.delete() } catch (_: Exception) {}
            return CopyOutcome.Failed
        }
        return CopyOutcome.Copied
    }

    private fun deleteSource(entry: SourceEntry) {
        try {
            when (entry) {
                is SourceEntry.FileEntry -> entry.file.delete()
                is SourceEntry.DocEntry -> entry.doc.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "deleteSource failed: ${entry.fileName}", e)
        }
    }

    private fun guessMimeFromName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "application/octet-stream"
        }
    }
}
