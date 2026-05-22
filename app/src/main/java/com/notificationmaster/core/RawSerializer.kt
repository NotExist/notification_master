package com.notificationmaster.core

import android.app.PendingIntent
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.UserHandle
import android.util.Base64
import android.widget.RemoteViews
import java.lang.reflect.Modifier
import org.json.JSONArray
import org.json.JSONObject

/**
 * 通用遞迴 JSON 序列化器（Plan 2 Phase 2）
 *
 * 設計目標：
 * - 「callback 給什麼就存什麼」— 不對欄位做加工篩選
 * - 平台邊界以穩定描述形式取代不可序列化內容（PendingIntent / RemoteViews / Bitmap / Icon）
 * - 通用 reflection：public fields + no-arg getters，自動覆蓋未來新欄位
 *
 * 用途：
 * - Service 寫 NotificationEventEntity.eventRawJson（per-event 自包含 raw）
 * - DebugDumper 共用反射引擎（按 callback 維度組織檔案）
 *
 * Bitmap / Icon dedup hash 由呼叫端（如 MediaExtractor）負責——RawSerializer 只輸出
 * metadata（width / height / type 等），dedup hash 後續由呼叫端注入或外部關聯。
 */
object RawSerializer {

    private const val MAX_DEPTH = 5

    /** 不呼叫的 getter 名稱（可能觸發副作用或無用） */
    private val UNSAFE_METHODS = setOf(
        "loadDrawable", "getResources", "getApplicationContext", "getBaseContext",
        "getClass", "hashCode", "notify", "notifyAll", "wait", "clone",
        "toString", "describeContents", "writeToParcel"
    )

    /**
     * 不追蹤回傳值的型別（持有 Context / IBinder 等不可序列化資源 + reflection 爆炸源）
     *
     * Phase 31c：ApplicationInfo 從 skip 改為 special handler（serializeApplicationInfo），
     * 保留 packageName / labelRes / iconRes / SDK 等基本 metadata（~300B vs 完整反射 5KB）。
     */
    private val SKIP_RETURN_TYPES: Set<Class<*>> = setOf(
        Context::class.java,
        android.content.res.Resources::class.java,
        ClassLoader::class.java,
        IBinder::class.java,
        android.content.pm.PackageManager::class.java
    )

    /**
     * 序列化任意物件為 JSON 可表達結構（JSONObject / JSONArray / 基本型別 / JSONObject.NULL）。
     *
     * 呼叫端可直接 put 到 JSONObject 或 toString 後寫入 DB。
     *
     * @param obj 任意物件（含 null）
     * @param depth 起始深度（呼叫端通常傳 0）
     * @param visited identityHashCode 集合，循環引用偵測用；呼叫端通常傳 mutableSetOf()
     */
    fun serialize(obj: Any?, depth: Int = 0, visited: MutableSet<Int> = mutableSetOf()): Any? {
        if (obj == null) return JSONObject.NULL

        // 基本型別
        when (obj) {
            is Boolean, is Int, is Long, is Float, is Double, is Short, is Byte -> return obj
            is CharSequence -> return obj.toString()
            is Enum<*> -> return obj.name
        }

        // Phase 31c+：SKIP type instance 本身進來時也要標位置（不只 reflectObject/reflectBundle
        // 的 field/method skip）。保留痕跡 + className FQN + identityHash 供事後辨識同 instance 重複。
        if (shouldSkipReturnType(obj.javaClass)) return buildSkippedMarker(obj)

        // Binary 型別：標示但不傾印內容（Bitmap / Drawable / Icon — 已由 MediaExtractor 另存）
        if (isBinaryType(obj)) return buildBinaryMetadata(obj)
        // Phase 31c：ByteArray 加 base64 保留完整 bytes（事後分析用）。raw 多 1.33x byte 大小，
        // 通常 < 1KB，可接受。size 仍保留供快速辨識。
        if (obj is ByteArray) return JSONObject().apply {
            put("_type", "ByteArray"); put("size", obj.size)
            put("base64", Base64.encodeToString(obj, Base64.NO_WRAP))
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
                // Phase 31b：UserHandle 含 5 個 static field（OWNER/SYSTEM/ALL/CURRENT/
                // CURRENT_OR_SELF）互相 reference，identityHashCode 各不同繞過循環偵測，
                // reflection 5 層深度展開造成 5^5=3125x 序列化爆炸（單筆通知 119KB / 47% raw）。
                //
                // UserHandle 對 app 唯一有意義的資訊是 user id（主用戶=0 / 工作 profile=10+ /
                // ALL=-1 / CURRENT=-2 / CURRENT_OR_SELF=-3）。`hashCode()` 通常返回內部
                // `mHandle` field = user id（Android framework 慣例，API 24+ 已驗證）。
                is UserHandle -> JSONObject().apply {
                    put("_type", "UserHandle")
                    val userId = obj.hashCode()
                    put("userId", userId)
                    put("label", when (userId) {
                        0 -> "owner"           // 主用戶 / SYSTEM
                        -1 -> "ALL"
                        -2 -> "CURRENT"
                        -3 -> "CURRENT_OR_SELF"
                        in 10..99 -> "work_profile_or_secondary"  // 工作 profile / 次要用戶
                        else -> "other"
                    })
                }
                // Phase 31c：ApplicationInfo special handler — 保留 minimal metadata（packageName /
                // labelRes / iconRes / SDK 版本 / flags 等），避免完整反射爆炸（~5KB → ~300B）。
                // ApplicationInfo 經 Notification 內部處理會嵌套（如 extras.android.appInfo），
                // 每筆通知同 app 重複序列化太浪費。
                is ApplicationInfo -> serializeApplicationInfo(obj)
                is Array<*> -> JSONArray().apply {
                    obj.forEach { put(serialize(it, depth + 1, visited)) }
                }
                is Collection<*> -> JSONArray().apply {
                    obj.forEach { put(serialize(it, depth + 1, visited)) }
                }
                is Map<*, *> -> JSONObject().apply {
                    obj.forEach { (k, v) -> put(k.toString(), serialize(v, depth + 1, visited)) }
                }
                else -> reflectObject(obj, depth, visited)
            }
        } finally {
            visited.remove(id)
        }
    }

    /** Bundle 用 keySet() 迭代（比反射更可靠） */
    @Suppress("DEPRECATION")
    private fun reflectBundle(bundle: Bundle, depth: Int, visited: MutableSet<Int>): JSONObject {
        val json = JSONObject()
        json.put("_type", "Bundle")
        for (key in bundle.keySet()) {
            try {
                val value = bundle.get(key)
                // Phase 31b：Bundle 內也檢查 SKIP_RETURN_TYPES（之前 reflectObject 才檢查，
                // 但 Notification.extras 內 `android.appInfo` 是 ApplicationInfo，反射展開 5KB+
                // 每筆通知重複）。直接 skip 跟 SKIP_RETURN_TYPES 一致。
                if (value != null && shouldSkipReturnType(value.javaClass)) {
                    json.put(key, buildSkippedMarker(value))
                } else {
                    json.put(key, serialize(value, depth + 1, visited))
                }
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
            // Phase 31g：跳過 static field（class-level constants 如 USER_SENTIMENT_*,
            // PARCELABLE_*, USAGE_*, AUDIO_ATTRIBUTES_DEFAULT, INTENT_CATEGORY_*, EXTRA_* 等）。
            // 這些是 public static final 常數，每個 instance reflection 都重複抓 → ranking dump
            // 400KB / event raw 多 N KB 的隱性浪費。class 定義不變，正式分析時看 SDK 即可。
            if (Modifier.isStatic(field.modifiers)) continue
            seenNames.add(name)
            try {
                val value = field.get(obj)
                // Phase 31c+：有 instance 時優先用 buildSkippedMarker（含 identityHash）
                if (value != null && shouldSkipReturnType(value.javaClass)) {
                    json.put(name, buildSkippedMarker(value))
                } else if (shouldSkipReturnType(field.type)) {
                    // declared type 是 SKIP 但 value 是 null（或同 type）— 用 type-only marker
                    json.put(name, buildSkippedMarkerByType(field.type))
                } else {
                    json.put(name, serialize(value, depth + 1, visited))
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
                // Phase 31g：跳 static method（同 static field 理由）
                if (Modifier.isStatic(method.modifiers)) continue
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

                // declared return type 已知為 SKIP — 直接寫 type-only marker，不 invoke 避免取到 instance 後反射
                if (shouldSkipReturnType(returnType)) {
                    json.put(propName, buildSkippedMarkerByType(returnType))
                    continue
                }

                val value = method.invoke(obj)
                // 也檢查 value 實際 type（可能 declared 是父 class，actual instance 是 SKIP 子 class）
                if (value != null && shouldSkipReturnType(value.javaClass)) {
                    json.put(propName, buildSkippedMarker(value))
                } else {
                    json.put(propName, serialize(value, depth + 1, visited))
                }
            } catch (_: Exception) {
                // API 版本不符 / SecurityException 等 — 靜默跳過
            }
        }

        return json
    }

    // === Binary 型別處理 ===

    /**
     * Phase 31c：RemoteViews 從 binary metadata-only 改走通用反射（actions 內容對事後分析有意義），
     * 由 reflectObject 處理（深度上限 + 循環偵測仍保護）。
     */
    private fun isBinaryType(obj: Any): Boolean {
        return obj is Bitmap ||
            obj is Drawable ||
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
        }
        if (Build.VERSION.SDK_INT >= 23 && obj is android.graphics.drawable.Icon) {
            if (Build.VERSION.SDK_INT >= 28) {
                put("iconType", obj.type)
                put("resPackage", obj.resPackage)
                put("resId", obj.resId)
            }
        }
    }

    /**
     * Phase 31c：ApplicationInfo special handler。
     * 完整反射展開 ~5KB（含 dataDir / nativeLibraryDir / resources 等大量內部欄位），
     * user 通知記錄角度只需要識別 app + 基本屬性。保留：packageName / processName / 各種 resId /
     * SDK 版本 / flags / category（Android 8+）。約 200-400B。
     */
    private fun serializeApplicationInfo(info: ApplicationInfo): JSONObject = JSONObject().apply {
        put("_type", "ApplicationInfo")
        put("packageName", info.packageName)
        put("processName", info.processName)
        put("labelRes", info.labelRes)
        put("iconRes", info.icon)
        put("logoRes", info.logo)
        put("themeRes", info.theme)
        put("targetSdkVersion", info.targetSdkVersion)
        if (Build.VERSION.SDK_INT >= 24) {
            put("minSdkVersion", info.minSdkVersion)
        }
        put("flags", info.flags)
        if (Build.VERSION.SDK_INT >= 26) {
            put("category", info.category)
        }
        put("enabled", info.enabled)
        info.taskAffinity?.let { put("taskAffinity", it) }
        info.permission?.let { put("permission", it) }
        info.uid.takeIf { it != 0 }?.let { put("uid", it) }
    }

    private fun shouldSkipReturnType(type: Class<*>): Boolean {
        return SKIP_RETURN_TYPES.any { it.isAssignableFrom(type) }
    }

    /**
     * Phase 31c+：skip 標記統一格式。保留：
     * - `_type`：simpleName 快速辨識
     * - `_className`：FQN，區分不同 ClassLoader / 內部 class
     * - `_skipped`: true
     * - `_identityHash`：System.identityHashCode，用於 grep 找同 instance 多處重複位置
     *
     * 用於三處：
     * 1. serialize() 入口（obj 本身是 SKIP type instance）
     * 2. reflectObject 內 field/method 回傳 SKIP type
     * 3. reflectBundle 內 value 是 SKIP type
     */
    internal fun buildSkippedMarker(obj: Any): JSONObject = JSONObject().apply {
        put("_type", obj.javaClass.simpleName)
        put("_className", obj.javaClass.name)
        put("_skipped", true)
        put("_identityHash", System.identityHashCode(obj))
    }

    /** field/method 回傳 type 是 SKIP（沒有 instance 可拿 identityHash）— 仍記類別位置。 */
    internal fun buildSkippedMarkerByType(type: Class<*>): JSONObject = JSONObject().apply {
        put("_type", type.simpleName)
        put("_className", type.name)
        put("_skipped", true)
    }
}
