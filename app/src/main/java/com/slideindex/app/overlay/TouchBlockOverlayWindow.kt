package com.slideindex.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.slideindex.app.R
import com.slideindex.app.gesture.TouchBlockLoopDetector
import com.slideindex.app.service.SlideIndexAccessibilityService

/**
 * 「禁止触摸」：一个全屏透明浮窗，把所有触摸都吞掉，让下面的应用完全收不到点击。
 *
 * 解除路径（对齐 MyGesture 那套动作）：
 * 1. **在屏幕上画一个闭合的圈** —— 判定见 [TouchBlockLoopDetector]（纯几何量，日志里能看清为什么不通过）；
 * 2. **熄屏静默兜底（best-effort，不对外承诺）** —— 注册 `ACTION_SCREEN_OFF`（复用 [ScreenOffDismissReceiver]），
 *    收到就撤窗。实测在 Flyme + Android 14+ 上可能因进程冻结 / AOD / 快速解锁漏掉，所以文案里**不写**"锁屏恢复"，
 *    只当"能收到就赚到"的路径；
 * 3. **双击** 只是切换黑屏 / 亮屏（不解除），和 MyGesture 一致；
 * 4. **5 分钟超时兜底** —— 既画不出圈又没熄屏时，别把人锁死。
 *
 * 窗口挂点：优先挂到无障碍服务那层（`TYPE_ACCESSIBILITY_OVERLAY`，mBaseLayer 311000），
 * 这样它压在悬浮球 / 触钮 / 侧栏（同为无障碍层）之上，屏幕才真的"点不动"。
 * 没有无障碍宿主时退回 `TYPE_APPLICATION_OVERLAY`（需要悬浮窗权限），此时图层可能低于自家其它悬浮窗。
 *
 * 黑屏怎么实现：本窗口只在无障碍层时（[blackModeUsesPseudoWindow]）直接复用
 * [PseudoScreenOffOverlayWindow] —— 它挂在应用层、位于本窗口之下，负责把整屏涂黑 + 亮度归零 + 保持唤醒，
 * 而吞触摸仍然由本窗口负责。退回应用层时没有更低的层可借，就在本窗口上改亮度 + 涂黑。
 */
@SuppressLint("StaticFieldLeak")
object TouchBlockOverlayWindow {
    private const val TAG = "TouchBlockOverlay"

    /** 保险丝：既画不出圈、又没熄屏时，别把用户锁太久。 */
    private const val AUTO_RELEASE_MS = 5 * 60 * 1000L

    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var overlayView: TouchBlockView? = null
    private var screenOffReceiver: ScreenOffDismissReceiver? = null
    private var appContext: Context? = null
    private var blackModeUsesPseudoWindow = false
    private var autoRelease: Runnable? = null

    val isShowing: Boolean
        get() = overlayView != null

    /** 动作入口：正在禁止触摸就解除，否则开始禁止。 */
    fun toggle(context: Context): Boolean {
        if (isShowing) {
            dismiss(released = true)
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

        val host = SlideIndexAccessibilityService.overlayHostContext() ?: context.applicationContext
        val wm = host.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false

        val view = TouchBlockView(
            context = host,
            onCircleReleased = { dismiss(released = true) },
            onToggleBlack = { setBlackMode(!isBlackMode()) },
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            OverlayWindowTypes.overlayWindowType(host),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            OverlayWindowTypes.ensureNoBrightnessOverride(this)
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        return try {
            wm.addView(view, params)
            windowManager = wm
            windowParams = params
            overlayView = view
            appContext = host.applicationContext
            blackModeUsesPseudoWindow =
                params.type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit

                override fun onViewDetachedFromWindow(v: View) {
                    // 无障碍被关掉 / 服务进程被回收时，系统会自己把窗口摘下来。状态要跟着清空，
                    // 否则下次触发会以为"还在禁止触摸"（而屏幕其实早就恢复了）。
                    // 注意：自己 dismiss 时先把 overlayView 置空再 removeView，所以这里不会重复清理。
                    if (overlayView !== v) return
                    overlayView = null
                    autoRelease?.let { mainHandler.removeCallbacks(it) }
                    autoRelease = null
                    screenOffReceiver?.unregister()
                    screenOffReceiver = null
                    if (blackModeUsesPseudoWindow) PseudoScreenOffOverlayWindow.dismiss()
                    blackModeUsesPseudoWindow = false
                    windowManager = null
                    windowParams = null
                }
            })
            view.showHint()
            screenOffReceiver = ScreenOffDismissReceiver { dismiss(released = false) }
                .also { it.register(host) }
            scheduleAutoRelease()
            Log.i(TAG, "show: type=${params.type} pseudoBlack=$blackModeUsesPseudoWindow")
            true
        } catch (error: Exception) {
            Log.w(TAG, "show: addView failed", error)
            overlayView = null
            windowManager = null
            windowParams = null
            screenOffReceiver?.unregister()
            screenOffReceiver = null
            false
        }
    }

    fun dismiss(released: Boolean = false, timeout: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { dismiss(released, timeout) }
            return
        }
        val view = overlayView
        if (view == null) {
            // 没有任何窗口时也要把借用来的黑屏收掉（例如伪息屏被我们自己开过）。
            if (blackModeUsesPseudoWindow) PseudoScreenOffOverlayWindow.dismiss()
            return
        }
        overlayView = null
        autoRelease?.let { mainHandler.removeCallbacks(it) }
        autoRelease = null
        screenOffReceiver?.unregister()
        screenOffReceiver = null
        if (blackModeUsesPseudoWindow) PseudoScreenOffOverlayWindow.dismiss()
        if (released) {
            runCatching { view.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }
        }
        try {
            windowManager?.removeViewImmediate(view)
        } catch (_: Exception) {
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {}
        }
        windowManager = null
        windowParams = null
        blackModeUsesPseudoWindow = false

        val context = appContext
        if (released && context != null) {
            val message = context.getString(
                if (timeout) R.string.touch_block_released_timeout else R.string.touch_block_released
            )
            OverlayToastWindow.show(context, message)
        }
        Log.i(TAG, "dismiss: released=$released timeout=$timeout")
    }

    private fun isBlackMode(): Boolean = overlayView?.isInBlackMode == true

    /**
     * 切黑屏/亮屏。无障碍层时才借 [PseudoScreenOffOverlayWindow] 的整窗黑幕；
     * 应用层时本窗口自己就是最上层，只能自己改亮度 + 涂黑（亮度在部分 OEM 上可能被忽略，此时至少是黑底遮罩）。
     */
    private fun setBlackMode(black: Boolean) {
        val view = overlayView ?: return
        val host = appContext ?: return
        if (blackModeUsesPseudoWindow) {
            val pseudoShown = if (black) {
                PseudoScreenOffOverlayWindow.show(host)
            } else {
                PseudoScreenOffOverlayWindow.dismiss()
                true
            }
            view.setBlackMode(black, darkFallback = black && !pseudoShown)
            if (black && !pseudoShown) applyFallbackBrightness(black = true)
        } else {
            view.setBlackMode(black, darkFallback = black)
            applyFallbackBrightness(black)
        }
        view.showHint()
    }

    /** 应用层兜底黑屏：改本窗口亮度并保持唤醒；关掉时还原成"不覆盖系统亮度"。 */
    private fun applyFallbackBrightness(black: Boolean) {
        val wm = windowManager ?: return
        val view = overlayView ?: return
        val params = windowParams ?: return
        if (black) {
            params.screenBrightness = 0f
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            OverlayWindowTypes.ensureNoBrightnessOverride(params)
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        runCatching { wm.updateViewLayout(view, params) }
            .onFailure { Log.w(TAG, "applyFallbackBrightness failed", it) }
    }

    private fun scheduleAutoRelease() {
        autoRelease?.let { mainHandler.removeCallbacks(it) }
        val handle = Runnable {
            if (isShowing) dismiss(released = true, timeout = true)
        }
        autoRelease = handle
        mainHandler.postDelayed(handle, AUTO_RELEASE_MS)
    }
}

/**
 * 全屏吞触摸的宿主 View：自身透明（或纯黑兜底），底部挂一张提示卡。
 *
 * 触摸一律 `return true`，不往下透传；只把采样点喂给 [TouchBlockLoopDetector]。
 * 三指及以上涂抹视为无效输入，直接清空轨迹（避免复杂手势被误判成圈）。
 */
@SuppressLint("ViewConstructor")
private class TouchBlockView(
    context: Context,
    private val onCircleReleased: () -> Unit,
    private val onToggleBlack: () -> Unit,
) : FrameLayout(context) {

    private val density = context.resources.displayMetrics.density
    private val hintCard: LinearLayout
    private val hintBody: TextView
    private val trace = ArrayList<TouchBlockLoopDetector.Sample>(256)
    private val loopConfig = TouchBlockLoopDetector.Config(
        // 120Hz 触摸的抖动位移在 1~3px 量级，6dp 以下不算"移动过"。
        minSampleDistancePx = 6f * density,
        // 半径下限 20dp：更小的圈基本是手抖，不当作"画圈解除"。
        minRadiusPx = 20f * density,
    )

    private val hideHintRunnable = Runnable { fadeHintOut() }
    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                showHint()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                onToggleBlack()
                return true
            }
        }
    )

    private var lastEvaluateAt = 0L
    private var blackMode = false
    private var released = false

    val isInBlackMode: Boolean
        get() = blackMode

    init {
        setBackgroundColor(Color.TRANSPARENT)
        hintCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            val padH = (20f * density).toInt()
            val padV = (14f * density).toInt()
            setPadding(padH, padV, padH, padV)
            alpha = 0f
        }
        hintCard.addView(
            TextView(context).apply {
                text = context.getString(R.string.touch_block_hint_title)
                setTextColor(Color.argb(255, 26, 26, 26))
                textSize = 15f
                maxWidth = (context.resources.displayMetrics.widthPixels * 0.82f).toInt()
            }
        )
        hintBody = TextView(context).apply {
            text = context.getString(R.string.touch_block_hint_body)
            setTextColor(Color.argb(255, 110, 110, 110))
            textSize = 12f
            maxWidth = (context.resources.displayMetrics.widthPixels * 0.82f).toInt()
        }
        hintCard.addView(hintBody)
        addView(
            hintCard,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = (104f * density).toInt()
                leftMargin = (24f * density).toInt()
                rightMargin = (24f * density).toInt()
            }
        )
    }

    /** 提示卡：淡入，4 秒后淡出；单击可再次唤出。 */
    fun showHint() {
        removeCallbacks(hideHintRunnable)
        hintCard.animate().cancel()
        hintCard.animate().alpha(1f).setDuration(160L).start()
        postDelayed(hideHintRunnable, 4_000L)
    }

    fun setBlackMode(black: Boolean, darkFallback: Boolean) {
        blackMode = black
        setBackgroundColor(if (darkFallback) Color.BLACK else Color.TRANSPARENT)
        hintBody.text = context.getString(
            if (black) R.string.touch_block_black_hint_body else R.string.touch_block_hint_body
        )
    }

    private fun fadeHintOut() {
        hintCard.animate().alpha(0f).setDuration(320L).start()
    }

    private fun cardBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 18f * density
        setColor(Color.argb(242, 255, 255, 255))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 让单击/双击仍然生效（多次单击会重复弹提示，不影响解除判定）。
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                trace.clear()
                trace.add(sampleOf(event))
            }
            MotionEvent.ACTION_POINTER_DOWN -> trace.clear()
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) {
                    trace.clear()
                } else {
                    trace.add(sampleOf(event))
                    evaluateThrottled(event.eventTime)
                }
            }
            MotionEvent.ACTION_UP -> {
                trace.add(sampleOf(event))
                evaluateTrace()
                trace.clear()
            }
            MotionEvent.ACTION_CANCEL -> trace.clear()
            else -> Unit
        }
        // 关键：吞掉一切，不再交给下面的窗口。
        return true
    }

    private fun sampleOf(event: MotionEvent): TouchBlockLoopDetector.Sample =
        TouchBlockLoopDetector.Sample(x = event.x, y = event.y, timeMs = event.eventTime)

    /** 画到一半就够一圈时立刻解除：不必等抬手。 */
    private fun evaluateThrottled(eventTime: Long) {
        if (eventTime - lastEvaluateAt < 60L) return
        lastEvaluateAt = eventTime
        evaluateTrace()
    }

    private fun evaluateTrace() {
        if (released || trace.size < 6) return
        val result = TouchBlockLoopDetector.detect(trace, loopConfig)
        Log.d("TouchBlockView", "trace: ${result.describe()}")
        if (result.accepted) {
            released = true
            onCircleReleased()
        }
    }
}
