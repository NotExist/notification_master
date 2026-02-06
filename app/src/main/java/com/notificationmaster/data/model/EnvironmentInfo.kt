package com.notificationmaster.data.model

import android.os.Build

/**
 * 環境資訊模型
 * 記錄裝置和 App 環境
 */
data class EnvironmentInfo(
    /** Android 版本名稱 (如 "14") */
    val androidVersion: String,
    /** API Level */
    val apiLevel: Int,
    /** SDK Int */
    val sdkInt: Int,
    /** 裝置型號 */
    val deviceModel: String,
    /** 裝置製造商 */
    val deviceManufacturer: String,
    /** 裝置品牌 */
    val deviceBrand: String,
    /** 裝置代號 */
    val deviceProduct: String,
    /** App 版本名稱 */
    val appVersion: String,
    /** App 版本號 */
    val appVersionCode: Long,
    /** 支援的功能 */
    val supportedFeatures: SupportedFeatures
) {
    companion object {
        fun create(appVersion: String, appVersionCode: Long): EnvironmentInfo {
            return EnvironmentInfo(
                androidVersion = Build.VERSION.RELEASE ?: "Unknown",
                apiLevel = Build.VERSION.SDK_INT,
                sdkInt = Build.VERSION.SDK_INT,
                deviceModel = Build.MODEL ?: "Unknown",
                deviceManufacturer = Build.MANUFACTURER ?: "Unknown",
                deviceBrand = Build.BRAND ?: "Unknown",
                deviceProduct = Build.PRODUCT ?: "Unknown",
                appVersion = appVersion,
                appVersionCode = appVersionCode,
                supportedFeatures = SupportedFeatures.detect()
            )
        }
    }
}

/**
 * 功能項目資訊
 */
data class FeatureInfo(
    /** 功能名稱 */
    val name: String,
    /** 功能說明 */
    val description: String,
    /** 最低需求 API */
    val requiredApi: Int,
    /** 是否支援 */
    val supported: Boolean,
    /** 不支援時的說明 */
    val unsupportedReason: String
)

/**
 * 支援的功能
 */
data class SupportedFeatures(
    /** 通知 Key (API 21+) */
    val notificationKey: Boolean,
    /** 通知 Channel (API 26+) */
    val notificationChannel: Boolean,
    /** 直接回覆 (API 24+) */
    val directReply: Boolean,
    /** MessagingStyle (API 24+) */
    val messagingStyle: Boolean,
    /** Icon 類別 (API 23+) */
    val iconClass: Boolean,
    /** Bubbles (API 29+) */
    val bubbles: Boolean,
    /** 移除原因 (API 21+) */
    val removalReason: Boolean,
    /** POST_NOTIFICATIONS 權限 (API 33+) */
    val postNotificationsPermission: Boolean,
    /** 語意動作 (API 28+) */
    val semanticAction: Boolean,
    /** Authentication Required (API 31+) */
    val authenticationRequired: Boolean
) {
    companion object {
        fun detect(): SupportedFeatures {
            val sdk = Build.VERSION.SDK_INT
            return SupportedFeatures(
                notificationKey = sdk >= 21,
                notificationChannel = sdk >= 26,
                directReply = sdk >= 24,
                messagingStyle = sdk >= 24,
                iconClass = sdk >= 23,
                bubbles = sdk >= 29,
                removalReason = sdk >= 21,
                postNotificationsPermission = sdk >= 33,
                semanticAction = sdk >= 28,
                authenticationRequired = sdk >= 31
            )
        }

        private fun getUnsupportedReason(requiredApi: Int): String {
            val currentApi = Build.VERSION.SDK_INT
            val androidVersion = when (requiredApi) {
                21 -> "5.0 Lollipop"
                23 -> "6.0 Marshmallow"
                24 -> "7.0 Nougat"
                26 -> "8.0 Oreo"
                28 -> "9.0 Pie"
                29 -> "10"
                31 -> "12"
                33 -> "13"
                else -> requiredApi.toString()
            }
            return "需要 Android $androidVersion (API $requiredApi)，目前為 API $currentApi"
        }
    }

    /**
     * 取得功能清單 (用於 UI 顯示) - 簡化版
     */
    fun toDisplayList(): List<Pair<String, Boolean>> = listOf(
        "通知 Key (API 21+)" to notificationKey,
        "通知 Channel (API 26+)" to notificationChannel,
        "直接回覆 (API 24+)" to directReply,
        "MessagingStyle (API 24+)" to messagingStyle,
        "Icon 類別 (API 23+)" to iconClass,
        "Bubbles (API 29+)" to bubbles,
        "移除原因 (API 21+)" to removalReason,
        "語意動作 (API 28+)" to semanticAction
    )

    /**
     * 取得詳細功能清單 (含不支援原因)
     */
    fun toDetailedList(): List<FeatureInfo> = listOf(
        FeatureInfo(
            name = "通知 Key",
            description = "使用系統提供的唯一識別碼追蹤通知",
            requiredApi = 21,
            supported = notificationKey,
            unsupportedReason = if (!notificationKey) Companion.getUnsupportedReason(21) else ""
        ),
        FeatureInfo(
            name = "通知 Channel",
            description = "按 Channel 分類歸檔通知",
            requiredApi = 26,
            supported = notificationChannel,
            unsupportedReason = if (!notificationChannel) Companion.getUnsupportedReason(26) else ""
        ),
        FeatureInfo(
            name = "直接回覆",
            description = "記錄通知的直接回覆動作資訊",
            requiredApi = 24,
            supported = directReply,
            unsupportedReason = if (!directReply) Companion.getUnsupportedReason(24) else ""
        ),
        FeatureInfo(
            name = "MessagingStyle",
            description = "解析訊息類通知的對話內容",
            requiredApi = 24,
            supported = messagingStyle,
            unsupportedReason = if (!messagingStyle) Companion.getUnsupportedReason(24) else ""
        ),
        FeatureInfo(
            name = "Icon 類別",
            description = "提取高品質通知圖示",
            requiredApi = 23,
            supported = iconClass,
            unsupportedReason = if (!iconClass) Companion.getUnsupportedReason(23) else ""
        ),
        FeatureInfo(
            name = "Bubbles",
            description = "記錄氣泡通知資訊",
            requiredApi = 29,
            supported = bubbles,
            unsupportedReason = if (!bubbles) Companion.getUnsupportedReason(29) else ""
        ),
        FeatureInfo(
            name = "移除原因",
            description = "區分使用者操作與 App 取消",
            requiredApi = 21,
            supported = removalReason,
            unsupportedReason = if (!removalReason) Companion.getUnsupportedReason(21) else ""
        ),
        FeatureInfo(
            name = "語意動作",
            description = "識別動作按鈕的語意類型（回覆、刪除等）",
            requiredApi = 28,
            supported = semanticAction,
            unsupportedReason = if (!semanticAction) Companion.getUnsupportedReason(28) else ""
        )
    )
}
