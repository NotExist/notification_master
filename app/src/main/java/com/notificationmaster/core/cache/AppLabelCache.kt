package com.notificationmaster.core.cache

import android.content.Context
import android.content.pm.PackageManager

/**
 * App 顯示名稱快取（TTL + LRU）
 *
 * 多個 Fragment/Adapter 共用，避免重複呼叫 PackageManager。
 * TTL 30 分鐘，最多快取 200 個 App。
 */
object AppLabelCache {

    private const val TTL_MS = 30 * 60 * 1000L  // 30 分鐘
    private const val MAX_SIZE = 200

    private data class Entry(val label: String, val timestamp: Long)

    private val cache = LinkedHashMap<String, Entry>(32, 0.75f, true)

    /**
     * 取得 App 顯示名稱（帶 TTL 快取）
     *
     * @return App label，查詢失敗時回傳 packageName
     */
    fun getLabel(context: Context, packageName: String): String {
        val now = System.currentTimeMillis()

        synchronized(cache) {
            val entry = cache[packageName]
            if (entry != null && now - entry.timestamp < TTL_MS) {
                return entry.label
            }
        }

        val label = resolveLabel(context, packageName)

        synchronized(cache) {
            cache[packageName] = Entry(label, now)
            trimIfNeeded()
        }

        return label
    }

    /**
     * 清除所有快取（App 安裝/解除安裝時可呼叫）
     */
    fun clear() {
        synchronized(cache) {
            cache.clear()
        }
    }

    /**
     * 移除指定 packageName 的快取
     */
    fun invalidate(packageName: String) {
        synchronized(cache) {
            cache.remove(packageName)
        }
    }

    private fun resolveLabel(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        } catch (_: Exception) {
            packageName
        }
    }

    private fun trimIfNeeded() {
        while (cache.size > MAX_SIZE) {
            val oldest = cache.entries.first()
            cache.remove(oldest.key)
        }
    }
}
