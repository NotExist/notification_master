package com.notificationmaster.ui.settings

import android.annotation.SuppressLint
import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.R
import com.notificationmaster.core.media.MediaExtractor
import com.notificationmaster.core.permission.PermissionDescriptions
import com.notificationmaster.core.permission.PermissionInfo
import com.notificationmaster.core.prefs.AppPreferences
import com.notificationmaster.data.db.NotificationDatabase
import com.notificationmaster.databinding.FragmentSettingsBinding
import com.notificationmaster.databinding.ItemPermissionInfoBinding
import com.notificationmaster.debug.DebugDumper
import com.notificationmaster.export.archive.ArchiveExporter
import com.notificationmaster.export.archive.ArchiveImporter
import com.notificationmaster.export.calendar.CalendarExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DecimalFormat
import java.util.Calendar

/**
 * 設定頁面 Fragment
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var debugDumper: DebugDumper

    // JSON 封存匯出 SAF
    private val exportJsonLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { exportArchiveToUri(it) }
    }

    // JSON 封存匯入 SAF
    private val importJsonLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importArchiveFromUri(it) }
    }

    // 媒體目錄選擇 SAF
    private val mediaDirPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { handleMediaDirSelected(it) }
    }

    // 日曆權限請求
    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            showCalendarPicker()
        } else {
            context?.let { Toast.makeText(it, "需要日曆權限才能匯出", Toast.LENGTH_SHORT).show() }
        }
    }

    // 權限說明頁面用的通用權限請求
    private val generalPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // 授權結果回來後重新顯示對話框，反映最新狀態
        showPermissionInfoDialog()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        debugDumper = DebugDumper(requireContext())

        setupEnvironmentCard()
        setupDebugSettings()
        setupExportButtons()
        setupImportButtons()
        setupMediaDirSettings()
        setupDataManagement()
    }

    private fun setupEnvironmentCard() {
        binding.cardEnvironment.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_home)
        }
    }

    override fun onResume() {
        super.onResume()
        updateDebugInfo()
        updateStorageInfo()
        updateMediaDirDisplay()
        validateCustomMediaDir()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupDebugSettings() {
        binding.switchDebug.isChecked = debugDumper.isEnabled

        binding.switchDebug.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                debugDumper.enable()
            } else {
                debugDumper.disable()
            }
            updateDebugInfo()
        }

        binding.btnClearDebug.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("清除 Debug 資料")
                .setMessage("確定要清除所有 Debug dump 檔案嗎？")
                .setPositiveButton(R.string.ok) { _, _ ->
                    val count = debugDumper.clearDumpFiles()
                    Toast.makeText(
                        requireContext(),
                        "已清除 $count 個檔案",
                        Toast.LENGTH_SHORT
                    ).show()
                    updateDebugInfo()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun updateDebugInfo() {
        val fileCount = debugDumper.getDumpFileCount()
        val totalSize = debugDumper.getDumpTotalSize()
        val sizeStr = formatFileSize(totalSize)

        binding.textDebugInfo.text = buildString {
            append("路徑: ${debugDumper.dumpDir.absolutePath}\n")
            append("檔案數: $fileCount, 大小: $sizeStr")
        }
    }

    /**
     * 更新儲存空間資訊
     */
    private fun updateStorageInfo() {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val dbSize = withContext(Dispatchers.IO) {
                val dbFile = ctx.getDatabasePath(NotificationDatabase.DATABASE_NAME)
                val walFile = File(dbFile.path + "-wal")
                val shmFile = File(dbFile.path + "-shm")
                val db = if (dbFile.exists()) dbFile.length() else 0L
                val wal = if (walFile.exists()) walFile.length() else 0L
                val shm = if (shmFile.exists()) shmFile.length() else 0L
                Triple(db, wal, shm)
            }

            val mediaSize = withContext(Dispatchers.IO) {
                MediaExtractor(ctx).getMediaDirSize()
            }

            val totalSize = dbSize.first + dbSize.second + dbSize.third + mediaSize

            _binding?.textStorageInfo?.text = buildString {
                append("資料庫: ${formatFileSize(dbSize.first)}")
                if (dbSize.second > 0) append(" (WAL: ${formatFileSize(dbSize.second)})")
                append("\n媒體: ${formatFileSize(mediaSize)}")
                append("\n總計: ${formatFileSize(totalSize)}")
            }
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"

        val units = arrayOf("KB", "MB", "GB")
        var value = bytes.toDouble() / 1024
        var unitIndex = 0

        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex++
        }

        val df = DecimalFormat("#.##")
        return "${df.format(value)} ${units[unitIndex]}"
    }

    private fun setupExportButtons() {
        binding.btnExportIcal.setOnClickListener {
            requestCalendarExport()
        }

        binding.btnExportJson.setOnClickListener {
            showArchiveExportDialog()
        }
    }

    private fun setupImportButtons() {
        binding.btnImportJson.setOnClickListener {
            importJsonLauncher.launch(arrayOf("application/json"))
        }
    }

    // === 媒體目錄設定 ===

    private fun setupMediaDirSettings() {
        binding.btnChooseMediaDir.setOnClickListener {
            mediaDirPickerLauncher.launch(null)
        }

        binding.btnResetMediaDir.setOnClickListener {
            resetMediaDir()
        }

        updateMediaDirDisplay()
    }

    /**
     * 處理使用者選擇的媒體目錄
     */
    private fun handleMediaDirSelected(treeUri: android.net.Uri) {
        val ctx = context ?: return
        try {
            // 取得持久性 URI 權限
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            ctx.contentResolver.takePersistableUriPermission(treeUri, flags)

            // 驗證可寫入
            val docFile = DocumentFile.fromTreeUri(ctx, treeUri)
            if (docFile == null || !docFile.canWrite()) {
                Toast.makeText(ctx, R.string.settings_media_dir_invalid, Toast.LENGTH_SHORT).show()
                return
            }

            // 儲存設定
            val displayName = docFile.name ?: treeUri.lastPathSegment ?: treeUri.toString()
            AppPreferences.setCustomMediaDir(ctx, treeUri, displayName)

            Toast.makeText(ctx, R.string.settings_media_dir_success, Toast.LENGTH_SHORT).show()
            updateMediaDirDisplay()
        } catch (e: SecurityException) {
            Log.w(TAG, "Failed to take persistable URI permission", e)
            Toast.makeText(ctx, R.string.settings_media_dir_invalid, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 重設為預設媒體目錄
     */
    private fun resetMediaDir() {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.settings_media_dir_reset)
            .setMessage(R.string.settings_media_dir_reset_confirm)
            .setPositiveButton(R.string.ok) { _, _ ->
                // 釋放 persistable URI 權限
                val oldUri = AppPreferences.getCustomMediaDirUri(ctx)
                if (oldUri != null) {
                    try {
                        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        ctx.contentResolver.releasePersistableUriPermission(oldUri, flags)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to release persistable URI permission", e)
                    }
                }

                AppPreferences.clearCustomMediaDir(ctx)
                Toast.makeText(ctx, R.string.settings_media_dir_reset_done, Toast.LENGTH_SHORT).show()
                updateMediaDirDisplay()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 更新媒體目錄顯示狀態
     */
    private fun updateMediaDirDisplay() {
        val ctx = context ?: return
        val b = _binding ?: return

        if (AppPreferences.isCustomMediaDirEnabled(ctx)) {
            val displayName = AppPreferences.getCustomMediaDirDisplay(ctx) ?: "..."
            b.textMediaDirPath.text = getString(R.string.settings_media_dir_set, displayName)
            b.btnResetMediaDir.visibility = View.VISIBLE
        } else {
            b.textMediaDirPath.text = getString(R.string.settings_media_dir_default)
            b.btnResetMediaDir.visibility = View.GONE
        }
    }

    /**
     * 驗證已儲存的自訂目錄 URI 是否仍可存取
     * 在 onResume 時呼叫，偵測權限撤銷或外部儲存移除等情況
     */
    private fun validateCustomMediaDir() {
        val ctx = context ?: return
        val uri = AppPreferences.getCustomMediaDirUri(ctx) ?: return

        try {
            val docFile = DocumentFile.fromTreeUri(ctx, uri)
            if (docFile == null || !docFile.canWrite()) {
                _binding?.textMediaDirPath?.let { tv ->
                    tv.text = getString(R.string.settings_media_dir_lost)
                    tv.setTextColor(ContextCompat.getColor(ctx, R.color.status_disabled))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Custom media dir validation failed", e)
            _binding?.textMediaDirPath?.let { tv ->
                tv.text = getString(R.string.settings_media_dir_lost)
                tv.setTextColor(ContextCompat.getColor(ctx, R.color.status_disabled))
            }
        }
    }

    private fun setupDataManagement() {
        // 權限說明按鈕
        binding.btnPermissionInfo.setOnClickListener {
            showPermissionInfoDialog()
        }

        binding.btnClearData.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("清除所有資料")
                .setMessage("確定要清除所有通知記錄嗎？此操作無法復原。")
                .setPositiveButton(R.string.ok) { _, _ ->
                    clearAllData()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    // === 日曆匯出 ===

    private fun requestCalendarExport() {
        val calendarExporter = CalendarExporter(requireContext())
        if (calendarExporter.hasCalendarPermission()) {
            showCalendarPicker()
        } else {
            // 先顯示權限說明
            AlertDialog.Builder(requireContext())
                .setTitle("需要日曆權限")
                .setMessage("匯出到日曆需要讀取和寫入日曆的權限。\n\n功能：將通知記錄寫入系統日曆\n拒絕影響：無法使用日曆匯出功能")
                .setPositiveButton("授予權限") { _, _ ->
                    calendarPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_CALENDAR,
                            Manifest.permission.WRITE_CALENDAR
                        )
                    )
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun showCalendarPicker() {
        val calendarExporter = CalendarExporter(requireContext())
        val calendars = calendarExporter.getAvailableCalendars()

        if (calendars.isEmpty()) {
            Toast.makeText(requireContext(), "找不到可用的日曆", Toast.LENGTH_SHORT).show()
            return
        }

        val names = calendars.map { "${it.displayName} (${it.accountName})" }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("選擇目標日曆")
            .setItems(names) { _, which ->
                val calendar = calendars[which]
                showCalendarDetailLevelPicker(calendar.id, calendarExporter)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCalendarDetailLevelPicker(calendarId: Long, exporter: CalendarExporter) {
        val levels = arrayOf("僅標題", "含內容", "完整資訊")

        AlertDialog.Builder(requireContext())
            .setTitle("選擇匯出精細程度")
            .setItems(levels) { _, which ->
                exportToCalendar(calendarId, which, exporter)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun exportToCalendar(calendarId: Long, detailLevel: Int, exporter: CalendarExporter) {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val database = NotificationMasterApp.getInstance().database

            // 預設匯出最近 7 天
            val endTime = System.currentTimeMillis()
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -7)
            val startTime = cal.timeInMillis

            val notifications = withContext(Dispatchers.IO) {
                database.notificationDao()
                    .getNotificationsByTimeRangePaged(startTime, endTime, 500, 0)
            }

            val result = withContext(Dispatchers.IO) {
                exporter.exportToCalendar(notifications, calendarId, detailLevel)
            }

            Toast.makeText(
                ctx,
                "匯出完成：${result.successCount} 筆成功" +
                    if (result.failCount > 0) "，${result.failCount} 筆失敗" else "",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // === JSON 封存匯出 ===

    private fun showArchiveExportDialog() {
        val ranges = arrayOf("最近 7 天", "最近 30 天", "最近 90 天", "全部")

        AlertDialog.Builder(requireContext())
            .setTitle("選擇匯出範圍")
            .setItems(ranges) { _, which ->
                val endTime = System.currentTimeMillis()
                val cal = Calendar.getInstance()
                val startTime = when (which) {
                    0 -> { cal.add(Calendar.DAY_OF_YEAR, -7); cal.timeInMillis }
                    1 -> { cal.add(Calendar.DAY_OF_YEAR, -30); cal.timeInMillis }
                    2 -> { cal.add(Calendar.DAY_OF_YEAR, -90); cal.timeInMillis }
                    else -> 0L
                }
                exportJsonLauncher.launch("notification_master_archive.json")
                // 暫存時間範圍
                pendingExportStartTime = startTime
                pendingExportEndTime = endTime
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private var pendingExportStartTime = 0L
    private var pendingExportEndTime = 0L

    private fun exportArchiveToUri(uri: android.net.Uri) {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val database = NotificationMasterApp.getInstance().database
                val exporter = ArchiveExporter(ctx, database)

                val outputStream = ctx.contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("無法開啟輸出串流")

                val stats = exporter.export(
                    pendingExportStartTime,
                    pendingExportEndTime,
                    outputStream
                )
                outputStream.close()

                Toast.makeText(
                    ctx,
                    "匯出完成：${stats.notificationCount} 筆通知、${stats.eventCount} 筆事件",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    ctx,
                    "匯出失敗：${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // === JSON 封存匯入 ===

    private fun importArchiveFromUri(uri: android.net.Uri) {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val inputStream = ctx.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("無法開啟輸入串流")

                val importer = ArchiveImporter(ctx)
                val data = importer.import(inputStream)
                inputStream.close()

                // 將匯入的資料存入資料庫
                val database = NotificationMasterApp.getInstance().database
                withContext(Dispatchers.IO) {
                    // 使用 ID=0 讓 Room 自動產生新 ID
                    val notifications = data.notifications.map { it.copy(id = 0) }
                    database.notificationDao().insertAll(notifications)

                    val events = data.events.map { it.copy(id = 0) }
                    database.notificationEventDao().insertAll(events)
                }

                val envText = data.environment?.let {
                    "\n來源：${it.deviceManufacturer} ${it.deviceModel} (API ${it.apiLevel})"
                } ?: ""

                Toast.makeText(
                    ctx,
                    "匯入完成：${data.notifications.size} 筆通知${envText}",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    ctx,
                    "匯入失敗：${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // === 權限說明 ===

    private fun showPermissionInfoDialog() {
        val permissions = PermissionDescriptions.getApplicablePermissions()
        val ctx = requireContext()

        val scrollView = ScrollView(ctx)
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(container)

        var dialog: AlertDialog? = null

        for (p in permissions) {
            val granted = PermissionDescriptions.checkGrantStatus(ctx, p)
            val actionable = !granted && p.type != "普通權限"

            val itemBinding = ItemPermissionInfoBinding.inflate(layoutInflater, container, false)

            // 名稱
            itemBinding.textPermissionName.text = buildString {
                if (p.isRequired) append("[必要] ")
                append(p.displayName)
            }

            // 授權狀態
            itemBinding.textPermissionStatus.text = when {
                granted -> getString(R.string.permission_status_granted)
                actionable -> getString(R.string.permission_status_tap_to_grant)
                else -> getString(R.string.permission_status_not_granted)
            }
            itemBinding.textPermissionStatus.setTextColor(
                ContextCompat.getColor(ctx,
                    if (granted) R.color.status_enabled else R.color.status_disabled)
            )

            // 說明：功能 + 未授權時顯示拒絕影響
            itemBinding.textPermissionDesc.text = buildString {
                append(p.relatedFeature)
                if (!granted) {
                    append("\n拒絕影響：${p.deniedImpact}")
                }
            }

            // 未授權且可操作：加上點擊引導
            if (actionable) {
                val tv = TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                itemBinding.root.setBackgroundResource(tv.resourceId)
                itemBinding.root.isClickable = true
                itemBinding.root.isFocusable = true
                itemBinding.root.setOnClickListener {
                    dialog?.dismiss()
                    requestPermissionGrant(p)
                }
            }

            container.addView(itemBinding.root)
        }

        dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.settings_permission_info)
            .setView(scrollView)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /**
     * 引導使用者授予指定權限
     */
    @SuppressLint("InlinedApi")
    private fun requestPermissionGrant(info: PermissionInfo) {
        when (info.permission) {
            // 通知監聽服務需要到系統設定頁面開啟
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" -> {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            // 危險權限透過系統對話框請求
            else -> {
                generalPermissionLauncher.launch(arrayOf(info.permission))
            }
        }
    }

    // === 清除資料 ===

    private fun clearAllData() {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val database = NotificationMasterApp.getInstance().database

            withContext(Dispatchers.IO) {
                database.notificationDao().deleteAll()
                database.notificationEventDao().deleteAll()
                database.mediaAttachmentDao().deleteAll()
                database.actionDao().deleteAll()
                database.appSourceDao().deleteAll()
                database.channelDao().deleteAll()
                database.deviceStateDao().deleteAll()
            }

            Toast.makeText(ctx, "已清除所有資料", Toast.LENGTH_SHORT).show()
            updateStorageInfo()
        }
    }

    companion object {
        private const val TAG = "SettingsFragment"
    }
}
