package com.notificationmaster

import android.app.Application
import com.notificationmaster.data.db.NotificationDatabase

/**
 * Application 類別
 * 負責初始化全域資源
 */
class NotificationMasterApp : Application() {

    /** 資料庫實例 (lazy 初始化) */
    val database: NotificationDatabase by lazy {
        NotificationDatabase.getInstance(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        @Volatile
        private var instance: NotificationMasterApp? = null

        fun getInstance(): NotificationMasterApp {
            return instance ?: throw IllegalStateException(
                "NotificationMasterApp not initialized"
            )
        }
    }
}
