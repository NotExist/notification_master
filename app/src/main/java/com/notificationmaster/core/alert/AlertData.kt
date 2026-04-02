package com.notificationmaster.core.alert

import android.app.Notification
import android.app.PendingIntent

/**
 * 持續提醒資料載體
 *
 * 攜帶原始通知資訊供全螢幕 Activity 顯示。
 */
data class AlertData(
    val notificationKey: String,
    val title: String,
    val text: String?,
    val soundUri: String?,
    val vibrate: Boolean,
    val audioStream: String = "alarm",
    val appName: String,
    val packageName: String,
    val eventType: String,
    val timestamp: Long,
    val subText: String?,
    val bigText: String?,
    val contentIntent: PendingIntent?,
    val actions: Array<Notification.Action>?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AlertData) return false
        return notificationKey == other.notificationKey &&
                title == other.title &&
                text == other.text &&
                soundUri == other.soundUri &&
                vibrate == other.vibrate &&
                audioStream == other.audioStream &&
                appName == other.appName &&
                packageName == other.packageName &&
                eventType == other.eventType &&
                timestamp == other.timestamp &&
                subText == other.subText &&
                bigText == other.bigText &&
                contentIntent == other.contentIntent &&
                actions.contentDeepEquals(other.actions)
    }

    override fun hashCode(): Int {
        var result = notificationKey.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + (text?.hashCode() ?: 0)
        result = 31 * result + (soundUri?.hashCode() ?: 0)
        result = 31 * result + vibrate.hashCode()
        result = 31 * result + audioStream.hashCode()
        result = 31 * result + appName.hashCode()
        result = 31 * result + packageName.hashCode()
        result = 31 * result + eventType.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + (subText?.hashCode() ?: 0)
        result = 31 * result + (bigText?.hashCode() ?: 0)
        result = 31 * result + (contentIntent?.hashCode() ?: 0)
        result = 31 * result + (actions?.contentDeepHashCode() ?: 0)
        return result
    }
}
