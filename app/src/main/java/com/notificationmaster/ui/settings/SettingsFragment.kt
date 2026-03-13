package com.notificationmaster.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.notificationmaster.BuildConfig
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.R
import com.notificationmaster.core.filter.ActionType
import com.notificationmaster.core.filter.RuleEngine
import com.notificationmaster.core.media.MediaExtractor
import com.notificationmaster.core.prefs.AppPreferences
import com.notificationmaster.data.db.NotificationDatabase
import com.notificationmaster.databinding.FragmentSettingsBinding
import com.notificationmaster.debug.DebugDumper
import com.notificationmaster.export.archive.ArchiveExporter
import com.notificationmaster.export.archive.ArchiveImporter
import com.notificationmaster.export.calendar.CalendarExporter
import com.notificationmaster.export.ical.IcsExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

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

    // 過濾規則匯出 SAF
    private val exportFilterLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { exportFilterRulesToUri(it) }
    }

    // 過濾規則匯入 SAF
    private val importFilterLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { confirmAndImportFilterRules(it) }
    }

    // 備份目錄選擇 SAF
    private val backupDirPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { handleBackupDirSelected(it) }
    }

    // .ics 日曆檔匯出 SAF
    private val exportIcsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/calendar")
    ) { uri ->
        uri?.let { exportIcsToUri(it) }
    }

    // 防止 Switch 程式設值觸發 listener 迴圈
    private var isUpdatingRealtimeSwitch = false

    // 日曆權限請求（通用）
    private var pendingCalendarAction: (() -> Unit)? = null

    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            pendingCalendarAction?.invoke()
        } else {
            context?.let { Toast.makeText(it, "需要日曆權限", Toast.LENGTH_SHORT).show() }
        }
        pendingCalendarAction = null
    }

    // 通知權限請求（持續提醒用，API 33+）
    private var pendingNotificationAction: (() -> Unit)? = null

    @SuppressLint("InlinedApi")
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingNotificationAction?.invoke()
        } else {
            context?.let {
                Toast.makeText(it, R.string.permission_post_notifications_denied, Toast.LENGTH_SHORT).show()
            }
        }
        pendingNotificationAction = null
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

        // 確保規則已載入
        RuleEngine.load(requireContext())

        setupEnvironmentCard()
        setupFilterSettings()
        setupMediaDirSettings()
        setupDebugSettings()
        setupCalendarIntegration()
        setupDataManagement()

        checkBackupAndSuggestImport()
    }

    private fun setupEnvironmentCard() {
        binding.cardEnvironment.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_home)
        }
    }

    private fun setupFilterSettings() {
        binding.btnFilter.setOnClickListener {
            findNavController().navigate(
                R.id.action_settings_to_filter,
                bundleOf("actionType" to ActionType.SKIP_RECORD.name)
            )
        }
        updateFilterSummary()

        binding.btnAutoDismiss.setOnClickListener {
            findNavController().navigate(
                R.id.action_settings_to_filter,
                bundleOf("actionType" to ActionType.AUTO_DISMISS.name)
            )
        }
        updateAutoDismissSummary()

        binding.btnPersistentAlert.setOnClickListener {
            requestNotificationPermissionThen {
                findNavController().navigate(
                    R.id.action_settings_to_filter,
                    bundleOf("actionType" to ActionType.PERSISTENT_ALERT.name)
                )
            }
        }
        updatePersistentAlertSummary()
    }

    private fun updateFilterSummary() {
        val b = _binding ?: return
        val count = RuleEngine.getRules(ActionType.SKIP_RECORD).size
        b.textFilterSummary.text = if (count > 0) {
            getString(R.string.settings_filter_count, count)
        } else {
            getString(R.string.settings_filter_summary)
        }
    }

    private fun updateAutoDismissSummary() {
        val b = _binding ?: return
        val count = RuleEngine.getRules(ActionType.AUTO_DISMISS).size
        b.textAutoDismissSummary.text = if (count > 0) {
            getString(R.string.settings_auto_dismiss_count, count)
        } else {
            getString(R.string.settings_auto_dismiss_summary)
        }
    }

    private fun updatePersistentAlertSummary() {
        val b = _binding ?: return
        val count = RuleEngine.getRules(ActionType.PERSISTENT_ALERT).size
        b.textPersistentAlertSummary.text = if (count > 0) {
            getString(R.string.settings_persistent_alert_count, count)
        } else {
            getString(R.string.settings_persistent_alert_summary)
        }
    }

    override fun onResume() {
        super.onResume()
        updateDebugInfo()
        updateStorageInfo()
        updateMediaDirDisplay()
        validateCustomMediaDir()
        updateFilterSummary()
        updateAutoDismissSummary()
        updatePersistentAlertSummary()
        updateCalendarWhitelistSummary()
        updateRealtimeCalendarDisplay()
        updateBackupDirDisplay()
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

        displaySigningInfo()
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
     * 顯示簽章資訊（僅 debug build）
     */
    @SuppressLint("PackageManagerGetSignatures")
    private fun displaySigningInfo() {
        if (!BuildConfig.DEBUG) return

        val ctx = requireContext()
        binding.textSigningInfo.visibility = View.VISIBLE

        try {
            val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val info = ctx.packageManager.getPackageInfo(
                    ctx.packageName,
                    android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                )
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                val info = ctx.packageManager.getPackageInfo(
                    ctx.packageName,
                    android.content.pm.PackageManager.GET_SIGNATURES
                )
                @Suppress("DEPRECATION")
                info.signatures
            }

            if (signatures.isNullOrEmpty()) {
                binding.textSigningInfo.text = "No signing certificates found"
                return
            }

            val sb = StringBuilder()
            for ((index, sig) in signatures.withIndex()) {
                if (index > 0) sb.append("\n\n")

                val certFactory = CertificateFactory.getInstance("X.509")
                val cert = certFactory.generateCertificate(sig.toByteArray().inputStream()) as X509Certificate

                val sha256 = MessageDigest.getInstance("SHA-256")
                    .digest(cert.encoded)
                    .joinToString(":") { "%02X".format(it) }

                val sha1 = MessageDigest.getInstance("SHA-1")
                    .digest(cert.encoded)
                    .joinToString(":") { "%02X".format(it) }

                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

                sb.append("SHA-256:\n$sha256\n\n")
                sb.append("SHA-1:\n$sha1\n\n")
                sb.append("Issuer: ${cert.issuerX500Principal.name}\n")
                sb.append("Subject: ${cert.subjectX500Principal.name}\n")
                sb.append("Valid: ${dateFormat.format(cert.notBefore)} ~ ${dateFormat.format(cert.notAfter)}")
            }

            binding.textSigningInfo.text = sb.toString()
        } catch (e: Exception) {
            binding.textSigningInfo.text = "Error: ${e.message}"
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

    private fun setupCalendarIntegration() {
        // 匯出到系統日曆（既有的批次匯出）
        binding.btnExportIcal.setOnClickListener {
            requestCalendarExport()
        }

        // .ics 匯出按鈕
        binding.btnExportIcsFile.setOnClickListener {
            showIcsExportDialog()
        }

        // 即時匯出開關
        binding.switchRealtimeCalendar.isChecked =
            AppPreferences.isRealtimeCalendarEnabled(requireContext())
        binding.switchRealtimeCalendar.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingRealtimeSwitch) handleRealtimeCalendarToggle(isChecked)
        }
        updateRealtimeCalendarDisplay()

        // 目標日曆選擇
        binding.btnChooseTargetCalendar.setOnClickListener {
            requestCalendarPermissionThen { showTargetCalendarPicker() }
        }

        // 白名單
        binding.btnCalendarWhitelist.setOnClickListener {
            findNavController().navigate(
                R.id.action_settings_to_filter,
                bundleOf("actionType" to ActionType.CALENDAR_EXPORT.name)
            )
        }
        updateCalendarWhitelistSummary()
    }

    private fun updateCalendarWhitelistSummary() {
        val b = _binding ?: return
        val count = RuleEngine.getRules(ActionType.CALENDAR_EXPORT).size
        b.textCalendarWhitelistSummary.text = if (count > 0) {
            getString(R.string.settings_calendar_whitelist_count, count)
        } else {
            getString(R.string.settings_calendar_whitelist_summary)
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
        binding.btnExportJson.setOnClickListener {
            showArchiveExportDialog()
        }

        binding.btnImportJson.setOnClickListener {
            importJsonLauncher.launch(arrayOf("application/json"))
        }

        binding.btnExportFilterRules.setOnClickListener {
            val totalRules = RuleEngine.getRules().size
            if (totalRules == 0) {
                Toast.makeText(requireContext(), R.string.filter_export_empty, Toast.LENGTH_SHORT).show()
            } else {
                exportFilterLauncher.launch("notification_master_filter_rules.json")
            }
        }

        binding.btnImportFilterRules.setOnClickListener {
            importFilterLauncher.launch(arrayOf("application/json"))
        }

        // 備份目錄
        binding.btnChooseBackupDir.setOnClickListener {
            backupDirPickerLauncher.launch(null)
        }
        binding.btnResetBackupDir.setOnClickListener {
            resetBackupDir()
        }
        updateBackupDirDisplay()

        binding.btnClearData.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("清除通知記錄資料庫")
                .setMessage("確定要清除所有通知記錄嗎？此操作無法復原。")
                .setPositiveButton(R.string.ok) { _, _ ->
                    clearAllData()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    // === 日曆匯出 ===

    /**
     * 共用日曆權限請求
     */
    private fun requestCalendarPermissionThen(action: () -> Unit) {
        val exporter = CalendarExporter(requireContext())
        if (exporter.hasCalendarPermission()) {
            action()
        } else {
            pendingCalendarAction = action
            AlertDialog.Builder(requireContext())
                .setTitle("需要日曆權限")
                .setMessage("此功能需要讀取和寫入日曆的權限。")
                .setPositiveButton("授予權限") { _, _ ->
                    calendarPermissionLauncher.launch(arrayOf(
                        Manifest.permission.READ_CALENDAR,
                        Manifest.permission.WRITE_CALENDAR
                    ))
                }
                .setNegativeButton(R.string.cancel) { _, _ ->
                    pendingCalendarAction = null
                }
                .show()
        }
    }

    /**
     * 共用通知權限請求（API 33+）
     *
     * API 33 以下不需要此權限，直接執行 action。
     */
    @SuppressLint("InlinedApi")
    private fun requestNotificationPermissionThen(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            action()
            return
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingNotificationAction = action
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.permission_post_notifications_title)
                .setMessage(R.string.permission_post_notifications_message)
                .setPositiveButton(R.string.permission_grant) { _, _ ->
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                .setNegativeButton(R.string.cancel) { _, _ ->
                    pendingNotificationAction = null
                }
                .show()
        }
    }

    private fun requestCalendarExport() {
        requestCalendarPermissionThen { showCalendarPicker() }
    }

    private fun showCalendarPicker() {
        val calendarExporter = CalendarExporter(requireContext())
        // 確保 local calendar 存在
        calendarExporter.getOrCreateLocalCalendar()
        val calendars = calendarExporter.getAvailableCalendars()

        if (calendars.isEmpty()) {
            Toast.makeText(requireContext(), "找不到可用的日曆", Toast.LENGTH_SHORT).show()
            return
        }

        val names = calendars.map { cal ->
            if (cal.isLocal) cal.displayName
            else "${cal.displayName} (${cal.accountName})"
        }.toTypedArray()

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

            val filteredNotifications = applyCalendarWhitelist(notifications)

            val result = withContext(Dispatchers.IO) {
                exporter.exportToCalendar(filteredNotifications, calendarId, detailLevel)
            }

            Toast.makeText(
                ctx,
                "匯出完成：${result.successCount} 筆成功" +
                    if (result.failCount > 0) "，${result.failCount} 筆失敗" else "",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // === .ics 匯出 ===

    private fun showIcsExportDialog() {
        val ranges = arrayOf("最近 7 天", "最近 30 天", "最近 90 天", "全部")
        AlertDialog.Builder(requireContext())
            .setTitle("選擇匯出範圍")
            .setItems(ranges) { _, which ->
                pendingExportStartTime = calculateStartTime(which)
                pendingExportEndTime = System.currentTimeMillis()
                exportIcsLauncher.launch("notification_master.ics")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun exportIcsToUri(uri: android.net.Uri) {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val database = NotificationMasterApp.getInstance().database
                val notifications = withContext(Dispatchers.IO) {
                    database.notificationDao()
                        .getNotificationsByTimeRangePaged(
                            pendingExportStartTime, pendingExportEndTime, Int.MAX_VALUE, 0
                        )
                }

                val filtered = applyCalendarWhitelist(notifications)

                val icsContent = withContext(Dispatchers.IO) {
                    IcsExporter().export(filtered)
                }

                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.use {
                        it.write(icsContent.toByteArray())
                    }
                }

                _binding ?: return@launch
                Toast.makeText(ctx, "已匯出 ${filtered.size} 筆通知到 .ics", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                _binding ?: return@launch
                Toast.makeText(ctx, "匯出失敗：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // === 即時匯出設定 ===

    private fun handleRealtimeCalendarToggle(enabled: Boolean) {
        if (enabled) {
            requestCalendarPermissionThen {
                val calId = AppPreferences.getRealtimeCalendarId(requireContext())
                if (calId < 0) {
                    // 尚未選擇目標日曆，先引導選擇
                    showTargetCalendarPicker()
                } else {
                    AppPreferences.setRealtimeCalendarEnabled(requireContext(), true)
                    updateRealtimeCalendarDisplay()
                }
            }
        } else {
            AppPreferences.setRealtimeCalendarEnabled(requireContext(), false)
            updateRealtimeCalendarDisplay()
        }
    }

    private fun showTargetCalendarPicker() {
        val exporter = CalendarExporter(requireContext())
        // 確保 local calendar 存在
        exporter.getOrCreateLocalCalendar()
        val calendars = exporter.getAvailableCalendars()

        if (calendars.isEmpty()) {
            Toast.makeText(requireContext(), "找不到可用的日曆", Toast.LENGTH_SHORT).show()
            setRealtimeSwitchChecked(false)
            return
        }

        val names = calendars.map { cal ->
            if (cal.isLocal) cal.displayName
            else "${cal.displayName} (${cal.accountName})"
        }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("選擇即時匯出目標日曆")
            .setItems(names) { _, which ->
                val cal = calendars[which]
                AppPreferences.setRealtimeCalendarTarget(requireContext(), cal.id, cal.displayName)
                AppPreferences.setRealtimeCalendarEnabled(requireContext(), true)
                setRealtimeSwitchChecked(true)
                updateRealtimeCalendarDisplay()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                setRealtimeSwitchChecked(false)
            }
            .show()
    }

    private fun setRealtimeSwitchChecked(checked: Boolean) {
        isUpdatingRealtimeSwitch = true
        _binding?.switchRealtimeCalendar?.isChecked = checked
        isUpdatingRealtimeSwitch = false
    }

    private fun updateRealtimeCalendarDisplay() {
        val ctx = context ?: return
        val b = _binding ?: return
        val enabled = AppPreferences.isRealtimeCalendarEnabled(ctx)
        b.layoutRealtimeCalendarOptions.visibility = if (enabled) View.VISIBLE else View.GONE
        val calName = AppPreferences.getRealtimeCalendarName(ctx)
        b.textRealtimeCalendarStatus.text = if (enabled && calName != null) {
            getString(R.string.settings_realtime_calendar_target, calName)
        } else {
            getString(R.string.settings_realtime_calendar_off)
        }
        if (enabled && calName != null) {
            b.textTargetCalendarName.text = getString(R.string.settings_realtime_calendar_target, calName)
            b.textTargetCalendarName.visibility = View.VISIBLE
        } else {
            b.textTargetCalendarName.visibility = View.GONE
        }
    }

    // === 白名單篩選（共用） ===

    private fun applyCalendarWhitelist(
        notifications: List<com.notificationmaster.data.db.entity.NotificationEntity>
    ): List<com.notificationmaster.data.db.entity.NotificationEntity> {
        val ctx = context ?: return notifications
        RuleEngine.load(ctx)
        val whitelistRules = RuleEngine.getRules(ActionType.CALENDAR_EXPORT)
        return if (whitelistRules.isEmpty()) {
            notifications  // 無白名單 → 全部
        } else {
            notifications.filter {
                RuleEngine.matchesSource(ActionType.CALENDAR_EXPORT, it.packageName, it.channelId)
            }
        }
    }

    private fun calculateStartTime(rangeIndex: Int): Long {
        if (rangeIndex == 3) return 0L  // 全部
        val cal = Calendar.getInstance()
        when (rangeIndex) {
            0 -> cal.add(Calendar.DAY_OF_YEAR, -7)
            1 -> cal.add(Calendar.DAY_OF_YEAR, -30)
            2 -> cal.add(Calendar.DAY_OF_YEAR, -90)
        }
        return cal.timeInMillis
    }

    // === JSON 封存匯出 ===

    private fun showArchiveExportDialog() {
        val ranges = arrayOf("最近 7 天", "最近 30 天", "最近 90 天", "全部")

        AlertDialog.Builder(requireContext())
            .setTitle("選擇匯出範圍")
            .setItems(ranges) { _, which ->
                pendingExportStartTime = calculateStartTime(which)
                pendingExportEndTime = System.currentTimeMillis()
                exportJsonLauncher.launch("notification_master_archive.json")
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

            Toast.makeText(ctx, "已清除通知記錄資料庫", Toast.LENGTH_SHORT).show()
            updateStorageInfo()
        }
    }

    // === 過濾規則匯出匯入 ===

    private fun exportFilterRulesToUri(uri: android.net.Uri) {
        val ctx = context ?: return
        try {
            val json = RuleEngine.exportAllToJson()
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                ?: throw IllegalStateException("無法開啟輸出串流")

            val totalRules = RuleEngine.getRules().size
            Toast.makeText(
                ctx,
                getString(R.string.filter_export_success, totalRules),
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "匯出失敗：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmAndImportFilterRules(uri: android.net.Uri) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.settings_import_filter_rules)
            .setMessage(R.string.filter_import_confirm)
            .setPositiveButton(R.string.ok) { _, _ ->
                importFilterRulesFromUri(uri)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun importFilterRulesFromUri(uri: android.net.Uri) {
        val ctx = context ?: return
        try {
            val json = ctx.contentResolver.openInputStream(uri)?.use {
                it.bufferedReader().readText()
            } ?: throw IllegalStateException("無法開啟輸入串流")

            val result = RuleEngine.importAllFromJson(ctx, json)
            val notifCount = result[ActionType.SKIP_RECORD] ?: 0
            val calCount = result[ActionType.CALENDAR_EXPORT] ?: 0
            val dismissCount = result[ActionType.AUTO_DISMISS] ?: 0
            val alertCount = result[ActionType.PERSISTENT_ALERT] ?: 0

            Toast.makeText(
                ctx,
                getString(R.string.filter_import_success, notifCount, calCount) +
                    (if (dismissCount > 0) "、自動清除 ${dismissCount} 條" else "") +
                    (if (alertCount > 0) "、持續提醒 ${alertCount} 條" else ""),
                Toast.LENGTH_LONG
            ).show()

            updateFilterSummary()
            updateAutoDismissSummary()
            updatePersistentAlertSummary()
            updateCalendarWhitelistSummary()
        } catch (e: Exception) {
            Toast.makeText(ctx, "匯入失敗：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // === 過濾規則備份 ===

    private fun checkBackupAndSuggestImport() {
        if (hasPromptedThisSession) return
        if (!RuleEngine.isAllEmpty()) return
        hasPromptedThisSession = true

        val ctx = context ?: return
        if (AppPreferences.isBackupDirEnabled(ctx)) {
            val json = RuleEngine.readBackupFromDir(ctx) ?: return
            AlertDialog.Builder(ctx)
                .setTitle(R.string.filter_backup_found_title)
                .setMessage(R.string.filter_backup_found_message)
                .setPositiveButton(R.string.ok) { _, _ ->
                    importBackupJson(json)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else if (!AppPreferences.isBackupSetupDeclined(ctx)) {
            AlertDialog.Builder(ctx)
                .setTitle(R.string.filter_backup_setup_title)
                .setMessage(R.string.filter_backup_setup_message)
                .setPositiveButton(R.string.ok) { _, _ ->
                    backupDirPickerLauncher.launch(null)
                }
                .setNegativeButton(R.string.cancel) { _, _ ->
                    AppPreferences.setBackupSetupDeclined(ctx, true)
                }
                .show()
        }
    }

    private fun importBackupJson(json: String) {
        val ctx = context ?: return
        try {
            val result = RuleEngine.importAllFromJson(ctx, json)
            val notifCount = result[ActionType.SKIP_RECORD] ?: 0
            val calCount = result[ActionType.CALENDAR_EXPORT] ?: 0
            val dismissCount = result[ActionType.AUTO_DISMISS] ?: 0
            val alertCount = result[ActionType.PERSISTENT_ALERT] ?: 0
            Toast.makeText(
                ctx,
                getString(R.string.filter_import_success, notifCount, calCount) +
                    (if (dismissCount > 0) "、自動清除 ${dismissCount} 條" else "") +
                    (if (alertCount > 0) "、持續提醒 ${alertCount} 條" else ""),
                Toast.LENGTH_LONG
            ).show()
            updateFilterSummary()
            updateAutoDismissSummary()
            updatePersistentAlertSummary()
            updateCalendarWhitelistSummary()
        } catch (e: Exception) {
            Toast.makeText(ctx, "匯入失敗：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleBackupDirSelected(treeUri: android.net.Uri) {
        val ctx = context ?: return
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            ctx.contentResolver.takePersistableUriPermission(treeUri, flags)

            val docFile = DocumentFile.fromTreeUri(ctx, treeUri)
            if (docFile == null || !docFile.canWrite()) {
                Toast.makeText(ctx, R.string.settings_filter_backup_dir_invalid, Toast.LENGTH_SHORT).show()
                return
            }

            val displayName = docFile.name ?: treeUri.lastPathSegment ?: treeUri.toString()
            AppPreferences.setBackupDir(ctx, treeUri, displayName)

            Toast.makeText(ctx, R.string.settings_filter_backup_dir_success, Toast.LENGTH_SHORT).show()
            updateBackupDirDisplay()

            // 規則為空且備份檔案存在 → 建議匯入
            if (RuleEngine.isAllEmpty()) {
                val json = RuleEngine.readBackupFromDir(ctx)
                if (json != null) {
                    AlertDialog.Builder(ctx)
                        .setTitle(R.string.filter_backup_found_title)
                        .setMessage(R.string.filter_backup_found_message)
                        .setPositiveButton(R.string.ok) { _, _ ->
                            importBackupJson(json)
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            } else {
                // 規則非空 → 立即執行一次自動備份
                RuleEngine.autoBackup(ctx)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Failed to take persistable URI permission for backup dir", e)
            Toast.makeText(ctx, R.string.settings_filter_backup_dir_invalid, Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetBackupDir() {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.settings_filter_backup_dir_reset)
            .setMessage(R.string.settings_filter_backup_dir_reset_confirm)
            .setPositiveButton(R.string.ok) { _, _ ->
                val oldUri = AppPreferences.getBackupDirUri(ctx)
                if (oldUri != null) {
                    try {
                        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        ctx.contentResolver.releasePersistableUriPermission(oldUri, flags)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to release persistable URI permission for backup dir", e)
                    }
                }
                AppPreferences.clearBackupDir(ctx)
                Toast.makeText(ctx, R.string.settings_filter_backup_dir_reset_done, Toast.LENGTH_SHORT).show()
                updateBackupDirDisplay()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateBackupDirDisplay() {
        val ctx = context ?: return
        val b = _binding ?: return

        if (AppPreferences.isBackupDirEnabled(ctx)) {
            val displayName = AppPreferences.getBackupDirDisplay(ctx) ?: "..."
            b.textBackupDirPath.text = getString(R.string.settings_filter_backup_dir_set, displayName)
            b.btnResetBackupDir.visibility = View.VISIBLE
        } else {
            b.textBackupDirPath.text = getString(R.string.settings_filter_backup_dir_not_set)
            b.btnResetBackupDir.visibility = View.GONE
        }
    }

    companion object {
        private const val TAG = "SettingsFragment"
        private var hasPromptedThisSession = false
    }
}
