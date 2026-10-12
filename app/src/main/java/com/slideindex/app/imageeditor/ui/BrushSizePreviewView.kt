/*
 * 移植自 GestureEVO（https://github.com/LumiaGG/GestureEVO，GPL-3.0-only）
 *   源文件：inspire/imageeditor/ui/BrushSizePreviewView.kt
 * 已按本项目的数据模型、主题与集成层改写（部分模块拆分为多文件、部分能力为本项目新增）；
 * 版权与许可声明见 THIRD_PARTY_NOTICES.md，文件级映射见 docs/ATTRIBUTIONS.md。
 */

package com.slideindex.app.imageeditor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class BrushSizePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.RED
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
        color = Color.WHITE
    }
    private var brushSizePx = resources.displayMetrics.density * 18f
    private var hollow = false

    fun setBrushPreview(sizePx: Float, color: Int, hollow: Boolean) {
        brushSizePx = sizePx
        fillPaint.color = color
        this.hollow = hollow
        invalidate()
    }

    fun setOutlineColor(color: Int) {
        outlinePaint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width - paddingLeft - paddingRight
        val height = height - paddingTop - paddingBottom
        if (width <= 0 || height <= 0) return
        val radius = min(brushSizePx, min(width, height).toFloat()) / 2f
        val cx = paddingLeft + width / 2f
        val cy = paddingTop + height / 2f
        if (!hollow) {
            canvas.drawCircle(cx, cy, radius, fillPaint)
        }
        canvas.drawCircle(cx, cy, radius, outlinePaint)
    }
}
