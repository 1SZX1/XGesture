package com.slideindex.app.screencontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.FrameLayout

/**
 * 屏幕控制面板的投影宿主。
 *
 * 悬浮窗根视图不能直接用 `View.elevation`：AOSP 上画不出窗口阴影，部分 ROM 还会把
 * 根视图海拔当成整个窗口的高度，给悬浮窗套一层巨大的系统阴影（用户反馈过
 * “面板下方阴影很大”）。这里改成窗口四周留 [shadowPadPx] 的 padding，
 * 由宿主自己用多层扩散圆角矩形画投影，子视图（真正的面板）放在 padding 内。
 *
 * 视觉参数与任务切换器的 `drawElevationShadow` 对齐：多层扩散、8dp 模糊、深色半透明。
 */
internal class ScreenControlShadowHost(
    context: Context,
    private val shadowPadPx: Int,
) : FrameLayout(context) {

    /** 挂起成球时投影是圆的；否则跟着面板的 18dp 圆角走。 */
    var minimized: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val panelCornerPx = 18f * density
    private val ballSizePx = ScreenControlPanelView.BALL_SIZE_DP * density

    init {
        setPadding(shadowPadPx, shadowPadPx, shadowPadPx, shadowPadPx)
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }

    override fun dispatchDraw(canvas: Canvas) {
        drawShadow(canvas)
        super.dispatchDraw(canvas)
    }

    private fun drawShadow(canvas: Canvas) {
        val left = shadowPadPx.toFloat()
        val top = shadowPadPx.toFloat()
        val right = width - shadowPadPx.toFloat()
        val bottom = height - shadowPadPx.toFloat()
        if (right <= left || bottom <= top) return

        val layers = 4
        val blur = 3f * density
        val shadowDy = 0f
        val baseAlpha = 26
        val halfBall = ballSizePx / 2f
        val centerX = width / 2f
        val centerY = height / 2f + shadowDy
        for (layer in layers downTo 1) {
            val fraction = layer / layers.toFloat()
            val spread = blur * fraction * 0.8f
            val alpha = (baseAlpha * fraction * fraction / layers).toInt().coerceIn(1, 255)
            shadowPaint.color = Color.argb(alpha, 0, 0, 0)
            if (minimized) {
                canvas.drawOval(
                    centerX - halfBall - spread,
                    centerY - halfBall - spread,
                    centerX + halfBall + spread,
                    centerY + halfBall + spread,
                    shadowPaint,
                )
            } else {
                val radius = panelCornerPx + spread * 0.2f
                canvas.drawRoundRect(
                    left - spread,
                    top - spread + shadowDy,
                    right + spread,
                    bottom + spread + shadowDy,
                    radius,
                    radius,
                    shadowPaint,
                )
            }
        }
    }
}
