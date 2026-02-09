package com.notificationmaster.ui.main

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.notificationmaster.BuildConfig
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.R
import com.notificationmaster.data.model.EnvironmentInfo
import com.notificationmaster.databinding.FragmentHomeBinding
import com.notificationmaster.service.NotificationCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Calendar

/**
 * 主畫面 Fragment
 * 顯示環境資訊、權限狀態、支援功能和統計
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

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

        setupPermissionButton()
        displayEnvironmentInfo()
        displaySupportedFeatures()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        loadStatistics()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * 設定權限按鈕
     */
    private fun setupPermissionButton() {
        binding.btnEnablePermission.setOnClickListener {
            showPermissionDialog()
        }
    }

    /**
     * 顯示權限引導對話框
     */
    private fun showPermissionDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.permission_required_title)
            .setMessage(R.string.permission_required_message)
            .setPositiveButton(R.string.go_to_settings) { _, _ ->
                openNotificationListenerSettings()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 開啟通知監聽設定頁面
     */
    private fun openNotificationListenerSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        startActivity(intent)
    }

    /**
     * 更新權限狀態顯示
     */
    private fun updatePermissionStatus() {
        val isEnabled = isNotificationListenerEnabled()

        // 更新狀態指示燈
        val indicatorColor = if (isEnabled) {
            ContextCompat.getColor(requireContext(), R.color.status_enabled)
        } else {
            ContextCompat.getColor(requireContext(), R.color.status_disabled)
        }
        (binding.statusIndicator.background as? GradientDrawable)?.setColor(indicatorColor)

        // 更新狀態文字
        binding.textPermissionStatus.text = if (isEnabled) {
            getString(R.string.notification_listener_enabled)
        } else {
            getString(R.string.notification_listener_disabled)
        }

        // 更新按鈕可見性
        binding.btnEnablePermission.visibility = if (isEnabled) View.GONE else View.VISIBLE
    }

    /**
     * 檢查 NotificationListenerService 是否已啟用
     */
    private fun isNotificationListenerEnabled(): Boolean {
        val componentName = ComponentName(
            requireContext(),
            NotificationCaptureService::class.java
        )
        val flat = Settings.Secure.getString(
            requireContext().contentResolver,
            "enabled_notification_listeners"
        )
        return flat?.contains(componentName.flattenToString()) == true
    }

    /**
     * 顯示環境資訊
     */
    private fun displayEnvironmentInfo() {
        val envInfo = EnvironmentInfo.create(
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE.toLong()
        )

        binding.textAndroidVersion.text = "Android ${envInfo.androidVersion} (API ${envInfo.apiLevel})"
        binding.textDeviceModel.text = "${envInfo.deviceManufacturer} ${envInfo.deviceModel}"
        binding.textAppVersion.text = "App 版本: ${envInfo.appVersion} (${envInfo.appVersionCode})"
    }

    /**
     * 顯示支援功能清單
     */
    private fun displaySupportedFeatures() {
        val envInfo = EnvironmentInfo.create(
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE.toLong()
        )
        val features = envInfo.supportedFeatures.toDetailedList()

        binding.featuresContainer.removeAllViews()

        for (feature in features) {
            val featureView = createFeatureView(feature)
            binding.featuresContainer.addView(featureView)
        }
    }

    /**
     * 建立功能項目 View
     */
    private fun createFeatureView(feature: com.notificationmaster.data.model.FeatureInfo): View {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = resources.getDimensionPixelSize(R.dimen.feature_item_margin) * 2
            }
        }

        // 第一行：狀態圖示 + 功能名稱
        val headerLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val statusText = TextView(requireContext()).apply {
            text = if (feature.supported) "✓" else "✗"
            setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (feature.supported) R.color.status_enabled else R.color.status_disabled
                )
            )
            textSize = 14f
        }

        val nameText = TextView(requireContext()).apply {
            text = "${feature.name} (API ${feature.requiredApi}+)"
            textSize = 14f
            setTextColor(
                if (feature.supported) {
                    com.google.android.material.color.MaterialColors.getColor(
                        requireContext(),
                        android.R.attr.textColorPrimary,
                        ContextCompat.getColor(requireContext(), R.color.text_secondary)
                    )
                } else {
                    ContextCompat.getColor(requireContext(), R.color.text_secondary)
                }
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.feature_item_margin)
            }
        }

        headerLayout.addView(statusText)
        headerLayout.addView(nameText)
        layout.addView(headerLayout)

        // 第二行：功能說明
        val descText = TextView(requireContext()).apply {
            text = feature.description
            textSize = 12f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.feature_item_margin) * 5
            }
        }
        layout.addView(descText)

        // 第三行：不支援原因（僅在不支援時顯示）
        if (!feature.supported) {
            val reasonText = TextView(requireContext()).apply {
                text = feature.unsupportedReason
                textSize = 11f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.status_disabled))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = resources.getDimensionPixelSize(R.dimen.feature_item_margin) * 5
                    topMargin = 2
                }
            }
            layout.addView(reasonText)
        }

        return layout
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
            val numberFormat = NumberFormat.getNumberInstance()
            binding.textTodayCount.text = numberFormat.format(todayCount)
            binding.textTotalCount.text = numberFormat.format(totalCount)
        }
    }
}
