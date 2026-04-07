package com.notificationmaster.ui.widget

import android.content.Intent
import android.widget.RemoteViewsService

/**
 * 清單式 Widget 的 RemoteViewsService
 */
class NotificationRemoteViewsService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return NotificationRemoteViewsFactory(applicationContext, intent)
    }
}
