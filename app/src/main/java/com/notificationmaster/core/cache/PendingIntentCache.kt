package com.notificationmaster.core.cache

import android.app.PendingIntent

/**
 * PendingIntent 記憶體快取
 * 僅存活於 process 生命週期內，Service 重啟後由 onListenerConnected INITIAL 事件重新填充。
 * Service (IO thread) 寫入、UI (Main thread) 讀取，以 @Synchronized 確保執行緒安全。
 */
object PendingIntentCache {

    data class IntentSet(
        val contentIntent: PendingIntent? = null,
        val deleteIntent: PendingIntent? = null,
        val fullScreenIntent: PendingIntent? = null,
        val actionIntents: Map<Int, PendingIntent> = emptyMap()
    )

    private val cache = mutableMapOf<String, IntentSet>()

    @Synchronized
    fun put(key: String, set: IntentSet) {
        cache[key] = set
    }

    @Synchronized
    fun get(key: String): IntentSet? = cache[key]

    @Synchronized
    fun remove(key: String) {
        cache.remove(key)
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }
}
