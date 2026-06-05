package com.notificationmaster.ui.timeline

import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView

/**
 * Disable add / change 的 alpha 動畫，保留 remove / move。
 *
 * DefaultItemAnimator.animateAdd 起始 setItemAlpha(view, 0) → 動畫終值 1.0；
 * animateChange cross-fade 新 ViewHolder 同樣 0 → 1.0。兩者都會覆寫
 * TimelineAdapter.bind() 設的 isRemoved → 0.55 alpha。
 *
 * 詳見 memory/pitfall_recyclerview_alpha_animator.md。
 */
class TimelineItemAnimator : DefaultItemAnimator() {
    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        dispatchAddFinished(holder)
        return false
    }

    override fun animateChange(
        oldHolder: RecyclerView.ViewHolder,
        newHolder: RecyclerView.ViewHolder,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int
    ): Boolean {
        if (oldHolder == newHolder) {
            dispatchAnimationFinished(oldHolder)
        } else {
            dispatchAnimationFinished(oldHolder)
            dispatchAnimationFinished(newHolder)
        }
        return false
    }
}
