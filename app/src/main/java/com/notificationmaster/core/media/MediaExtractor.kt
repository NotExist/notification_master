package com.notificationmaster.core.media

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.notificationmaster.data.db.entity.MediaAttachmentEntity
import com.notificationmaster.data.db.entity.MediaType
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 媒體提取器
 * 從 Notification extras 提取圖片等媒體資源並儲存到 App 內部儲存
 */
class MediaExtractor(private val context: Context) {

    companion object {
        private const val TAG = "MediaExtractor"
        private const val MEDIA_DIR = "media"
    }

    private val mediaDir: File by lazy {
        File(context.filesDir, MEDIA_DIR).apply { mkdirs() }
    }

    /**
     * 提取通知中的所有媒體附件
     *
     * @param notification 通知物件
     * @param notificationId 通知記錄 ID
     * @param captureTime 擷取時間
     * @return 媒體附件清單
     */
    fun extractMedia(
        notification: Notification,
        notificationId: Long,
        captureTime: Long
    ): List<MediaAttachmentEntity> {
        val attachments = mutableListOf<MediaAttachmentEntity>()
        val extras = notification.extras ?: return attachments

        // 1. EXTRA_LARGE_ICON
        extractBitmapFromExtras(extras, Notification.EXTRA_LARGE_ICON)?.let { bitmap ->
            saveBitmap(bitmap, notificationId, MediaType.LARGE_ICON, captureTime)?.let {
                attachments.add(it)
            }
        }

        // 2. EXTRA_PICTURE (BigPictureStyle)
        extractBitmapFromExtras(extras, Notification.EXTRA_PICTURE)?.let { bitmap ->
            saveBitmap(bitmap, notificationId, MediaType.PICTURE, captureTime)?.let {
                attachments.add(it)
            }
        }

        // 3. EXTRA_LARGE_ICON_BIG (BigPictureStyle 大圖示)
        extractBitmapFromExtras(extras, Notification.EXTRA_LARGE_ICON_BIG)?.let { bitmap ->
            saveBitmap(bitmap, notificationId, MediaType.LARGE_ICON_BIG, captureTime)?.let {
                attachments.add(it)
            }
        }

        // 4. Small Icon (API 23+ 使用 Icon 類別)
        if (Build.VERSION.SDK_INT >= 23) {
            notification.smallIcon?.let { icon ->
                saveIcon(icon, notificationId, MediaType.SMALL_ICON, captureTime)?.let {
                    attachments.add(it)
                }
            }
        }

        return attachments
    }

    @Suppress("DEPRECATION")
    private fun extractBitmapFromExtras(extras: Bundle, key: String): Bitmap? {
        return try {
            when (val value = extras.get(key)) {
                is Bitmap -> value
                is Icon -> {
                    if (Build.VERSION.SDK_INT >= 23) {
                        value.loadDrawable(context)?.let { drawable ->
                            val bitmap = Bitmap.createBitmap(
                                drawable.intrinsicWidth.coerceAtLeast(1),
                                drawable.intrinsicHeight.coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888
                            )
                            val canvas = android.graphics.Canvas(bitmap)
                            drawable.setBounds(0, 0, canvas.width, canvas.height)
                            drawable.draw(canvas)
                            bitmap
                        }
                    } else null
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract bitmap for key: $key", e)
            null
        }
    }

    /**
     * 儲存 Bitmap 並建立 Entity
     */
    private fun saveBitmap(
        bitmap: Bitmap,
        notificationId: Long,
        mediaType: MediaType,
        captureTime: Long
    ): MediaAttachmentEntity? {
        return try {
            val hash = bitmapHash(bitmap)

            // 檢查是否已有相同 hash 的檔案
            val existingFile = File(mediaDir, "$hash.png")
            val filePath = "${MEDIA_DIR}/$hash.png"

            if (!existingFile.exists()) {
                FileOutputStream(existingFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }

            MediaAttachmentEntity(
                notificationId = notificationId,
                mediaType = mediaType,
                filePath = filePath,
                mimeType = "image/png",
                fileSize = existingFile.length(),
                width = bitmap.width,
                height = bitmap.height,
                captureTime = captureTime,
                contentHash = hash
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save bitmap", e)
            null
        }
    }

    /**
     * 儲存 Icon (API 23+)
     */
    private fun saveIcon(
        icon: Icon,
        notificationId: Long,
        mediaType: MediaType,
        captureTime: Long
    ): MediaAttachmentEntity? {
        if (Build.VERSION.SDK_INT < 23) return null

        return try {
            val drawable = icon.loadDrawable(context) ?: return null
            val bitmap = Bitmap.createBitmap(
                drawable.intrinsicWidth.coerceAtLeast(1),
                drawable.intrinsicHeight.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)

            saveBitmap(bitmap, notificationId, mediaType, captureTime)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save icon", e)
            null
        }
    }

    /**
     * 計算 Bitmap Hash
     */
    private fun bitmapHash(bitmap: Bitmap): String {
        val buffer = java.nio.ByteBuffer.allocate(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(buffer)
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(buffer.array())
        return bytes.joinToString("") { "%02x".format(it) }.take(32)
    }

    /**
     * 取得媒體目錄大小
     */
    fun getMediaDirSize(): Long {
        return mediaDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    /**
     * 清除指定時間之前的媒體檔案
     *
     * @param filePaths 要刪除的檔案路徑清單 (相對路徑)
     * @return 實際刪除的數量
     */
    fun deleteMediaFiles(filePaths: List<String>): Int {
        var count = 0
        for (path in filePaths) {
            val file = File(context.filesDir, path)
            if (file.exists() && file.delete()) {
                count++
            }
        }
        return count
    }
}
