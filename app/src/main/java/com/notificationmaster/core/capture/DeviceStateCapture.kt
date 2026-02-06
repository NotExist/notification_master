package com.notificationmaster.core.capture

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.PowerManager
import com.notificationmaster.data.db.entity.DeviceStateEntity

/**
 * 裝置狀態擷取工具
 * 擷取通知到達時的裝置執行狀態
 *
 * 注意：此資料為裝置層面資訊，與 Notification API 資料明確區分
 */
class DeviceStateCapture(private val context: Context) {

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val powerManager: PowerManager by lazy {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    private val connectivityManager: ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    /**
     * 擷取當前裝置狀態快照
     *
     * @param notificationId 關聯的通知記錄 ID
     * @param captureTime 擷取時間
     */
    fun capture(notificationId: Long, captureTime: Long): DeviceStateEntity {
        return DeviceStateEntity(
            notificationId = notificationId,
            captureTime = captureTime,
            ringerMode = captureRingerMode(),
            isScreenOn = captureScreenState(),
            batteryLevel = captureBatteryLevel(),
            batteryStatus = captureBatteryStatus(),
            isConnected = captureNetworkConnected(),
            connectionType = captureConnectionType()
        )
    }

    /**
     * 響鈴模式
     * AudioManager.RINGER_MODE_SILENT (0) / RINGER_MODE_VIBRATE (1) / RINGER_MODE_NORMAL (2)
     */
    private fun captureRingerMode(): Int {
        return audioManager.ringerMode
    }

    /**
     * 螢幕狀態
     * 使用 PowerManager.isInteractive() (API 20+, minSdk=21 可直接使用)
     *
     * TODO (API < 21): 使用已棄用的 PowerManager.isScreenOn()
     */
    private fun captureScreenState(): Boolean {
        return powerManager.isInteractive
    }

    /**
     * 電池電量 (0-100)
     * 使用 sticky broadcast 取得，相容所有 API level
     *
     * TODO (API < 21): 同樣使用 sticky broadcast，無差異
     */
    private fun captureBatteryLevel(): Int {
        return try {
            val batteryIntent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            if (batteryIntent != null) {
                val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    (level * 100) / scale
                } else {
                    -1
                }
            } else {
                -1
            }
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * 電池狀態
     * 使用 sticky broadcast 取得（非 API 26 的 BatteryManager.getIntProperty）
     *
     * TODO (API < 21): 同樣使用 sticky broadcast，無差異
     */
    private fun captureBatteryStatus(): Int {
        return try {
            val batteryIntent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * 網路連線狀態
     * 使用 ConnectivityManager.getActiveNetworkInfo()（已棄用但相容所有 API）
     *
     * TODO (API 29+): 考慮使用 NetworkCapabilities 替代已棄用的 getActiveNetworkInfo()
     */
    private fun captureNetworkConnected(): Boolean? {
        return try {
            @Suppress("DEPRECATION")
            connectivityManager.activeNetworkInfo?.isConnected
        } catch (e: SecurityException) {
            // 無 ACCESS_NETWORK_STATE 權限
            null
        }
    }

    /**
     * 網路連線類型
     * ConnectivityManager.TYPE_WIFI (1) / TYPE_MOBILE (0) 等
     * -1 表示無連線或無法取得
     *
     * TODO (API 29+): 考慮使用 NetworkCapabilities 判斷傳輸類型
     */
    private fun captureConnectionType(): Int {
        return try {
            @Suppress("DEPRECATION")
            connectivityManager.activeNetworkInfo?.type ?: -1
        } catch (e: SecurityException) {
            -1
        }
    }
}
