package com.notificationmaster.ui.filter

import android.Manifest
import android.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.notificationmaster.R
import com.notificationmaster.export.calendar.CalendarExporter
import com.notificationmaster.export.calendar.CalendarInfo

/**
 * 日曆選擇器包裝類別。
 *
 * 由 Fragment 在 field initializer 中建構：
 * ```
 * private val calendarPicker = CalendarPickerLauncher(this)
 * ```
 * 確保 ActivityResultLauncher 在 Fragment STARTED 之前完成註冊。
 *
 * 集中封裝下列流程：
 * 1. 權限檢查（READ_CALENDAR / WRITE_CALENDAR）
 * 2. 未授權時的引導 AlertDialog + runtime permission request
 * 3. 已授權後呼叫 [CalendarExporter.showPickerDialog]
 *
 * 之所以需要這層包裝：FilterRuleDialogHelper 是 `object`，無法
 * 直接持有 launcher（不是 LifecycleOwner）。透過此類別將 launcher
 * 持有在 Fragment 端，並以參考形式傳入 helper 函式。
 */
class CalendarPickerLauncher(private val fragment: Fragment) {

    private var pendingPickerAction: (() -> Unit)? = null

    private var pendingDeniedAction: (() -> Unit)? = null

    private val permissionLauncher = fragment.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.READ_CALENDAR] == true &&
            result[Manifest.permission.WRITE_CALENDAR] == true
        val action = pendingPickerAction
        val denied = pendingDeniedAction
        pendingPickerAction = null
        pendingDeniedAction = null
        if (granted) action?.invoke() else denied?.invoke()
    }

    /**
     * 開啟日曆選擇器。若無權限，先彈出說明 dialog 引導使用者授權。
     *
     * 參數順序對齊 [SoundPickerLauncher.pick] 慣例：onSelected 為最後，
     * 讓呼叫端用 trailing lambda 不會誤綁到 onCancel。
     *
     * @param onCancel 取消或拒絕授權時的 callback
     * @param onSelected 使用者選定日曆後的 callback（取消時不觸發）
     */
    fun pick(
        onCancel: (() -> Unit)? = null,
        onSelected: (CalendarInfo) -> Unit
    ) {
        val ctx = fragment.requireContext()
        val exporter = CalendarExporter(ctx)
        if (exporter.hasCalendarPermission()) {
            exporter.showPickerDialog(onCancel = onCancel, onSelected = onSelected)
            return
        }
        ensurePermission(
            onGranted = { CalendarExporter(ctx).showPickerDialog(onCancel = onCancel, onSelected = onSelected) },
            onDenied = onCancel
        )
    }

    /**
     * 僅請求日曆權限（不開 picker）。供僅需要授權門檻的入口使用，例如即時匯出總開關。
     */
    fun requestPermissionOnly(
        onGranted: () -> Unit,
        onDenied: (() -> Unit)? = null
    ) {
        val ctx = fragment.requireContext()
        if (CalendarExporter(ctx).hasCalendarPermission()) {
            onGranted()
            return
        }
        ensurePermission(onGranted, onDenied)
    }

    private fun ensurePermission(onGranted: () -> Unit, onDenied: (() -> Unit)?) {
        val ctx = fragment.requireContext()
        pendingPickerAction = onGranted
        pendingDeniedAction = onDenied
        AlertDialog.Builder(ctx)
            .setTitle(R.string.permission_calendar_title)
            .setMessage(R.string.permission_calendar_message)
            .setPositiveButton(R.string.permission_grant) { _, _ ->
                permissionLauncher.launch(arrayOf(
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR
                ))
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                pendingPickerAction = null
                pendingDeniedAction = null
                onDenied?.invoke()
            }
            .show()
    }

    /**
     * 取得日曆顯示標籤。找不到時 fallback 為「目標日曆 #N（找不到）」。
     * 不需權限即可呼叫；若無權限則一律視為「找不到」。
     */
    fun resolveLabel(calendarId: Long?): String = resolveLabel(fragment.requireContext(), calendarId)

    companion object {
        /**
         * 集中「日曆顯示文字」，避免 fallback 文字四散。
         * - calendarId 為 null → 「未選擇」
         * - 找得到 → displayName
         * - 找不到（被刪 / 無權限） → 「目標日曆 #N（找不到）」
         */
        fun resolveLabel(context: android.content.Context, calendarId: Long?): String {
            if (calendarId == null) {
                return context.getString(R.string.calendar_target_not_selected)
            }
            val exporter = CalendarExporter(context)
            if (!exporter.hasCalendarPermission()) {
                return context.getString(R.string.calendar_target_missing, calendarId)
            }
            val cal = exporter.getAvailableCalendars().firstOrNull { it.id == calendarId }
            return cal?.displayName
                ?: context.getString(R.string.calendar_target_missing, calendarId)
        }
    }
}
