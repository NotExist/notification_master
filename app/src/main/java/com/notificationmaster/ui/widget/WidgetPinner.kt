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
 * 從 Timeline preset chip 長按選單請求建立 Widget（API 26+）。
 * API<26 顯示說明提示；未支援 launcher 也有 fallback 提示。
 */
object WidgetPinner {

    /** 嘗試 pin 一個 Widget 並預先帶入 preset name；呼叫 callback activity 從中取出存入 widget prefs */
    @SuppressLint("InlinedApi")
    fun requestPin(context: Context, presetName: String, singleVariant: Boolean = false) {
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

        // Pin 成功後系統會呼叫 PendingIntent，把 EXTRA_APPWIDGET_ID 塞進其 Intent。
        // 再轉交給 WidgetConfigActivity，並附帶預選 preset name。
        val callbackIntent = Intent(context, WidgetConfigActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(WidgetConfigActivity.EXTRA_PRESELECTED_PRESET, presetName)
        }
        val callback = PendingIntent.getActivity(
            context, presetName.hashCode(),
            callbackIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        awm.requestPinAppWidget(provider, null, callback)
    }
}
