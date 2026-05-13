package com.notificationmaster.core

import android.app.Notification
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.notificationmaster.core.compat.ApiVersionHelper
import com.notificationmaster.core.dedup.ContentHashGenerator
import com.notificationmaster.data.db.entity.ActionEntity
import com.notificationmaster.data.db.entity.EventType
import com.notificationmaster.data.db.entity.NotificationEventEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * 通知資料提取器（Plan 2 Phase 9-7 精簡版）
 *
 * 移除舊 NotificationEntity 提取邏輯（extractNotification 與相關 50+ 欄位 helper）；
 * 只保留事件主體所需：
 * - [extractEvent]：產出 [NotificationEventEntity]（eventRawJson 由 [RawSerializer] 序列化）
 * - [extractActions]：FK = event_id 的 ActionEntity 列表
 *
 * Plan 2 Phase 13：移除原本 64KB 截斷上限（`MAX_JSON_SIZE` / `truncateJson`）。
 * - Plan 2 §3 設計初衷是「raw 接近 callback」，任意截斷會破壞 JSON 結構導致
 *   [NotificationSnapshotParser] 完全 parse 失敗 → 所有 chip / 衍生欄位回預設
 * - Bitmap / Icon / Drawable / RemoteViews 已由 RawSerializer 限制只輸出 metadata
 * - 大圖檔（LARGE_ICON / PICTURE / EXTRA_LARGE_ICON_BIG）已由 MediaExtractor 抽到 disk
 * - Android framework 對 Notification 本身有 ~1MB IPC enforcement，sbn 序列化不會無限大
 */
class NotificationExtractor(@Suppress("unused") private val context: Context) {

    /**
     * 產出 [NotificationEventEntity]：事件主體 + 索引投影 + eventRawJson 自包含完整 sbn 序列化。
     *
     * @param sbn 來源通知
     * @param eventType POSTED/UPDATED/INITIAL/REMOVED
     * @param eventTime 事件發生時間（service 端決定，通常 = captureTime）
     * @param captureTime 擷取進入 service 時間
     * @param isAudible RankingProcessor 推斷後傳入
     * @param likelyHeadsup RankingProcessor 推斷後傳入
     * @param removalReason REMOVED 時系統回傳的原因碼；其他事件 null
     */
    fun extractEvent(
        sbn: StatusBarNotification,
        eventType: EventType,
        eventTime: Long,
        captureTime: Long,
        isAudible: Boolean,
        likelyHeadsup: Boolean,
        removalReason: Int? = null
    ): NotificationEventEntity {
        val notification = sbn.notification
        val extras = notification.extras ?: Bundle()

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val template = extras.getString(Notification.EXTRA_TEMPLATE)

        val contentHash = ContentHashGenerator.generateHash(
            sbn.packageName, title, text, template
        )

        val rawJson = buildEventRawJson(sbn, eventType, captureTime, removalReason)

        return NotificationEventEntity(
            notificationKey = ApiVersionHelper.getNotificationKey(sbn),
            eventType = eventType,
            eventTime = eventTime,
            captureTime = captureTime,
            packageName = sbn.packageName,
            channelId = if (Build.VERSION.SDK_INT >= 26) notification.channelId else null,
            postTime = sbn.postTime,
            contentHash = contentHash,
            title = title,
            text = text,
            removalReason = removalReason,
            removalReasonCategory = removalReason?.let { ApiVersionHelper.categorizeRemovalReason(it) },
            isAudible = isAudible,
            likelyHeadsup = likelyHeadsup,
            eventRawJson = rawJson
        )
    }

    /**
     * 建構 eventRawJson：callback 元資料 + sbn（含 notification 子物件），**不含 ranking**。
     *
     * 結構對齊 Plan 2 §3：
     * ```
     * {
     *   "callbackType": "POSTED|UPDATED|INITIAL|REMOVED",
     *   "captureTime": <long>,
     *   "removalReason": <int?>,
     *   "sbn": { ... RawSerializer 序列化結果 ... }
     * }
     * ```
     */
    private fun buildEventRawJson(
        sbn: StatusBarNotification,
        eventType: EventType,
        captureTime: Long,
        removalReason: Int?
    ): String {
        val root = JSONObject()
        root.put("callbackType", eventType.name)
        root.put("captureTime", captureTime)
        if (removalReason != null) root.put("removalReason", removalReason)
        root.put("sbn", RawSerializer.serialize(sbn))
        return root.toString()
    }

    /**
     * 提取 Action 按鈕資訊
     *
     * Plan 2：FK 參數改名 [eventId]，呼叫端在 transaction 中取得 event id 後 copy(eventId = ...)
     * 或先傳 0 再 batch 補正。
     */
    @Suppress("DEPRECATION")
    fun extractActions(
        notification: Notification,
        eventId: Long,
        captureTime: Long = System.currentTimeMillis()
    ): List<ActionEntity> {
        val actions = notification.actions ?: return emptyList()

        return actions.mapIndexed { index, action ->
            val remoteInputs = action.remoteInputs

            val replyInput = remoteInputs?.firstOrNull { it.allowFreeFormInput }

            ActionEntity(
                eventId = eventId,
                actionIndex = index,
                title = action.title?.toString(),
                iconResId = action.icon,
                creatorPackage = action.actionIntent?.creatorPackage,
                semanticAction = if (Build.VERSION.SDK_INT >= 28) {
                    action.semanticAction
                } else 0,
                isReplyAction = replyInput != null,
                replyLabel = replyInput?.label?.toString(),
                replyChoices = replyInput?.choices?.map { it.toString() }?.let { JSONArray(it).toString() },
                remoteInputKey = replyInput?.resultKey,
                allowsFreeFormInput = replyInput?.allowFreeFormInput ?: false,
                isContextual = if (Build.VERSION.SDK_INT >= 29) {
                    action.isContextual
                } else false,
                isAuthenticationRequired = if (Build.VERSION.SDK_INT >= 31) {
                    action.isAuthenticationRequired
                } else false,
                captureTime = captureTime
            )
        }
    }
}
