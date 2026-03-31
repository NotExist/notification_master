package com.notificationmaster.debug

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import android.util.Log
import android.widget.RemoteViews
import com.notificationmaster.BuildConfig
import com.notificationmaster.core.compat.ApiVersionHelper
import com.notificationmaster.core.permission.PermissionDescriptions
import com.notificationmaster.data.model.EnvironmentInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug 模式 Raw Data Dumper
 * 將所有收到的原始資料以反射方式完整傾印到外部儲存供分析
 *
 * 所有事件類型共用同一個通用傾印邏輯，不做任何省略。
 * 使用反射自動涵蓋所有 public field 和 no-arg getter，
 * 新增 API 欄位無需修改程式碼。
 */
class DebugDumper(private val context: Context) {

    companion object {
        private const val TAG = "DebugDumper"
        private const val DEBUG_DIR_NAME = "NotificationMaster/debug"
        private const val MAX_DEPTH = 5

        /** 不呼叫的 getter 名稱（可能觸發副作用或無用） */
        private val UNSAFE_METHODS = setOf(
            "loadDrawable", "getResources", "getApplicationContext", "getBaseContext",
            "getClass", "hashCode", "notify", "notifyAll", "wait", "clone",
            "toString", "describeContents", "writeToParcel",
        )

        /** 不追蹤回傳值的型別（持有 Context/IBinder 等不可序列化資源） */
        private val SKIP_RETURN_TYPES: Set<Class<*>> = setOf(
            Context::class.java,
            android.content.res.Resources::class.java,
            ClassLoader::class.java,
            IBinder::class.java,
            android.content.pm.ApplicationInfo::class.java,
            android.content.pm.PackageManager::class.java,
        )
    }

    @Volatile
    var isEnabled = false
        private set

    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    /**
     * Debug 輸出目錄
     * 優先使用外部儲存讓其他工具可存取
     */
    val dumpDir: File by lazy {
        val publicDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOCUMENTS
        )
        val debugDir = File(publicDir, DEBUG_DIR_NAME)

        if (debugDir.mkdirs() || debugDir.isDirectory) {
            debugDir
        } else {
            File(context.getExternalFilesDir(null), "debug").apply { mkdirs() }
        }
    }

    fun enable() {
        isEnabled = true
        Log.i(TAG, "Debug mode enabled. Output dir: ${dumpDir.absolutePath}")
        dumpSystemInfo()
    }

    /**
     * 傾印系統環境資訊（啟用 debug 時立即輸出一次）
     * 包含裝置型號、Android 版本、API 支援狀況、權限授予狀態
     */
    private fun dumpSystemInfo() {
        try {
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
        }
    }

    fun disable() {
        isEnabled = false
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
        notifications?.forEach { sbn -> dumpEvent(sbn, "INITIAL", rankingMap) }
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

        try {
            val timestamp = dateFormat.format(Date())
            val safePackageName = sbn.packageName.replace(".", "_")
            val filename = "${safePackageName}_${timestamp}_${eventType}.json"

            val visited = mutableSetOf<Int>()
            val json = JSONObject().apply {
                put("dumpTime", System.currentTimeMillis())
                put("dumpTimeFormatted", timestamp)
                put("eventType", eventType)

                if (removalReason != null) {
                    put("removalReason", removalReason)
                    put("removalReasonCategory", ApiVersionHelper.categorizeRemovalReason(removalReason))
                    put("removalReasonDescription", ApiVersionHelper.getRemovalReasonDescription(removalReason))
                }

                put("sbn", reflectToJson(sbn, 0, visited))

                if (rankingMap != null) {
                    val key = ApiVersionHelper.getNotificationKey(sbn)
                    val ranking = Ranking()
                    if (rankingMap.getRanking(key, ranking)) {
                        put("ranking", reflectToJson(ranking, 0, mutableSetOf()))
                    }
                }

                put("flagsDecoded", decodeFlagsToJson(sbn.notification.flags))
                put("environment", buildEnvironmentJson())
            }

            File(dumpDir, filename).writeText(json.toString(2))
            Log.d(TAG, "Dumped $eventType for ${sbn.packageName}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dump $eventType", e)
        }
    }

    /**
     * 傾印 Ranking 更新（無 sbn，獨立格式）
     */
    fun dumpRankingUpdate(rankingMap: RankingMap) {
        if (!isEnabled) return

        try {
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
                        rankingsArray.put(reflectToJson(ranking, 0, mutableSetOf()))
                    }
                }
                put("rankings", rankingsArray)
                put("totalCount", keys.size)

                put("environment", buildEnvironmentJson())
            }

            File(dumpDir, filename).writeText(json.toString(2))
            Log.d(TAG, "Dumped RANKING (${rankingMap.orderedKeys.size} entries)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dump ranking", e)
        }
    }

    // === 反射引擎 ===

    /**
     * 遞迴反射序列化任意物件為 JSON 相容值
     */
    private fun reflectToJson(obj: Any?, depth: Int, visited: MutableSet<Int>): Any? {
        if (obj == null) return JSONObject.NULL

        // 基本型別
        when (obj) {
            is Boolean, is Int, is Long, is Float, is Double, is Short, is Byte -> return obj
            is CharSequence -> return obj.toString()
            is Enum<*> -> return obj.name
        }

        // Binary 型別：標示但不傾印內容
        if (isBinaryType(obj)) return buildBinaryMetadata(obj)
        if (obj is ByteArray) return JSONObject().apply {
            put("_type", "ByteArray"); put("_binary", true); put("size", obj.size)
        }

        // 基本陣列
        when (obj) {
            is IntArray -> return JSONArray(obj.toList())
            is LongArray -> return JSONArray(obj.toList())
            is FloatArray -> return JSONArray(obj.toList())
            is DoubleArray -> return JSONArray(obj.toList())
            is BooleanArray -> return JSONArray(obj.toList())
            is ShortArray -> return JSONArray(obj.toList())
        }

        // 深度限制
        if (depth > MAX_DEPTH) return JSONObject().apply {
            put("_type", obj.javaClass.simpleName); put("_truncated", true)
        }

        // 循環引用偵測
        val id = System.identityHashCode(obj)
        if (id in visited) return JSONObject().apply {
            put("_type", obj.javaClass.simpleName); put("_circular", true)
        }
        visited.add(id)

        try {
            return when (obj) {
                is Bundle -> reflectBundle(obj, depth, visited)
                is PendingIntent -> JSONObject().apply {
                    put("_type", "PendingIntent")
                    put("creatorPackage", obj.creatorPackage)
                    put("creatorUid", obj.creatorUid)
                }
                is Array<*> -> JSONArray().apply {
                    obj.forEach { put(reflectToJson(it, depth + 1, visited)) }
                }
                is Collection<*> -> JSONArray().apply {
                    obj.forEach { put(reflectToJson(it, depth + 1, visited)) }
                }
                is Map<*, *> -> JSONObject().apply {
                    obj.forEach { (k, v) -> put(k.toString(), reflectToJson(v, depth + 1, visited)) }
                }
                else -> reflectObject(obj, depth, visited)
            }
        } finally {
            visited.remove(id)
        }
    }

    /**
     * Bundle 用 keySet() 迭代（比反射更可靠）
     */
    @Suppress("DEPRECATION")
    private fun reflectBundle(bundle: Bundle, depth: Int, visited: MutableSet<Int>): JSONObject {
        val json = JSONObject()
        json.put("_type", "Bundle")
        for (key in bundle.keySet()) {
            try {
                json.put(key, reflectToJson(bundle.get(key), depth + 1, visited))
            } catch (e: Exception) {
                json.put(key, JSONObject().apply { put("_error", e.message) })
            }
        }
        return json
    }

    /**
     * 通用物件反射：public fields + no-arg getters
     */
    private fun reflectObject(obj: Any, depth: Int, visited: MutableSet<Int>): JSONObject {
        val json = JSONObject()
        json.put("_type", obj.javaClass.simpleName)

        val seenNames = mutableSetOf<String>()

        // 1. Public fields
        for (field in obj.javaClass.fields) {
            val name = field.name
            if (name.startsWith("$") || name.startsWith("CREATOR")) continue
            seenNames.add(name)
            try {
                val value = field.get(obj)
                if (shouldSkipReturnType(field.type)) {
                    json.put(name, JSONObject().apply {
                        put("_type", field.type.simpleName); put("_skipped", true)
                    })
                } else {
                    json.put(name, reflectToJson(value, depth + 1, visited))
                }
            } catch (e: Exception) {
                json.put(name, JSONObject().apply { put("_error", e.message) })
            }
        }

        // 2. Public no-arg getters (get* / is*)
        for (method in obj.javaClass.methods) {
            try {
                val name = method.name
                if (method.parameterCount != 0) continue
                if (name in UNSAFE_METHODS) continue
                if (!name.startsWith("get") && !name.startsWith("is")) continue

                val returnType = method.returnType
                if (returnType == Void.TYPE) continue

                val propName = when {
                    name.startsWith("get") && name.length > 3 ->
                        name.removePrefix("get").replaceFirstChar { it.lowercase() }
                    name.startsWith("is") && name.length > 2 -> name
                    else -> continue
                }
                if (propName in seenNames) continue
                seenNames.add(propName)

                if (shouldSkipReturnType(returnType)) {
                    json.put(propName, JSONObject().apply {
                        put("_type", returnType.simpleName); put("_skipped", true)
                    })
                    continue
                }

                val value = method.invoke(obj)
                json.put(propName, reflectToJson(value, depth + 1, visited))
            } catch (_: Exception) {
                // API 版本不符、SecurityException 等 — 靜默跳過
            }
        }

        return json
    }

    // === Binary 型別處理 ===

    private fun isBinaryType(obj: Any): Boolean {
        return obj is Bitmap ||
                obj is android.graphics.drawable.Drawable ||
                obj is RemoteViews ||
                (Build.VERSION.SDK_INT >= 23 && obj is android.graphics.drawable.Icon)
    }

    private fun buildBinaryMetadata(obj: Any): JSONObject = JSONObject().apply {
        put("_type", obj.javaClass.simpleName)
        put("_binary", true)
        when (obj) {
            is Bitmap -> {
                put("width", obj.width)
                put("height", obj.height)
                put("config", obj.config?.toString())
                put("byteCount", obj.byteCount)
            }
            is RemoteViews -> {
                put("package", obj.`package`)
                put("layoutId", obj.layoutId)
            }
        }
        if (Build.VERSION.SDK_INT >= 23 && obj is android.graphics.drawable.Icon) {
            if (Build.VERSION.SDK_INT >= 28) {
                put("iconType", obj.type)
                put("resPackage", obj.resPackage)
                put("resId", obj.resId)
            }
        }
    }

    private fun shouldSkipReturnType(type: Class<*>): Boolean {
        return SKIP_RETURN_TYPES.any { it.isAssignableFrom(type) }
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
