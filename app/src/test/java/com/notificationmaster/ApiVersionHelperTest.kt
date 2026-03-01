package com.notificationmaster

import android.app.Notification
import android.service.notification.NotificationListenerService
import com.notificationmaster.core.compat.ApiVersionHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ApiVersionHelper 單元測試
 */
class ApiVersionHelperTest {

    @Test
    fun `categorizeRemovalReason returns correct category for user click`() {
        val result = ApiVersionHelper.categorizeRemovalReason(
            NotificationListenerService.REASON_CLICK
        )
        assertEquals("USER_CLICK", result)
    }

    @Test
    fun `categorizeRemovalReason returns correct category for app cancel`() {
        val result = ApiVersionHelper.categorizeRemovalReason(
            NotificationListenerService.REASON_APP_CANCEL
        )
        assertEquals("APP_CANCEL", result)
    }

    @Test
    fun `categorizeRemovalReason returns correct category for timeout`() {
        val result = ApiVersionHelper.categorizeRemovalReason(
            NotificationListenerService.REASON_TIMEOUT
        )
        assertEquals("TIMEOUT", result)
    }

    @Test
    fun `categorizeRemovalReason returns OTHER for unknown reason`() {
        val result = ApiVersionHelper.categorizeRemovalReason(9999)
        assertEquals("OTHER", result)
    }

    @Test
    fun `isOngoing returns true when flag is set`() {
        val flags = Notification.FLAG_ONGOING_EVENT
        assertTrue(ApiVersionHelper.isOngoing(flags))
    }

    @Test
    fun `isOngoing returns false when flag is not set`() {
        val flags = 0
        assertFalse(ApiVersionHelper.isOngoing(flags))
    }

    @Test
    fun `isForegroundService returns true when flag is set`() {
        val flags = Notification.FLAG_FOREGROUND_SERVICE
        assertTrue(ApiVersionHelper.isForegroundService(flags))
    }

    @Test
    fun `isAutoCancel returns true when flag is set`() {
        val flags = Notification.FLAG_AUTO_CANCEL
        assertTrue(ApiVersionHelper.isAutoCancel(flags))
    }

    @Test
    fun `isGroupSummary returns true when flag is set`() {
        val flags = Notification.FLAG_GROUP_SUMMARY
        assertTrue(ApiVersionHelper.isGroupSummary(flags))
    }

    @Test
    fun `multiple flags can be detected together`() {
        val flags = Notification.FLAG_ONGOING_EVENT or
                    Notification.FLAG_AUTO_CANCEL or
                    Notification.FLAG_LOCAL_ONLY

        assertTrue(ApiVersionHelper.isOngoing(flags))
        assertTrue(ApiVersionHelper.isAutoCancel(flags))
        assertTrue(ApiVersionHelper.isLocalOnly(flags))
        assertFalse(ApiVersionHelper.isForegroundService(flags))
    }

    @Test
    fun `getRemovalReasonDescription returns human readable text`() {
        val description = ApiVersionHelper.getRemovalReasonDescription(
            NotificationListenerService.REASON_CLICK
        )
        assertEquals("使用者點擊通知", description)
    }
}
