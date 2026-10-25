package com.slideindex.app.screencontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import com.slideindex.app.R
import com.slideindex.app.gesture.GestureAction
import com.slideindex.app.overlay.FloatingPointerRealtimeGesture
import com.slideindex.app.overlay.OverlayCompose
import com.slideindex.app.overlay.OverlayComposeOwner
import com.slideindex.app.overlay.OverlayDisplayRotationMonitor
import com.slideindex.app.overlay.OverlayScreenMetrics
import com.slideindex.app.overlay.OverlayTouchableRegion
import com.slideindex.app.overlay.OverlayWindowTypes
import com.slideindex.app.overlay.ScreenOffDismissReceiver
import com.slideindex.app.service.SlideIndexAccessibilityGestureInjector
import com.slideindex.app.service.SlideIndexAccessibilityService
import kotlin.math.roundToInt

/**
 * 「屏幕控制」：悬浮触控板 + 自绘指针 + 4 方向键 + 4 导航键。
 *
 * - 触控板是**相对**移动：手指滑动只移动指针，面板本身不动；
 * - 指针只是视觉，不是系统鼠标；点击/滑动都通过无障碍 `dispatchGesture` 在指针坐标注入；
 * - 面板与指针是两个独立窗口：面板可触摸，指针窗口 `NOT_TOUCHABLE`。
 */
object ScreenControlOverlay {

    private const val TAG = "ScreenControl"
    private const val POINTER_SIZE_PX = 120f
    private const val POINTER_HALF_PX = POINTER_SIZE_PX / 2f
    private const val BALL_SIZE_DP = ScreenControlPanelView.BALL_SIZE_DP
    private const val POINTER_FACTOR = 2.15f
    private const val EDGE_MARGIN_DP = 12f
    private const val POINTER_EDGE_PX = 4f

    /**
     * 窗口在面板四周为投影预留的空间（dp）。
     *
     * 面板的投影由 [ScreenControlShadowHost] 自绘（不用 elevation：悬浮窗根视图的
     * elevation 会被部分 ROM 当成整窗高度，套一层巨大的系统阴影）。
     */
    private const val SHADOW_PAD_DP = 8f

    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var hostContext: Context? = null
    private var panelView: ScreenControlPanelView? = null
    private var shadowHost: ScreenControlShadowHost? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var pointerView: ComposeView? = null
    private var pointerOwner: OverlayComposeOwner? = null
    private var pointerState: ScreenControlPointerState? = null
    private var dismissDragActive = false
    private var dismissDragBaseY = 0
    private var dismissAnimator: ValueAnimator? = null

    private var screenWidth = 1
    private var screenHeight = 1
    private var density = 1f
    private var panelBlurEnabled = true

    private var panelWidthDp = ScreenControlPrefs.DEFAULT_WIDTH_DP
    private var panelHeightDp = ScreenControlPrefs.DEFAULT_HEIGHT_DP
    private var panelX = 0
    private var panelY = 0
    private var savedPanelX = 0
    private var savedPanelY = 0
    private var minimized = false

    private var pointerX = 0f
    private var pointerY = 0f

    private var realtimeGesture: FloatingPointerRealtimeGesture? = null
    private var repeatDirection: ScreenControlDirection? = null
    private var repeatRunnable: Runnable? = null

    private var resizeStartWidthDp = 0
    private var resizeStartHeightDp = 0

    private var rotationMonitor: OverlayDisplayRotationMonitor? = null
    private var screenOffReceiver: ScreenOffDismissReceiver? = null

    val isShowing: Boolean get() = panelView != null

    fun toggle(context: Context, anchorX: Float? = null, blurEnabled: Boolean = true): Boolean {
        if (isShowing) {
            dismiss()
            return true
        }
        return show(context, anchorX, blurEnabled)
    }

    fun show(context: Context, anchorX: Float? = null, blurEnabled: Boolean = true): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(context, anchorX, blurEnabled) }
            return true
        }
        if (isShowing) return true

        val service = SlideIndexAccessibilityService.accessibilityInstance()
        if (service == null) {
            toast(context, R.string.screen_control_accessibility_required)
            return false
        }

        val host = SlideIndexAccessibilityService.overlayHostContext() ?: context.applicationContext
        val wm = host.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm == null) return false

        val metrics = OverlayScreenMetrics.sizePx(host)
        screenWidth = metrics.first.coerceAtLeast(1)
        screenHeight = metrics.second.coerceAtLeast(1)
        density = host.resources.displayMetrics.density
        panelBlurEnabled = blurEnabled
        hostContext = host
        windowManager = wm

        val geometry = ScreenControlPrefs.read(host)
        panelWidthDp = geometry.widthDp
        panelHeightDp = geometry.heightDp
        val panelWidthPx = dp(panelWidthDp)
        val panelHeightPx = dp(panelHeightDp)

        val defaultX = screenWidth - panelWidthPx - dp(EDGE_MARGIN_DP)
        panelX = when {
            anchorX != null && anchorX > screenWidth / 2f ->
                screenWidth - panelWidthPx - dp(EDGE_MARGIN_DP)
            anchorX != null -> dp(EDGE_MARGIN_DP)
            geometry.xRatio >= 0f -> ((screenWidth - panelWidthPx) * geometry.xRatio).roundToInt()
            else -> defaultX
        }.coerceIn(0, (screenWidth - panelWidthPx).coerceAtLeast(0))
        panelY = if (geometry.yRatio >= 0f) {
            ((screenHeight - panelHeightPx) * geometry.yRatio).roundToInt()
        } else {
            ((screenHeight - panelHeightPx) * 0.35f).roundToInt()
        }.coerceIn(0, (screenHeight - panelHeightPx).coerceAtLeast(0))

        val panel = ScreenControlPanelView(host, panelListener, blurEnabled)
        val shadowPad = dp(SHADOW_PAD_DP)
        val shadowHost = ScreenControlShadowHost(host, shadowPad).apply {
            addView(
                panel,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        val params = WindowManager.LayoutParams(
            panelWidthPx + shadowPad * 2,
            panelHeightPx + shadowPad * 2,
            OverlayWindowTypes.contentPanelWindowType(host),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = panelX - shadowPad
            y = panelY - shadowPad
            alpha = 1f
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            OverlayWindowTypes.ensureNoBrightnessOverride(this)
        }

        try {
            wm.addView(shadowHost, params)
        } catch (error: Exception) {
            Log.e(TAG, "add panel failed", error)
            return false
        }
        panelView = panel
        this.shadowHost = shadowHost
        panelParams = params
        shadowHost.post { installTouchableRegion(shadowHost) }

        val pointerState = ScreenControlPointerState()
        pointerX = screenWidth / 2f
        pointerY = screenHeight / 2f
        pointerState.x = pointerX
        pointerState.y = pointerY
        val pointerOwner = OverlayComposeOwner()
        val pointer = OverlayCompose.createComposeView(host, pointerOwner).apply {
            setContent { ScreenControlPointerCanvas(pointerState) }
        }
        val pointerLayout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            OverlayWindowTypes.overlayWindowType(host),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 1f
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            OverlayWindowTypes.ensureNoBrightnessOverride(this)
        }
        try {
            wm.addView(pointer, pointerLayout)
        } catch (error: Exception) {
            Log.e(TAG, "add pointer failed", error)
            wm.removeView(shadowHost)
            panelView = null
            this.shadowHost = null
            panelParams = null
            return false
        }
        pointerView = pointer
        this.pointerOwner = pointerOwner
        this.pointerState = pointerState

        ensurePointerOutsidePanel()
        screenOffReceiver = ScreenOffDismissReceiver { dismiss() }.also { it.register(host) }
        rotationMonitor = OverlayDisplayRotationMonitor(host) { handleRotationChanged() }.also { it.start() }

        Log.i(TAG, "shown panel=${panelWidthDp}x${panelHeightDp}dp at ($panelX,$panelY)")
        return true
    }

    private fun handleRotationChanged() {
        val host = hostContext ?: return
        val metrics = OverlayScreenMetrics.sizePx(host)
        val newW = metrics.first.coerceAtLeast(1)
        val newH = metrics.second.coerceAtLeast(1)
        if (newW == screenWidth && newH == screenHeight) return
        screenWidth = newW
        screenHeight = newH
        density = host.resources.displayMetrics.density

        val panelW = dp(panelWidthDp)
        val panelH = dp(panelHeightDp)
        savedPanelX = savedPanelX.coerceIn(0, (screenWidth - panelW).coerceAtLeast(0))
        savedPanelY = savedPanelY.coerceIn(0, (screenHeight - panelH).coerceAtLeast(0))

        if (minimized) {
            val ballSize = dp(BALL_SIZE_DP)
            val margin = dp(10f)
            panelX = if (panelX + ballSize / 2 < screenWidth / 2) {
                margin
            } else {
                screenWidth - ballSize - margin
            }.coerceIn(0, (screenWidth - ballSize).coerceAtLeast(0))
            panelY = panelY.coerceIn(0, (screenHeight - ballSize).coerceAtLeast(0))
            val shadowPad = dp(SHADOW_PAD_DP)
            panelParams?.x = panelX - shadowPad
            panelParams?.y = panelY - shadowPad
            windowManager?.let { wm ->
                shadowHost?.let { view -> runCatching { wm.updateViewLayout(view, panelParams) } }
            }
        } else {
            panelX = panelX.coerceIn(0, (screenWidth - panelW).coerceAtLeast(0))
            panelY = panelY.coerceIn(0, (screenHeight - panelH).coerceAtLeast(0))
            updatePanelLayout()
            pointerX = pointerX.coerceIn(POINTER_EDGE_PX, screenWidth - POINTER_EDGE_PX)
            pointerY = pointerY.coerceIn(POINTER_EDGE_PX, screenHeight - POINTER_EDGE_PX)
            pointerState?.let {
                it.x = pointerX
                it.y = pointerY
            }
            ensurePointerOutsidePanel()
        }
    }

    private val panelListener = object : ScreenControlPanelView.Listener {
        override fun onPointerMove(dx: Float, dy: Float) {
            movePointerBy(dx, dy)
        }

        override fun onTap() {
            pointerState?.playClick()
            tapAtPointer()
        }

        override fun onDoubleTap() {
            pointerState?.playClick()
            tapAtPointer()
            mainHandler.postDelayed({ tapAtPointer() }, 80L)
        }

        override fun onLongPressStart() {
            pointerState?.hold = true
            val service = SlideIndexAccessibilityService.accessibilityInstance() ?: return
            realtimeGesture?.finish()
            realtimeGesture = FloatingPointerRealtimeGesture(
                service = service,
                startX = pointerX,
                startY = pointerY,
                onError = {
                    Log.w(TAG, "continuous press failed")
                    realtimeGesture = null
                },
            )
        }

        override fun onLongPressMove(dx: Float, dy: Float) {
            movePointerBy(dx, dy)
        }

        override fun onLongPressEnd() {
            pointerState?.hold = false
            realtimeGesture?.finish()
            realtimeGesture = null
        }

        override fun onDirection(direction: ScreenControlDirection, longPress: Boolean) {
            if (longPress) startDirectionRepeat(direction) else dispatchDirectionSwipe(direction, false)
        }

        override fun onDirectionEnd() {
            stopDirectionRepeat()
        }

        override fun onNav(action: ScreenControlNav) {
            val gesture = when (action) {
                ScreenControlNav.BACK -> GestureAction.Back
                ScreenControlNav.RECENTS -> GestureAction.Recents
                ScreenControlNav.HOME -> GestureAction.Home
            }
            SlideIndexAccessibilityService.perform(gesture)
        }

        override fun onMinimize() = minimize()

        override fun onRestore() = restore()

        override fun onClose() = dismiss()

        override fun onMovePanel(dx: Float, dy: Float) {
            if (minimized) return
            val width = dp(panelWidthDp)
            val height = dp(panelHeightDp)
            panelX = (panelX + dx.roundToInt()).coerceIn(0, (screenWidth - width).coerceAtLeast(0))
            panelY = (panelY + dy.roundToInt()).coerceIn(0, (screenHeight - height).coerceAtLeast(0))
            updatePanelLayout()
            ensurePointerOutsidePanel()
        }

        override fun onPanelMoveFinished() {
            saveGeometry()
        }

        override fun onResizeStart() {
            resizeStartWidthDp = panelWidthDp
            resizeStartHeightDp = panelHeightDp
        }

        override fun onResize(dx: Float, dy: Float) {
            if (minimized) return
            val newWidth = (resizeStartWidthDp + dx / density).roundToInt()
                .coerceIn(ScreenControlPrefs.MIN_WIDTH_DP, ScreenControlPrefs.MAX_WIDTH_DP)
            val newHeight = (resizeStartHeightDp + dy / density).roundToInt()
                .coerceIn(ScreenControlPrefs.MIN_HEIGHT_DP, ScreenControlPrefs.MAX_HEIGHT_DP)
            if (newWidth == panelWidthDp && newHeight == panelHeightDp) return
            panelWidthDp = newWidth
            panelHeightDp = newHeight
            val shadowPad = dp(SHADOW_PAD_DP)
            panelParams?.width = dp(newWidth) + shadowPad * 2
            panelParams?.height = dp(newHeight) + shadowPad * 2
            panelX = panelX.coerceIn(0, (screenWidth - dp(newWidth)).coerceAtLeast(0))
            panelY = panelY.coerceIn(0, (screenHeight - dp(newHeight)).coerceAtLeast(0))
            updatePanelLayout()
            ensurePointerOutsidePanel()
        }

        override fun onResizeEnd() {
            saveGeometry()
        }

        override fun onBallMove(dx: Float, dy: Float) {
            if (!minimized) return
            val size = dp(BALL_SIZE_DP)
            panelX = (panelX + dx.roundToInt())
                .coerceIn(0, (screenWidth - size).coerceAtLeast(0))
            panelY = (panelY + dy.roundToInt())
                .coerceIn(0, (screenHeight - size).coerceAtLeast(0))
            updatePanelLayout()
        }

        override fun onBallMoveFinished() {
            // 圆球位置只在本次会话内保留；恢复面板时仍回到挂起前的位置。
        }

        override fun onDismissDrag(dy: Float) {
            dragDismiss(dy)
        }

        override fun onDismissRelease(dismiss: Boolean) {
            settleDismiss(dismiss)
        }
    }

    private fun dragDismiss(dy: Float) {
        val view = shadowHost ?: return
        val params = panelParams ?: return
        val wm = windowManager ?: return
        if (!dismissDragActive) {
            dismissDragActive = true
            dismissDragBaseY = params.y
        }
        val safeDy = dy.coerceAtLeast(0f)
        params.y = dismissDragBaseY - safeDy.roundToInt()
        params.alpha = (1f - safeDy / (dp(panelHeightDp).coerceAtLeast(1) * 1.15f))
            .coerceIn(0f, 1f)
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun settleDismiss(dismiss: Boolean) {
        val view = shadowHost ?: return
        val params = panelParams ?: return
        val wm = windowManager ?: return
        val startY = params.y
        val endY = if (dismiss) -params.height else dismissDragBaseY
        val startAlpha = params.alpha
        val endAlpha = if (dismiss) 0f else 1f
        dismissAnimator?.cancel()
        dismissAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (dismiss) 200L else 160L
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                params.y = (startY + (endY - startY) * fraction).roundToInt()
                params.alpha = startAlpha + (endAlpha - startAlpha) * fraction
                runCatching { wm.updateViewLayout(view, params) }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    dismissAnimator = null
                    dismissDragActive = false
                    if (dismiss) {
                        dismiss()
                    } else {
                        params.y = dismissDragBaseY
                        params.alpha = 1f
                        runCatching { wm.updateViewLayout(view, params) }
                    }
                }
            })
            start()
        }
    }

    private fun movePointerBy(dx: Float, dy: Float) {
        pointerX = (pointerX + dx * POINTER_FACTOR)
            .coerceIn(POINTER_EDGE_PX, (screenWidth - POINTER_EDGE_PX).coerceAtLeast(POINTER_EDGE_PX))
        pointerY = (pointerY + dy * POINTER_FACTOR)
            .coerceIn(POINTER_EDGE_PX, (screenHeight - POINTER_EDGE_PX).coerceAtLeast(POINTER_EDGE_PX))
        applyPointerPosition()
        pointerState?.addTrail()
        realtimeGesture?.updatePosition(pointerX, pointerY)
    }

    private fun applyPointerPosition() {
        pointerState?.let {
            it.x = pointerX
            it.y = pointerY
        }
    }

    private fun tapAtPointer() {
        val service = SlideIndexAccessibilityService.accessibilityInstance() ?: return
        val path = Path().apply { moveTo(pointerX, pointerY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 50L))
            .build()
        runCatching { service.dispatchGesture(gesture, null, null) }
    }

    private fun dispatchDirectionSwipe(
        direction: ScreenControlDirection,
        longPress: Boolean,
        onFinished: (Boolean) -> Unit = {},
    ) {
        val service = SlideIndexAccessibilityService.accessibilityInstance()
        if (service == null) {
            onFinished(false)
            return
        }
        val stepX = screenWidth * if (longPress) 0.14f else 0.20f
        val stepY = screenHeight * if (longPress) 0.16f else 0.26f
        val deltaX = when (direction) {
            ScreenControlDirection.LEFT -> -stepX
            ScreenControlDirection.RIGHT -> stepX
            else -> 0f
        }
        val deltaY = when (direction) {
            ScreenControlDirection.UP -> -stepY
            ScreenControlDirection.DOWN -> stepY
            else -> 0f
        }
        val startX = pointerX.coerceIn(10f, (screenWidth - 10f).coerceAtLeast(10f))
        val startY = pointerY.coerceIn(10f, (screenHeight - 10f).coerceAtLeast(10f))
        val endX = (startX + deltaX).coerceIn(10f, (screenWidth - 10f).coerceAtLeast(10f))
        val endY = (startY + deltaY).coerceIn(10f, (screenHeight - 10f).coerceAtLeast(10f))
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        SlideIndexAccessibilityGestureInjector.dispatchPointerSwipePath(
            service = service,
            startX = startX,
            startY = startY,
            path = path,
            durationMs = if (longPress) 85L else 110L,
            maxDurationMs = 220L,
            onFinished = onFinished,
        )
    }

    private fun startDirectionRepeat(direction: ScreenControlDirection) {
        stopDirectionRepeat()
        repeatDirection = direction
        val runnable = object : Runnable {
            override fun run() {
                if (repeatDirection != direction) return
                dispatchDirectionSwipe(direction, longPress = true) { ok ->
                    if (ok && repeatDirection == direction) {
                        mainHandler.postDelayed(this, 24L)
                    }
                }
            }
        }
        repeatRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun stopDirectionRepeat() {
        repeatDirection = null
        repeatRunnable?.let { mainHandler.removeCallbacks(it) }
        repeatRunnable = null
    }

    private fun minimize() {
        if (minimized) return
        val params = panelParams ?: return
        saveGeometry()
        savedPanelX = panelX
        savedPanelY = panelY
        minimized = true
        panelView?.setMinimized(true)
        shadowHost?.minimized = true
        pointerView?.visibility = View.GONE
        val ballSize = dp(BALL_SIZE_DP)
        val shadowPad = dp(SHADOW_PAD_DP)
        val margin = dp(10f)
        val nearTop = panelY < screenHeight / 3
        val left = if (panelX + dp(panelWidthDp) / 2 < screenWidth / 2) {
            margin
        } else {
            screenWidth - ballSize - margin
        }
        val top = if (nearTop) {
            (screenHeight - ballSize) / 2
        } else {
            screenHeight - ballSize - margin
        }
        params.width = ballSize + shadowPad * 2
        params.height = ballSize + shadowPad * 2
        params.x = left - shadowPad
        params.y = top - shadowPad
        panelX = left
        panelY = top
        // 这里不能走 updatePanelLayout()，它会把 x/y 重置回面板位置。
        windowManager?.let { wm ->
            shadowHost?.let { view -> runCatching { wm.updateViewLayout(view, params) } }
        }
    }

    private fun restore() {
        if (!minimized) return
        minimized = false
        panelView?.setMinimized(false)
        shadowHost?.minimized = false
        val shadowPad = dp(SHADOW_PAD_DP)
        panelParams?.width = dp(panelWidthDp) + shadowPad * 2
        panelParams?.height = dp(panelHeightDp) + shadowPad * 2
        panelX = savedPanelX.coerceIn(0, (screenWidth - dp(panelWidthDp)).coerceAtLeast(0))
        panelY = savedPanelY.coerceIn(0, (screenHeight - dp(panelHeightDp)).coerceAtLeast(0))
        updatePanelLayout()
        pointerView?.visibility = View.VISIBLE
        ensurePointerOutsidePanel()
    }

    private fun updatePanelLayout() {
        val view = shadowHost ?: return
        val params = panelParams ?: return
        val wm = windowManager ?: return
        val shadowPad = dp(SHADOW_PAD_DP)
        params.x = panelX - shadowPad
        params.y = panelY - shadowPad
        runCatching { wm.updateViewLayout(view, params) }
    }

    /**
     * 窗口比面板大一圈（容纳投影），只把面板rect声明为可触摸区域：
     * 四周透明部分穿透给下层 app，避免面板外多出一圈死区。
     */
    private fun installTouchableRegion(host: ScreenControlShadowHost) {
        OverlayTouchableRegion.install(host) {
            val shadowPad = dp(SHADOW_PAD_DP)
            if (minimized) {
                val ballSize = dp(BALL_SIZE_DP)
                Rect(shadowPad, shadowPad, shadowPad + ballSize, shadowPad + ballSize)
            } else {
                Rect(
                    shadowPad,
                    shadowPad,
                    shadowPad + dp(panelWidthDp),
                    shadowPad + dp(panelHeightDp),
                )
            }
        }
    }

    private fun ensurePointerOutsidePanel() {
        if (minimized) return
        val left = panelX.toFloat()
        val top = panelY.toFloat()
        val right = (panelX + dp(panelWidthDp)).toFloat()
        val bottom = (panelY + dp(panelHeightDp)).toFloat()
        val inside = pointerX in left..right && pointerY in top..bottom
        if (!inside) return
        pointerX = if (panelX + dp(panelWidthDp) / 2 < screenWidth / 2) {
            (right + dp(20f)).coerceAtMost(screenWidth - POINTER_EDGE_PX)
        } else {
            (left - dp(20f)).coerceAtLeast(POINTER_EDGE_PX)
        }
        pointerY = ((top + bottom) / 2f).coerceIn(POINTER_EDGE_PX, screenHeight - POINTER_EDGE_PX)
        applyPointerPosition()
    }

    private fun saveGeometry() {
        val host = hostContext ?: return
        if (minimized) return
        val width = dp(panelWidthDp)
        val height = dp(panelHeightDp)
        val maxX = (screenWidth - width).coerceAtLeast(1)
        val maxY = (screenHeight - height).coerceAtLeast(1)
        ScreenControlPrefs.saveGeometry(
            context = host,
            widthDp = panelWidthDp,
            heightDp = panelHeightDp,
            xRatio = (panelX.toFloat() / maxX).coerceIn(0f, 1f),
            yRatio = (panelY.toFloat() / maxY).coerceIn(0f, 1f),
        )
    }

    fun dismiss() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { dismiss() }
            return
        }
        stopDirectionRepeat()
        dismissAnimator?.cancel()
        dismissAnimator = null
        dismissDragActive = false
        realtimeGesture?.finish()
        realtimeGesture = null
        saveGeometry()

        val wm = windowManager
        pointerView?.let { view -> runCatching { wm?.removeView(view) } }
        OverlayCompose.teardownOverlayCompose(pointerView, pointerOwner)
        pointerView = null
        pointerOwner = null
        pointerState?.clearTrail()
        pointerState = null
        shadowHost?.let { view -> runCatching { wm?.removeView(view) } }
        panelView = null
        shadowHost = null
        panelParams = null

        screenOffReceiver?.unregister()
        screenOffReceiver = null
        rotationMonitor?.stop()
        rotationMonitor = null

        minimized = false
        windowManager = null
        hostContext = null
        Log.i(TAG, "dismissed")
    }

    private fun toast(context: Context, resId: Int) {
        runCatching {
            Toast.makeText(context.applicationContext, context.getString(resId), Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private fun dp(value: Float): Int = (value * density).roundToInt()
}
