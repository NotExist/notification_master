package com.notificationmaster.core.media

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.notificationmaster.core.prefs.AppPreferences
import com.notificationmaster.data.db.entity.MediaAttachmentEntity
import com.notificationmaster.data.db.entity.MediaType
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 媒體提取器
 * 從 Notification extras 提取圖片等媒體資源並儲存
 *
 * 支援兩種儲存模式：
 * - 預設模式：getExternalFilesDir(null)/media/，filePath 為相對路徑 "media/{MediaType}_{hash}.png"
 * - 自訂模式：使用者透過 SAF 選擇的目錄，filePath 為完整 content URI 字串
 */
class MediaExtractor(private val context: Context) {

    companion object {
        private const val TAG = "MediaExtractor"
        private const val MEDIA_DIR = "media"

        /**
         * 取得媒體檔案的基底目錄（外部儲存）
         * 回傳 getExternalFilesDir(null)，外部儲存不可用時 fallback 到 filesDir
         */
        fun getMediaBaseDir(context: Context): File {
            return context.getExternalFilesDir(null) ?: context.filesDir
        }

        /**
         * 判斷 filePath 是否為 content URI（自訂目錄模式）
         */
        fun isContentUri(filePath: String): Boolean = filePath.startsWith("content://")

        /**
         * 統一檢查媒體檔案是否存在
         */
        fun mediaFileExists(context: Context, filePath: String): Boolean {
            if (filePath.isEmpty()) return false
            return try {
                if (isContentUri(filePath)) {
                    val uri = Uri.parse(filePath)
                    context.contentResolver.openInputStream(uri)?.use { true } ?: false
                } else {
                    File(getMediaBaseDir(context), filePath).exists()
                }
            } catch (e: Exception) {
                Log.w(TAG, "mediaFileExists failed: $filePath", e)
                false
            }
        }

        /**
         * 統一載入媒體縮圖（含取樣以節省記憶體）
         * @param targetWidth 目標寬度（px）
         * @param targetHeight 目標高度（px）
         */
        fun loadMediaBitmapSampled(
            context: Context,
            filePath: String,
            targetWidth: Int,
            targetHeight: Int
        ): Bitmap? {
            return try {
                if (isContentUri(filePath)) {
                    val uri = Uri.parse(filePath)
                    // 先取得原始尺寸
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    }
                    val sampleSize = maxOf(
                        bounds.outWidth / targetWidth,
                        bounds.outHeight / targetHeight,
                        1
                    )
                    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, opts)
                    }
                } else {
                    val file = File(getMediaBaseDir(context), filePath)
                    if (!file.exists()) return null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    val sampleSize = maxOf(
                        bounds.outWidth / targetWidth,
                        bounds.outHeight / targetHeight,
                        1
                    )
                    BitmapFactory.decodeFile(
                        file.absolutePath,
                        BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "loadMediaBitmapSampled failed: $filePath", e)
                null
            }
        }

        /**
         * 統一讀取媒體檔案的完整位元組（匯出 Base64 用）
         */
        fun readMediaBytes(context: Context, filePath: String): ByteArray? {
            return try {
                if (isContentUri(filePath)) {
                    val uri = Uri.parse(filePath)
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } else {
                    val file = File(getMediaBaseDir(context), filePath)
                    if (file.exists()) file.readBytes() else null
                }
            } catch (e: Exception) {
                Log.w(TAG, "readMediaBytes failed: $filePath", e)
                null
            }
        }

        /**
         * 統一取得可用於分享的 URI
         * - 相對路徑：透過 FileProvider
         * - content URI：直接使用
         */
        fun getShareUri(context: Context, filePath: String): Uri? {
            return try {
                if (isContentUri(filePath)) {
                    Uri.parse(filePath)
                } else {
                    val file = File(getMediaBaseDir(context), filePath)
                    if (!file.exists()) return null
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "getShareUri failed: $filePath", e)
                null
            }
        }

        /**
         * 統一刪除媒體檔案
         */
        fun deleteMediaFile(context: Context, filePath: String): Boolean {
            return try {
                if (isContentUri(filePath)) {
                    val uri = Uri.parse(filePath)
                    val docFile = DocumentFile.fromSingleUri(context, uri)
                    docFile?.delete() ?: false
                } else {
                    val file = File(getMediaBaseDir(context), filePath)
                    file.exists() && file.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "deleteMediaFile failed: $filePath", e)
                false
            }
        }
    }

    private val mediaDir: File by lazy {
        File(getMediaBaseDir(context), MEDIA_DIR).apply { mkdirs() }
    }

    /** 自訂媒體目錄的 DocumentFile（不可用時為 null） */
    private val customMediaDocDir: DocumentFile? by lazy {
        AppPreferences.getCustomMediaDirUri(context)?.let { treeUri ->
            try {
                DocumentFile.fromTreeUri(context, treeUri)?.takeIf { it.canWrite() }
            } catch (e: Exception) {
                Log.w(TAG, "Custom media dir unavailable", e)
                null
            }
        }
    }

    /** 是否使用自訂目錄 */
    private val useCustomDir: Boolean by lazy { customMediaDocDir != null }

    /**
     * 提取通知中的所有媒體附件
     *
     * @param notification 通知物件
     * @param notificationId 通知記錄 ID
     * @param captureTime 擷取時間
     * @return 媒體附件清單
     */
    @Suppress("DEPRECATION")
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

        // 5. MessagingStyle 對話頭像 (API 28+)
        if (Build.VERSION.SDK_INT >= 28) {
            extractMessagingAvatars(extras, notificationId, captureTime, attachments)
        }

        // 6. MessagingStyle 訊息中的媒體 (API 24+)
        if (Build.VERSION.SDK_INT >= 24) {
            extractMessagingMedia(extras, notificationId, captureTime, attachments)
        }

        return attachments
    }

    /**
     * 提取 MessagingStyle 對話中的頭像 (API 28+)
     * 包含各 sender 的 Person.getIcon() 以及 EXTRA_MESSAGING_PERSON（發送者自己）
     */
    @Suppress("DEPRECATION")
    private fun extractMessagingAvatars(
        extras: Bundle,
        notificationId: Long,
        captureTime: Long,
        attachments: MutableList<MediaAttachmentEntity>
    ) {
        if (Build.VERSION.SDK_INT < 28) return

        val processedHashes = mutableSetOf<String>()
        // 收集已有附件的 hash，避免與其他類型圖片重複
        attachments.mapTo(processedHashes) { it.contentHash }

        try {
            // EXTRA_MESSAGING_PERSON：發送者自己的頭像
            val messagingPerson = extras.getParcelable<android.app.Person>(
                Notification.EXTRA_MESSAGING_PERSON
            )
            messagingPerson?.icon?.let { icon ->
                saveIconDedup(icon, notificationId, captureTime, processedHashes, attachments)
            }

            // EXTRA_MESSAGES：每條訊息的 sender_person 頭像
            val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            messages?.forEach { msg ->
                val bundle = msg as? Bundle ?: return@forEach
                val senderPerson = bundle.getParcelable<android.app.Person>("sender_person")
                senderPerson?.icon?.let { icon ->
                    saveIconDedup(icon, notificationId, captureTime, processedHashes, attachments)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract messaging avatars", e)
        }
    }

    /**
     * 提取 MessagingStyle 訊息中的媒體附件
     * 訊息透過 Message.setData(mimeType, uri) 設定媒體，
     * Bundle 中以 "type" (MIME) 和 "uri" (content URI) 存放
     */
    @SuppressLint("InlinedApi")
    @Suppress("DEPRECATION")
    private fun extractMessagingMedia(
        extras: Bundle,
        notificationId: Long,
        captureTime: Long,
        attachments: MutableList<MediaAttachmentEntity>
    ) {
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return

        // 收集已有附件的 hash 避免重複
        val processedHashes = mutableSetOf<String>()
        attachments.mapTo(processedHashes) { it.contentHash }

        for (msg in messages) {
            val bundle = msg as? Bundle ?: continue
            val mimeType = bundle.getString("type") ?: continue
            val uri = bundle.getParcelable<Uri>("uri") ?: continue

            // 僅處理圖片類型（影片/音訊未來可擴展）
            if (!mimeType.startsWith("image/")) continue

            try {
                val saved = saveFromUri(uri, mimeType, notificationId, captureTime, processedHashes)
                if (saved != null) {
                    attachments.add(saved)
                } else {
                    // URI 無法讀取（權限過期等），記錄為不可用附件
                    attachments.add(MediaAttachmentEntity(
                        notificationId = notificationId,
                        mediaType = MediaType.MESSAGE_MEDIA,
                        filePath = "",
                        mimeType = mimeType,
                        fileSize = 0,
                        width = 0, height = 0,
                        captureTime = captureTime,
                        contentHash = "unavailable_${uri.hashCode()}",
                        sourceUri = uri.toString()
                    ))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extract messaging media: $uri", e)
                // 例外時同樣記錄為不可用附件
                attachments.add(MediaAttachmentEntity(
                    notificationId = notificationId,
                    mediaType = MediaType.MESSAGE_MEDIA,
                    filePath = "",
                    mimeType = mimeType,
                    fileSize = 0,
                    width = 0, height = 0,
                    captureTime = captureTime,
                    contentHash = "unavailable_${uri.hashCode()}",
                    sourceUri = uri.toString()
                ))
            }
        }
    }

    /**
     * 從 content URI 讀取媒體並儲存
     * NLS 收到通知時 URI 權限仍有效，需即時提取
     */
    private fun saveFromUri(
        uri: Uri,
        mimeType: String,
        notificationId: Long,
        captureTime: Long,
        processedHashes: MutableSet<String>
    ): MediaAttachmentEntity? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return null

        val hash = bytesHash(bytes)
        if (hash in processedHashes) return null
        processedHashes.add(hash)

        // 取得圖片尺寸
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        val ext = mimeTypeToExt(mimeType)

        val uriString = uri.toString()

        if (useCustomDir) {
            saveBytesToCustomDir(bytes, hash, ext, mimeType, notificationId,
                MediaType.MESSAGE_MEDIA, captureTime,
                bounds.outWidth, bounds.outHeight)?.let {
                return it.copy(sourceUri = uriString)
            }
            Log.w(TAG, "Custom dir write failed for URI media, falling back")
        }

        return saveBytesToDefaultDir(bytes, hash, ext, mimeType, notificationId,
            MediaType.MESSAGE_MEDIA, captureTime,
            bounds.outWidth, bounds.outHeight)?.copy(sourceUri = uriString)
    }

    private fun bytesHash(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
            .take(32)
    }

    private fun mimeTypeToExt(mimeType: String): String {
        return when {
            mimeType.contains("png") -> "png"
            mimeType.contains("gif") -> "gif"
            mimeType.contains("webp") -> "webp"
            else -> "jpg"
        }
    }

    /**
     * 原始位元組寫入預設目錄
     */
    private fun saveBytesToDefaultDir(
        bytes: ByteArray, hash: String, ext: String, mimeType: String,
        notificationId: Long, mediaType: MediaType, captureTime: Long, width: Int, height: Int
    ): MediaAttachmentEntity? {
        return try {
            val fileName = "${mediaType.name}_$hash.$ext"
            val file = File(mediaDir, fileName)
            val filePath = "$MEDIA_DIR/$fileName"
            if (!file.exists()) {
                FileOutputStream(file).use { it.write(bytes) }
            }
            MediaAttachmentEntity(
                notificationId = notificationId,
                mediaType = mediaType,
                filePath = filePath,
                mimeType = mimeType,
                fileSize = file.length(),
                width = width, height = height,
                captureTime = captureTime,
                contentHash = hash
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save bytes to default dir", e)
            null
        }
    }

    /**
     * 原始位元組寫入自訂目錄
     */
    private fun saveBytesToCustomDir(
        bytes: ByteArray, hash: String, ext: String, mimeType: String,
        notificationId: Long, mediaType: MediaType, captureTime: Long, width: Int, height: Int
    ): MediaAttachmentEntity? {
        val docDir = customMediaDocDir ?: return null
        return try {
            val fileName = "${mediaType.name}_$hash.$ext"
            val existing = docDir.findFile(fileName)
            val docFile = if (existing != null && existing.exists()) {
                existing
            } else {
                docDir.createFile(mimeType, "${mediaType.name}_$hash") ?: return null
            }
            if (existing == null) {
                context.contentResolver.openOutputStream(docFile.uri)?.use { it.write(bytes) }
                    ?: return null
            }
            MediaAttachmentEntity(
                notificationId = notificationId,
                mediaType = mediaType,
                filePath = docFile.uri.toString(),
                mimeType = mimeType,
                fileSize = docFile.length(),
                width = width, height = height,
                captureTime = captureTime,
                contentHash = hash
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save bytes to custom dir", e)
            null
        }
    }

    /**
     * 儲存 Icon 並透過 hash 去重
     */
    private fun saveIconDedup(
        icon: Icon,
        notificationId: Long,
        captureTime: Long,
        processedHashes: MutableSet<String>,
        attachments: MutableList<MediaAttachmentEntity>
    ) {
        if (Build.VERSION.SDK_INT < 23) return

        try {
            val drawable = icon.loadDrawable(context) ?: return
            val bitmap = Bitmap.createBitmap(
                drawable.intrinsicWidth.coerceAtLeast(1),
                drawable.intrinsicHeight.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)

            val hash = bitmapHash(bitmap)
            if (hash in processedHashes) return
            processedHashes.add(hash)

            saveBitmap(bitmap, notificationId, MediaType.MESSAGING_AVATAR, captureTime)?.let {
                attachments.add(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save messaging avatar icon", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun extractBitmapFromExtras(extras: Bundle, key: String): Bitmap? {
        return try {
            when (val value = extras.get(key)) {
                is Bitmap -> value
                else -> if (Build.VERSION.SDK_INT >= 23 && value is Icon) {
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
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract bitmap for key: $key", e)
            null
        }
    }

    /**
     * 儲存 Bitmap 並建立 Entity
     * 自訂目錄啟用時優先寫入自訂目錄，失敗時 fallback 到預設目錄
     */
    private fun saveBitmap(
        bitmap: Bitmap,
        notificationId: Long,
        mediaType: MediaType,
        captureTime: Long
    ): MediaAttachmentEntity? {
        val hash = bitmapHash(bitmap)

        if (useCustomDir) {
            saveBitmapToCustomDir(bitmap, hash, notificationId, mediaType, captureTime)?.let {
                return it
            }
            // fallback 到預設目錄
            Log.w(TAG, "Custom dir write failed, falling back to default dir")
        }

        return saveBitmapToDefaultDir(bitmap, hash, notificationId, mediaType, captureTime)
    }

    /**
     * 寫入自訂目錄（SAF DocumentFile）
     */
    private fun saveBitmapToCustomDir(
        bitmap: Bitmap,
        hash: String,
        notificationId: Long,
        mediaType: MediaType,
        captureTime: Long
    ): MediaAttachmentEntity? {
        val docDir = customMediaDocDir ?: return null
        return try {
            // 去重：檢查同名檔案是否已存在
            val fileName = "${mediaType.name}_$hash.png"
            val existing = docDir.findFile(fileName)
            val docFile = if (existing != null && existing.exists()) {
                existing
            } else {
                docDir.createFile("image/png", "${mediaType.name}_$hash") ?: return null
            }

            // 僅新建的檔案需要寫入
            if (existing == null) {
                val outputUri = docFile.uri
                context.contentResolver.openOutputStream(outputUri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                } ?: return null
            }

            MediaAttachmentEntity(
                notificationId = notificationId,
                mediaType = mediaType,
                filePath = docFile.uri.toString(),
                mimeType = "image/png",
                fileSize = docFile.length(),
                width = bitmap.width,
                height = bitmap.height,
                captureTime = captureTime,
                contentHash = hash
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save bitmap to custom dir", e)
            null
        }
    }

    /**
     * 寫入預設目錄（現有邏輯）
     */
    private fun saveBitmapToDefaultDir(
        bitmap: Bitmap,
        hash: String,
        notificationId: Long,
        mediaType: MediaType,
        captureTime: Long
    ): MediaAttachmentEntity? {
        return try {
            val fileName = "${mediaType.name}_$hash.png"
            val existingFile = File(mediaDir, fileName)
            val filePath = "${MEDIA_DIR}/$fileName"

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
            Log.e(TAG, "Failed to save bitmap to default dir", e)
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
     * 取得媒體目錄大小（合計預設 + 自訂目錄）
     */
    fun getMediaDirSize(): Long {
        val defaultSize = mediaDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }

        val customSize = try {
            customMediaDocDir?.listFiles()?.sumOf { it.length() } ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "Failed to calculate custom dir size", e)
            0L
        }

        return defaultSize + customSize
    }

    /**
     * 刪除指定的媒體檔案
     *
     * @param filePaths 要刪除的檔案路徑清單（相對路徑或 content URI）
     * @return 實際刪除的數量
     */
    fun deleteMediaFiles(filePaths: List<String>): Int {
        var count = 0
        for (path in filePaths) {
            if (deleteMediaFile(context, path)) {
                count++
            }
        }
        return count
    }
}
