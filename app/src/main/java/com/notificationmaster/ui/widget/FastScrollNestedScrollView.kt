package com.notificationmaster.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.core.widget.NestedScrollView

/**
 * NestedScrollView 的子類別，支援觸摸拖曳 scrollbar 快速捲動。
 *
 * 原生 NestedScrollView.onTouchEvent() 不委派給 View 基類處理 scrollbar thumb tracking，
 * 因此自行偵測觸摸在 scrollbar 區域時進行比例捲動。
 */
class FastScrollNestedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {

    /** 是否正在拖曳 scrollbar */
    private var isDraggingScrollbar = false

    /** scrollbar 觸摸感應區寬度 (dp) */
    private val scrollbarTouchWidthDp = 24f

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isScrollable() && isTouchOnScrollbarTrack(ev.x)) {
                    isDraggingScrollbar = true
                    scrollToFraction(ev.y / height)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    awakenScrollBars()
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDraggingScrollbar) {
                    scrollToFraction(ev.y / height)
                    awakenScrollBars()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDraggingScrollbar) {
                    isDraggingScrollbar = false
                    return true
                }
            }
        }
        return super.onTouchEvent(ev)
    }

    /** 內容是否超出可視區域（可捲動） */
    private fun isScrollable(): Boolean {
        val child = getChildAt(0) ?: return false
        return child.height > height
    }

    /** 觸摸位置是否在 scrollbar 感應區（螢幕右側邊緣） */
    private fun isTouchOnScrollbarTrack(x: Float): Boolean {
        val touchWidthPx = scrollbarTouchWidthDp * resources.displayMetrics.density
        return if (layoutDirection == LAYOUT_DIRECTION_RTL) {
            x <= touchWidthPx
        } else {
            x >= width - touchWidthPx
        }
    }

    /** 依觸摸位置比例捲動到對應位置 */
    private fun scrollToFraction(fraction: Float) {
        val child = getChildAt(0) ?: return
        val scrollRange = child.height - height
        if (scrollRange > 0) {
            val targetY = (fraction.coerceIn(0f, 1f) * scrollRange).toInt()
            scrollTo(0, targetY)
        }
    }
}
