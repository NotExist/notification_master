package com.notificationmaster.core.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 權限說明
 * 每個權限附帶功能說明、為何需要、拒絕後的影響
 */
data class PermissionInfo(
    /** 權限名稱（系統常數） */
    val permission: String,
    /** 顯示名稱 */
    val displayName: String,
    /** 權限類型 */
    val type: String,
    /** 關聯功能 */
    val relatedFeature: String,
    /** 為何需要此權限 */
    val rationale: String,
    /** 拒絕後的影響 */
    val deniedImpact: String,
    /** 是否為必要權限 */
    val isRequired: Boolean,
    /** 需要此權限的最低 API (0 表示所有版本) */
    val minApi: Int = 0,
    /** 此權限的最高 API (-1 表示無上限) */
    val maxApi: Int = -1
)

object PermissionDescriptions {

    fun getAllPermissions(): List<PermissionInfo> = listOf(
        PermissionInfo(
            permission = "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
            displayName = "通知存取權",
            type = "系統權限",
            relatedFeature = "核心通知監聽功能",
            rationale = "需要此權限才能攔截和記錄所有系統通知。這是本 App 的核心功能。",
            deniedImpact = "App 將無法擷取任何通知，所有功能都無法使用。",
            isRequired = true
        ),
        PermissionInfo(
            permission = Manifest.permission.POST_NOTIFICATIONS,
            displayName = "發送通知",
            type = "危險權限",
            relatedFeature = "App 自身狀態通知",
            rationale = "用於顯示服務運行狀態的前景通知（Android 13+ 要求）。",
            deniedImpact = "無法顯示服務運行狀態通知，但核心功能不受影響。",
            isRequired = false,
            minApi = 33
        ),
        PermissionInfo(
            permission = Manifest.permission.WRITE_EXTERNAL_STORAGE,
            displayName = "寫入外部儲存",
            type = "危險權限",
            relatedFeature = "Debug dump 和匯出",
            rationale = "用於將 Debug 資料寫入外部儲存，讓其他工具可以存取分析。",
            deniedImpact = "Debug dump 將使用 App 內部儲存（其他工具無法直接存取）。",
            isRequired = false,
            maxApi = 28
        ),
        PermissionInfo(
            permission = Manifest.permission.READ_EXTERNAL_STORAGE,
            displayName = "讀取外部儲存",
            type = "危險權限",
            relatedFeature = "封存匯入",
            rationale = "用於讀取外部儲存中的封存檔案。",
            deniedImpact = "無法從外部儲存匯入封存檔案。",
            isRequired = false,
            maxApi = 32
        ),
        PermissionInfo(
            permission = "android.permission.FOREGROUND_SERVICE",
            displayName = "前景服務",
            type = "普通權限",
            relatedFeature = "保持服務持續運行",
            rationale = "確保通知監聽服務不會被系統殺死，持續記錄通知。",
            deniedImpact = "服務可能被系統殺死，導致部分通知未被記錄。",
            isRequired = true
        ),
        PermissionInfo(
            permission = "android.permission.QUERY_ALL_PACKAGES",
            displayName = "查詢所有 App",
            type = "普通權限",
            relatedFeature = "查詢 App 名稱和圖示",
            rationale = "用於取得發送通知的 App 的名稱和圖示。",
            deniedImpact = "部分 App 名稱可能顯示為包名，缺少圖示。",
            isRequired = false
        ),
        PermissionInfo(
            permission = Manifest.permission.ACCESS_NETWORK_STATE,
            displayName = "網路狀態",
            type = "普通權限",
            relatedFeature = "裝置狀態記錄（網路）",
            rationale = "記錄通知到達時的網路連線狀態（Wi-Fi/行動數據）。",
            deniedImpact = "裝置狀態記錄中的網路資訊將顯示為「未知」。",
            isRequired = false
        ),
        PermissionInfo(
            permission = Manifest.permission.READ_CALENDAR,
            displayName = "讀取日曆",
            type = "危險權限",
            relatedFeature = "日曆匯出功能（預設關閉）",
            rationale = "讀取可用的日曆清單，讓使用者選擇匯出目標日曆。",
            deniedImpact = "無法使用日曆匯出功能。",
            isRequired = false
        ),
        PermissionInfo(
            permission = Manifest.permission.WRITE_CALENDAR,
            displayName = "寫入日曆",
            type = "危險權限",
            relatedFeature = "日曆匯出功能（預設關閉）",
            rationale = "將通知記錄寫入系統日曆作為事件。",
            deniedImpact = "無法使用日曆匯出功能。",
            isRequired = false
        )
    )

    /**
     * 取得目前 API 版本適用的權限清單
     */
    fun getApplicablePermissions(): List<PermissionInfo> {
        val apiLevel = Build.VERSION.SDK_INT
        return getAllPermissions().filter { permission ->
            apiLevel >= permission.minApi &&
            (permission.maxApi == -1 || apiLevel <= permission.maxApi)
        }
    }

    /**
     * 檢查權限是否已授予
     */
    fun checkGrantStatus(context: Context, info: PermissionInfo): Boolean {
        return when {
            // 通知監聽服務 — 透過系統 API 檢查
            info.permission == "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" -> {
                NotificationManagerCompat.getEnabledListenerPackages(context)
                    .contains(context.packageName)
            }
            // 普通權限 — 安裝時自動授予
            info.type == "普通權限" -> true
            // 危險權限 — 檢查運行時授權狀態
            else -> ContextCompat.checkSelfPermission(context, info.permission) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }
}
