package com.notificationmaster.ui.widget

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import com.notificationmaster.R

/**
 * 從 Timeline rule chip 長按選單請求建立 Widget（API 26+）。
 * API<26 / launcher 不支援時顯示說明。
 */
object WidgetPinner {

    @SuppressLint("InlinedApi")
    fun requestPin(context: Context, ruleId: String, singleVariant: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(context, R.string.widget_pin_below_api26, Toast.LENGTH_LONG).show()
            return
        }

        val awm = AppWidgetManager.getInstance(context)
        if (!awm.isRequestPinAppWidgetSupported) {
            Toast.makeText(context, R.string.widget_pin_not_supported, Toast.LENGTH_LONG).show()
            return
        }

        val provider = if (singleVariant) {
            ComponentName(context, NotificationSingleWidgetProvider::class.java)
        } else {
            ComponentName(context, NotificationWidgetProvider::class.java)
        }

        // Pin 完成後 launcher 呼叫此 PendingIntent；WidgetConfigActivity 會接收 ruleId
        val callbackIntent = Intent(context, WidgetConfigActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(WidgetConfigActivity.EXTRA_PRESELECTED_RULE_ID, ruleId)
        }
        val callback = PendingIntent.getActivity(
            context, ruleId.hashCode(),
            callbackIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        awm.requestPinAppWidget(provider, null, callback)
    }
}
