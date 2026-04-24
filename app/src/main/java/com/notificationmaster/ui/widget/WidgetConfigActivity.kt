package com.notificationmaster.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.notificationmaster.R
import com.notificationmaster.core.prefs.AppPreferences
import com.notificationmaster.data.filter.EventFilterSpec
import com.notificationmaster.data.filter.FilterPreset
import com.notificationmaster.data.filter.FilterPresetRepository
import com.notificationmaster.data.filter.PresetSource
import com.notificationmaster.ui.filter.FilterEditorBottomSheet

/**
 * Widget 設定 Activity
 *
 * 新 widget：列出系統 + 使用者 preset + 自訂篩選選項
 * 重新設定（API 28+ reconfigurable）：預選目前設定
 */
class WidgetConfigActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var presetRepo: FilterPresetRepository

    companion object {
        /** Pin 流程帶入的預選 preset name（WidgetPinner 使用） */
        const val EXTRA_PRESELECTED_PRESET = "preselected_preset"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setResult(RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        presetRepo = FilterPresetRepository.getInstance(this)

        // 預選：優先 requestPin 帶入的 preset；其次重設時的現有設定
        val preselected = intent?.getStringExtra(EXTRA_PRESELECTED_PRESET)
            ?: AppPreferences.getWidgetPresetName(this, appWidgetId)

        showPresetChooser(preselected)
    }

    private fun showPresetChooser(preselectedName: String?) {
        val presets = presetRepo.getAllPresets()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (resources.displayMetrics.density * 16).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val rg = RadioGroup(this)
        container.addView(rg)

        val presetIds = mutableListOf<Pair<Int, String>>() // viewId → presetName

        // 系統區段
        addSectionHeader(container, getString(R.string.widget_config_section_system))
        var idCounter = 1
        presets.filter { it.source == PresetSource.SYSTEM }.forEach { p ->
            val rb = buildRadioButton(idCounter++, presetDisplayName(p))
            rg.addView(rb)
            presetIds.add(rb.id to p.name)
        }

        // 使用者區段
        val userPresets = presets.filter { it.source == PresetSource.USER }
        if (userPresets.isNotEmpty()) {
            addSectionHeader(container, getString(R.string.widget_config_section_user))
            userPresets.forEach { p ->
                val rb = buildRadioButton(idCounter++, p.name)
                rg.addView(rb)
                presetIds.add(rb.id to p.name)
            }
        }

        // 進階：自訂篩選
        addSectionHeader(container, getString(R.string.widget_config_section_custom))
        val customRb = buildRadioButton(idCounter++, getString(R.string.widget_config_custom))
        rg.addView(customRb)
        val customId = customRb.id

        // 預選
        val preselectedId = presetIds.firstOrNull { it.second == preselectedName }?.first
            ?: presetIds.firstOrNull()?.first
            ?: customId
        rg.check(preselectedId)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.widget_config_show_what)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val checked = rg.checkedRadioButtonId
                if (checked == customId) {
                    openCustomEditor()
                } else {
                    val name = presetIds.firstOrNull { it.first == checked }?.second
                    val preset = name?.let { presetRepo.getPreset(it) }
                    if (preset != null) {
                        saveAndFinish(preset.spec, preset.name, presetDisplayName(preset))
                    } else {
                        finish()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun addSectionHeader(parent: LinearLayout, text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val m = (resources.displayMetrics.density * 8).toInt()
            setPadding(0, m, 0, m / 2)
        }
        parent.addView(tv)
    }

    private fun buildRadioButton(id: Int, label: String): RadioButton = RadioButton(this).apply {
        this.id = id
        this.text = label
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        layoutParams = lp
    }

    private fun openCustomEditor() {
        val existingSpec = AppPreferences.getWidgetSpec(this, appWidgetId)
            ?.let { runCatching { EventFilterSpec.fromJsonString(it) }.getOrNull() }
            ?: EventFilterSpec.RecentAudible

        FilterEditorBottomSheet.show(
            fm = supportFragmentManager,
            initial = existingSpec,
            editingPresetName = null,
            onApply = { spec ->
                saveAndFinish(spec, presetName = null, label = deriveLabel(spec))
            },
            onSaveAsPreset = { name, spec ->
                presetRepo.savePreset(FilterPreset(
                    name = name,
                    spec = spec,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    source = PresetSource.USER
                ))
                saveAndFinish(spec, presetName = name, label = name)
            }
        )
    }

    private fun presetDisplayName(p: FilterPreset): String = when (p.name) {
        FilterPresetRepository.SYSTEM_RECENT_AUDIBLE -> getString(R.string.preset_recent_audible)
        FilterPresetRepository.SYSTEM_RECENT_HEADSUP -> getString(R.string.preset_recent_headsup)
        FilterPresetRepository.SYSTEM_RECENT_DISMISSED -> getString(R.string.preset_recent_dismissed)
        else -> p.name
    }

    private fun deriveLabel(spec: EventFilterSpec): String = when {
        spec.isAudible == true -> getString(R.string.preset_recent_audible)
        spec.likelyHeadsup == true -> getString(R.string.preset_recent_headsup)
        spec.isRemoved == true -> getString(R.string.preset_recent_dismissed)
        spec.packageName != null -> spec.packageName
        else -> getString(R.string.filter_editor_title)
    }

    private fun saveAndFinish(spec: EventFilterSpec, presetName: String?, label: String) {
        AppPreferences.setWidgetSpecSync(this, appWidgetId, spec.toJsonString())
        AppPreferences.setWidgetPresetNameSync(this, appWidgetId, presetName)
        AppPreferences.setWidgetLabelSync(this, appWidgetId, label)

        val appWidgetManager = AppWidgetManager.getInstance(this)
        val providerClassName = appWidgetManager.getAppWidgetInfo(appWidgetId)?.provider?.className
        when (providerClassName) {
            NotificationWidgetProvider::class.java.name ->
                NotificationWidgetProvider.updateWidget(this, appWidgetManager, appWidgetId)
            NotificationSingleWidgetProvider::class.java.name ->
                NotificationSingleWidgetProvider.updateWidget(this, appWidgetManager, appWidgetId)
            else -> {
                NotificationWidgetProvider.updateWidget(this, appWidgetManager, appWidgetId)
                NotificationSingleWidgetProvider.updateWidget(this, appWidgetManager, appWidgetId)
            }
        }

        // 清除舊 matchers 架構 key（若有），避免混淆
        AppPreferences.prefsBridgeRemoveMatchers(this, appWidgetId)

        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }
}
