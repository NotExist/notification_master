package com.notificationmaster.ui.main

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.notificationmaster.BuildConfig
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.R
import com.notificationmaster.core.permission.PermissionDescriptions
import com.notificationmaster.core.permission.PermissionInfo
import com.notificationmaster.core.media.MediaExtractor
import com.notificationmaster.data.db.NotificationDatabase
import com.notificationmaster.data.model.EnvironmentInfo
import com.notificationmaster.databinding.FragmentHomeBinding
import com.notificationmaster.databinding.ItemPermissionInfoBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.text.format.Formatter
import java.text.NumberFormat
import java.util.Calendar

/**
 * 主畫面 Fragment
 * 顯示環境資訊、權限狀態、支援功能和統計
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    // 權限請求回來後 onResume() 會自動更新清單
    private val generalPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        displayEnvironmentInfo()
        displaySupportedFeatures()
    }

    override fun onResume() {
        super.onResume()
        displayPermissions()
        loadStatistics()
        updateStorageInfo()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * 在卡片內直接展示所有適用權限的授權狀態
     * 可點擊未授予的危險/系統權限以觸發授權流程
     */
    private fun displayPermissions() {
        val permissions = PermissionDescriptions.getApplicablePermissions()
        val ctx = requireContext()
        val container = binding.permissionsContainer
        container.removeAllViews()

        for (p in permissions) {
            val granted = PermissionDescriptions.checkGrantStatus(ctx, p)
            val actionable = !granted && p.type != "普通權限"

            val itemBinding = ItemPermissionInfoBinding.inflate(layoutInflater, container, false)

            itemBinding.textPermissionName.text = buildString {
                if (p.isRequired) append("[必要] ")
                append(p.displayName)
            }

            itemBinding.textPermissionStatus.text = when {
                granted -> getString(R.string.permission_status_granted)
                actionable -> getString(R.string.permission_status_tap_to_grant)
                else -> getString(R.string.permission_status_not_granted)
            }
            itemBinding.textPermissionStatus.setTextColor(
                ContextCompat.getColor(ctx,
                    if (granted) R.color.status_enabled else R.color.status_disabled)
            )

            itemBinding.textPermissionDesc.text = buildString {
                append(p.relatedFeature)
                if (!granted) {
                    append("\n拒絕影響：${p.deniedImpact}")
                }
            }

            if (actionable) {
                val tv = TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                itemBinding.root.setBackgroundResource(tv.resourceId)
                itemBinding.root.isClickable = true
                itemBinding.root.isFocusable = true
                itemBinding.root.setOnClickListener {
                    requestPermissionGrant(p)
                }
            }

            container.addView(itemBinding.root)
        }
    }

    @SuppressLint("InlinedApi")
    private fun requestPermissionGrant(info: PermissionInfo) {
        when (info.permission) {
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" -> {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            else -> {
                generalPermissionLauncher.launch(arrayOf(info.permission))
            }
        }
    }

    /**
     * 顯示環境資訊
     */
    private fun displayEnvironmentInfo() {
        val envInfo = EnvironmentInfo.create(
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE.toLong()
        )

        binding.textAndroidVersion.text = getString(R.string.format_android_version, envInfo.androidVersion, envInfo.apiLevel)
        binding.textDeviceModel.text = getString(R.string.format_device_model, envInfo.deviceManufacturer, envInfo.deviceModel)
        binding.textAppVersion.text = getString(R.string.format_app_version, envInfo.appVersion, envInfo.appVersionCode)
    }

    /**
     * 顯示支援功能清單（以 API 層級分組）
     */
    private fun displaySupportedFeatures() {
        val envInfo = EnvironmentInfo.create(
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE.toLong()
        )
        val groups = envInfo.supportedFeatures.toGroupedList()

        val container = binding.featuresContainer
        container.removeAllViews()
        val ctx = requireContext()
        val margin = resources.getDimensionPixelSize(R.dimen.feature_item_margin)

        for (group in groups) {
            // API 層級標題行：✓/✗ + "API 24 — Android 7.0 Nougat"
            val headerLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = margin * 3
                }
            }

            val allImplemented = group.features.all { it.implemented }
            val noneImplemented = group.features.none { it.implemented }
            val statusText = TextView(ctx).apply {
                text = when {
                    noneImplemented -> "⊘"
                    !allImplemented -> "△"
                    group.supported -> "✓"
                    else -> "✗"
                }
                textSize = 14f
                setTextColor(when {
                    group.supported && allImplemented -> ContextCompat.getColor(ctx, R.color.status_enabled)
                    !allImplemented && !noneImplemented -> ContextCompat.getColor(ctx, R.color.status_warning)
                    noneImplemented -> com.google.android.material.color.MaterialColors.getColor(
                        ctx, android.R.attr.textColorSecondary,
                        ContextCompat.getColor(ctx, R.color.text_secondary))
                    else -> ContextCompat.getColor(ctx, R.color.status_disabled)
                })
            }

            val titleText = TextView(ctx).apply {
                text = "API ${group.apiLevel} — Android ${group.androidVersion}"
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(
                    if (group.supported) {
                        com.google.android.material.color.MaterialColors.getColor(
                            ctx, android.R.attr.textColorPrimary,
                            ContextCompat.getColor(ctx, R.color.text_secondary))
                    } else {
                        ContextCompat.getColor(ctx, R.color.text_secondary)
                    }
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = margin }
            }

            headerLayout.addView(statusText)
            headerLayout.addView(titleText)
            container.addView(headerLayout)

            // 各功能項目：名稱 + 描述
            for (feature in group.features) {
                val prefix = if (!feature.implemented && !noneImplemented) "⊘ " else ""
                val nameText = TextView(ctx).apply {
                    text = "$prefix${feature.name}"
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(ctx,
                        if (!feature.implemented) R.color.text_tertiary
                        else if (group.supported) R.color.text_secondary
                        else R.color.text_tertiary))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = margin * 5
                        topMargin = margin
                    }
                }
                container.addView(nameText)

                val descText = TextView(ctx).apply {
                    text = feature.description
                    textSize = 11f
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_tertiary))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = margin * 5
                        topMargin = 2
                    }
                }
                container.addView(descText)
            }
        }
    }

    /**
     * 更新儲存空間資訊
     */
    private fun updateStorageInfo() {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val dbSize = withContext(Dispatchers.IO) {
                NotificationDatabase.getDatabaseFileSize(ctx)
            }
            val mediaSize = withContext(Dispatchers.IO) {
                MediaExtractor(ctx).getMediaDirSize()
            }

            val fmt = { bytes: Long -> Formatter.formatShortFileSize(ctx, bytes) }
            val totalSize = dbSize.first + dbSize.second + dbSize.third + mediaSize

            _binding?.textStorageInfo?.text = buildString {
                append("資料庫: ${fmt(dbSize.first)}")
                if (dbSize.second > 0) append(" (WAL: ${fmt(dbSize.second)})")
                append("\n媒體: ${fmt(mediaSize)}")
                append("\n總計: ${fmt(totalSize)}")
            }
        }
    }

    /**
     * 載入統計資料
     */
    private fun loadStatistics() {
        viewLifecycleOwner.lifecycleScope.launch {
            val database = NotificationMasterApp.getInstance().database
            val notificationDao = database.notificationDao()

            // 計算今日開始時間
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val startOfDay = calendar.timeInMillis

            val todayCount = withContext(Dispatchers.IO) {
                notificationDao.getTodayCount(startOfDay)
            }

            val totalCount = withContext(Dispatchers.IO) {
                notificationDao.getTotalCount()
            }

            // 更新 UI
            val binding = _binding ?: return@launch
            val numberFormat = NumberFormat.getNumberInstance()
            binding.textTodayCount.text = numberFormat.format(todayCount)
            binding.textTotalCount.text = numberFormat.format(totalCount)
        }
    }
}
