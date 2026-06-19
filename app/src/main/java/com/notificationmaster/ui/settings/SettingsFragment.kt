package com.notificationmaster.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.room.withTransaction
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.notificationmaster.BuildConfig
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.R
import com.notificationmaster.export.calendar.CalendarExportLog
import com.notificationmaster.core.filter.ActionType
import com.notificationmaster.core.filter.RuleEngine
import com.notificationmaster.core.filter.RuleRepository
import com.notificationmaster.core.media.MediaStorageMigrator
import com.notificationmaster.core.prefs.AppPreferences
import com.notificationmaster.core.prefs.AppPreferences.MediaStorageType
import com.notificationmaster.databinding.FragmentSettingsBinding
import com.notificationmaster.debug.DebugDumper
import com.notificationmaster.service.NotificationCaptureService
import com.notificationmaster.service.NlsKeepaliveService
import com.notificationmaster.data.db.dao.querySync
import com.notificationmaster.data.filter.EventFilterSpec
import com.notificationmaster.export.archive.ArchiveAggregateRebuilder
import com.notificationmaster.export.archive.ArchiveExporter
import com.notificationmaster.export.archive.ArchiveImporter
import com.notificationmaster.core.content.ExportDetailLevel
import com.notificationmaster.export.calendar.CalendarExporter
import com.notificationmaster.export.ical.IcsExporter
import com.notificationmaster.ui.common.NotificationDisplay
import com.notificationmaster.ui.filter.CalendarPickerLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
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
        if (uri != null) {
            handleMediaDirSelected(uri)
        } else if (pendingStorageType != null) {
            // Phase 31n：picker 取消（uri == null）但有 pending → 還原 radio + 清 pending
            // 不然 radio 會卡在 PUBLIC_EXTERNAL 但 SAF 未選
            pendingStorageType = null
            refreshStorageRadioFromPrefs()
        }
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

    // 日曆 picker（含權限請求 + picker dialog 統一封裝）
    private val calendarPicker = CalendarPickerLauncher(this)

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
        RuleRepository.load(requireContext())

        setupEnvironmentCard()
        setupFilterSettings()
        setupMediaDirSettings()
        setupDebugSettings()
        setupCalendarIntegration()
        setupDataManagement()
        setupKeepaliveSettings()
        setupExternalSearchSettings()

        checkBackupAndSuggestImport()
    }

    private fun setupEnvironmentCard() {
        binding.cardEnvironment.setOnClickListener {
            findNavController().navigate(R.id.action_settings_home_to_settings_env)
        }
    }

    private fun setupFilterSettings() {
        setupFilterButton(binding.btnFilter, ActionType.SKIP_RECORD)
        setupFilterButton(binding.btnAutoDismiss, ActionType.AUTO_DISMISS)
        binding.btnPersistentAlert.setOnClickListener {
            requestNotificationPermissionThen {
                findNavController().navigate(
                    R.id.action_settings_home_to_settings_filter,
                    bundleOf("actionType" to ActionType.PERSISTENT_ALERT.name)
                )
            }
        }
        setupFilterButton(binding.btnClipboardCopy, ActionType.CLIPBOARD_COPY)
        updateAllRuleSummaries()
        setupFilterRuleManagement()
    }

    private fun setupFilterRuleManagement() {
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
    }

    private fun setupFilterButton(button: View, actionType: ActionType) {
        button.setOnClickListener {
            findNavController().navigate(
                R.id.action_settings_home_to_settings_filter,
                bundleOf("actionType" to actionType.name)
            )
        }
    }

    /**
     * 共用規則摘要更新
     */
    private fun updateRuleSummary(
        actionType: ActionType,
        textView: TextView,
        summaryRes: Int,
        countRes: Int
    ) {
        val count = RuleEngine.getRules(actionType).size
        textView.text = if (count > 0) getString(countRes, count) else getString(summaryRes)
    }

    private fun updateAllRuleSummaries() {
        val b = _binding ?: return
        updateRuleSummary(ActionType.SKIP_RECORD, b.textFilterSummary, R.string.settings_filter_summary, R.string.settings_filter_count)
        updateRuleSummary(ActionType.AUTO_DISMISS, b.textAutoDismissSummary, R.string.settings_auto_dismiss_summary, R.string.settings_auto_dismiss_count)
        updateRuleSummary(ActionType.PERSISTENT_ALERT, b.textPersistentAlertSummary, R.string.settings_persistent_alert_summary, R.string.settings_persistent_alert_count)
        updateRuleSummary(ActionType.CLIPBOARD_COPY, b.textClipboardCopySummary, R.string.settings_clipboard_copy_summary, R.string.settings_clipboard_copy_count)
        updateCalendarWhitelistSummary()
    }

    /**
     * API 34+: 檢查 fullScreenIntent 權限，未授權時顯示提示
     */
    private fun updateFullScreenIntentHint() {
        val b = _binding ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (!nm.canUseFullScreenIntent()) {
                b.textFullscreenIntentHint.visibility = View.VISIBLE
                b.textFullscreenIntentHint.setOnClickListener {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                            data = android.net.Uri.parse("package:${requireContext().packageName}")
                        })
                    } catch (_: Exception) {
                        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                        })
                    }
                }
                return
            }
        }
        b.textFullscreenIntentHint.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        updateDebugInfo()
        updateMediaDirDisplay()
        validateCustomMediaDir()
        updateAllRuleSummaries()
        updateRealtimeCalendarDisplay()
        updateBackupDirDisplay()
        updateFullScreenIntentHint()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupKeepaliveSettings() {
        binding.switchKeepalive.isChecked = AppPreferences.isNlsKeepaliveEnabled(requireContext())

        binding.switchKeepalive.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setNlsKeepaliveEnabled(requireContext(), isChecked)
            if (isChecked) {
                NlsKeepaliveService.start(requireContext())
            } else {
                NlsKeepaliveService.stop(requireContext())
            }
        }

        binding.textKeepaliveSummary.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_keepalive_title)
                .setMessage(R.string.settings_keepalive_detail)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun setupExternalSearchSettings() {
        val pm = requireContext().packageManager
        val processTextComponent = android.content.ComponentName(
            requireContext(), "com.notificationmaster.ProcessTextAlias"
        )
        val shareTextComponent = android.content.ComponentName(
            requireContext(), "com.notificationmaster.ShareTextAlias"
        )

        // W22-mainthread-io：getComponentEnabledSetting 是 PackageManager 跨 process IPC，
        // 搬 IO scope。Switch 預先設 false 避免閃爍，IO 完才更新真實狀態。
        viewLifecycleOwner.lifecycleScope.launch {
            val (processEnabled, shareEnabled) = withContext(Dispatchers.IO) {
                val enabled = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                (pm.getComponentEnabledSetting(processTextComponent) == enabled) to
                    (pm.getComponentEnabledSetting(shareTextComponent) == enabled)
            }
            if (_binding == null) return@launch
            binding.switchProcessText.isChecked = processEnabled
            binding.switchShareText.isChecked = shareEnabled
        }

        // API 23 以下停用 PROCESS_TEXT
        if (android.os.Build.VERSION.SDK_INT < 23) {
            binding.switchProcessText.isEnabled = false
        }

        // W22-mainthread-io：setComponentEnabledSetting 也是 IPC，搬 IO scope 避免 user
        // 切換 switch 卡 UI。
        binding.switchProcessText.setOnCheckedChangeListener { _, isChecked ->
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                pm.setComponentEnabledSetting(
                    processTextComponent,
                    if (isChecked) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP
                )
            }
        }

        binding.switchShareText.setOnCheckedChangeListener { _, isChecked ->
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                pm.setComponentEnabledSetting(
                    shareTextComponent,
                    if (isChecked) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP
                )
            }
        }

        binding.textProcessTextSummary.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_process_text_title)
                .setMessage(R.string.settings_process_text_detail)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        binding.textShareTextSummary.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_share_text_title)
                .setMessage(R.string.settings_share_text_detail)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
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

        // Plan 1-zippy-thunder W10：ProfileLogger per-tag 開關（摺疊區）
        setupProfileLogTagSwitches()
        // Plan 1-zippy-thunder W14：DebugDumper per-type 開關（摺疊區）
        setupDumpTypeSwitches()
        // Plan 1-zippy-thunder W18：lazyload 觀察參數（runtime 可調）
        setupLazyloadDebugInputs()

        // 即時匯出歷程
        binding.switchCalendarExportLog.isChecked = CalendarExportLog.enabled
        binding.btnCalendarExportHistory.isEnabled = CalendarExportLog.enabled
        binding.switchCalendarExportLog.setOnCheckedChangeListener { _, isChecked ->
            CalendarExportLog.enabled = isChecked  // setter 會清 buffer + 重設 startedAt
            binding.btnCalendarExportHistory.isEnabled = isChecked
        }
        binding.btnCalendarExportHistory.setOnClickListener {
            CalendarExportHistoryDialogFragment().show(childFragmentManager, "calendar_export_history")
        }

        binding.btnClearDebug.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("清除 Debug 資料")
                .setMessage("確定要清除所有 Debug dump 檔案嗎？")
                .setPositiveButton(R.string.ok) { _, _ ->
                    // W22-mainthread-io：clearDumpFiles 內 listFiles + 每檔 delete syscall
                    // 搬 IO scope，避免 user 按下後 UI 卡住。完成後拉回 main 顯示 Toast +
                    // 更新檔案數/大小（updateDebugInfo 已內含 IO scope，可直接 call）。
                    viewLifecycleOwner.lifecycleScope.launch {
                        val count = withContext(Dispatchers.IO) {
                            debugDumper.clearDumpFiles()
                        }
                        if (_binding == null) return@launch
                        Toast.makeText(
                            requireContext(),
                            "已清除 $count 個檔案",
                            Toast.LENGTH_SHORT
                        ).show()
                        updateDebugInfo()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        // W23f：DB 統計（除錯用）
        binding.btnDbStats.setOnClickListener { showDbStatsDialog() }

        binding.btnTestAlert.setOnClickListener {
            Toast.makeText(requireContext(), R.string.settings_debug_test_alert_scheduled, Toast.LENGTH_SHORT).show()
            val handler = android.os.Handler(requireContext().mainLooper)
            handler.postDelayed({
                val data = com.notificationmaster.core.alert.AlertData(
                    notificationKey = "debug_test_${System.currentTimeMillis()}",
                    title = "[Debug] 測試持續提醒",
                    text = "這是一則測試提醒，請確認震動、鈴聲和全螢幕顯示是否正常。",
                    soundUri = null,
                    vibrate = true,
                    appName = getString(R.string.app_name),
                    packageName = requireContext().packageName,
                    eventType = "DEBUG",
                    timestamp = System.currentTimeMillis(),
                    subText = null,
                    bigText = null,
                    contentIntent = null,
                    actions = null
                )
                com.notificationmaster.core.alert.PersistentAlertService.start(requireContext(), data)
            }, 10_000L)
        }

        // 手動匯出到指定日曆（除錯用，每次手選目標）
        binding.btnExportIcal.setOnClickListener {
            requestCalendarExport()
        }

        // .ics 檔匯出（除錯用）
        binding.btnExportIcsFile.setOnClickListener {
            showIcsExportDialog()
        }

        NotificationCaptureService.showRankingBanner.observe(viewLifecycleOwner) { show ->
            _binding?.btnUpdateRankingMap?.alpha = if (show) 1.0f else 0.5f
        }
        binding.btnUpdateRankingMap.setOnClickListener {
            if (NotificationCaptureService.isRankingMapPopulated) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.ranking_map_warning_title)
                    .setMessage("目前 RankingMap 狀態正常，確定要送出更新通知嗎？")
                    .setPositiveButton("送出") { _, _ ->
                        sendRankingMapTrigger()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                sendRankingMapTrigger()
            }
        }

        displaySigningInfo()
    }

    /**
     * W23f：DB 統計 dialog — 大資料量測試時快速掌握各表規模與 raw_json 體積，
     * 全部統計 query 在 IO 執行。
     */
    private fun showDbStatsDialog() {
        val loading = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_debug_db_stats)
            .setMessage("計算中…")
            .show()
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext().applicationContext
            val text = withContext(Dispatchers.IO) {
                val db = NotificationMasterApp.getInstance().database
                val eventDao = db.notificationEventDao()
                val total = eventDao.getTotalCountSync()
                val byType = eventDao.getCountByTypeSync()
                val rawBytes = eventDao.getRawJsonTotalBytesSync()
                val topPackages = eventDao.getTopPackagesByCountSync(10)
                val topKeys = eventDao.getTopKeysByCountSync(10)
                val recordCount = db.notificationRecordDao().getTotalCount()
                val observationCount = db.rankingObservationDao().getTotalCount()
                val snapshotCount = db.rankingSnapshotDao().getTotalCount()
                val mediaCount = db.mediaAttachmentDao().getTotalCount()
                val mediaBytes = db.mediaAttachmentDao().getTotalSize() ?: 0L
                val fmt = { bytes: Long ->
                    android.text.format.Formatter.formatShortFileSize(ctx, bytes)
                }
                buildString {
                    appendLine("events: $total（raw_json ${fmt(rawBytes)}）")
                    for (t in byType) appendLine("  ${t.name}: ${t.cnt}")
                    appendLine()
                    appendLine("records: $recordCount")
                    appendLine("observations: $observationCount")
                    appendLine("snapshots: $snapshotCount")
                    appendLine("attachments: $mediaCount（${fmt(mediaBytes)}）")
                    appendLine()
                    appendLine("Top packages（by events）:")
                    for (g in topPackages) appendLine("  ${g.cnt} × ${g.name}")
                    appendLine()
                    appendLine("Top nkeys（by events）:")
                    for (g in topKeys) appendLine("  ${g.cnt} × ${g.name}")
                }
            }
            loading.dismiss()
            if (_binding == null) return@launch
            val textView = TextView(requireContext()).apply {
                this.text = text
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 12f
                setPadding(48, 24, 48, 24)
                setTextIsSelectable(true)
            }
            val scrollView = android.widget.ScrollView(requireContext()).apply { addView(textView) }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_debug_db_stats)
                .setView(scrollView)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    private fun sendRankingMapTrigger() {
        val service = NotificationCaptureService.getInstance()
        if (service != null && NotificationCaptureService.isConnected) {
            service.triggerRankingMapUpdate()
            Toast.makeText(requireContext(), "RankingMap 更新已觸發", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "服務未連線", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateDebugInfo() {
        // Plan 1-zippy-thunder 後續修補：呈現方式維持，僅把路徑「標的」從 event_dump 子層換成
        // debug 主目錄（W4 統一結構：debug/ 下派生 event_dump/ + profile_log/ 等子目錄）。
        //
        // W22-mainthread-io：listFiles + 每檔 stat 搬到 IO scope（user 切到 Settings 每次
        // onResume 都會跑，event_dump 累積上千檔時會卡 main thread 觸發 ANR）。原本
        // getDumpFileCount + getDumpTotalSize 各 call 一次 listFiles，合併為單次 IO 計算。
        val ctx = context ?: return
        val rootDir = com.notificationmaster.core.debug.DebugPaths.rootDir(ctx)
        binding.textDebugInfo.text = "路徑: ${rootDir.absolutePath}\n計算中…"
        viewLifecycleOwner.lifecycleScope.launch {
            val (fileCount, totalSize) = withContext(Dispatchers.IO) {
                val files = debugDumper.getDumpFiles()
                files.size to files.sumOf { it.length() }
            }
            if (_binding == null) return@launch
            val sizeStr = android.text.format.Formatter.formatShortFileSize(requireContext(), totalSize)
            binding.textDebugInfo.text = buildString {
                append("路徑: ${rootDir.absolutePath}\n")
                append("檔案數: $fileCount, 大小: $sizeStr")
            }
        }
    }

    /**
     * Plan 1-zippy-thunder W10：ProfileLogger per-tag 開關 UI。
     *
     * Header TextView 點擊切換展開／收合，container 內動態 inflate 12 個 row：
     * [TextView | SwitchMaterial]。Switch 狀態用 [AppPreferences.isDebugTagPrefEnabled]
     * 取得（純讀個別 pref，不被 debug 總開關 chain）。
     */
    private fun setupProfileLogTagSwitches() {
        val ctx = requireContext()
        val container = binding.containerProfileLogTags
        val header = binding.headerProfileLogTags

        container.removeAllViews()
        val inflater = LayoutInflater.from(ctx)
        for (tag in AppPreferences.KNOWN_PROFILE_LOG_TAGS) {
            val row = inflater.inflate(R.layout.row_debug_toggle, container, false)
            row.findViewById<TextView>(R.id.toggle_label).text = tag
            val sw = row.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.toggle_switch)
            sw.isChecked = AppPreferences.isDebugTagPrefEnabled(ctx, tag)
            sw.setOnCheckedChangeListener { _, isChecked ->
                AppPreferences.setDebugTagEnabled(ctx, tag, isChecked)
            }
            container.addView(row)
        }

        renderHeaderArrow(header, R.string.settings_debug_profile_log_tags_header, container.visibility == View.VISIBLE)
        header.setOnClickListener {
            val expanded = container.visibility != View.VISIBLE
            container.visibility = if (expanded) View.VISIBLE else View.GONE
            renderHeaderArrow(header, R.string.settings_debug_profile_log_tags_header, expanded)
        }
    }

    private fun renderHeaderArrow(header: TextView, @androidx.annotation.StringRes resId: Int, expanded: Boolean) {
        val arrow = if (expanded) getString(R.string.settings_debug_expanded_arrow)
                    else getString(R.string.settings_debug_collapsed_arrow)
        header.text = getString(resId, arrow)
    }

    /**
     * Plan 1-zippy-thunder W18：lazyload runtime debug inputs。
     *
     * 三個輸入（W22-debug 新增 initial page size）：
     * - footer 最少可見時間（ms）
     * - cold start 初始頁面大小（events）— 調大可避免 auto-fill 連發
     * - 末尾自動 lazyload 閾值（distance ≤ N，0=關閉）
     *
     * 失焦或 Enter 時寫入 AppPreferences；ViewModel/Fragment 每次 lazyload 時 runtime 讀。
     * Initial page size 變更需 process restart（ViewModel._pageSize 初始值讀一次）— 切離
     * App 重開即可生效。
     */
    private fun setupLazyloadDebugInputs() {
        val ctx = requireContext()
        val footerInput = binding.inputLazyloadFooterMin
        val initialPageSizeInput = binding.inputLazyloadInitialPageSize
        val thresholdInput = binding.inputLazyloadAutoThreshold

        footerInput.setText(AppPreferences.getLazyloadFooterMinMs(ctx).toString())
        initialPageSizeInput.setText(AppPreferences.getLazyloadInitialPageSize(ctx).toString())
        thresholdInput.setText(AppPreferences.getLazyloadAutoThreshold(ctx).toString())

        footerInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                footerInput.text?.toString()?.toLongOrNull()?.let {
                    AppPreferences.setLazyloadFooterMinMs(ctx, it)
                }
            }
        }
        initialPageSizeInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                initialPageSizeInput.text?.toString()?.toIntOrNull()?.let {
                    AppPreferences.setLazyloadInitialPageSize(ctx, it.coerceAtLeast(1))
                }
            }
        }
        thresholdInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                thresholdInput.text?.toString()?.toIntOrNull()?.let {
                    AppPreferences.setLazyloadAutoThreshold(ctx, it.coerceAtLeast(0))
                }
            }
        }
    }

    /**
     * Plan 1-zippy-thunder W14：DebugDumper per-type 開關 UI（與 W10 同 pattern）。
     */
    private fun setupDumpTypeSwitches() {
        val ctx = requireContext()
        val container = binding.containerDumpTypes
        val header = binding.headerDumpTypes

        container.removeAllViews()
        val inflater = LayoutInflater.from(ctx)
        val labels = mapOf(
            AppPreferences.DumpType.ENV to getString(R.string.settings_debug_dump_type_env),
            AppPreferences.DumpType.EVENT to getString(R.string.settings_debug_dump_type_event),
            AppPreferences.DumpType.INITIAL to getString(R.string.settings_debug_dump_type_initial),
            AppPreferences.DumpType.RANKING to getString(R.string.settings_debug_dump_type_ranking),
            AppPreferences.DumpType.CHANNEL to getString(R.string.settings_debug_dump_type_channel)
        )
        for (type in AppPreferences.DumpType.entries) {
            val row = inflater.inflate(R.layout.row_debug_toggle, container, false)
            row.findViewById<TextView>(R.id.toggle_label).text = labels[type] ?: type.name
            val sw = row.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.toggle_switch)
            sw.isChecked = AppPreferences.isDumpTypePrefEnabled(ctx, type)
            sw.setOnCheckedChangeListener { _, isChecked ->
                AppPreferences.setDumpTypeEnabled(ctx, type, isChecked)
            }
            container.addView(row)
        }

        renderHeaderArrow(header, R.string.settings_debug_dump_types_header, container.visibility == View.VISIBLE)
        header.setOnClickListener {
            val expanded = container.visibility != View.VISIBLE
            container.visibility = if (expanded) View.VISIBLE else View.GONE
            renderHeaderArrow(header, R.string.settings_debug_dump_types_header, expanded)
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



    private fun setupCalendarIntegration() {
        // 即時匯出總開關（per-rule calendar 後，目標日曆改由各規則自帶）
        binding.switchRealtimeCalendar.isChecked =
            AppPreferences.isRealtimeCalendarEnabled(requireContext())
        binding.switchRealtimeCalendar.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingRealtimeSwitch) handleRealtimeCalendarToggle(isChecked)
        }
        updateRealtimeCalendarDisplay()

        // 白名單（CALENDAR_EXPORT 規則管理）
        binding.btnCalendarWhitelist.setOnClickListener {
            findNavController().navigate(
                R.id.action_settings_home_to_settings_filter,
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

    // Phase 31l：防止程式設定 RadioGroup 觸發 onCheckedChange 迴圈
    private var isUpdatingStorageRadio = false
    // 待搬遷的目標 type（PUBLIC_EXTERNAL 流程需要等 SAF picker 回來才能正式切換）
    private var pendingStorageType: MediaStorageType? = null

    private fun setupMediaDirSettings() {
        binding.btnChooseMediaDir.setOnClickListener {
            mediaDirPickerLauncher.launch(null)
        }

        binding.btnResetMediaDir.setOnClickListener {
            resetMediaDir()
        }

        // 初始化 RadioGroup 選中當前 type
        refreshStorageRadioFromPrefs()

        binding.radioMediaStorage.setOnCheckedChangeListener { _, checkedId ->
            // Phase 31n：先依 radio 即時顯隱 SAF section（不論程式 sync 或 user 手動切換），
            // user 切到 PUBLIC_EXTERNAL 瞬間就能看到當前 SAF 路徑做參考。
            updateMediaDirDisplay()
            if (isUpdatingStorageRadio) return@setOnCheckedChangeListener
            val newType = when (checkedId) {
                R.id.radio_media_internal -> MediaStorageType.INTERNAL
                R.id.radio_media_app_external -> MediaStorageType.APP_EXTERNAL
                R.id.radio_media_public_external -> MediaStorageType.PUBLIC_EXTERNAL
                else -> return@setOnCheckedChangeListener
            }
            onStorageTypeSelected(newType)
        }

        updateMediaDirDisplay()
    }

    /** Phase 31l：依當前 prefs 設 RadioGroup checked，不觸發 listener */
    private fun refreshStorageRadioFromPrefs() {
        val ctx = context ?: return
        val b = _binding ?: return
        val current = AppPreferences.getMediaStorageType(ctx)
        val targetId = when (current) {
            MediaStorageType.INTERNAL -> R.id.radio_media_internal
            MediaStorageType.APP_EXTERNAL -> R.id.radio_media_app_external
            MediaStorageType.PUBLIC_EXTERNAL -> R.id.radio_media_public_external
        }
        if (b.radioMediaStorage.checkedRadioButtonId != targetId) {
            isUpdatingStorageRadio = true
            try { b.radioMediaStorage.check(targetId) }
            finally { isUpdatingStorageRadio = false }
        }
    }

    /**
     * Phase 31l：使用者選了新 storage type。
     *
     * - newType == current → no-op
     * - newType == PUBLIC_EXTERNAL 且未設 SAF → 啟動 SAF picker，picker 完成後續走 onStorageTypeSelected
     * - 其他 → confirm dialog + migrate
     */
    private fun onStorageTypeSelected(newType: MediaStorageType) {
        val ctx = context ?: return
        val current = AppPreferences.getMediaStorageType(ctx)
        if (newType == current) return

        if (newType == MediaStorageType.PUBLIC_EXTERNAL &&
            !AppPreferences.isCustomMediaDirEnabled(ctx)
        ) {
            // 先請 user 選 SAF 目錄，picker 回來才正式切換
            pendingStorageType = newType
            Toast.makeText(ctx, R.string.settings_media_migrate_need_saf, Toast.LENGTH_SHORT).show()
            mediaDirPickerLauncher.launch(null)
            return
        }

        confirmAndMigrateStorageType(current, newType)
    }

    /** Phase 31l：confirm dialog + migrate */
    private fun confirmAndMigrateStorageType(from: MediaStorageType, to: MediaStorageType) {
        val ctx = context ?: return
        // 估算 source 檔案數，給 confirm dialog 顯示
        viewLifecycleOwner.lifecycleScope.launch {
            val fileCount = withContext(Dispatchers.IO) {
                countSourceFiles(ctx, from)
            }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.settings_media_migrate_title)
                .setMessage(getString(R.string.settings_media_migrate_message, fileCount))
                .setPositiveButton(R.string.ok) { _, _ ->
                    executeMigration(from, to)
                }
                .setNegativeButton(R.string.cancel) { _, _ ->
                    // 取消 → radio 還原為原 type
                    refreshStorageRadioFromPrefs()
                }
                .setOnCancelListener { refreshStorageRadioFromPrefs() }
                .show()
        }
    }

    private fun executeMigration(from: MediaStorageType, to: MediaStorageType) {
        val ctx = context ?: return
        val progress = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.settings_media_migrate_title)
            .setMessage(R.string.settings_media_migrate_in_progress)
            .setCancelable(false)
            .show()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = MediaStorageMigrator.migrate(ctx, from, to)
            // 切換 prefs（搬運完才正式切換，這樣搬運中 NLS 寫入仍走舊位置；
            // 若搬運中有新檔案產生，下次切換或 cross-type fallback 仍能命中）
            AppPreferences.setMediaStorageType(ctx, to)
            progress.dismiss()
            Toast.makeText(
                ctx,
                getString(
                    R.string.settings_media_migrate_done,
                    result.copied, result.skipped, result.failed
                ),
                Toast.LENGTH_LONG
            ).show()
            refreshStorageRadioFromPrefs()
            updateMediaDirDisplay()
        }
    }

    /** 估算搬遷檔案數，給 confirm dialog 顯示 */
    private fun countSourceFiles(ctx: Context, type: MediaStorageType): Int {
        return try {
            when (type) {
                MediaStorageType.INTERNAL, MediaStorageType.APP_EXTERNAL -> {
                    val dir = java.io.File(
                        com.notificationmaster.core.media.MediaExtractor.getMediaBaseDir(ctx, type),
                        com.notificationmaster.core.media.MediaExtractor.MEDIA_DIR
                    )
                    if (!dir.isDirectory) 0 else (dir.listFiles()?.count { it.isFile } ?: 0)
                }
                MediaStorageType.PUBLIC_EXTERNAL -> {
                    val uri = AppPreferences.getCustomMediaDirUri(ctx) ?: return 0
                    DocumentFile.fromTreeUri(ctx, uri)?.listFiles()
                        ?.count { it.isFile && !it.name.isNullOrEmpty() } ?: 0
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "countSourceFiles failed for $type", e)
            0
        }
    }

    /**
     * 處理使用者選擇的媒體目錄（SAF picker 回傳）
     *
     * Phase 31l：若 pendingStorageType == PUBLIC_EXTERNAL，picker 完成後正式觸發 migrate；
     * 否則僅更新 SAF tree URI（user 在 PUBLIC_EXTERNAL 模式下換目錄）。
     */
    private fun handleMediaDirSelected(treeUri: android.net.Uri) {
        // W22-mainthread-io：SAF picker callback 內的 takePersistableUriPermission +
        // DocumentFile.fromTreeUri + canWrite() 是 ContentResolver / SAF IPC，搬 IO scope。
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    ctx.contentResolver.takePersistableUriPermission(treeUri, flags)

                    val docFile = DocumentFile.fromTreeUri(ctx, treeUri)
                    if (docFile == null || !docFile.canWrite()) {
                        return@withContext MediaDirSelectResult.INVALID
                    }
                    val displayName = docFile.name ?: treeUri.lastPathSegment ?: treeUri.toString()
                    AppPreferences.setCustomMediaDir(ctx, treeUri, displayName)
                    MediaDirSelectResult.OK
                } catch (e: SecurityException) {
                    Log.w(TAG, "Failed to take persistable URI permission", e)
                    MediaDirSelectResult.INVALID
                }
            }
            if (_binding == null) return@launch
            when (result) {
                MediaDirSelectResult.OK -> {
                    Toast.makeText(ctx, R.string.settings_media_dir_success, Toast.LENGTH_SHORT).show()
                    updateMediaDirDisplay()
                    // 若是 PUBLIC_EXTERNAL pending → 接續 migrate 流程
                    val pending = pendingStorageType
                    pendingStorageType = null
                    if (pending == MediaStorageType.PUBLIC_EXTERNAL) {
                        confirmAndMigrateStorageType(AppPreferences.getMediaStorageType(ctx), pending)
                    }
                }
                MediaDirSelectResult.INVALID -> {
                    Toast.makeText(ctx, R.string.settings_media_dir_invalid, Toast.LENGTH_SHORT).show()
                    pendingStorageType = null
                    refreshStorageRadioFromPrefs()
                }
            }
        }
    }

    private enum class MediaDirSelectResult { OK, INVALID }

    /**
     * 重設 PUBLIC_EXTERNAL 自訂 SAF 目錄（不切換 storage type，僅清掉 tree URI）。
     */
    private fun resetMediaDir() {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.settings_media_dir_reset)
            .setMessage(R.string.settings_media_dir_reset_confirm)
            .setPositiveButton(R.string.ok) { _, _ ->
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
     * Phase 31l：更新 SAF section 顯示與按鈕狀態。
     *
     * Phase 31n：改依 RadioGroup 當下 checkedId 顯隱（不依 prefs），這樣 user 切到
     * PUBLIC_EXTERNAL 的瞬間（即使 prefs 尚未寫入）就能立刻看到當前 SAF 路徑做參考；
     * 切換 confirm dialog 取消還原 radio 也會即時隱藏 SAF section。
     */
    private fun updateMediaDirDisplay() {
        val ctx = context ?: return
        val b = _binding ?: return
        val isPublic = b.radioMediaStorage.checkedRadioButtonId == R.id.radio_media_public_external
        b.layoutMediaSafSection.visibility = if (isPublic) View.VISIBLE else View.GONE
        if (!isPublic) return

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
     *
     * W22-mainthread-io：`DocumentFile.fromTreeUri` + `canWrite()` 是 SAF + ContentResolver
     * IPC，搬到 IO scope 避免每次 onResume 卡 main thread。失敗才更新 UI（顯示 lost）。
     */
    private fun validateCustomMediaDir() {
        val ctx = context ?: return
        val uri = AppPreferences.getCustomMediaDirUri(ctx) ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            val accessible = withContext(Dispatchers.IO) {
                try {
                    val docFile = DocumentFile.fromTreeUri(ctx, uri)
                    docFile != null && docFile.canWrite()
                } catch (e: Exception) {
                    Log.w(TAG, "Custom media dir validation failed", e)
                    false
                }
            }
            if (!accessible) {
                _binding?.textMediaDirPath?.let { tv ->
                    tv.text = getString(R.string.settings_media_dir_lost)
                    tv.setTextColor(ContextCompat.getColor(ctx, R.color.status_disabled))
                }
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

    /**
     * 手動匯出（除錯用）：每次手選目標日曆 + detail level，匯出最近 7 天通知
     */
    private fun requestCalendarExport() {
        calendarPicker.pick { cal ->
            val exporter = CalendarExporter(requireContext()).apply {
                setTargetAccount(cal.accountName, cal.accountType)
            }
            showCalendarDetailLevelPicker(cal.id, exporter)
        }
    }

    private fun showCalendarDetailLevelPicker(calendarId: Long, exporter: CalendarExporter) {
        val levels = ExportDetailLevel.entries
        val labels = levels.map { it.displayName() }.toTypedArray<CharSequence>()

        AlertDialog.Builder(requireContext())
            .setTitle("選擇匯出精細程度")
            .setItems(labels) { _, which ->
                exportToCalendar(calendarId, levels[which], exporter)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun exportToCalendar(calendarId: Long, detailLevel: ExportDetailLevel, exporter: CalendarExporter) {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            // 預設匯出最近 7 天
            val endTime = System.currentTimeMillis()
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -7)
            val startTime = cal.timeInMillis

            val displays = withContext(Dispatchers.IO) {
                loadDisplaysForExport(startTime, endTime, limit = 500)
            }

            val filtered = applyCalendarWhitelist(displays)

            val result = withContext(Dispatchers.IO) {
                exporter.exportToCalendar(filtered, calendarId, detailLevel)
            }

            showResultDialog(
                "日曆匯出完成",
                "匯出完成：${result.successCount} 筆成功" +
                    if (result.failCount > 0) "，${result.failCount} 筆失敗" else ""
            )
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
                val displays = withContext(Dispatchers.IO) {
                    loadDisplaysForExport(pendingExportStartTime, pendingExportEndTime, limit = null)
                }

                val filtered = applyCalendarWhitelist(displays)

                val icsContent = withContext(Dispatchers.IO) {
                    IcsExporter(requireContext()).export(filtered)
                }

                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.use {
                        it.write(icsContent.toByteArray())
                    }
                }

                _binding ?: return@launch
                showResultDialog("ICS 匯出完成", "已匯出 ${filtered.size} 筆通知到 .ics")
            } catch (e: Exception) {
                _binding ?: return@launch
                Toast.makeText(ctx, "匯出失敗：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // === 即時匯出設定 ===

    /**
     * per-rule calendar 後，總開關不再涉及目標日曆選擇 — 目標日曆改由各 CALENDAR_EXPORT
     * rule 自帶。開關時僅檢查日曆權限（service 寫入時需要）。
     */
    private fun handleRealtimeCalendarToggle(enabled: Boolean) {
        if (enabled) {
            calendarPicker.requestPermissionOnly(
                onGranted = {
                    AppPreferences.setRealtimeCalendarEnabled(requireContext(), true)
                    updateRealtimeCalendarDisplay()
                },
                onDenied = { setRealtimeSwitchChecked(false) }
            )
        } else {
            AppPreferences.setRealtimeCalendarEnabled(requireContext(), false)
            updateRealtimeCalendarDisplay()
        }
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
        b.textRealtimeCalendarStatus.text = getString(
            if (enabled) R.string.settings_realtime_calendar_on
            else R.string.settings_realtime_calendar_off
        )
    }

    // === 白名單篩選（共用） ===

    /**
     * Plan 2 Phase 8：匯出資料來源改為 NotificationEventEntity（每個 notificationKey 取最新一筆）
     * → 攤平為 NotificationDisplay 供 Calendar / Ics exporter 使用。
     */
    private suspend fun loadDisplaysForExport(
        startTime: Long,
        endTime: Long,
        limit: Int?
    ): List<NotificationDisplay> = withContext(Dispatchers.IO) {
        val database = NotificationMasterApp.getInstance().database
        val spec = EventFilterSpec(
            deduplicate = true,
            timeFrom = startTime,
            timeTo = endTime,
            limit = limit
        )
        database.notificationEventDao().querySync(spec).map { NotificationDisplay.from(it) }
    }

    private fun applyCalendarWhitelist(
        displays: List<NotificationDisplay>
    ): List<NotificationDisplay> {
        val ctx = context ?: return displays
        RuleRepository.load(ctx)
        val whitelistRules = RuleEngine.getRules(ActionType.CALENDAR_EXPORT)
        return if (whitelistRules.isEmpty()) {
            displays  // 無白名單 → 全部
        } else {
            displays.filter {
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
                // W23v：預設檔名帶 timestamp，避免多次匯出互相覆蓋、便於辨識版本
                val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date(pendingExportEndTime))
                exportJsonLauncher.launch("notification_master_archive_$stamp.json")
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

                // W22-mainthread-io：openOutputStream + outputStream.close() 都是
                // ContentResolver IPC，搬到 IO dispatcher。exporter.export 本身已是
                // suspend + withContext(IO)，這裡將 open/close 包進來統一在 IO 跑。
                val stats = withContext(Dispatchers.IO) {
                    val outputStream = ctx.contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("無法開啟輸出串流")
                    outputStream.use {
                        exporter.export(
                            pendingExportStartTime,
                            pendingExportEndTime,
                            it
                        )
                    }
                }

                showResultDialog(
                    "封存匯出完成",
                    buildString {
                        append("通知：${stats.recordCount} 筆")
                        append("\n事件：${stats.eventCount} 筆")
                        if (stats.observationCount > 0) append("\nRanking 觀察：${stats.observationCount} 筆")
                        if (stats.snapshotCount > 0) append("\nRanking 快照：${stats.snapshotCount} 筆")
                        if (stats.mediaCount > 0) append("\n媒體：${stats.mediaCount} 筆")
                    }
                )
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
        val totalBytes = queryUriSize(uri)

        // W23r：determinate 進度條（位元組）+ 即時計數文字。setCancelable(false)：
        // transaction 進行中不可中途取消（會留半套；rollback 由例外路徑負責）。
        val bar = LinearProgressIndicator(ctx).apply {
            isIndeterminate = totalBytes <= 0
            max = 100
        }
        val text = TextView(ctx).apply {
            setPadding(0, 24, 0, 0)
            setText(R.string.import_progress_preparing)
        }
        val content = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 48, 64, 16)
            addView(bar)
            addView(text)
        }
        val progressDialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.import_progress_title)
            .setView(content)
            .setCancelable(false)
            .show()

        val mainHandler = Handler(Looper.getMainLooper())

        // 進度回呼 → main：phase 標籤 + 位元組進度條 + 即時計數
        fun postProgress(phaseLabel: String, p: ArchiveImporter.ImportProgress) {
            mainHandler.post {
                if (!progressDialog.isShowing) return@post
                if (totalBytes > 0) {
                    bar.isIndeterminate = false
                    bar.progress = ((p.bytesRead * 100) / totalBytes).toInt().coerceIn(0, 100)
                }
                text.text = getString(
                    R.string.import_progress_phase, phaseLabel,
                    p.events, p.observations, p.snapshots, p.records
                )
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val database = NotificationMasterApp.getInstance().database
            try {
                val importer = ArchiveImporter(ctx)
                val validateLabel = getString(R.string.import_progress_validating)
                val writeLabel = getString(R.string.import_progress_writing)

                // PASS 1：純驗證（結構 + 版本 + 計數），不寫 DB；不通過直接拋、零寫入
                val report = ctx.contentResolver.openInputStream(uri)?.use { ins ->
                    importer.validate(ins, totalBytes) { p -> postProgress(validateLabel, p) }
                } ?: throw IllegalStateException("無法開啟輸入串流")

                // PASS 2：通過後才寫入；整段 + 聚合重建包單一 transaction（crash-safe）
                var rebuilt: Pair<Int, Int> = 0 to 0
                val result = database.withTransaction {
                    // W23t：匯出檔 key 順序為 events 先於 records，但 events 對 records
                    // 有 FK（notification_key）。串流按檔案順序插入 → events 早於其
                    // 父 record → 立即 FK 違反。defer_foreign_keys 把 FK 檢查延到
                    // commit（records/snapshots 屆時都已在本 transaction 內），插入
                    // 順序即無關。pragma 於每次 commit/rollback 自動關閉，僅作用本 txn。
                    database.openHelper.writableDatabase.execSQL("PRAGMA defer_foreign_keys = ON")
                    val r = ctx.contentResolver.openInputStream(uri)?.use { ins ->
                        importer.import(ins, database, totalBytes, report.orphanEventKeys) { p ->
                            postProgress(writeLabel, p)
                        }
                    } ?: throw IllegalStateException("無法開啟輸入串流")
                    // W23q：聚合重建放同一 transaction，原子性 + 可見剛插入的 events
                    rebuilt = ArchiveAggregateRebuilder.rebuild(ctx, database)
                    r
                }

                progressDialog.dismiss()
                showResultDialog(
                    getString(R.string.import_done_title),
                    buildString {
                        append("通知：${result.records} 筆")
                        append("\n事件：${result.events} 筆")
                        if (result.observations > 0) append("\nRanking 觀察：${result.observations} 筆")
                        if (result.snapshots > 0) append("\nRanking 快照：${result.snapshots} 筆")
                        if (result.mediaRestored > 0) append("\n媒體還原：${result.mediaRestored} 筆")
                        append("\n歸檔聚合重建：${rebuilt.first} apps / ${rebuilt.second} channels")
                        if (result.placeholderRecords > 0) {
                            append("\n⚠ 補建佔位通知：${result.placeholderRecords} 筆")
                            append("（事件缺對應通知記錄，已補 placeholder 以保留事件）")
                        }
                        append(
                            if (report.countVerified) "\n\n✓ 已通過完整性驗證（計數核對 + 參照完整性）"
                            else "\n\n✓ 已通過結構與參照完整性驗證（此檔無計數中繼資料）"
                        )
                        result.environment?.let {
                            append("\n來源裝置：${it.deviceManufacturer} ${it.deviceModel}")
                            append("\nAPI：${it.apiLevel}")
                        }
                    }
                )
            } catch (e: Exception) {
                progressDialog.dismiss()
                Toast.makeText(ctx, "匯入失敗：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 從 content uri 取檔案大小（OpenableColumns.SIZE）；取不到回 0 → 進度條 indeterminate */
    private fun queryUriSize(uri: android.net.Uri): Long {
        val ctx = context ?: return 0L
        return try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else 0L
                } else 0L
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // === 清除資料 ===

    private fun clearAllData() {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val database = NotificationMasterApp.getInstance().database

            withContext(Dispatchers.IO) {
                // 依 FK 順序：observations → events → records → snapshots
                database.rankingObservationDao().deleteAll()
                database.notificationEventDao().deleteAll()
                database.notificationRecordDao().deleteAll()
                database.rankingSnapshotDao().deleteAll()
                database.mediaAttachmentDao().deleteAll()
                database.actionDao().deleteAll()
                database.appSourceDao().deleteAll()
                database.channelDao().deleteAll()
                database.deviceStateDao().deleteAll()
            }

            Toast.makeText(ctx, "已清除通知記錄資料庫", Toast.LENGTH_SHORT).show()
        }
    }

    // === 過濾規則匯出匯入 ===

    private fun exportFilterRulesToUri(uri: android.net.Uri) {
        val ctx = context ?: return
        try {
            val json = RuleEngine.exportAllToJson()
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                ?: throw IllegalStateException("無法開啟輸出串流")

            val counts = ActionType.entries.associateWith { RuleEngine.getRules(it).size }
            val total = counts.values.sum()
            val breakdown = formatRuleBreakdown(counts)
            showResultDialog(
                "規則匯出完成",
                getString(R.string.filter_export_success, total) +
                    if (breakdown.isNotEmpty()) "\n\n$breakdown" else ""
            )
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

            val result = RuleRepository.importAllFromJson(ctx, json)
            val total = result.values.sum()
            val breakdown = formatRuleBreakdown(result)

            showResultDialog(
                "規則匯入完成",
                getString(R.string.filter_import_success, total) +
                    if (breakdown.isNotEmpty()) "\n\n$breakdown" else ""
            )

            updateAllRuleSummaries()
        } catch (e: Exception) {
            Toast.makeText(ctx, "匯入失敗：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // === 過濾規則備份 ===

    /**
     * 進入設定頁時自動檢查備份狀態
     *
     * 流程：
     * 1. 已設定備份目錄 → 檢查備份檔案 → 有未同步規則 → 詢問合併匯入
     * 2. 未設定備份目錄（且未拒絕） → 建議設定
     * 3. 使用者忽略 → 本次 session 不再提問
     */
    private fun checkBackupAndSuggestImport() {
        if (hasPromptedThisSession) return
        hasPromptedThisSession = true

        val ctx = context ?: return
        if (AppPreferences.isBackupDirEnabled(ctx)) {
            // W22-mainthread-io：有備份目錄場景 `RuleRepository.readBackupFromDir`
            // (SAF findFile + ContentResolver openInputStream + readText) 跑 main thread
            // 卡 UI，是 SettingsFragment onViewCreated ANR root cause 之一。搬 IO scope
            // 計算完成才彈 dialog。
            viewLifecycleOwner.lifecycleScope.launch {
                val pair = withContext(Dispatchers.IO) {
                    val json = RuleRepository.readBackupFromDir(ctx) ?: return@withContext null
                    val newCount = RuleEngine.countNewRulesInBackup(json)
                    json to newCount
                }
                if (pair == null || _binding == null) return@launch
                val (json, newCount) = pair
                if (newCount > 0) {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.filter_backup_found_title)
                        .setMessage(getString(R.string.filter_backup_found_message, newCount))
                        .setPositiveButton(R.string.ok) { _, _ ->
                            mergeBackupJson(json)
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
        } else if (!AppPreferences.isBackupSetupDeclined(ctx)) {
            // 未設定備份目錄 → 建議設定（無 IO，main thread 即可）
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

    /**
     * 合併匯入備份規則（保留現有規則，僅加入新規則）
     */
    private fun mergeBackupJson(json: String) {
        val ctx = context ?: return
        try {
            val result = RuleRepository.mergeFromJson(ctx, json)
            if (result.isEmpty()) {
                showResultDialog("備份同步", "備份規則已全部同步，無需匯入")
                return
            }
            val total = result.values.sum()
            val breakdown = formatRuleBreakdown(result)
            showResultDialog(
                "備份匯入完成",
                getString(R.string.filter_import_success, total) +
                    if (breakdown.isNotEmpty()) "\n\n$breakdown" else ""
            )
            updateAllRuleSummaries()
        } catch (e: Exception) {
            Toast.makeText(ctx, "匯入失敗：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleBackupDirSelected(treeUri: android.net.Uri) {
        // W22-mainthread-io：SAF picker callback 內 takePersistableUriPermission +
        // DocumentFile + canWrite + readBackupFromDir + countNewRulesInBackup + autoBackup
        // 全是 ContentResolver / SAF / File IO，搬 IO scope；UI 結果（Toast / Dialog）拉回
        // main thread。
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    ctx.contentResolver.takePersistableUriPermission(treeUri, flags)

                    val docFile = DocumentFile.fromTreeUri(ctx, treeUri)
                    if (docFile == null || !docFile.canWrite()) {
                        return@withContext BackupDirSelectOutcome.Invalid
                    }
                    val displayName = docFile.name ?: treeUri.lastPathSegment ?: treeUri.toString()
                    AppPreferences.setBackupDir(ctx, treeUri, displayName)

                    val json = RuleRepository.readBackupFromDir(ctx)
                    if (json == null) {
                        // 無備份檔案 → 立即執行一次自動備份
                        RuleRepository.autoBackup(ctx)
                        BackupDirSelectOutcome.OkNoBackup
                    } else {
                        val newCount = RuleEngine.countNewRulesInBackup(json)
                        BackupDirSelectOutcome.OkWithBackup(json, newCount)
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "Failed to take persistable URI permission for backup dir", e)
                    BackupDirSelectOutcome.Invalid
                }
            }
            if (_binding == null) return@launch
            when (outcome) {
                BackupDirSelectOutcome.Invalid -> {
                    Toast.makeText(ctx, R.string.settings_filter_backup_dir_invalid, Toast.LENGTH_SHORT).show()
                }
                BackupDirSelectOutcome.OkNoBackup -> {
                    Toast.makeText(ctx, R.string.settings_filter_backup_dir_success, Toast.LENGTH_SHORT).show()
                    updateBackupDirDisplay()
                }
                is BackupDirSelectOutcome.OkWithBackup -> {
                    Toast.makeText(ctx, R.string.settings_filter_backup_dir_success, Toast.LENGTH_SHORT).show()
                    updateBackupDirDisplay()
                    if (outcome.newCount > 0) {
                        AlertDialog.Builder(requireContext())
                            .setTitle(R.string.filter_backup_found_title)
                            .setMessage(getString(R.string.filter_backup_found_message, outcome.newCount))
                            .setPositiveButton(R.string.ok) { _, _ ->
                                mergeBackupJson(outcome.json)
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    }
                }
            }
        }
    }

    private sealed class BackupDirSelectOutcome {
        object Invalid : BackupDirSelectOutcome()
        object OkNoBackup : BackupDirSelectOutcome()
        data class OkWithBackup(val json: String, val newCount: Int) : BackupDirSelectOutcome()
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

    private fun showResultDialog(title: String, message: String) {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /**
     * 將各 ActionType 的規則數量格式化為多行明細
     *
     * 例：
     *   通知過濾黑名單：3 條
     *   日曆匯出白名單：2 條
     *   持續提醒：1 條
     */
    private fun formatRuleBreakdown(counts: Map<ActionType, Int>): String {
        val categoryNames = mapOf(
            ActionType.SKIP_RECORD to getString(R.string.filter_rule_category_skip_record),
            ActionType.CALENDAR_EXPORT to getString(R.string.filter_rule_category_calendar_export),
            ActionType.AUTO_DISMISS to getString(R.string.filter_rule_category_auto_dismiss),
            ActionType.PERSISTENT_ALERT to getString(R.string.filter_rule_category_persistent_alert),
            ActionType.CLIPBOARD_COPY to getString(R.string.filter_rule_category_clipboard_copy)
        )
        return counts.entries
            .filter { it.value > 0 }
            .joinToString("\n") { (type, count) ->
                getString(R.string.filter_rule_count_line, categoryNames[type] ?: type.name, count)
            }
    }

    companion object {
        private const val TAG = "SettingsFragment"
        private var hasPromptedThisSession = false
    }
}
