package com.notificationmaster.core

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.UserHandle
import android.widget.RemoteViews
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

    /** 不追蹤回傳值的型別（持有 Context / IBinder 等不可序列化資源） */
    private val SKIP_RETURN_TYPES: Set<Class<*>> = setOf(
        Context::class.java,
        android.content.res.Resources::class.java,
        ClassLoader::class.java,
        IBinder::class.java,
        android.content.pm.ApplicationInfo::class.java,
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
                json.put(key, serialize(bundle.get(key), depth + 1, visited))
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
                json.put(propName, serialize(value, depth + 1, visited))
            } catch (_: Exception) {
                // API 版本不符 / SecurityException 等 — 靜默跳過
            }
        }

        return json
    }

    // === Binary 型別處理 ===

    private fun isBinaryType(obj: Any): Boolean {
        return obj is Bitmap ||
            obj is Drawable ||
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
}
