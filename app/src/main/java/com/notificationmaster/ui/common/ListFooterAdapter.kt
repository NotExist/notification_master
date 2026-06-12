package com.notificationmaster.ui.common

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.notificationmaster.R

/**
 * W23p：清單尾端載入狀態 footer 的通用 adapter，配 ConcatAdapter 附掛在主 adapter 之後。
 *
 * 複用 timeline 的 footer layout（item_timeline_loading / pending_more / end），
 * 讓非 TimelineAdapter 體系的清單（如 Search）也用同一套「lazyload footer」視覺語彙
 * （原則：不同 fragment 同意圖 UI 一致）。
 */
class ListFooterAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    enum class State { NONE, LOADING, PENDING, END }

    var state: State = State.NONE
        set(value) {
            if (field == value) return
            val had = field != State.NONE
            val has = value != State.NONE
            field = value
            when {
                had && has -> notifyItemChanged(0)
                has -> notifyItemInserted(0)
                else -> notifyItemRemoved(0)
            }
        }

    override fun getItemCount(): Int = if (state == State.NONE) 0 else 1

    override fun getItemViewType(position: Int): Int = state.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layout = when (State.entries[viewType]) {
            State.LOADING -> R.layout.item_timeline_loading
            State.PENDING -> R.layout.item_timeline_pending_more
            else -> R.layout.item_timeline_end
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return object : RecyclerView.ViewHolder(view) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        // 三種 footer 皆靜態佈局，無需綁定
    }
}
