package com.slideindex.app.pagescroll

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.slideindex.app.R
import com.slideindex.app.overlay.OverlayWindowTypes
import kotlin.math.abs
import kotlin.math.max

/**
 * 「页面滚动」的竖排控制条。
 *
 * 形态：圆角胶囊容器里竖着排 5 个 42dp 圆钮 ——
 * 方向（↑/↓）、减速（−）、暂停/继续（▶ / ‖，下方显示当前档位）、加速（+）、关闭（×）。
 *
 * 交互：整条可以上下拖动（只移动窗口，不改内容）；手指落在条上期间会回报"用户正在交互"，
 * 宿主据此暂停滚动，避免"想按按钮却让页面继续跑"。
 */
internal class PageScrollControlBar(
    private val context: Context,
    private val settingsProvider: () -> PageScrollSettings,
    private val listener: Listener,
) {

    interface Listener {
        fun onReverseDirection()
        fun onSpeedDown()
        fun onSpeedUp()
        fun onTogglePause()
        fun onClose()
        fun onUserTouchingControls(touching: Boolean)
    }

    private val density = context.resources.displayMetrics.density
    private val windowManager: WindowManager? =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    private var container: BarContainer? = null
    private var params: WindowManager.LayoutParams? = null

    private var directionButton: TextView? = null
    private var slowerButton: TextView? = null
    private var pauseButton: TextView? = null
    private var fasterButton: TextView? = null
    private var closeButton: TextView? = null

    var isShowing: Boolean = false
        private set

    fun show(side: Side, anchorRawY: Float): Boolean {
        if (isShowing) return true
        val manager = windowManager ?: return false

        val settings = settingsProvider()
        val bar = BarContainer(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = buildBarBackground(settings)
            elevation = 6f * density
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            onTouchStateChanged = listener::onUserTouchingControls
            dragHost = this@PageScrollControlBar
        }

        val textColor = resolveButtonColor(settings)
        directionButton = button(GLYPH_ARROW_DOWN, GLYPH_TEXT_SP, textColor) { listener.onReverseDirection() }
            .also(bar::addView)
        slowerButton = button(GLYPH_MINUS, GLYPH_TEXT_SP, textColor) { listener.onSpeedDown() }
            .also(bar::addView)
        pauseButton = button(GLYPH_PLAY, PAUSE_TEXT_SP, textColor) { listener.onTogglePause() }
            .also(bar::addView)
        fasterButton = button(GLYPH_PLUS, GLYPH_TEXT_SP, textColor) { listener.onSpeedUp() }
            .also(bar::addView)
        closeButton = button(GLYPH_CLOSE, GLYPH_TEXT_SP, textColor) { listener.onClose() }
            .also(bar::addView)

        val layoutParams = buildLayoutParams(side, anchorRawY)
        bar.layoutParams = layoutParams

        val added = runCatching { manager.addView(bar, layoutParams) }
        if (added.isFailure) {
            Log.w(TAG, "控制条无法挂载", added.exceptionOrNull())
            return false
        }
        container = bar
        params = layoutParams
        isShowing = true
        refresh(settings, paused = false, directionDown = true)
        return true
    }

    fun dismiss() {
        val bar = container ?: return
        container = null
        params = null
        isShowing = false
        directionButton = null
        slowerButton = null
        pauseButton = null
        fasterButton = null
        closeButton = null
        runCatching { windowManager?.removeView(bar) }
    }

    /** 刷新按钮外观（方向 / 档位 / 暂停态 / 档位边界置灰）。 */
    fun refresh(settings: PageScrollSettings, paused: Boolean, directionDown: Boolean) {
        directionButton?.apply {
            text = if (directionDown) GLYPH_ARROW_DOWN else GLYPH_ARROW_UP
            contentDescription = context.getString(R.string.page_scroll_reverse_direction)
        }
        slowerButton?.apply {
            text = GLYPH_MINUS
            contentDescription = context.getString(R.string.page_scroll_slower)
            isEnabled = settings.speedLevel > PageScrollSettings.MIN_SPEED_LEVEL
            alpha = if (isEnabled) 1f else 0.35f
        }
        pauseButton?.apply {
            // 暂停/继续 + 当前档位（两行）。
            text = (if (paused) GLYPH_PLAY else GLYPH_PAUSE) + "\n" + settings.speedLevel
            contentDescription = context.getString(
                if (paused) R.string.page_scroll_resume else R.string.page_scroll_pause,
            )
            setLineSpacing(0f, 0.82f)
        }
        fasterButton?.apply {
            text = GLYPH_PLUS
            contentDescription = context.getString(R.string.page_scroll_faster)
            isEnabled = settings.speedLevel < PageScrollSettings.MAX_SPEED_LEVEL
            alpha = if (isEnabled) 1f else 0.35f
        }
        closeButton?.apply {
            text = GLYPH_CLOSE
            contentDescription = context.getString(R.string.page_scroll_close)
        }
    }

    /** 供容器在拖动时调用：把窗口挪到新的 y（已夹取）。 */
    internal fun moveTo(newY: Int) {
        val bar = container ?: return
        val layoutParams = params ?: return
        layoutParams.y = newY
        runCatching { windowManager?.updateViewLayout(bar, layoutParams) }
    }

    internal fun clampY(candidate: Int, barHeight: Int): Int {
        val screenHeight = context.resources.displayMetrics.heightPixels
        val minY = dp(EDGE_MARGIN_DP)
        val maxY = max(minY, screenHeight - barHeight - dp(BOTTOM_MARGIN_DP))
        return candidate.coerceIn(minY, maxY)
    }

    private fun button(glyph: String, textSizeSp: Float, color: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            typeface = Typeface.DEFAULT_BOLD
            isFocusable = true
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            text = glyph
            val value = TypedValue()
            if (context.theme.resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless,
                    value,
                    true,
                )
            ) {
                setBackgroundResource(value.resourceId)
            }
            setOnClickListener { onClick() }
        }

    private fun buildBarBackground(settings: PageScrollSettings): GradientDrawable {
        val fill = settings.controlBarColorArgb or ALPHA_OPAQUE
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = 24f * density
            setStroke(dp(1), if (luminance(fill) <= 0.5) STROKE_ON_DARK else STROKE_ON_LIGHT)
        }
    }

    private fun resolveButtonColor(settings: PageScrollSettings): Int {
        val base = if (settings.controlBarUseAccentColor) themeAccentColor() else settings.controlBarColorArgb
        return base or ALPHA_OPAQUE
    }

    /** 跟随强调色时从主题取（浮层里拿不到 Compose 主题，所以直接读主题属性）。 */
    private fun themeAccentColor(): Int {
        val value = TypedValue()
        val resolved = context.theme.resolveAttribute(android.R.attr.colorAccent, value, true)
        return if (resolved && value.data != 0) value.data else ACCENT_FALLBACK
    }

    private fun buildLayoutParams(side: Side, anchorRawY: Float): WindowManager.LayoutParams {
        val metrics = context.resources.displayMetrics
        val barHeight = dp(BAR_HEIGHT_DP)
        val edgeMargin = dp(EDGE_MARGIN_DP)
        val x = if (side == Side.LEFT) edgeMargin else metrics.widthPixels - edgeMargin
        val y = clampY((anchorRawY - barHeight / 2f).toInt(), barHeight)
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            OverlayWindowTypes.fullscreenLauncherWindowType(context),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = if (side == Side.LEFT) Gravity.LEFT or Gravity.TOP else Gravity.RIGHT or Gravity.TOP
            this.x = x
            this.y = y
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            buttonBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    private fun luminance(color: Int): Double {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
    }

    private fun dp(value: Int): Int = max(1, (value * density).toInt())

    enum class Side { LEFT, RIGHT }

    /**
     * 控制条容器：需要在**子按钮也吃到事件**的情况下感知"手指是否按在条上"，并支持整条拖动，
     * 所以覆写 `dispatchTouchEvent` 做交互回报、覆写 `onInterceptTouchEvent`/`onTouchEvent` 做拖动。
     */
    private class BarContainer(context: Context) : LinearLayout(context) {

        var onTouchStateChanged: ((Boolean) -> Unit)? = null
        var dragHost: PageScrollControlBar? = null

        private var downRawY = 0f
        private var startY = 0
        private var dragging = false
        private val slop = ViewConfiguration.get(context).scaledTouchSlop

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onTouchStateChanged?.invoke(true)
            val handled = super.dispatchTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                onTouchStateChanged?.invoke(false)
            }
            return handled
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = event.rawY
                    startY = (layoutParams as? WindowManager.LayoutParams)?.y ?: 0
                    dragging = false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawY - downRawY) > slop) {
                        dragging = true
                        return true
                    }
                }
            }
            return super.onInterceptTouchEvent(event)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                downRawY = event.rawY
                startY = (layoutParams as? WindowManager.LayoutParams)?.y ?: 0
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE && dragging) {
                val host = dragHost ?: return true
                val barHeight = if (height > 0) height else host.dp(BAR_HEIGHT_DP)
                host.moveTo(host.clampY(startY + (event.rawY - downRawY).toInt(), barHeight))
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                dragging = false
                return true
            }
            return super.onTouchEvent(event)
        }
    }

    private companion object {
        const val TAG = "PageScrollBar"

        const val GLYPH_ARROW_UP = "↑"
        const val GLYPH_ARROW_DOWN = "↓"
        const val GLYPH_MINUS = "−"
        const val GLYPH_PLUS = "+"
        const val GLYPH_PAUSE = "‖"
        const val GLYPH_PLAY = "▶"
        const val GLYPH_CLOSE = "×"

        const val GLYPH_TEXT_SP = 17f
        const val PAUSE_TEXT_SP = 13f

        const val BAR_HEIGHT_DP = 216
        const val EDGE_MARGIN_DP = 8
        const val BOTTOM_MARGIN_DP = 48

        const val ALPHA_OPAQUE = 0xFF000000.toInt()
        const val STROKE_ON_DARK = 0x33FFFFFF
        const val STROKE_ON_LIGHT = 0x33000000
        const val ACCENT_FALLBACK = 0xFFF5F7FB.toInt()
    }
}
