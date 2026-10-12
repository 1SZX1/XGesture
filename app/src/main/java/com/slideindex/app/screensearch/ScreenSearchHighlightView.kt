/*
 * 移植自 GestureEVO（https://github.com/LumiaGG/GestureEVO，GPL-3.0-only）
 *   源文件：screen_search/ScreenSearchHighlightView.kt
 * 已按本项目的数据模型、主题与集成层改写（部分模块拆分为多文件、部分能力为本项目新增）；
 * 版权与许可声明见 THIRD_PARTY_NOTICES.md，文件级映射见 docs/ATTRIBUTIONS.md。
 */

package com.slideindex.app.screensearch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

class ScreenSearchHighlightView(context: Context) : View(context) {
    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
    }

    private val rects = ArrayList<Rect>()
    private val density = resources.displayMetrics.density
    private val outerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 5f
        color = Color.argb(230, 0, 0, 0)
    }
    private val innerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 2.5f
        color = Color.argb(255, 255, 255, 255)
        pathEffect = DashPathEffect(floatArrayOf(density * 10f, density * 6f), 0f)
    }
    private val accentStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 2.5f
        color = Color.argb(255, 33, 150, 243)
        pathEffect = DashPathEffect(floatArrayOf(density * 10f, density * 6f), density * 8f)
    }

    fun showRects(screenRects: List<Rect>) {
        rects.clear()
        rects.addAll(screenRects)
        visibility = VISIBLE
        invalidate()
    }

    fun hideRects() {
        rects.clear()
        visibility = GONE
        invalidate()
    }

    fun clearRects() = hideRects()

    fun hasRects(): Boolean = rects.isNotEmpty()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (rect in rects) {
            canvas.drawRect(rect, outerStrokePaint)
            canvas.drawRect(rect, innerStrokePaint)
            canvas.drawRect(rect, accentStrokePaint)
        }
    }
}
