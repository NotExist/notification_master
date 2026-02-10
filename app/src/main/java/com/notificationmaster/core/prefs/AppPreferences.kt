package com.notificationmaster.core.prefs

import android.content.Context
import android.net.Uri

/**
 * App 偏好設定管理
 * 使用 SharedPreferences 儲存非結構化設定（如自訂媒體目錄 URI）
 */
object AppPreferences {

    private const val PREFS_NAME = "notification_master_prefs"
    private const val KEY_CUSTOM_MEDIA_DIR_URI = "custom_media_dir_uri"
    private const val KEY_CUSTOM_MEDIA_DIR_DISPLAY = "custom_media_dir_display"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 取得自訂媒體目錄的 tree URI
     */
    fun getCustomMediaDirUri(context: Context): Uri? =
        prefs(context).getString(KEY_CUSTOM_MEDIA_DIR_URI, null)?.let { Uri.parse(it) }

    /**
     * 設定自訂媒體目錄
     * @param uri tree URI，null 表示清除
     * @param displayName 使用者可讀的目錄名稱
     */
    fun setCustomMediaDir(context: Context, uri: Uri?, displayName: String?) {
        prefs(context).edit()
            .putString(KEY_CUSTOM_MEDIA_DIR_URI, uri?.toString())
            .putString(KEY_CUSTOM_MEDIA_DIR_DISPLAY, displayName)
            .apply()
    }

    /**
     * 清除自訂媒體目錄設定
     */
    fun clearCustomMediaDir(context: Context) {
        prefs(context).edit()
            .remove(KEY_CUSTOM_MEDIA_DIR_URI)
            .remove(KEY_CUSTOM_MEDIA_DIR_DISPLAY)
            .apply()
    }

    /**
     * 是否已設定自訂媒體目錄
     */
    fun isCustomMediaDirEnabled(context: Context): Boolean =
        prefs(context).getString(KEY_CUSTOM_MEDIA_DIR_URI, null) != null

    /**
     * 取得自訂目錄的顯示名稱
     */
    fun getCustomMediaDirDisplay(context: Context): String? =
        prefs(context).getString(KEY_CUSTOM_MEDIA_DIR_DISPLAY, null)
}
