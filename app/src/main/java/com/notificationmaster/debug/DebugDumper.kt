package com.notificationmaster.debug

import android.app.Notification
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import android.util.Log
import com.notificationmaster.BuildConfig
import com.notificationmaster.core.RawSerializer
import com.notificationmaster.core.debug.DebugPaths
import com.notificationmaster.core.debug.RawSizeAnalyzer
import com.notificationmaster.core.prefs.AppPreferences
import com.notificationmaster.core.compat.ApiVersionHelper
import com.notificationmaster.core.permission.PermissionDescriptions
import com.notificationmaster.data.model.EnvironmentInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug 模式 Raw Data Dumper（Plan 2 Phase 10：反射引擎抽出共用 [RawSerializer]）
 *
 * 將所有收到的原始資料完整傾印到外部儲存供分析。所有事件類型共用同一個通用傾印邏輯，
 * 不做任何省略。底層反射由 [RawSerializer] 提供，自動涵蓋所有 public field 與 no-arg
 * getter，新增 API 欄位無需修改程式碼。
 */
class DebugDumper(private val context: Context) {

    companion object {
        private const val TAG = "DebugDumper"
    }

    /**
     * Phase 31a fixup：用 SharedPreferences 跨 instance 共享（settings 端與 service 端各自
     * 持有 instance，原本 var = false 不同步導致 settings 啟用後 service 不 dump events）。
     */
    val isEnabled: Boolean
        get() = AppPreferences.isDebugDumperEnabled(context)

    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    /**
     * Plan 1-zippy-thunder W15：所有 dump 寫入移 IO scope。
     *
     * 117 log Watchdog 鐵證 NLS callback (main thread) 同步呼叫 dumpEvent → RawSerializer
     * 反射 + JSONObject.toString + File.writeText + RawSizeAnalyzer 兩次寫檔 卡 2-9 秒 → ANR FC。
     * 改 SupervisorJob + IO scope 保證每次 dump fire-and-forget 不阻塞 caller thread。
     *
     * 物件 thread-safety：sbn / ranking 由 NLS 系統提供，callback 後通常仍可讀（system 不會
     * 主動 mutate）。實務風險可接受，換來避免 ANR 的明確收益。
     */
    private val dumpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Event dump 輸出目錄（Documents/NotificationMaster/debug/event_dump/）
     *
     * Plan 1-zippy-thunder W4：原本平鋪在 `NotificationMaster/debug/` root，現由
     * [DebugPaths] 統一改派生到 `event_dump/` 子資料夾，與 channel ranking dump
     * 合併互參、與 profile_log 區隔。
     */
    val dumpDir: File by lazy { DebugPaths.resolve(context, DebugPaths.Sink.EVENT_DUMP) }

    fun enable() {
        AppPreferences.setDebugDumperEnabled(context, true)
        Log.i(TAG, "Debug mode enabled. Output dir: ${dumpDir.absolutePath}")
        dumpSystemInfo()
    }

    /**
     * 傾印系統環境資訊（啟用 debug 時立即輸出一次）
     * 包含裝置型號、Android 版本、API 支援狀況、權限授予狀態
     */
    private fun dumpSystemInfo() {
        // W14：env 類型獨立 toggle
        if (!AppPreferences.isDumpTypeEnabled(context, AppPreferences.DumpType.ENV)) return
        // W15：序列化 + 寫檔 fire-and-forget 到 IO（caller 通常是 settings UI thread，避免阻塞）
        dumpScope.launch { try {
            val timestamp = dateFormat.format(Date())
            val filename = "system_${timestamp}_ENV.json"

            val envInfo = EnvironmentInfo.create(
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE.toLong()
            )

            val json = JSONObject().apply {
                put("dumpTime", System.currentTimeMillis())
                put("dumpTimeFormatted", timestamp)
                put("eventType", "ENV")

                // 裝置與 App 資訊
                put("device", JSONObject().apply {
                    put("androidVersion", envInfo.androidVersion)
                    put("apiLevel", envInfo.apiLevel)
                    put("sdkInt", envInfo.sdkInt)
                    put("model", envInfo.deviceModel)
                    put("manufacturer", envInfo.deviceManufacturer)
                    put("brand", envInfo.deviceBrand)
                    put("product", envInfo.deviceProduct)
                })
                put("app", JSONObject().apply {
                    put("versionName", envInfo.appVersion)
                    put("versionCode", envInfo.appVersionCode)
                    put("debug", BuildConfig.DEBUG)
                    put("packageName", context.packageName)
                })

                // API 功能支援
                val features = envInfo.supportedFeatures
                put("supportedFeatures", JSONObject().apply {
                    put("notificationKey", features.notificationKey)
                    put("notificationChannel", features.notificationChannel)
                    put("directReply", features.directReply)
                    put("messagingStyle", features.messagingStyle)
                    put("iconClass", features.iconClass)
                    put("rankingDetails", features.rankingDetails)
                    put("bubbles", features.bubbles)
                    put("semanticAction", features.semanticAction)
                    put("authenticationRequired", features.authenticationRequired)
                    put("postNotificationsPermission", features.postNotificationsPermission)
                })

                // 權限狀態
                val permissions = PermissionDescriptions.getApplicablePermissions()
                put("permissions", JSONArray().apply {
                    for (perm in permissions) {
                        put(JSONObject().apply {
                            put("permission", perm.permission)
                            put("displayName", perm.displayName)
                            put("type", perm.type)
                            put("granted", PermissionDescriptions.checkGrantStatus(context, perm))
                            put("required", perm.isRequired)
                        })
                    }
                })
            }

            File(dumpDir, filename).writeText(json.toString(2))
            Log.d(TAG, "Dumped system info")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dump system info", e)
        } }
    }

    fun disable() {
        AppPreferences.setDebugDumperEnabled(context, false)
        Log.i(TAG, "Debug mode disabled")
    }

    // === 公開傾印方法 ===

    /**
     * 傾印所有活躍通知（onListenerConnected 時呼叫）
     */
    fun dumpActiveNotifications(
        notifications: Array<StatusBarNotification>?,
        rankingMap: RankingMap?
    ) {
        if (!isEnabled) return
        // W14：initial 類型獨立 toggle
        if (!AppPreferences.isDumpTypeEnabled(context, AppPreferences.DumpType.INITIAL)) return
        // W15：fan-out 也包 launch — 避免 INITIAL 場景 N 個 sbn iterate 在 main thread 累積
        dumpScope.launch {
            notifications?.forEach { sbn -> dumpEvent(sbn, "INITIAL", rankingMap) }
        }
    }

    /**
     * 傾印通知事件（INITIAL / POSTED / REMOVED 等）
     * 所有事件類型共用完整反射傾印，REMOVED 額外附帶 removalReason。
     */
    fun dumpEvent(
        sbn: StatusBarNotification,
        eventType: String,
        rankingMap: RankingMap?,
        removalReason: Int? = null
    ) {
        if (!isEnabled) return
        // W14：event 類型獨立 toggle
        if (!AppPreferences.isDumpTypeEnabled(context, AppPreferences.DumpType.EVENT)) return
        // W15：117 log 證實 RawSerializer 反射 + writeText + RawSizeAnalyzer 在 main thread
        // 卡 2-9 秒造成 ANR。fire-and-forget 到 IO scope，caller (NLS callback) 立即 return。
        dumpScope.launch { try {
            val timestamp = dateFormat.format(Date())
            val safePackageName = sbn.packageName.replace(".", "_")
            val filename = "${safePackageName}_${timestamp}_${eventType}.json"

            val json = JSONObject().apply {
                put("dumpTime", System.currentTimeMillis())
                put("dumpTimeFormatted", timestamp)
                put("eventType", eventType)

                if (removalReason != null) {
                    put("removalReason", removalReason)
                    put("removalReasonCategory", ApiVersionHelper.categorizeRemovalReason(removalReason))
                    put("removalReasonDescription", ApiVersionHelper.getRemovalReasonDescription(removalReason))
                }

                put("sbn", RawSerializer.serialize(sbn))

                if (rankingMap != null) {
                    val key = ApiVersionHelper.getNotificationKey(sbn)
                    val ranking = Ranking()
                    if (rankingMap.getRanking(key, ranking)) {
                        put("ranking", RawSerializer.serialize(ranking))
                    }
                }

                put("flagsDecoded", decodeFlagsToJson(sbn.notification.flags))
                put("environment", buildEnvironmentJson())
            }

            val jsonText = json.toString(2)
            File(dumpDir, filename).writeText(jsonText)
            // Phase 31a：sibling size breakdown 摘要（驗證 130KB raw 內容分佈）
            val breakdownName = "${safePackageName}_${timestamp}_${eventType}_breakdown.txt"
            File(dumpDir, breakdownName).writeText(RawSizeAnalyzer.analyze(jsonText))
            Log.d(TAG, "Dumped $eventType for ${sbn.packageName}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dump $eventType", e)
        } }
    }

    /**
     * 傾印 Ranking 更新（無 sbn，獨立格式）
     */
    fun dumpRankingUpdate(rankingMap: RankingMap) {
        if (!isEnabled) return
        // W14：ranking 類型獨立 toggle
        if (!AppPreferences.isDumpTypeEnabled(context, AppPreferences.DumpType.RANKING)) return
        // W15：同 dumpEvent，移 IO 避免阻塞 NLS callback
        dumpScope.launch { try {
            val timestamp = dateFormat.format(Date())
            val filename = "system_${timestamp}_RANKING.json"

            val json = JSONObject().apply {
                put("dumpTime", System.currentTimeMillis())
                put("dumpTimeFormatted", timestamp)
                put("eventType", "RANKING")

                val rankingsArray = JSONArray()
                val keys = rankingMap.orderedKeys
                for (key in keys) {
                    val ranking = Ranking()
                    if (rankingMap.getRanking(key, ranking)) {
                        rankingsArray.put(RawSerializer.serialize(ranking))
                    }
                }
                put("rankings", rankingsArray)
                put("totalCount", keys.size)

                put("environment", buildEnvironmentJson())
            }

            val jsonText = json.toString(2)
            File(dumpDir, filename).writeText(jsonText)
            // Phase 31a：sibling breakdown 摘要（即便 ranking 通常較小也提供 breakdown 供對比參考）
            val breakdownName = "system_${timestamp}_RANKING_breakdown.txt"
            File(dumpDir, breakdownName).writeText(RawSizeAnalyzer.analyze(jsonText))
            Log.d(TAG, "Dumped RANKING (${rankingMap.orderedKeys.size} entries)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dump ranking", e)
        } }
    }

    // === 輔助 ===

    @Suppress("DEPRECATION")
    private fun decodeFlagsToJson(flags: Int): JSONObject {
        return JSONObject().apply {
            put("FLAG_SHOW_LIGHTS", (flags and Notification.FLAG_SHOW_LIGHTS) != 0)
            put("FLAG_ONGOING_EVENT", (flags and Notification.FLAG_ONGOING_EVENT) != 0)
            put("FLAG_INSISTENT", (flags and Notification.FLAG_INSISTENT) != 0)
            put("FLAG_ONLY_ALERT_ONCE", (flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0)
            put("FLAG_AUTO_CANCEL", (flags and Notification.FLAG_AUTO_CANCEL) != 0)
            put("FLAG_NO_CLEAR", (flags and Notification.FLAG_NO_CLEAR) != 0)
            put("FLAG_FOREGROUND_SERVICE", (flags and Notification.FLAG_FOREGROUND_SERVICE) != 0)
            put("FLAG_HIGH_PRIORITY", (flags and Notification.FLAG_HIGH_PRIORITY) != 0)
            put("FLAG_LOCAL_ONLY", (flags and Notification.FLAG_LOCAL_ONLY) != 0)
            put("FLAG_GROUP_SUMMARY", (flags and Notification.FLAG_GROUP_SUMMARY) != 0)
        }
    }

    private fun buildEnvironmentJson(): JSONObject = JSONObject().apply {
        put("apiLevel", Build.VERSION.SDK_INT)
        put("androidVersion", Build.VERSION.RELEASE)
        put("deviceModel", Build.MODEL)
    }

    // === 檔案管理 ===

    fun getDumpFiles(): List<File> {
        return dumpDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun getDumpFileCount(): Int = getDumpFiles().size

    fun getDumpTotalSize(): Long = getDumpFiles().sumOf { it.length() }

    fun clearDumpFiles(): Int {
        var count = 0
        getDumpFiles().forEach { file ->
            if (file.delete()) count++
        }
        Log.i(TAG, "Cleared $count dump files")
        return count
    }

    fun clearDumpFilesBefore(beforeTime: Long): Int {
        var count = 0
        getDumpFiles().forEach { file ->
            if (file.lastModified() < beforeTime && file.delete()) {
                count++
            }
        }
        Log.i(TAG, "Cleared $count old dump files")
        return count
    }
}
