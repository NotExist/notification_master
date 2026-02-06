package com.notificationmaster.debug

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import android.util.Log
import com.notificationmaster.core.compat.ApiVersionHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug 模式 Raw Data Dumper
 * 將所有收到的原始通知資料 dump 到外部儲存供分析
 */
class DebugDumper(private val context: Context) {

    companion object {
        private const val TAG = "DebugDumper"
        private const val DEBUG_DIR_NAME = "NotificationMaster/debug"
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
        // 嘗試使用公開的 Documents 目錄
        val publicDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOCUMENTS
        )
        val debugDir = File(publicDir, DEBUG_DIR_NAME)

        if (debugDir.mkdirs() || debugDir.isDirectory) {
            debugDir
        } else {
            // Fallback 到 App 專屬外部目錄
            File(context.getExternalFilesDir(null), "debug").apply { mkdirs() }
        }
    }

    /**
     * 啟用 Debug 模式
     */
    fun enable() {
        isEnabled = true
        Log.i(TAG, "Debug mode enabled. Output dir: ${dumpDir.absolutePath}")
    }

    /**
     * 停用 Debug 模式
     */
    fun disable() {
        isEnabled = false
        Log.i(TAG, "Debug mode disabled")
    }

    /**
     * Dump 通知資料
     */
    fun dumpNotification(sbn: StatusBarNotification, eventType: String) {
        if (!isEnabled) return

        try {
            val timestamp = dateFormat.format(Date())
            val safePackageName = sbn.packageName.replace(".", "_")
            val filename = "${timestamp}_${eventType}_${safePackageName}.json"

            val json = buildNotificationJson(sbn, eventType)

            val file = File(dumpDir, filename)
            file.writeText(json.toString(2))

            Log.d(TAG, "Dumped notification to: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dump notification", e)
        }
    }

    /**
     * Dump 通知移除事件
     */
    fun dumpRemoval(sbn: StatusBarNotification, reason: Int) {
        if (!isEnabled) return

        try {
            val timestamp = dateFormat.format(Date())
            val safePackageName = sbn.packageName.replace(".", "_")
            val filename = "${timestamp}_REMOVED_${safePackageName}.json"

            val json = JSONObject().apply {
                put("dumpTime", System.currentTimeMillis())
                put("dumpTimeFormatted", timestamp)
                put("eventType", "REMOVED")

                // 移除原因
                put("removalReason", reason)
                put("removalReasonCategory", ApiVersionHelper.categorizeRemovalReason(reason))
                put("removalReasonDescription", ApiVersionHelper.getRemovalReasonDescription(reason))

                // SBN 資訊
                put("key", ApiVersionHelper.getNotificationKey(sbn))
                put("packageName", sbn.packageName)
                put("id", sbn.id)
                put("tag", sbn.tag)
                put("postTime", sbn.postTime)
                put("isOngoing", sbn.isOngoing)
            }

            val file = File(dumpDir, filename)
            file.writeText(json.toString(2))

            Log.d(TAG, "Dumped removal to: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dump removal", e)
        }
    }

    /**
     * Dump Ranking 更新
     */
    fun dumpRankingUpdate(rankingMap: RankingMap) {
        if (!isEnabled) return

        try {
            val timestamp = dateFormat.format(Date())
            val filename = "${timestamp}_RANKING.json"

            val json = JSONObject().apply {
                put("dumpTime", System.currentTimeMillis())
                put("dumpTimeFormatted", timestamp)
                put("eventType", "RANKING")

                val rankingsArray = JSONArray()
                val keys = rankingMap.orderedKeys

                for (key in keys) {
                    val ranking = Ranking()
                    if (rankingMap.getRanking(key, ranking)) {
                        rankingsArray.put(JSONObject().apply {
                            put("key", key)
                            put("rank", ranking.rank)
                            put("importance", ranking.importance)
                            put("isAmbient", ranking.isAmbient)
                            if (Build.VERSION.SDK_INT >= 24) {
                                put("suppressedVisualEffects", ranking.suppressedVisualEffects)
                            }
                            if (Build.VERSION.SDK_INT >= 26) {
                                put("channel", ranking.channel?.id)
                                put("overrideGroupKey", ranking.overrideGroupKey)
                            }
                            if (Build.VERSION.SDK_INT >= 28) {
                                put("isSuspended", ranking.isSuspended)
                                put("canShowBadge", ranking.canShowBadge())
                            }
                        })
                    }
                }

                put("rankings", rankingsArray)
                put("totalCount", keys.size)
            }

            val file = File(dumpDir, filename)
            file.writeText(json.toString(2))

            Log.d(TAG, "Dumped ranking to: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dump ranking", e)
        }
    }

    /**
     * 建構完整的通知 JSON
     */
    private fun buildNotificationJson(sbn: StatusBarNotification, eventType: String): JSONObject {
        val notification = sbn.notification
        val extras = notification.extras ?: Bundle()

        return JSONObject().apply {
            // Meta
            put("dumpTime", System.currentTimeMillis())
            put("dumpTimeFormatted", dateFormat.format(Date()))
            put("eventType", eventType)

            // StatusBarNotification
            put("sbn", JSONObject().apply {
                put("key", ApiVersionHelper.getNotificationKey(sbn))
                put("packageName", sbn.packageName)
                put("id", sbn.id)
                put("tag", sbn.tag)
                put("postTime", sbn.postTime)
                put("isOngoing", sbn.isOngoing)
                put("isClearable", sbn.isClearable)
                if (Build.VERSION.SDK_INT >= 21) {
                    put("groupKey", sbn.groupKey)
                    put("user", sbn.user?.toString())
                }
                if (Build.VERSION.SDK_INT >= 24) {
                    put("overrideGroupKey", sbn.overrideGroupKey)
                }
            })

            // Notification
            put("notification", JSONObject().apply {
                put("flags", notification.flags)
                put("flagsDecoded", decodeFlagsToJson(notification.flags))

                @Suppress("DEPRECATION")
                put("priority", notification.priority)
                put("category", notification.category)
                put("visibility", notification.visibility)
                put("color", notification.color)
                put("when", notification.`when`)
                put("number", notification.number)
                put("group", notification.group)
                put("sortKey", notification.sortKey)
                put("tickerText", notification.tickerText?.toString())

                // Sound & Vibration
                put("sound", notification.sound?.toString())
                put("vibrate", notification.vibrate?.let { JSONArray(it.toList()) })
                put("ledARGB", notification.ledARGB)
                put("ledOnMS", notification.ledOnMS)
                put("ledOffMS", notification.ledOffMS)
                put("defaults", notification.defaults)

                // API 21+
                if (Build.VERSION.SDK_INT >= 21) {
                    put("publicVersion", notification.publicVersion != null)
                }

                // API 26+
                if (Build.VERSION.SDK_INT >= 26) {
                    put("channelId", notification.channelId)
                    put("timeoutAfter", notification.timeoutAfter)
                    put("badgeIconType", notification.badgeIconType)
                    put("shortcutId", notification.shortcutId)
                    put("settingsText", notification.settingsText?.toString())
                }

                // API 29+
                if (Build.VERSION.SDK_INT >= 29) {
                    put("bubbleMetadata", notification.bubbleMetadata != null)
                    put("locusId", notification.locusId?.id)
                    put("allowSystemGeneratedContextualActions",
                        notification.allowSystemGeneratedContextualActions)
                }

                // RemoteViews
                put("contentView", notification.contentView != null)
                put("bigContentView", notification.bigContentView != null)
                if (Build.VERSION.SDK_INT >= 21) {
                    put("headsUpContentView", notification.headsUpContentView != null)
                }
            })

            // Extras (完整 dump)
            put("extras", dumpExtras(extras))

            // Actions
            put("actions", dumpActions(notification))

            // 環境資訊
            put("environment", JSONObject().apply {
                put("apiLevel", Build.VERSION.SDK_INT)
                put("androidVersion", Build.VERSION.RELEASE)
                put("deviceModel", Build.MODEL)
            })
        }
    }

    /**
     * 解碼 Flags
     */
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

    /**
     * Dump Extras Bundle
     */
    private fun dumpExtras(extras: Bundle): JSONObject {
        val json = JSONObject()

        for (key in extras.keySet()) {
            try {
                val value = extras.get(key)
                val valueJson = when (value) {
                    null -> JSONObject.NULL
                    is CharSequence -> value.toString()
                    is Number -> value
                    is Boolean -> value
                    is Bitmap -> JSONObject().apply {
                        put("type", "Bitmap")
                        put("width", value.width)
                        put("height", value.height)
                        put("config", value.config?.toString())
                        put("byteCount", value.byteCount)
                    }
                    is android.graphics.drawable.Icon -> JSONObject().apply {
                        put("type", "Icon")
                        if (Build.VERSION.SDK_INT >= 23) {
                            put("iconType", value.type)
                            put("resPackage", value.resPackage)
                            put("resId", value.resId)
                        }
                    }
                    is Bundle -> JSONObject().apply {
                        put("type", "Bundle")
                        put("keys", JSONArray(value.keySet().toList()))
                    }
                    is Array<*> -> JSONArray(value.map { it?.toString() })
                    is IntArray -> JSONArray(value.toList())
                    is LongArray -> JSONArray(value.toList())
                    is FloatArray -> JSONArray(value.toList())
                    is DoubleArray -> JSONArray(value.toList())
                    is BooleanArray -> JSONArray(value.toList())
                    is ArrayList<*> -> JSONArray(value.map { it?.toString() })
                    else -> JSONObject().apply {
                        put("type", value.javaClass.name)
                        put("toString", value.toString())
                    }
                }
                json.put(key, valueJson)
            } catch (e: Exception) {
                json.put(key, JSONObject().apply {
                    put("error", e.message)
                })
            }
        }

        return json
    }

    /**
     * Dump Actions
     */
    private fun dumpActions(notification: Notification): JSONArray {
        val actions = notification.actions ?: return JSONArray()
        val array = JSONArray()

        for ((index, action) in actions.withIndex()) {
            array.put(JSONObject().apply {
                put("index", index)
                put("title", action.title?.toString())
                put("icon", action.icon)

                if (Build.VERSION.SDK_INT >= 17) {
                    put("creatorPackage", action.actionIntent?.creatorPackage)
                    put("creatorUid", action.actionIntent?.creatorUid)
                }

                if (Build.VERSION.SDK_INT >= 28) {
                    put("semanticAction", action.semanticAction)
                }

                if (Build.VERSION.SDK_INT >= 29) {
                    put("isContextual", action.isContextual)
                }

                if (Build.VERSION.SDK_INT >= 31) {
                    put("isAuthenticationRequired", action.isAuthenticationRequired)
                }

                // RemoteInputs
                if (Build.VERSION.SDK_INT >= 20) {
                    val remoteInputs = action.remoteInputs
                    if (remoteInputs != null && remoteInputs.isNotEmpty()) {
                        put("remoteInputs", JSONArray().apply {
                            for (input in remoteInputs) {
                                put(JSONObject().apply {
                                    put("resultKey", input.resultKey)
                                    put("label", input.label?.toString())
                                    put("allowFreeFormInput", input.allowFreeFormInput)
                                    put("choices", input.choices?.map { it.toString() }?.let { JSONArray(it) })
                                    if (Build.VERSION.SDK_INT >= 29) {
                                        put("editChoicesBeforeSending", input.editChoicesBeforeSending)
                                    }
                                })
                            }
                        })
                    }
                }
            })
        }

        return array
    }

    // === 管理功能 ===

    /**
     * 取得 dump 檔案清單
     */
    fun getDumpFiles(): List<File> {
        return dumpDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /**
     * 取得 dump 檔案總數
     */
    fun getDumpFileCount(): Int = getDumpFiles().size

    /**
     * 取得 dump 檔案總大小
     */
    fun getDumpTotalSize(): Long {
        return getDumpFiles().sumOf { it.length() }
    }

    /**
     * 清除所有 dump 檔案
     */
    fun clearDumpFiles(): Int {
        var count = 0
        getDumpFiles().forEach { file ->
            if (file.delete()) count++
        }
        Log.i(TAG, "Cleared $count dump files")
        return count
    }

    /**
     * 清除指定時間之前的 dump 檔案
     */
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
