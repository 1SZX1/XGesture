package com.slideindex.app.pagescroll

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.slideindex.app.R
import com.slideindex.app.overlay.OverlayWindowTypes
import kotlin.math.abs
import kotlin.math.max

/**
 * 「页面滚动」的浮动控制条。
 *
 * 外观：一根竖向胶囊 —— 半透明深色底 + 1dp 描边 + 投影，顶部一个拖动把手；
 * 里面自上而下是：方向、加速、暂停/继续（含当前档位）、减速、关闭，全部用矢量图标。
 *
 * 交互：
 * - 整条上下拖动改位置（位置会记住）
 * - 加速/减速支持**长按连调**
 * - 4 秒没有操作自动淡出到 55%，一碰就恢复
 * - 手指落在条上期间回报宿主，宿主据此暂停滚动
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
    private val handler = Handler(Looper.getMainLooper())

    private var container: BarContainer? = null
    private var params: WindowManager.LayoutParams? = null

    private var directionIcon: ImageView? = null
    private var slowerIcon: ImageView? = null
    private var pauseIcon: ImageView? = null
    private var pauseLevel: TextView? = null
    private var fasterIcon: ImageView? = null
    private var closeIcon: ImageView? = null

    private var repeatTask: Runnable? = null
    private var dimTask: Runnable? = null

    var isShowing: Boolean = false
        private set

    fun show(side: Side, anchorRawY: Float): Boolean {
        if (isShowing) return true
        val manager = windowManager ?: return false
        val settings = settingsProvider()

        val handle = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(3)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(4)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(2).toFloat()
                setColor(HANDLE_COLOR)
            }
        }

        val bar = BarContainer(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(4), dp(6), dp(4), dp(6))
            background = buildBarBackground(settings)
            elevation = 14f * density
            clipToOutline = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            onTouchStateChanged = { touching ->
                listener.onUserTouchingControls(touching)
                if (touching) wakeUp() else scheduleDim()
            }
            owner = this@PageScrollControlBar
        }

        val tint = resolveTint(settings)
        bar.addView(handle)
        directionIcon = icon(R.drawable.ic_page_scroll_direction, tint) { listener.onReverseDirection() }
            .also(bar::addView)
        fasterIcon = icon(R.drawable.ic_page_scroll_plus, tint) { listener.onSpeedUp() }
            .also { view -> view.setOnLongClickListener { startRepeat(view) { listener.onSpeedUp() } } }
            .also(bar::addView)
        pauseIcon = icon(R.drawable.ic_page_scroll_pause, tint) { listener.onTogglePause() }
            .also(bar::addView)
        pauseLevel = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = -dp(2) }
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, LEVEL_TEXT_SP)
            setTextColor(withAlpha(tint, 0.7f))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            text = levelText(settings)
        }.also(bar::addView)
        slowerIcon = icon(R.drawable.ic_page_scroll_minus, tint) { listener.onSpeedDown() }
            .also { view -> view.setOnLongClickListener { startRepeat(view) { listener.onSpeedDown() } } }
            .also(bar::addView)
        closeIcon = icon(R.drawable.ic_page_scroll_close, withAlpha(tint, 0.75f)) { listener.onClose() }
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
        scheduleDim()
        return true
    }

    fun dismiss() {
        val bar = container ?: return
        container = null
        params = null
        isShowing = false
        directionIcon = null
        slowerIcon = null
        pauseIcon = null
        pauseLevel = null
        fasterIcon = null
        closeIcon = null
        stopRepeat()
        dimTask?.let { handler.removeCallbacks(it) }
        dimTask = null
        runCatching { windowManager?.removeView(bar) }
    }

    /** 刷新图标、档位与可用状态。 */
    fun refresh(settings: PageScrollSettings, paused: Boolean, directionDown: Boolean) {
        directionIcon?.apply {
            setImageResource(R.drawable.ic_page_scroll_direction)
            rotation = if (directionDown) 0f else 180f
            contentDescription = context.getString(R.string.page_scroll_reverse_direction)
        }
        slowerIcon?.apply {
            setImageResource(R.drawable.ic_page_scroll_minus)
            contentDescription = context.getString(R.string.page_scroll_slower)
            isEnabled = settings.speedLevel > PageScrollSettings.MIN_SPEED_LEVEL
            alpha = if (isEnabled) 1f else DISABLED_ALPHA
        }
        fasterIcon?.apply {
            setImageResource(R.drawable.ic_page_scroll_plus)
            contentDescription = context.getString(R.string.page_scroll_faster)
            isEnabled = settings.speedLevel < PageScrollSettings.MAX_SPEED_LEVEL
            alpha = if (isEnabled) 1f else DISABLED_ALPHA
        }
        pauseIcon?.apply {
            setImageResource(
                if (paused) R.drawable.ic_page_scroll_play else R.drawable.ic_page_scroll_pause,
            )
            contentDescription = context.getString(
                if (paused) R.string.page_scroll_resume else R.string.page_scroll_pause,
            )
        }
        pauseLevel?.text = levelText(settings)
        closeIcon?.contentDescription = context.getString(R.string.page_scroll_close)
    }

    private fun levelText(settings: PageScrollSettings): String =
        context.getString(
            R.string.page_scroll_speed_value,
            settings.speedLevel,
            PageScrollSettings.MAX_SPEED_LEVEL,
        ).substringAfter(' ').take(3).trim().ifEmpty { settings.speedLevel.toString() }

    /** 供容器在拖动时调用：把窗口挪到新的 y（已夹取）。 */
    internal fun moveTo(newY: Int) {
        val bar = container ?: return
        val layoutParams = params ?: return
        layoutParams.y = newY
        runCatching { windowManager?.updateViewLayout(bar, layoutParams) }
    }

    /** 拖动结束：记住位置（按"可移动范围"的比例）。 */
    internal fun rememberPosition() {
        val layoutParams = params ?: return
        val screenHeight = context.resources.displayMetrics.heightPixels
        val maxY = max(dp(1), screenHeight - currentBarHeight() - dp(BOTTOM_MARGIN_DP))
        val minY = dp(TOP_MARGIN_DP)
        val ratio = if (maxY - minY <= 0) 0f else ((layoutParams.y - minY).toFloat() / (maxY - minY)).coerceIn(0f, 1f)
        PageScrollPrefs.saveBarYRatio(context, ratio)
    }

    internal fun clampY(candidate: Int, barHeight: Int): Int {
        val screenHeight = context.resources.displayMetrics.heightPixels
        val minY = dp(TOP_MARGIN_DP)
        val maxY = max(minY, screenHeight - barHeight - dp(BOTTOM_MARGIN_DP))
        return candidate.coerceIn(minY, maxY)
    }

    private fun currentBarHeight(): Int {
        val height = container?.height ?: 0
        return if (height > 0) height else dp(ESTIMATED_BAR_HEIGHT_DP)
    }

    // ---- 长按连调 ----

    private fun startRepeat(view: View, action: () -> Unit): Boolean {
        stopRepeat()
        val task = object : Runnable {
            override fun run() {
                action()
                handler.postDelayed(this, REPEAT_MS)
            }
        }
        repeatTask = task
        handler.postDelayed(task, REPEAT_START_DELAY_MS)
        view.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                stopRepeat()
                v.setOnTouchListener(null)
            }
            false
        }
        return true
    }

    private fun stopRepeat() {
        repeatTask?.let { handler.removeCallbacks(it) }
        repeatTask = null
    }

    // ---- 闲置淡出 ----

    private fun scheduleDim() {
        dimTask?.let { handler.removeCallbacks(it) }
        val task = Runnable {
            container?.animate()?.alpha(DIM_ALPHA)?.setDuration(DIM_ANIM_MS)?.start()
        }
        dimTask = task
        handler.postDelayed(task, DIM_DELAY_MS)
    }

    private fun wakeUp() {
        dimTask?.let { handler.removeCallbacks(it) }
        dimTask = null
        container?.animate()?.alpha(1f)?.setDuration(DIM_ANIM_MS)?.start()
    }

    // ---- 视图构造 ----

    private fun icon(resId: Int, tint: Int, onClick: () -> Unit): ImageView =
        ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageResource(resId)
            imageTintList = ColorStateList.valueOf(tint)
            isClickable = true
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            background = RippleDrawable(
                ColorStateList.valueOf(RIPPLE_COLOR),
                null,
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                },
            )
            setOnClickListener { onClick() }
        }

    private fun buildBarBackground(settings: PageScrollSettings): GradientDrawable {
        val base = settings.controlBarColorArgb or ALPHA_OPAQUE
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            withAlpha(base, 0.92f),
            withAlpha(base, 0.98f),
        )).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 26f * density
            setStroke(dp(1), if (luminance(base) <= 0.5) STROKE_ON_DARK else STROKE_ON_LIGHT)
        }
    }

    private fun resolveTint(settings: PageScrollSettings): Int = when {
        settings.controlBarUseAccentColor -> themeAccentColor()
        // 不跟随强调色时用与底色反差的中性色，保证图标看得清。
        luminance(settings.controlBarColorArgb or ALPHA_OPAQUE) <= 0.5 -> NEUTRAL_ON_DARK
        else -> NEUTRAL_ON_LIGHT
    }

    /** 跟随强调色时从主题取（浮层里拿不到 Compose 主题，所以直接读主题属性）。 */
    private fun themeAccentColor(): Int {
        val value = TypedValue()
        val resolved = context.theme.resolveAttribute(android.R.attr.colorAccent, value, true)
        return if (resolved && value.data != 0) value.data else ACCENT_FALLBACK
    }

    private fun buildLayoutParams(side: Side, anchorRawY: Float): WindowManager.LayoutParams {
        val metrics = context.resources.displayMetrics
        val barHeight = dp(ESTIMATED_BAR_HEIGHT_DP)
        val edgeMargin = dp(EDGE_MARGIN_DP)
        // x 是相对 gravity 锚点的偏移：RIGHT 重力下窗口右缘在 (屏宽 - x)，两种边缘用同一个边距值。
        val remembered = PageScrollPrefs.barYRatio(context)
        val minY = dp(TOP_MARGIN_DP)
        val maxY = max(minY, metrics.heightPixels - barHeight - dp(BOTTOM_MARGIN_DP))
        val y = if (remembered >= 0f) {
            (minY + (maxY - minY) * remembered).toInt()
        } else {
            clampY((anchorRawY - barHeight / 2f).toInt(), barHeight)
        }
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
            this.x = edgeMargin
            this.y = y
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            buttonBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    private fun withAlpha(color: Int, fraction: Float): Int {
        val alpha = (((color ushr 24) and 0xFF) * fraction).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (alpha shl 24)
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
     * 控制条容器：既要感知"手指是否按在条上"（子按钮也会吃到事件），又要支持整条拖动，
     * 所以覆写 `dispatchTouchEvent` 做交互回报、覆写拦截/触摸做拖动。
     */
    private class BarContainer(context: Context) : LinearLayout(context) {

        var onTouchStateChanged: ((Boolean) -> Unit)? = null
        var owner: PageScrollControlBar? = null

        private var downRawY = 0f
        private var startY = 0
        private var dragging = false
        private val slop = ViewConfiguration.get(context).scaledTouchSlop

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onTouchStateChanged?.invoke(true)
            val handled = super.dispatchTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                onTouchStateChanged?.invoke(false)
                if (dragging) {
                    dragging = false
                    owner?.rememberPosition()
                }
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
                val host = owner ?: return true
                val barHeight = if (height > 0) height else host.dp(ESTIMATED_BAR_HEIGHT_DP)
                host.moveTo(host.clampY(startY + (event.rawY - downRawY).toInt(), barHeight))
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                dragging = false
                owner?.rememberPosition()
                return true
            }
            return super.onTouchEvent(event)
        }
    }

    private companion object {
        const val TAG = "PageScrollBar"

        const val BUTTON_DP = 44
        const val ESTIMATED_BAR_HEIGHT_DP = 232
        const val EDGE_MARGIN_DP = 8
        const val TOP_MARGIN_DP = 48
        const val BOTTOM_MARGIN_DP = 48

        const val LEVEL_TEXT_SP = 10f
        const val DIM_ALPHA = 0.55f
        const val DIM_DELAY_MS = 4000L
        const val DIM_ANIM_MS = 220L
        const val DISABLED_ALPHA = 0.35f
        const val REPEAT_START_DELAY_MS = 420L
        const val REPEAT_MS = 160L

        const val ALPHA_OPAQUE = 0xFF000000.toInt()
        const val STROKE_ON_DARK = 0x33FFFFFF
        const val STROKE_ON_LIGHT = 0x33000000
        const val HANDLE_COLOR = 0x59FFFFFF
        const val RIPPLE_COLOR = 0x33FFFFFF
        const val ACCENT_FALLBACK = 0xFFF5F7FB.toInt()
        const val NEUTRAL_ON_DARK = 0xFFF2F4F8.toInt()
        const val NEUTRAL_ON_LIGHT = 0xFF16181D.toInt()
    }
}
