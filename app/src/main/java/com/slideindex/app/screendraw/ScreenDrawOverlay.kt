package com.slideindex.app.screendraw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import com.slideindex.app.R
import com.slideindex.app.overlay.FloatBallOverlay
import com.slideindex.app.overlay.FloatBallPickResultPanel
import com.slideindex.app.overlay.FloatingPointerOverlayWindow
import com.slideindex.app.overlay.GlobalOverlayDismissHelper
import com.slideindex.app.overlay.OverlayCompose
import com.slideindex.app.overlay.OverlayComposeOwner
import com.slideindex.app.overlay.OverlayDisplayRotationMonitor
import com.slideindex.app.overlay.OverlayPanelTheme
import com.slideindex.app.overlay.OverlayScreenMetrics
import com.slideindex.app.overlay.OverlayWindowTypes
import com.slideindex.app.overlay.ScreenOffDismissReceiver
import com.slideindex.app.overlay.screenshot.ScreenshotStorage
import com.slideindex.app.service.ScreenshotCaptureOutcome
import com.slideindex.app.service.SlideIndexAccessibilityService
import com.slideindex.app.ui.theme.OverlayAwareModuleTheme
import kotlin.math.roundToInt

/**
 * 「屏幕绘制」：全屏实时标注层。
 *
 * 画布是透明全屏窗口，直接盖在实时屏幕上（不是先截图）；只有导出时才调用无障碍截图，
 * 把画布位图叠到截图上再存相册。
 */
object ScreenDrawOverlay {

    private const val TAG = "ScreenDraw"
    private const val TOOLBAR_MARGIN_DP = 24f
    private const val CAPTURE_DELAY_MS = 150L
    private val COLORS_FOR_OPTIONS = intArrayOf(
        0xFFFF3B30.toInt(),
        0xFFFFCC00.toInt(),
        0xFF34C759.toInt(),
        0xFF0A84FF.toInt(),
        0xFFFFFFFF.toInt(),
    )
    private val WIDTHS_FOR_OPTIONS = floatArrayOf(1.5f, 3f, 6f)

    private enum class ExportAction { SAVE, COPY, SHARE }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var hostContext: Context? = null
    private var canvas: ScreenDrawCanvasView? = null
    private var canvasHost: FrameLayout? = null
    private var canvasParams: WindowManager.LayoutParams? = null
    private var toolbar: View? = null
    private var toolbarParams: WindowManager.LayoutParams? = null
    private var toolbarOwner: OverlayComposeOwner? = null
    private var toolbarBlurEnabled = false
    private val optionsModeState = mutableStateOf<ScreenDrawOptions?>(null)
    private var optionsView: View? = null
    private var optionsParams: WindowManager.LayoutParams? = null
    private var orientationState = mutableStateOf(ScreenDrawOrientation.HORIZONTAL)
    private val toolbarScreenYState = mutableStateOf(0)
    private val passthroughState = mutableStateOf(false)
    private var passthrough = false
    private var minimized = false
    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var textEditor: LinearLayout? = null
    private var textInput: EditText? = null
    private var textAnchorX = 0f
    private var textAnchorY = 0f

    private var density = 1f
    private var screenWidth = 1
    private var screenHeight = 1
    private var saving = false
    private var overlaysSuppressed = false

    private var rotationMonitor: OverlayDisplayRotationMonitor? = null
    private var screenOffReceiver: ScreenOffDismissReceiver? = null

    val isShowing: Boolean get() = canvas != null

    fun toggle(context: Context): Boolean {
        if (isShowing) {
            dismiss()
            return true
        }
        return show(context)
    }

    fun show(context: Context): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(context) }
            return true
        }
        if (isShowing) return true

        val service = SlideIndexAccessibilityService.accessibilityInstance()
        if (service == null) {
            toast(context, R.string.screen_draw_accessibility_required)
            return false
        }
        val host = SlideIndexAccessibilityService.overlayHostContext() ?: context.applicationContext
        val wm = host.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm == null) return false

        val metrics = OverlayScreenMetrics.sizePx(host)
        screenWidth = metrics.first.coerceAtLeast(1)
        screenHeight = metrics.second.coerceAtLeast(1)
        density = host.resources.displayMetrics.density
        hostContext = host
        windowManager = wm
        saving = false
        passthrough = false
        passthroughState.value = false
        minimized = false
        orientationState.value = ScreenDrawOrientation.HORIZONTAL
        toolbarScreenYState.value = 0

        val drawCanvas = ScreenDrawCanvasView(host) { dismiss() }
        val canvasHost = FrameLayout(host).apply {
            addView(
                drawCanvas,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        val editor = buildTextEditor(host)
        canvasHost.addView(
            editor,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER
                leftMargin = dp(24f)
                rightMargin = dp(24f)
            },
        )
        val canvasLayout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            OverlayWindowTypes.overlayWindowType(host),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            OverlayWindowTypes.ensureNoBrightnessOverride(this)
        }
        try {
            wm.addView(canvasHost, canvasLayout)
        } catch (error: Exception) {
            Log.e(TAG, "add canvas failed", error)
            return false
        }
        this.canvasHost = canvasHost
        canvas = drawCanvas
        canvasParams = canvasLayout
        textEditor = editor
        drawCanvas.requestFocus()

        val toolbarOwner = OverlayComposeOwner()
        val drawToolbar = OverlayCompose.createComposeView(host, toolbarOwner).apply {
            setContent {
                OverlayAwareModuleTheme {
                    ScreenDrawToolbarCompose(
                        orientation = orientationState.value,
                        passthrough = passthroughState.value,
                        toolbarScreenY = toolbarScreenYState.value,
                        optionsMode = optionsModeState.value,
                        initialTool = canvas?.tool ?: ScreenDrawTool.PEN,
                        initialShape = canvas?.shape ?: ScreenDrawShape.RECTANGLE,
                        initialColor = canvas?.color ?: ScreenDrawCanvasView.DEFAULT_COLOR,
                        initialWidthDp = (canvas?.widthPx ?: (3f * density)) / density,
                        onTool = { canvas?.tool = it },
                        onShape = { canvas?.shape = it },
                        onColor = { canvas?.color = it },
                        onWidth = { canvas?.widthPx = it },
                        onUndo = { canvas?.undo() },
                        onClear = { canvas?.clear() },
                        onToggleOrientation = {
                            toolbar?.visibility = View.INVISIBLE
                            orientationState.value =
                                if (orientationState.value == ScreenDrawOrientation.HORIZONTAL) {
                                    ScreenDrawOrientation.VERTICAL
                                } else {
                                    ScreenDrawOrientation.HORIZONTAL
                                }
                            mainHandler.postDelayed({
                                positionToolbar()
                                toolbar?.visibility = View.VISIBLE
                                toolbar?.alpha = 1f
                            }, 180L)
                        },
                        onTogglePassthrough = { setPassthrough(!passthrough) },
                        onMinimize = { minimizeDraw() },
                        onFinish = { export(ExportAction.SAVE) },
                        onExit = { dismiss() },
                        onShapeOptions = { toggleOptions(ScreenDrawOptions.SHAPE) },
                        onBrushOptions = { toggleOptions(ScreenDrawOptions.BRUSH) },
                        onBlurEnabled = { setToolbarBlur(it) },
                        onMoveBy = { dx, dy -> moveToolbarBy(dx, dy) },
                        onMoveFinished = {},
                    )
                }
            }
        }
        val toolbarLayout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            OverlayWindowTypes.overlayWindowType(host),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            OverlayWindowTypes.ensureNoBrightnessOverride(this)
        }
        try {
            drawToolbar.alpha = 0f
            wm.addView(drawToolbar, toolbarLayout)
        } catch (error: Exception) {
            Log.e(TAG, "add toolbar failed", error)
            wm.removeView(canvasHost)
            canvas = null
            canvasParams = null
            return false
        }
        toolbar = drawToolbar
        toolbarParams = toolbarLayout
        this.toolbarOwner = toolbarOwner
        setToolbarBlur(toolbarBlurEnabled)
        drawToolbar.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int,
            ) {
                if (drawToolbar.width <= 0 || drawToolbar.height <= 0) return
                drawToolbar.removeOnLayoutChangeListener(this)
                positionToolbar()
                drawToolbar.animate().alpha(1f).setDuration(120L).start()
            }
        })
        drawToolbar.post {
            if (drawToolbar.width > 0 && drawToolbar.height > 0) {
                positionToolbar()
                drawToolbar.animate().alpha(1f).setDuration(120L).start()
            }
        }

        screenOffReceiver = ScreenOffDismissReceiver { dismiss() }.also { it.register(host) }
        rotationMonitor = OverlayDisplayRotationMonitor(host) { dismiss() }.also { it.start() }

        // 悬浮指针/悬浮球是 TYPE_APPLICATION_OVERLAY，层级高于无障碍绘制层；
        // 不收起会全屏吞触摸，画布收不到事件。
        suppressOtherOverlays()

        Log.i(TAG, "shown")
        return true
    }

    private fun moveToolbarBy(dx: Float, dy: Float) {
        val view = toolbar ?: return
        val params = toolbarParams ?: return
        val wm = windowManager ?: return
        val width = view.width.coerceAtLeast(1)
        val height = view.height.coerceAtLeast(1)
        if (orientationState.value == ScreenDrawOrientation.HORIZONTAL) {
            // BOTTOM gravity：选项行出现时窗口向上长，底边不动，不抖。
            params.gravity = Gravity.BOTTOM or Gravity.START
            params.x = (params.x + dx.roundToInt())
                .coerceIn(0, (screenWidth - width).coerceAtLeast(0))
            params.y = (params.y - dy.roundToInt())
                .coerceIn(0, (screenHeight - height).coerceAtLeast(0))
        } else {
            params.gravity = Gravity.TOP or Gravity.END
            params.x = (params.x - dx.roundToInt())
                .coerceIn(0, (screenWidth - width).coerceAtLeast(0))
            params.y = (params.y + dy.roundToInt())
                .coerceIn(0, (screenHeight - height).coerceAtLeast(0))
        }
        runCatching { wm.updateViewLayout(view, params) }
        updateToolbarScreenY()
        if (optionsView != null) positionOptions()
    }

    private fun updateToolbarScreenY() {
        val view = toolbar ?: return
        val params = toolbarParams ?: return
        val height = view.height.coerceAtLeast(1)
        toolbarScreenYState.value = if (orientationState.value == ScreenDrawOrientation.HORIZONTAL) {
            (screenHeight - params.y - height).coerceAtLeast(0)
        } else {
            params.y
        }
    }

    private fun positionToolbar() {
        val view = toolbar ?: return
        val params = toolbarParams ?: return
        val wm = windowManager ?: return
        val width = view.width.coerceAtLeast(1)
        val height = view.height.coerceAtLeast(1)
        if (orientationState.value == ScreenDrawOrientation.HORIZONTAL) {
            params.gravity = Gravity.BOTTOM or Gravity.START
            params.x = ((screenWidth - width) / 2).coerceAtLeast(0)
            params.y = dp(28f)
        } else {
            params.gravity = Gravity.TOP or Gravity.END
            params.x = dp(16f)
            params.y = ((screenHeight - height) * 0.62f).roundToInt().coerceAtLeast(0)
        }
        runCatching { wm.updateViewLayout(view, params) }
        updateToolbarScreenY()
        if (optionsView != null) positionOptions()
    }

    private fun setToolbarBlur(enabled: Boolean) {
        toolbarBlurEnabled = enabled
    }

    private fun setPassthrough(enabled: Boolean) {
        val view = canvasHost ?: return
        val params = canvasParams ?: return
        val wm = windowManager ?: return
        passthrough = enabled
        passthroughState.value = enabled
        params.flags = if (enabled) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun toggleOptions(mode: ScreenDrawOptions) {
        if (optionsModeState.value == mode) {
            hideOptions()
        } else {
            showOptions(mode)
        }
    }

    private fun showOptions(mode: ScreenDrawOptions) {
        hideOptions()
        val host = hostContext ?: return
        val wm = windowManager ?: return
        optionsModeState.value = mode
        val row = buildOptionsView(host, mode).apply { alpha = 0f }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            OverlayWindowTypes.overlayWindowType(host),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 1f
        }
        optionsView = row
        optionsParams = params
        row.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int,
            ) {
                if (v.width <= 0 || v.height <= 0) return
                v.removeOnLayoutChangeListener(this)
                positionOptions()
                v.animate().alpha(1f).setDuration(120L).start()
            }
        })
        runCatching { wm.addView(row, params) }
        optionsView = row
        optionsParams = params
    }

    private fun hideOptions() {
        optionsModeState.value = null
        optionsView?.let { view -> runCatching { windowManager?.removeView(view) } }
        optionsView = null
        optionsParams = null
    }

    private fun positionOptions() {
        val view = optionsView ?: return
        val toolbarView = toolbar ?: return
        val params = optionsParams ?: return
        val wm = windowManager ?: return
        val loc = IntArray(2)
        toolbarView.getLocationOnScreen(loc)
        val toolbarWidth = toolbarView.width.coerceAtLeast(1)
        val toolbarHeight = toolbarView.height.coerceAtLeast(1)
        val optionsWidth = view.width.coerceAtLeast(1)
        val optionsHeight = view.height.coerceAtLeast(1)
        val centerY = loc[1] + toolbarHeight / 2
        val above = centerY >= screenHeight / 2
        params.x = (loc[0] + (toolbarWidth - optionsWidth) / 2)
            .coerceIn(0, (screenWidth - optionsWidth).coerceAtLeast(0))
        params.y = if (above) {
            (loc[1] - optionsHeight - dp(6f)).coerceAtLeast(0)
        } else {
            (loc[1] + toolbarHeight + dp(6f))
                .coerceAtMost((screenHeight - optionsHeight).coerceAtLeast(0))
        }
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun buildOptionsView(host: Context, mode: ScreenDrawOptions): View {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24f * density
                setColor(withAlpha(OverlayPanelTheme.colors(host).panelBackground, 245))
                setStroke(dp(1f), withAlpha(OverlayPanelTheme.colors(host).cardBorder, 120))
            }
        }
        if (mode == ScreenDrawOptions.SHAPE) {
            val options = listOf(
                "□" to ScreenDrawShape.RECTANGLE,
                "○" to ScreenDrawShape.OVAL,
                "／" to ScreenDrawShape.LINE,
                "→" to ScreenDrawShape.ARROW,
            )
            options.forEach { (label, shape) ->
                val item = android.widget.TextView(host).apply {
                    text = label
                    textSize = 20f
                    gravity = Gravity.CENTER
                    setTextColor(OverlayPanelTheme.colors(host).textPrimary)
                    setOnClickListener {
                        canvas?.shape = shape
                        canvas?.tool = ScreenDrawTool.SHAPE
                        hideOptions()
                    }
                }
                row.addView(item, LinearLayout.LayoutParams(dp(40f), dp(40f)))
            }
        } else {
            COLORS_FOR_OPTIONS.forEach { swatch ->
                val dot = View(host).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(swatch)
                        setStroke(dp(1f), 0x33000000)
                    }
                    setOnClickListener {
                        canvas?.color = swatch
                        showOptions(ScreenDrawOptions.BRUSH)
                    }
                }
                row.addView(dot, LinearLayout.LayoutParams(dp(28f), dp(28f)).apply {
                    marginStart = dp(4f)
                    marginEnd = dp(4f)
                })
            }
            row.addView(View(host).apply {
                setBackgroundColor(withAlpha(OverlayPanelTheme.colors(host).divider, 120))
            }, LinearLayout.LayoutParams(dp(1f), dp(22f)).apply {
                marginStart = dp(4f)
                marginEnd = dp(4f)
            })
            WIDTHS_FOR_OPTIONS.forEach { widthDp ->
                val cell = android.widget.FrameLayout(host).apply {
                    setOnClickListener {
                        canvas?.widthPx = widthDp * density
                        showOptions(ScreenDrawOptions.BRUSH)
                    }
                }
                val dot = View(host).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(OverlayPanelTheme.colors(host).textPrimary)
                    }
                }
                cell.addView(dot, android.widget.FrameLayout.LayoutParams(
                    dp(2f * widthDp + 4f),
                    dp(2f * widthDp + 4f),
                ).apply { gravity = Gravity.CENTER })
                row.addView(cell, LinearLayout.LayoutParams(dp(36f), dp(36f)))
            }
        }
        return row
    }

    private fun minimizeDraw() {
        if (minimized) return
        hideOptions()
        minimized = true
        canvasHost?.visibility = View.GONE
        toolbar?.visibility = View.GONE
        val host = hostContext ?: return
        val wm = windowManager ?: return
        val size = dp(60f)
        val tool = canvas?.tool ?: ScreenDrawTool.PEN
        val iconRes = when (tool) {
            ScreenDrawTool.PEN -> R.drawable.ic_iconoir_edit_pencil
            ScreenDrawTool.ERASER -> R.drawable.ic_remix_eraser
            ScreenDrawTool.LASER -> R.drawable.ic_remix_magic_line
            ScreenDrawTool.SHAPE -> R.drawable.ic_iconoir_square
        }
        val ball = android.widget.ImageView(host).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF3A3F4A.toInt())
                setStroke(dp(1f), 0x33FFFFFF)
            }
            elevation = dp(10f).toFloat()
            contentDescription = "恢复绘制"
            val self = this
            var downX = 0f
            var downY = 0f
            var lastX = 0f
            var lastY = 0f
            var dragging = false
            val slop = android.view.ViewConfiguration.get(host).scaledTouchSlop
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        lastX = event.rawX
                        lastY = event.rawY
                        dragging = false
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (kotlin.math.abs(event.rawX - downX) > slop ||
                            kotlin.math.abs(event.rawY - downY) > slop
                        ) {
                            dragging = true
                        }
                        if (dragging) {
                            val p = ballParams ?: return@setOnTouchListener true
                            p.x = (p.x + (event.rawX - lastX).toInt())
                                .coerceIn(0, (screenWidth - size).coerceAtLeast(0))
                            p.y = (p.y + (event.rawY - lastY).toInt())
                                .coerceIn(0, (screenHeight - size).coerceAtLeast(0))
                            runCatching { wm.updateViewLayout(self, p) }
                        }
                        lastX = event.rawX
                        lastY = event.rawY
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        if (!dragging) restoreDraw()
                        true
                    }
                    else -> true
                }
            }
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            OverlayWindowTypes.overlayWindowType(host),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - size - dp(12f)
            y = (screenHeight - size) / 2
        }
        runCatching { wm.addView(ball, params) }
        ballView = ball
        ballParams = params
    }

    private fun restoreDraw() {
        if (!minimized) return
        minimized = false
        ballView?.let { view -> runCatching { windowManager?.removeView(view) } }
        ballView = null
        ballParams = null
        canvasHost?.visibility = View.VISIBLE
        toolbar?.visibility = View.VISIBLE
        toolbar?.post { positionToolbar() }
    }

    private fun buildTextEditor(context: Context): LinearLayout {
        val colors = OverlayPanelTheme.colors(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(10f), dp(14f), dp(8f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18f * density
                setColor(withAlpha(colors.panelBackground, 250))
                setStroke((1f * density).roundToInt(), withAlpha(colors.cardBorder, 150))
            }
            elevation = 14f * density
            visibility = View.GONE
            isClickable = true
        }

        val input = EditText(context).apply {
            hint = "输入文字"
            setSingleLine(true)
            textSize = 16f
            setTextColor(colors.textPrimary)
            setHintTextColor(colors.textMuted)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    confirmText()
                    true
                } else {
                    false
                }
            }
        }
        container.addView(
            input,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val cancel = Button(context).apply {
            text = context.getString(android.R.string.cancel)
            isAllCaps = false
            setOnClickListener { hideTextEditor() }
        }
        val confirm = Button(context).apply {
            text = context.getString(android.R.string.ok)
            isAllCaps = false
            setOnClickListener { confirmText() }
        }
        row.addView(cancel)
        row.addView(confirm)
        container.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        textInput = input
        return container
    }

    private fun showTextEditor(x: Float, y: Float) {
        textAnchorX = x
        textAnchorY = y
        val editor = textEditor ?: return
        textInput?.setText("")
        editor.visibility = View.VISIBLE
        textInput?.requestFocus()
        val input = textInput ?: return
        val imm = hostContext?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        input.post { runCatching { imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) } }
    }

    private fun hideTextEditor() {
        textEditor?.visibility = View.GONE
        val input = textInput
        val imm = hostContext?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        if (input != null) {
            runCatching { imm?.hideSoftInputFromWindow(input.windowToken, 0) }
        }
        canvas?.requestFocus()
    }

    private fun confirmText() {
        val text = textInput?.text?.toString().orEmpty()
        if (text.isNotBlank()) canvas?.addText(textAnchorX, textAnchorY, text)
        hideTextEditor()
    }

    private fun export(action: ExportAction) {
        if (saving) return
        val drawCanvas = canvas ?: return
        val host = hostContext ?: return
        if (!drawCanvas.hasContent) {
            toast(host, R.string.screen_draw_empty)
            return
        }
        saving = true
        hideTextEditor()
        toolbar?.visibility = View.GONE
        drawCanvas.visibility = View.INVISIBLE
        suppressOtherOverlays()

        mainHandler.postDelayed({
            val accepted = SlideIndexAccessibilityService.captureDisplayBitmap { outcome ->
                when (outcome) {
                    is ScreenshotCaptureOutcome.Success -> {
                        val composed = compose(drawCanvas, outcome.bitmap)
                        outcome.bitmap.recycle()
                        if (composed != null) {
                            val ok = when (action) {
                                ExportAction.SAVE -> {
                                    val uri = ScreenshotStorage.saveToGallery(host, composed)
                                    uri != null
                                }

                                ExportAction.COPY -> {
                                    val uri = ScreenshotStorage.createClipboardUri(host, composed)
                                    uri != null && ScreenshotStorage.copyToClipboard(host, uri)
                                }

                                ExportAction.SHARE -> {
                                    val uri = ScreenshotStorage.createShareUri(host, composed)
                                    uri != null && ScreenshotStorage.share(host, uri)
                                }
                            }
                            composed.recycle()
                            if (ok) {
                                toast(
                                    host,
                                    when (action) {
                                        ExportAction.SAVE -> R.string.screen_draw_saved
                                        ExportAction.COPY -> R.string.screen_draw_copied
                                        ExportAction.SHARE -> R.string.screen_draw_shared
                                    },
                                )
                                restoreOtherOverlays()
                                dismiss()
                            } else {
                                failSave()
                            }
                        } else {
                            failSave()
                        }
                    }

                    else -> failSave()
                }
            }
            if (!accepted) failSave()
        }, CAPTURE_DELAY_MS)
    }

    private fun compose(canvasView: ScreenDrawCanvasView, screenshot: Bitmap): Bitmap? {
        val strokes = canvasView.snapshotBitmap() ?: return null
        val result = screenshot.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        val target = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        target.drawBitmap(
            strokes,
            null,
            Rect(0, 0, result.width, result.height),
            paint,
        )
        return result
    }

    private fun failSave() {
        saving = false
        toolbar?.visibility = View.VISIBLE
        canvas?.visibility = View.VISIBLE
        restoreOtherOverlays()
        hostContext?.let { toast(it, R.string.screen_draw_save_failed) }
    }

    private fun suppressOtherOverlays() {
        if (overlaysSuppressed) return
        overlaysSuppressed = true
        runCatching { GlobalOverlayDismissHelper.dismissAllPanels() }
        runCatching { FloatingPointerOverlayWindow.suppressForScreenshotCapture() }
        runCatching { FloatBallOverlay.suppressForScreenshotCapture() }
        runCatching { FloatBallPickResultPanel.suppressForScreenshotCapture() }
    }

    private fun restoreOtherOverlays() {
        if (!overlaysSuppressed) return
        overlaysSuppressed = false
        runCatching { FloatingPointerOverlayWindow.restoreAfterScreenshotCapture() }
        runCatching { FloatBallOverlay.restoreAfterScreenshotCapture() }
        runCatching { FloatBallPickResultPanel.restoreAfterScreenshotCapture() }
    }

    fun dismiss() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { dismiss() }
            return
        }
        restoreOtherOverlays()
        hideOptions()
        val wm = windowManager
        ballView?.let { view -> runCatching { wm?.removeView(view) } }
        ballView = null
        ballParams = null
        minimized = false
        val toolbarView = toolbar
        toolbarView?.let { view -> runCatching { wm?.removeView(view) } }
        OverlayCompose.teardownOverlayCompose(toolbarView as? ComposeView, toolbarOwner)
        toolbar = null
        toolbarParams = null
        toolbarOwner = null
        canvasHost?.let { view -> runCatching { wm?.removeView(view) } }
        canvasHost = null
        canvas = null
        textEditor = null
        textInput = null
        canvasParams = null

        screenOffReceiver?.unregister()
        screenOffReceiver = null
        rotationMonitor?.stop()
        rotationMonitor = null

        windowManager = null
        hostContext = null
        saving = false
        Log.i(TAG, "dismissed")
    }

    private fun toast(context: Context, resId: Int) {
        runCatching {
            Toast.makeText(context.applicationContext, context.getString(resId), Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Float): Int = (value * density).roundToInt()

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
