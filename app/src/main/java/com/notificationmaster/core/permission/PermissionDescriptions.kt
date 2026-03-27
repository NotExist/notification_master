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
            permission = Manifest.permission.WRITE_EXTERNAL_STORAGE,
            displayName = "寫入外部儲存",
            type = "危險權限",
            relatedFeature = "Debug dump",
            rationale = "用於將 Debug 資料寫入外部儲存，讓其他工具可以存取分析。",
            deniedImpact = "Debug dump 將使用 App 內部儲存（其他工具無法直接存取）。",
            isRequired = false,
            maxApi = 28
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
        ),
        PermissionInfo(
            permission = Manifest.permission.VIBRATE,
            displayName = "振動",
            type = "普通權限",
            relatedFeature = "持續提醒功能（振動）",
            rationale = "持續提醒觸發時產生循環振動，直到使用者回應。",
            deniedImpact = "持續提醒無法使用振動，僅能以鈴聲提示。",
            isRequired = false
        ),
        PermissionInfo(
            permission = "android.permission.POST_NOTIFICATIONS",
            displayName = "發送通知",
            type = "危險權限",
            relatedFeature = "持續提醒功能（停止按鈕通知）",
            rationale = "持續提醒觸發時需要發送 heads-up 通知，提供「停止提醒」按鈕讓使用者回應。",
            deniedImpact = "持續提醒功能無法運作（無法顯示停止按鈕，提醒將無法被停止）。",
            isRequired = false,
            minApi = 33
        ),
        PermissionInfo(
            permission = "android.permission.USE_FULL_SCREEN_INTENT",
            displayName = "全螢幕通知",
            type = "特殊權限",
            relatedFeature = "持續提醒功能（鎖屏全螢幕顯示）",
            rationale = "持續提醒觸發時以全螢幕 Activity 顯示，鎖屏時喚醒螢幕，確保使用者不會錯過提醒。API 34 以前自動授予，API 34+ 需使用者手動授權。",
            deniedImpact = "鎖屏時無法顯示全螢幕提醒，退回一般 heads-up 通知（約 5 秒後自動縮回）。",
            isRequired = false
        ),
        // === 未實作（預留） ===
        PermissionInfo(
            permission = "android.permission.BIND_ACCESSIBILITY_SERVICE",
            displayName = "無障礙服務",
            type = "系統權限",
            relatedFeature = "Toast 訊息擷取（尚未實作）",
            rationale = "透過 AccessibilityService 監聽 TYPE_NOTIFICATION_STATE_CHANGED 事件，" +
                "擷取 Toast 等不經 NotificationManager 的短暫訊息。" +
                "Toast 不屬於 Notification，NotificationListenerService 無法攔截。",
            deniedImpact = "無法擷取 Toast 訊息，僅能記錄標準通知。",
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
            // 全螢幕通知 — API 34+ 需透過 NotificationManager 檢查
            info.permission == "android.permission.USE_FULL_SCREEN_INTENT" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                        .canUseFullScreenIntent()
                } else true  // API 34 以下自動授予
            }
            // 普通權限 — 安裝時自動授予
            info.type == "普通權限" -> true
            // 危險權限 — 檢查運行時授權狀態
            else -> ContextCompat.checkSelfPermission(context, info.permission) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }
}
