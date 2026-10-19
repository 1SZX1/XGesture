package com.slideindex.app.screencontrol

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.slideindex.app.R
import com.slideindex.app.overlay.OverlayPanelTheme
import com.slideindex.app.overlay.setupBackgroundBlur
import kotlin.math.hypot
import kotlin.math.roundToInt

internal enum class ScreenControlDirection { UP, DOWN, LEFT, RIGHT }

internal enum class ScreenControlNav { BACK, RECENTS, HOME }

/**
 * 「屏幕控制」悬浮面板。
 *
 * 视觉：半透明磨砂底 + 细描边 + 浮动按键；触控板区域只做很淡的点阵和中心圆环，
 * 不再是一整块灰色矩形。
 *
 * 交互：三点拖动条 → 触控板 → 方向键（2×2）→ 导航键（1×4）→ 底部上滑关闭条。
 * 右下角圆弧负责缩放；「挂起」后整个窗口缩成可拖动的 72dp 圆球。
 */
internal class ScreenControlPanelView(
    context: Context,
    private val listener: Listener,
    private val blurEnabled: Boolean = false,
) : FrameLayout(context) {

    interface Listener {
        fun onPointerMove(dx: Float, dy: Float)
        fun onTap()
        fun onDoubleTap()
        fun onLongPressStart()
        fun onLongPressMove(dx: Float, dy: Float)
        fun onLongPressEnd()
        fun onDirection(direction: ScreenControlDirection, longPress: Boolean)
        fun onDirectionEnd()
        fun onNav(action: ScreenControlNav)
        fun onMinimize()
        fun onRestore()
        fun onClose()
        fun onMovePanel(dx: Float, dy: Float)
        fun onPanelMoveFinished()
        fun onResizeStart()
        fun onResize(dx: Float, dy: Float)
        fun onResizeEnd()
        fun onBallMove(dx: Float, dy: Float)
        fun onBallMoveFinished()
        fun onDismissDrag(dy: Float)
        fun onDismissRelease(dismiss: Boolean)
    }

    private val density = resources.displayMetrics.density
    private val themeColors = OverlayPanelTheme.colors(context)

    private val content: LinearLayout
    private val header: FrameLayout
    private val pad: PadView
    private val resizeHandle: ResizeHandleView
    private val ball: BallView
    private var minimized = false

    init {
        background = if (blurEnabled) null else panelBackground()
        clipToOutline = false
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 24f * density)
            }
        }
        elevation = 16f * density

        content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7f), dp(4f), dp(7f), dp(0f))
        }
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        header = FrameLayout(context).apply {
            setOnTouchListener(HeaderDragListener())
        }
        header.addView(
            DotsPill(context),
            LayoutParams(dp(140f), dp(14f)).apply { gravity = Gravity.CENTER },
        )
        content.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(16f)))

        pad = PadView(context, object : PadListener {
            override fun onMove(dx: Float, dy: Float) = listener.onPointerMove(dx, dy)
            override fun onTap() = listener.onTap()
            override fun onDoubleTap() = listener.onDoubleTap()
            override fun onLongPressStart() = listener.onLongPressStart()
            override fun onLongPressMove(dx: Float, dy: Float) = listener.onLongPressMove(dx, dy)
            override fun onLongPressEnd() = listener.onLongPressEnd()
        }).apply {
            background = padBackground()
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 20f * density)
                }
            }
        }
        content.addView(
            pad,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(6f)
                bottomMargin = dp(8f)
            },
        )

        content.addView(
            directionRow(
                ScreenControlDirection.UP,
                ScreenControlDirection.DOWN,
                ScreenControlDirection.LEFT,
                ScreenControlDirection.RIGHT,
            ),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34f)),
        )
        content.addView(
            navRow(),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34f)).apply {
                topMargin = dp(4f)
            },
        )
        content.addView(
            BottomBarView(
                context = context,
                onDragProgress = { dy -> listener.onDismissDrag(dy) },
                onDismiss = { listener.onDismissRelease(true) },
                onCancel = { listener.onDismissRelease(false) },
            ),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(18f)).apply {
                topMargin = dp(0f)
            },
        )

        resizeHandle = ResizeHandleView(context, object : ResizeListener {
            override fun onStart() = listener.onResizeStart()
            override fun onResize(dx: Float, dy: Float) = listener.onResize(dx, dy)
            override fun onEnd() = listener.onResizeEnd()
        })
        addView(
            resizeHandle,
            LayoutParams(dp(24f), dp(24f)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, 0, 0)
            },
        )

        ball = BallView(context, object : BallListener {
            override fun onMove(dx: Float, dy: Float) = listener.onBallMove(dx, dy)
            override fun onMoveFinished() = listener.onBallMoveFinished()
            override fun onRestore() = listener.onRestore()
        }).apply {
            visibility = GONE
        }
        addView(ball, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyPanelBackground()
    }

    private fun applyPanelBackground() {
        if (!blurEnabled) {
            background = panelBackground()
            return
        }
        background = null
        setupBackgroundBlur(
            view = this,
            cornerRadiusPx = 24f * density,
            blurRadiusPx = (24f * density).roundToInt(),
            tintColor = withAlpha(themeColors.panelBackground, 120),
        )
        post {
            val bg = background
            if (bg == null || !bg.javaClass.name.contains("BackgroundBlurDrawable")) {
                background = panelBackground()
            }
        }
    }

    fun setMinimized(minimized: Boolean) {
        if (this.minimized == minimized) return
        this.minimized = minimized
        if (minimized) {
            content.visibility = GONE
            resizeHandle.visibility = GONE
            ball.visibility = VISIBLE
            background = null
            clipToOutline = false
        } else {
            content.visibility = VISIBLE
            resizeHandle.visibility = VISIBLE
            ball.visibility = GONE
            applyPanelBackground()
            clipToOutline = false
        }
        requestLayout()
        invalidate()
    }

    private fun directionRow(vararg directions: ScreenControlDirection): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        directions.forEachIndexed { index, direction ->
            val key = iconKey(iconFor(direction)).apply {
                background = rippleKey(selected = false)
                setOnTouchListener(
                    RepeatKeyTouchListener(
                        onShort = { listener.onDirection(direction, false) },
                        onLongStart = { listener.onDirection(direction, true) },
                        onLongEnd = { listener.onDirectionEnd() },
                    ),
                )
            }
            row.addView(
                key,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginStart = if (index == 0) 0 else dp(6f)
                },
            )
        }
        return row
    }

    private fun navRow(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val items = listOf(
            R.drawable.ic_iconoir_undo to ScreenControlNav.BACK,
            R.drawable.ic_iconoir_view_grid to ScreenControlNav.RECENTS,
            R.drawable.ic_iconoir_home to ScreenControlNav.HOME,
        )
        items.forEachIndexed { index, (icon, nav) ->
            val key = iconKey(icon).apply {
                background = rippleKey(selected = false)
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    listener.onNav(nav)
                }
            }
            row.addView(
                key,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginStart = if (index == 0) 0 else dp(8f)
                },
            )
        }
        val minimize = iconKey(R.drawable.ic_circle_dot, tint = Color.WHITE).apply {
            background = rippleKey(selected = true, accent = true)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                listener.onMinimize()
            }
        }
        row.addView(
            minimize,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(8f)
            },
        )
        return row
    }

    private fun iconFor(direction: ScreenControlDirection): Int = when (direction) {
        ScreenControlDirection.UP -> R.drawable.ic_iconoir_nav_arrow_up
        ScreenControlDirection.DOWN -> R.drawable.ic_iconoir_nav_arrow_down
        ScreenControlDirection.LEFT -> R.drawable.ic_iconoir_nav_arrow_left
        ScreenControlDirection.RIGHT -> R.drawable.ic_iconoir_nav_arrow_right
    }

    private fun iconKey(iconRes: Int, tint: Int = themeColors.textPrimary): ImageView =
        ImageView(context).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(7f), dp(7f), dp(7f), dp(7f))
            imageTintList = ColorStateList.valueOf(tint)
            isClickable = true
            isFocusable = true
            contentDescription = null
        }

    private fun keyView(label: String): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 21f
        setTextColor(themeColors.textPrimary)
        isClickable = true
        isFocusable = true
        contentDescription = label
    }

    private fun labelOf(direction: ScreenControlDirection): String = when (direction) {
        ScreenControlDirection.UP -> "↑"
        ScreenControlDirection.DOWN -> "↓"
        ScreenControlDirection.LEFT -> "←"
        ScreenControlDirection.RIGHT -> "→"
    }

    private fun panelBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 24f * density
        colors = intArrayOf(
            withAlpha(themeColors.panelBackground, if (blurEnabled) 150 else 255),
            withAlpha(themeColors.panelBackground, if (blurEnabled) 150 else 255),
        )
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
        setStroke((1f * density).roundToInt(), withAlpha(themeColors.cardBorder, 150))
    }

    private fun padBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 20f * density
        setColor(withAlpha(themeColors.panelBackground, if (blurEnabled) 110 else 255))
        setStroke((1f * density).roundToInt(), withAlpha(themeColors.divider, 55))
    }

    private fun rippleKey(selected: Boolean = false, accent: Boolean = false): RippleDrawable {
        val content = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(
                when {
                    accent -> themeColors.accent
                    selected -> withAlpha(themeColors.accentSoft, 220)
                    else -> withAlpha(themeColors.textPrimary, 12)
                }
            )
            setStroke(
                (1f * density).roundToInt(),
                withAlpha(
                    if (accent || selected) themeColors.accent else themeColors.textPrimary,
                    if (accent || selected) 140 else 18,
                ),
            )
        }
        return RippleDrawable(
            ColorStateList.valueOf(withAlpha(themeColors.accent, 48)),
            content,
            null,
        )
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun dp(value: Float): Int = (value * density).roundToInt()

    private inner class HeaderDragListener : OnTouchListener {
        private var lastRawX = 0f
        private var lastRawY = 0f
        private var dragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    dragging = true
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return true
                    val dx = event.rawX - lastRawX
                    val dy = event.rawY - lastRawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    if (dx != 0f || dy != 0f) listener.onMovePanel(dx, dy)
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) listener.onPanelMoveFinished()
                    dragging = false
                    return true
                }
            }
            return false
        }
    }

    private class RepeatKeyTouchListener(
        private val onShort: () -> Unit,
        private val onLongStart: () -> Unit,
        private val onLongEnd: () -> Unit,
    ) : OnTouchListener {
        private val handler = Handler(Looper.getMainLooper())
        private var longFired = false

        private val longRunnable = Runnable {
            longFired = true
            onLongStart()
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longFired = false
                    view.isPressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    handler.postDelayed(longRunnable, 360L)
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longRunnable)
                    view.isPressed = false
                    if (longFired) onLongEnd() else onShort()
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longRunnable)
                    view.isPressed = false
                    if (longFired) onLongEnd()
                    return true
                }
            }
            return false
        }
    }

    private interface PadListener {
        fun onMove(dx: Float, dy: Float)
        fun onTap()
        fun onDoubleTap()
        fun onLongPressStart()
        fun onLongPressMove(dx: Float, dy: Float)
        fun onLongPressEnd()
    }

    private class PadView(
        context: Context,
        private val listener: PadListener,
    ) : View(context) {

        private val density = resources.displayMetrics.density
        private val handler = Handler(Looper.getMainLooper())
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var moved = false
        private var longPressFired = false
        private var lastTapAt = 0L

        private val longPressRunnable = Runnable {
            longPressFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listener.onLongPressStart()
        }

        init {
            setWillNotDraw(false)
            isClickable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    lastX = event.x
                    lastY = event.y
                    moved = false
                    longPressFired = false
                    handler.postDelayed(longPressRunnable, 380L)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    lastX = event.x
                    lastY = event.y
                    if (!moved && hypot(event.x - downX, event.y - downY) > touchSlop) {
                        moved = true
                        if (!longPressFired) handler.removeCallbacks(longPressRunnable)
                    }
                    if (longPressFired) {
                        listener.onLongPressMove(dx, dy)
                    } else {
                        listener.onMove(dx, dy)
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (longPressFired) {
                        listener.onLongPressEnd()
                    } else if (!moved) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastTapAt <= 300L) {
                            lastTapAt = 0L
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            listener.onDoubleTap()
                        } else {
                            lastTapAt = now
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            listener.onTap()
                        }
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (longPressFired) listener.onLongPressEnd()
                    return true
                }
            }
            return false
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // 一墨的触控区是纯白/同色，不画点阵、圆环或提示文字。
        }
    }

    private class BottomBarView(
        context: Context,
        private val onDragProgress: (Float) -> Unit,
        private val onDismiss: () -> Unit,
        private val onCancel: () -> Unit,
    ) : View(context) {

        private var startRawY = 0f
        private var armed = false
        private val density = resources.displayMetrics.density
        private val themeColors = OverlayPanelTheme.colors(context)
        private val threshold = 42f * density

        init {
            setWillNotDraw(false)
            isClickable = true
            contentDescription = "Close"
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawY = event.rawY
                    armed = false
                    isPressed = true
                    onDragProgress(0f)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dy = (startRawY - event.rawY).coerceAtLeast(0f)
                    val nowArmed = dy >= threshold
                    if (nowArmed && !armed) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    armed = nowArmed
                    onDragProgress(dy)
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    isPressed = false
                    val dy = startRawY - event.rawY
                    if (dy >= threshold) onDismiss() else onCancel()
                    armed = false
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    isPressed = false
                    armed = false
                    onCancel()
                    return true
                }
            }
            return false
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(themeColors.textPrimary, if (armed) 220 else 165)
                style = Paint.Style.FILL
            }
            val barWidth = (64f * density).coerceAtMost(width * 0.7f)
            val barHeight = 3.5f * density
            val left = (width - barWidth) / 2f
            val top = (height - barHeight - 6f * density).coerceAtLeast(0f)
            canvas.drawRoundRect(
                RectF(left, top, left + barWidth, top + barHeight),
                barHeight / 2f,
                barHeight / 2f,
                paint,
            )
        }

        private fun withAlpha(color: Int, alpha: Int): Int =
            (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
    }

    private interface ResizeListener {
        fun onStart()
        fun onResize(dx: Float, dy: Float)
        fun onEnd()
    }

    private class ResizeHandleView(
        context: Context,
        private val listener: ResizeListener,
    ) : View(context) {

        private var startRawX = 0f
        private var startRawY = 0f
        private var resizing = false

        init {
            setWillNotDraw(false)
            isClickable = true
            contentDescription = "Resize"
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    resizing = true
                    isPressed = true
                    listener.onStart()
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!resizing) return true
                    listener.onResize(event.rawX - startRawX, event.rawY - startRawY)
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isPressed = false
                    if (resizing) listener.onEnd()
                    resizing = false
                    return true
                }
            }
            return false
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val density = resources.displayMetrics.density
            val themeColors = OverlayPanelTheme.colors(context)
            val lineWidth = 2f * density
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(themeColors.accent, 200)
                style = Paint.Style.STROKE
                strokeWidth = lineWidth
                strokeCap = Paint.Cap.ROUND
            }
            val radius = minOf(width, height).toFloat() - lineWidth / 2f
            canvas.drawArc(
                RectF(-radius, -radius, radius, radius),
                0f,
                90f,
                false,
                paint,
            )
        }

        private fun withAlpha(color: Int, alpha: Int): Int =
            (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
    }

    private class DotsPill(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val themeColors = OverlayPanelTheme.colors(context)

        init {
            setWillNotDraw(false)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(150, 70, 80, 100)
                style = Paint.Style.FILL
            }
            val radius = 1.8f * density
            val gap = 6f * density
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx - gap, cy, radius, dot)
            canvas.drawCircle(cx, cy, radius, dot)
            canvas.drawCircle(cx + gap, cy, radius, dot)
        }
    }

    private interface BallListener {
        fun onMove(dx: Float, dy: Float)
        fun onMoveFinished()
        fun onRestore()
    }

    private class BallView(
        context: Context,
        private val listener: BallListener,
    ) : View(context) {

        private val density = resources.displayMetrics.density
        private val themeColors = OverlayPanelTheme.colors(context)
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val icon = context.getDrawable(R.drawable.ic_circle_dot)?.mutate()

        private var downRawX = 0f
        private var downRawY = 0f
        private var lastRawX = 0f
        private var lastRawY = 0f
        private var dragging = false

        init {
            setWillNotDraw(false)
            isClickable = true
            contentDescription = "Restore touchpad"
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            elevation = 8f * density
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    dragging = false
                    isPressed = true
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val totalX = event.rawX - downRawX
                    val totalY = event.rawY - downRawY
                    if (!dragging && hypot(totalX, totalY) > touchSlop) {
                        dragging = true
                    }
                    if (dragging) {
                        listener.onMove(event.rawX - lastRawX, event.rawY - lastRawY)
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    isPressed = false
                    if (dragging) {
                        listener.onMoveFinished()
                    } else {
                        performClick()
                        listener.onRestore()
                    }
                    dragging = false
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    isPressed = false
                    if (dragging) listener.onMoveFinished()
                    dragging = false
                    return true
                }
            }
            return false
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(width, height) / 2f - 1f * density

            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(themeColors.panelBackground, 242)
                style = Paint.Style.FILL
            }
            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(themeColors.cardBorder, 150)
                style = Paint.Style.STROKE
                strokeWidth = 1f * density
            }
            canvas.drawCircle(cx, cy, radius, fill)
            canvas.drawCircle(cx, cy, radius, border)

            // 一墨挂起球：48dp 玻璃圆 + 24dp 圆点图标。
            icon?.let { drawable ->
                val size = 24f * density
                drawable.setBounds(
                    (cx - size / 2f).toInt(),
                    (cy - size / 2f).toInt(),
                    (cx + size / 2f).toInt(),
                    (cy + size / 2f).toInt(),
                )
                drawable.setTint(withAlpha(themeColors.textPrimary, 220))
                drawable.draw(canvas)
            }
        }

        private fun withAlpha(color: Int, alpha: Int): Int =
            (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
    }
}
