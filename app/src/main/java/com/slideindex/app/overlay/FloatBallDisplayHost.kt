package com.slideindex.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView

/**
 * Display 层根容器：线条仍走 Compose，球体与准星由原生 View 直接 layout，避免拖拽时整层重组。
 */
@SuppressLint("ViewConstructor")
internal class FloatBallDisplayHost(
    context: Context,
    private val lineChromeOwner: OverlayComposeOwner,
    val ballIconView: FloatBallIconView,
    val cursorPreviewView: FloatBallCursorPreviewView
) : FrameLayout(context) {

    val lineChromeComposeView: ComposeView

    private var lastBallLeft = 0
    private var lastBallTop = 0
    private var lastBallWidth = 0
    private var lastBallHeight = 0
    private var hasBallLayout = false

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        OverlayCompose.bindOwners(this, lineChromeOwner)

        lineChromeComposeView = OverlayCompose.createComposeView(context, lineChromeOwner)
        addView(lineChromeComposeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        ballIconView.layoutParams = LayoutParams(0, 0)
        addView(ballIconView)

        cursorPreviewView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(cursorPreviewView)
    }

    /** 原生定位球体；热路径直接 layout，不触发整树 requestLayout。 */
    fun layoutBall(left: Int, top: Int, widthPx: Int, heightPx: Int) {
        if (
            hasBallLayout &&
            left == lastBallLeft &&
            top == lastBallTop &&
            widthPx == lastBallWidth &&
            heightPx == lastBallHeight
        ) return
        val sizeChanged = widthPx != lastBallWidth || heightPx != lastBallHeight
        hasBallLayout = widthPx > 0 && heightPx > 0
        lastBallLeft = left
        lastBallTop = top
        lastBallWidth = widthPx
        lastBallHeight = heightPx

        if (width <= 0 || height <= 0) {
            requestLayout()
            return
        }
        if (sizeChanged) {
            ballIconView.measure(
                MeasureSpec.makeMeasureSpec(widthPx, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(heightPx, MeasureSpec.EXACTLY)
            )
        }
        ballIconView.layout(left, top, left + widthPx, top + heightPx)
    }

    fun setBallVisible(visible: Boolean) {
        ballIconView.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun ballLayoutRect(): Rect = Rect(
        lastBallLeft,
        lastBallTop,
        lastBallLeft + lastBallWidth,
        lastBallTop + lastBallHeight
    )

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val fullWidthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val fullHeightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        lineChromeComposeView.measure(fullWidthSpec, fullHeightSpec)
        cursorPreviewView.measure(fullWidthSpec, fullHeightSpec)
        if (hasBallLayout && lastBallWidth > 0 && lastBallHeight > 0) {
            ballIconView.measure(
                MeasureSpec.makeMeasureSpec(lastBallWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(lastBallHeight, MeasureSpec.EXACTLY)
            )
        } else {
            ballIconView.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        lineChromeComposeView.layout(0, 0, width, height)
        cursorPreviewView.layout(0, 0, width, height)
        if (hasBallLayout && lastBallWidth > 0 && lastBallHeight > 0) {
            ballIconView.layout(
                lastBallLeft,
                lastBallTop,
                lastBallLeft + lastBallWidth,
                lastBallTop + lastBallHeight
            )
        } else {
            ballIconView.layout(0, 0, 0, 0)
        }
    }

    fun dispose() {
        OverlayCompose.teardownOverlayCompose(lineChromeComposeView, lineChromeOwner)
    }
}
