package com.notificationmaster.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 從 NotificationEventEntity.eventRawJson 解析出的結構化 snapshot（Plan 2 Phase 3）
 *
 * 設計：保留 root JSONObject 作為 single source of truth，常用欄位透過 lazy getter
 * 提供型別安全存取；不為每個欄位 typed-mirror，避免維護負擔且自然支援未來新增欄位。
 *
 * 用途：
 * - Detail 頁摘要區：渲染當下 anchor event 的快照
 * - EventDiffer：read-time 算前後事件 diff（直接走 JSON tree 比對，不必經 Snapshot）
 *
 * 由 [NotificationSnapshotParser.parse] 建構。
 */
data class NotificationSnapshot(
    /** 完整 raw JSON root（Service 寫入時的 RawSerializer 輸出） */
    val raw: JSONObject
) {
    val callbackType: String? get() = raw.optStringOrNull("callbackType")
    val captureTime: Long get() = raw.optLong("captureTime")
    val removalReason: Int? get() = raw.optIntOrNull("removalReason")

    /** sbn 子物件（StatusBarNotification 序列化結果） */
    val sbn: JSONObject? get() = raw.optJSONObject("sbn")

    /** sbn 內的 notification 子物件（Notification 序列化結果） */
    val notification: JSONObject? get() = sbn?.optJSONObject("notification")

    // === 常用 sbn 衍生欄位 ===

    val notificationKey: String? get() = sbn?.optStringOrNull("key")
    val packageName: String? get() = sbn?.optStringOrNull("packageName")
    val sbnId: Int? get() = sbn?.optIntOrNull("id")
    val tag: String? get() = sbn?.optStringOrNull("tag")
    val postTime: Long? get() = sbn?.optLongOrNull("postTime")
    val isClearable: Boolean? get() = sbn?.optBooleanOrNull("isClearable")
    val overrideGroupKey: String? get() = sbn?.optStringOrNull("overrideGroupKey")
    val uid: Int? get() = sbn?.optIntOrNull("uid")

    // === 常用 notification 衍生欄位 ===

    val flags: Int? get() = notification?.optIntOrNull("flags")
    val channelId: String? get() = notification?.optStringOrNull("channelId")
    val category: String? get() = notification?.optStringOrNull("category")
    val color: Int? get() = notification?.optIntOrNull("color")
    val visibility: Int? get() = notification?.optIntOrNull("visibility")
    val number: Int? get() = notification?.optIntOrNull("number")
    val groupKey: String? get() = notification?.optStringOrNull("group")

    /** Notification.extras Bundle 序列化結果 */
    val extras: JSONObject? get() = notification?.optJSONObject("extras")

    // === Notification.extras 內常用 KEY（android.* 開頭由系統定義） ===

    val title: String? get() = extras?.optStringOrNull("android.title")
    val text: String? get() = extras?.optStringOrNull("android.text")
    val bigTitle: String? get() = extras?.optStringOrNull("android.title.big")
    val bigText: String? get() = extras?.optStringOrNull("android.bigText")
    val subText: String? get() = extras?.optStringOrNull("android.subText")
    val infoText: String? get() = extras?.optStringOrNull("android.infoText")
    val summaryText: String? get() = extras?.optStringOrNull("android.summaryText")
    val tickerText: String? get() = notification?.optStringOrNull("tickerText")

    val progress: Int? get() = extras?.optIntOrNull("android.progress")
    val progressMax: Int? get() = extras?.optIntOrNull("android.progressMax")
    val progressIndeterminate: Boolean? get() = extras?.optBooleanOrNull("android.progressIndeterminate")

    val actions: JSONArray? get() = notification?.optJSONArray("actions")
    val sound: String? get() = notification?.optStringOrNull("sound")
}

// === JSONObject extension：null 返回（標準 opt* 把 null 視為「不存在」並回傳預設值，
// 但我們需要區分「不存在」和「真實值是 0/false/空字串」） ===

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
    if (has(key) && !isNull(key)) optBoolean(key) else null
