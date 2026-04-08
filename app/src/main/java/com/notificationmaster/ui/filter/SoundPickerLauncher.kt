package com.notificationmaster.ui.filter

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment

/**
 * 系統原生鈴聲選擇器包裝類別。
 *
 * 由 Fragment 在 field initializer 中建構：
 * ```
 * private val soundPicker = SoundPickerLauncher(this)
 * ```
 * 確保 ActivityResultLauncher 在 Fragment STARTED 之前完成註冊。
 *
 * 之所以需要這層包裝：FilterRuleDialogHelper 是 `object`，無法
 * 直接持有 launcher（不是 LifecycleOwner）。透過此類別將 launcher
 * 持有在 Fragment 端，並以參考形式傳入 helper 函式。
 */
class SoundPickerLauncher(fragment: Fragment) {

    private var pendingCallback: ((String?) -> Unit)? = null

    private val launcher = fragment.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(
                    RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                    Uri::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            pendingCallback?.invoke(uri?.toString())
        }
        pendingCallback = null
    }

    /**
     * 開啟系統原生鈴聲選擇器。
     *
     * @param currentUri 目前選用的 ringtone URI（null 代表預設），用於 picker 預選
     * @param title Picker dialog 的標題
     * @param onSelected 使用者選擇後 callback，傳回所選 URI 字串（null = 預設）
     */
    fun pick(currentUri: String?, title: String, onSelected: (String?) -> Unit) {
        pendingCallback = onSelected
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_TYPE,
                RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_NOTIFICATION
            )
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, title)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            // 持續提醒不該沒聲音，禁止選靜音
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            )
            if (currentUri != null) {
                putExtra(
                    RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                    Uri.parse(currentUri)
                )
            }
        }
        launcher.launch(intent)
    }
}
