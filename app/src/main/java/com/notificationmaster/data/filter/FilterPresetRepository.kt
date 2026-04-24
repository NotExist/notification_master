package com.notificationmaster.data.filter

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONArray

/**
 * 使用者命名 Preset 的 CRUD；系統 preset 硬編碼由 Repository 直接提供。
 *
 * 儲存格式：SharedPreferences key = `filter_presets_v1`，value = JSON array of [FilterPreset]。
 * 同一 SharedPreferences 檔（app 預設 prefs）。
 */
class FilterPresetRepository private constructor(private val prefs: SharedPreferences) {

    companion object {
        private const val PREFS_NAME = "notification_master_prefs"
        private const val KEY_USER_PRESETS = "filter_presets_v1"

        // 系統 preset name（不可與使用者命名衝突；此三者視為保留字）
        const val SYSTEM_RECENT_AUDIBLE = "RecentAudible"
        const val SYSTEM_RECENT_HEADSUP = "RecentHeadsup"
        const val SYSTEM_RECENT_DISMISSED = "RecentDismissed"

        val SYSTEM_NAMES = setOf(SYSTEM_RECENT_AUDIBLE, SYSTEM_RECENT_HEADSUP, SYSTEM_RECENT_DISMISSED)

        @Volatile private var instance: FilterPresetRepository? = null

        fun getInstance(context: Context): FilterPresetRepository =
            instance ?: synchronized(this) {
                instance ?: FilterPresetRepository(
                    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }

    /** 內建系統 preset，name 與顯示名解耦（顯示名由 UI 端 i18n） */
    val systemPresets: List<FilterPreset> = listOf(
        FilterPreset(SYSTEM_RECENT_AUDIBLE, EventFilterSpec.RecentAudible, 0L, 0L, PresetSource.SYSTEM),
        FilterPreset(SYSTEM_RECENT_HEADSUP, EventFilterSpec.RecentHeadsup, 0L, 0L, PresetSource.SYSTEM),
        FilterPreset(SYSTEM_RECENT_DISMISSED, EventFilterSpec.RecentDismissed, 0L, 0L, PresetSource.SYSTEM)
    )

    /** 系統 + 使用者 preset 的 Flow，響應 SharedPreferences 變動 */
    fun observePresets(): Flow<List<FilterPreset>> = callbackFlow {
        fun emit() { trySend(getAllPresets()) }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_USER_PRESETS) emit()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        emit()
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    /** 同步取得系統 + 使用者 preset（Widget 用） */
    fun getAllPresets(): List<FilterPreset> = systemPresets + getUserPresets()

    fun getUserPresets(): List<FilterPreset> {
        val json = prefs.getString(KEY_USER_PRESETS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull {
                try { FilterPreset.fromJson(arr.getJSONObject(it)) } catch (_: Exception) { null }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getPreset(name: String): FilterPreset? =
        systemPresets.firstOrNull { it.name == name } ?: getUserPresets().firstOrNull { it.name == name }

    /** upsert；系統 preset 不可覆寫（name 若命中系統保留字會拋例外） */
    fun savePreset(preset: FilterPreset) {
        require(preset.source == PresetSource.USER) { "Cannot save system preset" }
        require(preset.name !in SYSTEM_NAMES) { "Preset name '${preset.name}' is reserved" }
        require(preset.name.isNotBlank()) { "Preset name must not be blank" }

        val users = getUserPresets().toMutableList()
        val idx = users.indexOfFirst { it.name == preset.name }
        if (idx >= 0) users[idx] = preset else users.add(preset)
        persist(users)
    }

    fun deletePreset(name: String) {
        require(name !in SYSTEM_NAMES) { "System preset cannot be deleted" }
        val users = getUserPresets().filterNot { it.name == name }
        persist(users)
    }

    fun renamePreset(oldName: String, newName: String) {
        require(oldName !in SYSTEM_NAMES) { "System preset cannot be renamed" }
        require(newName !in SYSTEM_NAMES) { "Preset name '$newName' is reserved" }
        require(newName.isNotBlank()) { "Preset name must not be blank" }

        val users = getUserPresets().toMutableList()
        val idx = users.indexOfFirst { it.name == oldName }
        require(idx >= 0) { "Preset not found: $oldName" }
        users[idx] = users[idx].copy(name = newName, updatedAt = System.currentTimeMillis())
        persist(users)
    }

    private fun persist(users: List<FilterPreset>) {
        val arr = JSONArray(users.map { it.toJson() })
        prefs.edit().putString(KEY_USER_PRESETS, arr.toString()).apply()
    }
}
