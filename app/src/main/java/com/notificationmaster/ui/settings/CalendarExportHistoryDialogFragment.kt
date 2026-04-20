package com.notificationmaster.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.notificationmaster.R
import com.notificationmaster.core.cache.AppLabelCache
import com.notificationmaster.export.calendar.CalendarExportLog
import com.notificationmaster.export.calendar.CalendarExportLog.Entry
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * 日曆即時匯出歷程檢視器
 *
 * 即時更新：collect CalendarExportLog.logFlow，新 entry 進入時自動刷新列表。
 */
class CalendarExportHistoryDialogFragment : androidx.fragment.app.DialogFragment() {

    private lateinit var adapter: HistoryAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var textEmpty: TextView
    private lateinit var textStarted: TextView

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val context = requireContext()
        val rootView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val hPad = (16 * resources.displayMetrics.density).toInt()
            val vPad = (8 * resources.displayMetrics.density).toInt()
            setPadding(hPad, vPad, hPad, 0)
        }

        textStarted = TextView(context).apply {
            text = getString(R.string.calendar_export_history_started,
                CalendarExportLog.formatRfc3339(CalendarExportLog.startedAt))
            androidx.core.widget.TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_LabelSmall)
            setTextColor(com.google.android.material.color.MaterialColors.getColor(
                context, android.R.attr.textColorSecondary, 0))
            val bm = (8 * resources.displayMetrics.density).toInt()
            setPadding(0, 0, 0, bm)
        }
        rootView.addView(textStarted)

        textEmpty = TextView(context).apply {
            text = getString(R.string.calendar_export_history_empty)
            androidx.core.widget.TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            gravity = android.view.Gravity.CENTER
            val vPad = (24 * resources.displayMetrics.density).toInt()
            setPadding(0, vPad, 0, vPad)
            visibility = View.GONE
        }
        rootView.addView(textEmpty)

        adapter = HistoryAdapter(context)
        recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = this@CalendarExportHistoryDialogFragment.adapter
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        rootView.addView(recyclerView)

        refreshList()

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.calendar_export_history_title)
            .setView(rootView)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.calendar_export_history_share) { _, _ ->
                shareHistory()
            }
            .setNegativeButton(R.string.calendar_export_history_clear) { _, _ ->
                CalendarExportLog.clear()
                refreshList()
            }
            .create()

        // 即時更新
        lifecycleScope.launch {
            CalendarExportLog.logFlow
                .drop(1)
                .collect { refreshList() }
        }

        return dialog
    }

    private fun refreshList() {
        val entries = CalendarExportLog.getEntries().reversed()
        adapter.submitList(entries)
        textEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun shareHistory() {
        val text = CalendarExportLog.exportAsText()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.calendar_export_history_share)))
    }

    // === Adapter ===

    private class HistoryAdapter(
        private val context: android.content.Context
    ) : ListAdapter<Entry, HistoryAdapter.ViewHolder>(EntryDiffCallback()) {

        class ViewHolder(val layout: LinearLayout) : RecyclerView.ViewHolder(layout) {
            val textTime: TextView = layout.getChildAt(0) as TextView
            val textContent: TextView = layout.getChildAt(1) as TextView
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val density = parent.resources.displayMetrics.density
            val bm = (4 * density).toInt()
            val layout = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = bm }
            }
            val textTime = TextView(parent.context).apply {
                androidx.core.widget.TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_LabelSmall)
                setTextColor(com.google.android.material.color.MaterialColors.getColor(
                    parent.context, android.R.attr.textColorSecondary, 0))
            }
            val textContent = TextView(parent.context).apply {
                androidx.core.widget.TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            }
            layout.addView(textTime)
            layout.addView(textContent)
            return ViewHolder(layout)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = getItem(position)
            holder.textTime.text = CalendarExportLog.formatRfc3339(entry.timestamp)
            val appLabel = AppLabelCache.getLabel(context, entry.packageName)
            val display = if (appLabel != entry.packageName) "$appLabel (${entry.packageName})" else entry.packageName
            holder.textContent.text = buildString {
                append("[${entry.outcome}] $display")
                entry.detail?.let { append("\n$it") }
            }
        }

        class EntryDiffCallback : DiffUtil.ItemCallback<Entry>() {
            override fun areItemsTheSame(a: Entry, b: Entry) = a.timestamp == b.timestamp
            override fun areContentsTheSame(a: Entry, b: Entry) = a == b
        }
    }
}
