package com.notificationmaster.core.cache

import android.app.PendingIntent
import java.util.concurrent.ConcurrentHashMap

/**
 * PendingIntent 記憶體快取
 * 僅存活於 process 生命週期內，Service 重啟後由 onListenerConnected INITIAL 事件重新填充。
 * Service (IO thread) 寫入、UI (Main thread) 讀取，以 ConcurrentHashMap 確保執行緒安全。
 *
 * 每個 entry 帶時間戳，超過 TTL 視為過期。
 * put() 時順便清理過期 entry，避免無限增長。
 */
object PendingIntentCache {

    /** 預設 TTL：24 小時 */
    private const val TTL_MS = 24 * 60 * 60 * 1000L
    /** 每次清理的觸發門檻 */
    private const val CLEANUP_THRESHOLD = 100

    data class IntentSet(
        val contentIntent: PendingIntent? = null,
        val deleteIntent: PendingIntent? = null,
        val fullScreenIntent: PendingIntent? = null,
        val actionIntents: Map<Int, PendingIntent> = emptyMap()
    )

    private data class TimedEntry(val set: IntentSet, val timestamp: Long)

    private val cache = ConcurrentHashMap<String, TimedEntry>()
    private var putCount = 0

    fun put(key: String, set: IntentSet) {
        cache[key] = TimedEntry(set, System.currentTimeMillis())
        if (++putCount % CLEANUP_THRESHOLD == 0) {
            evictExpired()
        }
    }

    fun get(key: String): IntentSet? {
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > TTL_MS) {
            cache.remove(key)
            return null
        }
        return entry.set
    }

    fun remove(key: String) {
        cache.remove(key)
    }

    fun clear() {
        cache.clear()
        putCount = 0
    }

    val size: Int get() = cache.size

    private fun evictExpired() {
        val now = System.currentTimeMillis()
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.timestamp > TTL_MS) {
                iterator.remove()
            }
        }
    }
}
