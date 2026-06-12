package com.notificationmaster.export.archive

import android.content.Context
import android.content.pm.ApplicationInfo
import com.notificationmaster.data.db.NotificationDatabase
import com.notificationmaster.data.db.entity.AppSourceEntity
import com.notificationmaster.data.db.entity.ChannelEntity

/**
 * W23q：以 events 表為 SSOT 重建 archive 聚合（app_sources / channels）。
 *
 * 背景：app_sources / channels 的 notification_count 由 service 在捕獲當下增量維護
 * （gate：POSTED/UPDATED/INITIAL +1、排除 REMOVED），且不在封存匯出範圍內。
 * 匯入封存後 events 表多了大量歷史列，但聚合表完全不知情 → archive 頁面只反映
 * 本機新捕獲的事件。
 *
 * 重建語意：count = `COUNT(*) WHERE event_type != 'REMOVED'`（與 service gate /
 * EventFilterSqlBuilder 統一排除一致；活捕事件 row 與增量 +1 一一對應，重算結果
 * 等價且冪等）。既有列覆寫 count、firstSeen 取較早、lastUpdated 取較晚；
 * 新列以 PackageManager 盡力補 appName / isSystemApp（未安裝則標 isUninstalled），
 * channel 中繼資料（channelName 等）留空，照舊由 RANKING 事件 / 下拉更新補齊。
 */
object ArchiveAggregateRebuilder {

    /** @return Pair(重建後 app 數, channel 數) — 供匯入結果 dialog 顯示 */
    suspend fun rebuild(context: Context, database: NotificationDatabase): Pair<Int, Int> {
        val eventDao = database.notificationEventDao()
        val appDao = database.appSourceDao()
        val channelDao = database.channelDao()
        val pm = context.packageManager

        val pkgAggs = eventDao.getPackageAggregatesSync()
        for (agg in pkgAggs) {
            val existing = appDao.getByPackageName(agg.packageName)
            if (existing != null) {
                if (existing.notificationCount != agg.cnt ||
                    existing.firstSeen > agg.firstTime ||
                    existing.lastUpdated < agg.lastTime
                ) {
                    appDao.update(existing.copy(
                        notificationCount = agg.cnt,
                        firstSeen = minOf(existing.firstSeen, agg.firstTime),
                        lastUpdated = maxOf(existing.lastUpdated, agg.lastTime)
                    ))
                }
            } else {
                val appInfo = try {
                    pm.getApplicationInfo(agg.packageName, 0)
                } catch (e: Exception) {
                    null
                }
                appDao.insert(AppSourceEntity(
                    packageName = agg.packageName,
                    appName = appInfo?.let { pm.getApplicationLabel(it).toString() },
                    versionName = null,
                    versionCode = null,
                    iconPath = null,
                    firstSeen = agg.firstTime,
                    lastUpdated = agg.lastTime,
                    notificationCount = agg.cnt,
                    isSystemApp = appInfo?.let { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 } ?: false,
                    isDisabled = false,
                    isUninstalled = appInfo == null
                ))
            }
        }

        // channels 的 FK 需要 app_sources.id — 上面補完後重撈一次對映
        val appIdByPkg = appDao.getAll().associate { it.packageName to it.id }
        val chAggs = eventDao.getChannelAggregatesSync()
        for (agg in chAggs) {
            val existing = channelDao.getByPackageAndChannelId(agg.packageName, agg.channelId)
            if (existing != null) {
                if (existing.notificationCount != agg.cnt ||
                    existing.firstSeen > agg.firstTime ||
                    existing.lastUpdated < agg.lastTime
                ) {
                    channelDao.update(existing.copy(
                        notificationCount = agg.cnt,
                        firstSeen = minOf(existing.firstSeen, agg.firstTime),
                        lastUpdated = maxOf(existing.lastUpdated, agg.lastTime)
                    ))
                }
            } else {
                val appSourceId = appIdByPkg[agg.packageName] ?: continue
                channelDao.insert(ChannelEntity(
                    appSourceId = appSourceId,
                    packageName = agg.packageName,
                    channelId = agg.channelId,
                    channelName = null,
                    description = null,
                    importance = -1,
                    groupId = null,
                    showBadge = true,
                    canBubble = false,
                    soundUri = null,
                    vibratePattern = null,
                    lightColor = 0,
                    lockScreenVisibility = android.app.Notification.VISIBILITY_PRIVATE,
                    isBlocked = false,
                    firstSeen = agg.firstTime,
                    lastUpdated = agg.lastTime,
                    notificationCount = agg.cnt
                ))
            }
        }
        return pkgAggs.size to chAggs.size
    }
}
