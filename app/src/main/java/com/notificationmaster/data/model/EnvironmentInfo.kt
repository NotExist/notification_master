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
 * API 層級功能群組
 * 以 API 版本為大分類，各功能項為小項目
 */
data class ApiFeatureGroup(
    /** API 層級 */
    val apiLevel: Int,
    /** Android 版本名稱 (如 "6.0 Marshmallow") */
    val androidVersion: String,
    /** 當前裝置是否支援此 API 層級 */
    val supported: Boolean,
    /** 此 API 層級下的功能項目 */
    val features: List<FeatureItem>
)

/**
 * 個別功能項目
 */
data class FeatureItem(
    /** 功能名稱 */
    val name: String,
    /** 實質影響功能描述 */
    val description: String
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
    /** Ranking 詳細資訊 (API 24+) */
    val rankingDetails: Boolean,
    /** Bubbles (API 29+) */
    val bubbles: Boolean,
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
                notificationKey = true,  // minSdk 21 >= 21
                notificationChannel = sdk >= 26,
                directReply = sdk >= 24,
                messagingStyle = sdk >= 24,
                iconClass = sdk >= 23,
                rankingDetails = sdk >= 24,
                bubbles = sdk >= 29,
                postNotificationsPermission = sdk >= 33,
                semanticAction = sdk >= 28,
                authenticationRequired = sdk >= 31
            )
        }

        private fun androidVersionName(apiLevel: Int): String = when (apiLevel) {
            23 -> "6.0 Marshmallow"
            24 -> "7.0 Nougat"
            25 -> "7.1 Nougat"
            26 -> "8.0 Oreo"
            28 -> "9 Pie"
            29 -> "10"
            31 -> "12"
            33 -> "13"
            34 -> "14"
            else -> "API $apiLevel"
        }
    }

    /**
     * 以 API 層級分組的功能清單（用於 UI 顯示）
     */
    fun toGroupedList(): List<ApiFeatureGroup> {
        val sdk = Build.VERSION.SDK_INT
        return listOf(
            ApiFeatureGroup(
                apiLevel = 23,
                androidVersion = androidVersionName(23),
                supported = sdk >= 23,
                features = listOf(
                    FeatureItem("小圖示提取", "使用 Icon 類別提取高品質通知小圖示")
                )
            ),
            ApiFeatureGroup(
                apiLevel = 24,
                androidVersion = androidVersionName(24),
                supported = sdk >= 24,
                features = listOf(
                    FeatureItem("直接回覆", "記錄通知的直接回覆動作資訊"),
                    FeatureItem("MessagingStyle", "提取訊息通知的對話內容與媒體附件"),
                    FeatureItem("Ranking 追蹤", "記錄通知排序位置、重要性和環境模式變更"),
                    FeatureItem("服務重新綁定", "支援主動重新連接通知監聽服務")
                )
            ),
            ApiFeatureGroup(
                apiLevel = 25,
                androidVersion = androidVersionName(25),
                supported = sdk >= 25,
                features = listOf(
                    FeatureItem("App Shortcut", "長按 App 圖示顯示快捷選單（如「最近發聲」）")
                )
            ),
            ApiFeatureGroup(
                apiLevel = 26,
                androidVersion = androidVersionName(26),
                supported = sdk >= 26,
                features = listOf(
                    FeatureItem("通知頻道", "記錄頻道 ID、名稱、重要性等分類資訊"),
                    FeatureItem("頻道群組", "NotificationChannelGroup 支援頻道分組管理"),
                    FeatureItem("通知屬性擴充", "Shortcut ID、角標類型、自動過期時間"),
                    FeatureItem("Ranking 擴充", "overrideGroupKey 等欄位"),
                    FeatureItem("發聲推斷（importance）", "Channel importance ≥ DEFAULT 推斷通知可能產生可感知提示（聲音或振動）")
                )
            ),
            ApiFeatureGroup(
                apiLevel = 28,
                androidVersion = androidVersionName(28),
                supported = sdk >= 28,
                features = listOf(
                    FeatureItem("語意動作", "識別動作按鈕類型（回覆、刪除、封存等）"),
                    FeatureItem("Person 資訊", "提取通知中的人物名稱和頭像圖片"),
                    FeatureItem("Ranking Channel 物件", "ranking.channel 可存取完整頻道物件（API 26-27 改由 NotificationManager 取得）"),
                    FeatureItem("Ranking 擴充", "canShowBadge、isSuspended 等狀態欄位"),
                    FeatureItem("App 暫停狀態", "記錄 App 是否被系統暫停")
                )
            ),
            ApiFeatureGroup(
                apiLevel = 29,
                androidVersion = androidVersionName(29),
                supported = sdk >= 29,
                features = listOf(
                    FeatureItem("發聲偵測", "透過 lastAudiblyAlertedMillis 精確判斷通知是否產生可感知提示（聲音或振動）"),
                    FeatureItem("氣泡通知", "記錄 Bubble metadata 和頻道氣泡支援"),
                    FeatureItem("Ranking 擴充", "canBubble、smartReplies、smartActions 等欄位"),
                    FeatureItem("智慧建議", "記錄系統生成的建議回覆和建議動作"),
                    FeatureItem("網路類型偵測", "裝置狀態中記錄 Wi-Fi / 行動數據等連線類型")
                )
            ),
            ApiFeatureGroup(
                apiLevel = 31,
                androidVersion = androidVersionName(31),
                supported = sdk >= 31,
                features = listOf(
                    FeatureItem("動作需認證", "記錄需要解鎖認證的動作按鈕"),
                    FeatureItem("對話通知", "識別對話類型通知並記錄快捷方式資訊（isConversation、conversationShortcutInfo）"),
                    FeatureItem("VibratorManager", "取代已棄用的 VIBRATOR_SERVICE，提供 defaultVibrator 存取")
                )
            ),
            ApiFeatureGroup(
                apiLevel = 33,
                androidVersion = androidVersionName(33),
                supported = sdk >= 33,
                features = listOf(
                    FeatureItem("POST_NOTIFICATIONS", "App 自身通知需要 runtime 權限授權")
                )
            ),
            ApiFeatureGroup(
                apiLevel = 34,
                androidVersion = androidVersionName(34),
                supported = sdk >= 34,
                features = listOf(
                    FeatureItem("PendingIntent 類型", "識別通知意圖類型（Activity / Service / Broadcast）")
                )
            )
        )
    }
}
