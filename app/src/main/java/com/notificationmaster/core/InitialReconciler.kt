package com.notificationmaster.core

import android.util.Log
import androidx.room.withTransaction
import com.notificationmaster.core.compat.ApiVersionHelper
import com.notificationmaster.data.db.NotificationDatabase
import com.notificationmaster.data.db.entity.EventType
import com.notificationmaster.data.db.entity.NotificationEventEntity
import com.notificationmaster.data.db.entity.RemovalReasonCategory

/**
 * INITIAL reconciliation：補登錄漏接的 REMOVED 事件（Plan 2 §K）
 *
 * 觸發點：[com.notificationmaster.service.NotificationCaptureService.processInitialNotifications]
 * 完成 INITIAL upsert 後呼叫 [reconcile]。
 *
 * 邏輯：
 * 1. 取本機 active record key 集合（最後事件不是 REMOVED）
 * 2. 與 INITIAL callback 收到的 sbn key 集合做差集
 * 3. 差集 = 本機有但 INITIAL 沒有 = 漏接的 removal
 * 4. 對每個 missing key 補一筆 REMOVED event，reason=-100，
 *    eventRawJson 從該 record 最後一個 event 複製（保留最後已知狀態）
 *
 * 安全保護：OEM 早期綁定 sbn 為空時不執行（避免誤把所有 active 標移除）。
 */
class InitialReconciler(private val database: NotificationDatabase) {

    companion object {
        private const val TAG = "InitialReconciler"
    }

    /**
     * 執行 reconciliation。
     *
     * @param initialKeys INITIAL callback 收到的 sbn.key 集合
     * @param captureTime reconciliation 觸發時間（用作補登錄 REMOVED event 的 eventTime）
     * @return 補登錄筆數；安全門檻未通過時回 0
     */
    suspend fun reconcile(initialKeys: Set<String>, captureTime: Long): Int {
        val eventDao = database.notificationEventDao()
        val activeKeys = eventDao.getActiveRecordKeys().toSet()

        // 安全保護：本機有 active 但 INITIAL 為空 → 視為 OEM 早期綁定 / 異常情境，不執行
        if (activeKeys.isNotEmpty() && initialKeys.isEmpty()) {
            Log.w(TAG, "Skipping reconciliation: " +
                "activeKeys=${activeKeys.size} but initialKeys is empty " +
                "(likely OEM early bind or NLS not authorized)")
            return 0
        }

        val missingKeys = activeKeys - initialKeys
        if (missingKeys.isEmpty()) return 0

        Log.i(TAG, "Reconciliation: ${missingKeys.size} missing key(s) →補 REMOVED")

        var inserted = 0
        database.withTransaction {
            for (key in missingKeys) {
                val lastEvent = eventDao.getLatestEventByKey(key) ?: continue
                val reconciledEvent = lastEvent.copy(
                    id = 0,  // autoGenerate 重發
                    eventType = EventType.REMOVED,
                    eventTime = captureTime,
                    captureTime = captureTime,
                    removalReason = ApiVersionHelper.REASON_RECONCILED_AFTER_FACT,
                    removalReasonCategory = RemovalReasonCategory.RECONCILED_AFTER_FACT
                    // eventRawJson 沿用 lastEvent，保留最後已知狀態
                )
                eventDao.insert(reconciledEvent)
                inserted++
            }
        }
        return inserted
    }
}
