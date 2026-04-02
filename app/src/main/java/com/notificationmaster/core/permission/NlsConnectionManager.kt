package com.notificationmaster.core.permission

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.notificationmaster.service.NotificationCaptureService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * NLS 連線管理
 *
 * 集中管理 NotificationListenerService 的權限檢查和 service 重連邏輯。
 * rebind 由使用者主動觸發（下拉刷新），不自動偵測。
 */
object NlsConnectionManager {

    private const val TAG = "NlsConnectionManager"
    private const val REBIND_INITIAL_DELAY_MS = 1_000L
    private const val REBIND_MAX_DELAY_MS = 16_000L
    private const val MAX_REBIND_ATTEMPTS = 6

    private var rebindJob: Job? = null

    /**
     * NLS 權限是否已授予
     *
     * 使用 [NotificationManagerCompat.getEnabledListenerPackages]，
     * 與 [PermissionDescriptions.checkGrantStatus] 一致。
     */
    fun isNlsEnabled(context: Context): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
    }

    /**
     * 確保 NotificationListenerService 在授權後成功連線
     *
     * 系統授權後通常會自動綁定服務，但部分裝置/ROM 可能延遲或不觸發。
     * 此方法定期檢查服務狀態，在系統未自動綁定時透過 requestRebind (API 24+) 觸發。
     *
     * 注意：不使用 setComponentEnabledSetting 元件切換，
     * 因為 disable 元件會導致系統從 enabled_notification_listeners 移除，撤銷授權。
     *
     * @param appContext Application context，避免 Activity leak
     * @param scope 由 caller 傳入的 CoroutineScope，控制 job 生命週期
     */
    fun ensureServiceConnected(appContext: Context, scope: CoroutineScope) {
        rebindJob?.cancel()
        Log.d(TAG, "ensureServiceConnected: starting checks (exponential backoff, " +
            "max=$MAX_REBIND_ATTEMPTS)")
        rebindJob = scope.launch {
            var delayMs = REBIND_INITIAL_DELAY_MS
            for (attempt in 0 until MAX_REBIND_ATTEMPTS) {
                delay(delayMs)

                val connected = NotificationCaptureService.isConnected
                val instanceExists = NotificationCaptureService.getInstance() != null
                Log.d(TAG, "ensureServiceConnected check #${attempt + 1} " +
                    "(delay=${delayMs}ms): connected=$connected, instance=$instanceExists")

                if (connected) {
                    // 服務已連線，備援擷取（冪等，跳過已存在 key）
                    NotificationCaptureService.getInstance()?.captureActiveNotifications()
                    return@launch
                }

                if (Build.VERSION.SDK_INT >= 24) {
                    try {
                        val cn = ComponentName(appContext, NotificationCaptureService::class.java)
                        NotificationListenerService.requestRebind(cn)
                        Log.d(TAG, "requestRebind sent (attempt ${attempt + 1})")
                    } catch (e: Exception) {
                        Log.w(TAG, "requestRebind failed", e)
                    }
                }

                // Exponential backoff: 1s → 2s → 4s → 8s → 16s → 16s
                delayMs = (delayMs * 2).coerceAtMost(REBIND_MAX_DELAY_MS)
            }

            Log.w(TAG, "Service not connected after $MAX_REBIND_ATTEMPTS attempts. " +
                "User can pull-to-refresh to retry.")
        }
    }
}
