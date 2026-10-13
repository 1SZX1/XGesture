package com.slideindex.app.macro.record

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import com.slideindex.app.overlay.OverlayWindowTypes
import com.slideindex.app.service.SlideIndexAccessibilityService
import kotlin.math.hypot

/**
 * 录制期间的**全屏触摸捕获层**。
 *
 * 录制要"看见"用户的每一次按下 / 移动 / 抬起，但触摸被我们接住后目标 App 就收不到了，
 * 所以每次手势结束后要把**同一条手势**注入回系统。这里有两个必须处理的坑：
 *
 * 1. **注入回环**：注入的手势会走系统输入管线，如果本层此刻仍可触摸，它会被我们自己接住
 *    → 又记录一次 → 又注入一次，形成死循环（整屏点不动）。所以注入期间：
 *    ① 设 `FLAG_NOT_TOUCHABLE`；② 用 [injecting] 闸门忽略进来的触摸。
 * 2. **标志生效竞态**：`updateViewLayout` 是异步生效的，如果紧接着就注入，第一个 DOWN 仍可能
 *    落到我们自己身上被吞掉，目标 App 收到的是"没有按下的移动/抬起"从而**完全不响应**
 *    （表现就是"点了没反应、滑了没反应"）。所以注入前先等 [INJECTION_APPLY_DELAY_MS]。
 *
 * 采样规则与录制器一致（相邻采样点 ≥ 24px 才记；按下 / 抬起强制记录），
 * 于是"注入回去的轨迹"和"被录下来的轨迹"是同一条。
 *
 * 图层顺序：本层要先加，控制条后加（后加的在上层，按钮才点得到）。
 */
class MacroRecordingTouchCapture(
    private val hostContext: Context,
    private val listener: Listener,
    /** 注入被系统拒绝 / 注入链路不可用时回调：调用方应当**立即结束录制**，别让触摸一直被吞。 */
    private val onInjectionUnavailable: () -> Unit = {},
) {

    /** 触摸事件回调（原始屏幕像素坐标 + 事件时间）。 */
    interface Listener {
        fun onDown(x: Float, y: Float, eventTimeMs: Long)
        fun onMove(x: Float, y: Float, eventTimeMs: Long)
        fun onUp(x: Float, y: Float, eventTimeMs: Long)
        fun onCancel()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private val sampleXs = ArrayList<Float>()
    private val sampleYs = ArrayList<Float>()
    private var downX = 0f
    private var downY = 0f
    private var downTimeMs = 0L
    private var capturing = false
    private var injecting = false
    private var injectionFailures = 0

    /**
     * 注入期间把整屏窗**摘掉**（不是切换触摸标志）。
     *
     * 为什么这么做：真机验证过"引擎回放（完全没有这扇窗时）能正常滚动"，而"录制时（有窗 + 切换
     * NOT_TOUCHABLE）松手后那一下不生效"。所以这里让注入环境和已验证可行的环境**完全一致**：
     * 摘窗 → 注入 → 装回。代价：这段时间里的触摸会直接给目标 App，不被记录。
     */
    private var attached = false

    /**
     * 注入代次。**必须用它来结束注入**：兜底定时器是按次调度的，如果只用一个布尔闸门，
     * 上一次手势的定时器会在下一次注入进行中触发，把闸门提前打开 —— 注入的事件就会落回我们这里
     * 被当成真实触摸（真机日志确认过：`setTouchable(false)` 后 8ms 就被提前 `setTouchable(true)`），
     * 进而形成"记录→注入→再记录"的回环。
     */
    private var injectionGeneration = 0

    /**
     * 待注入的手势队列。
     *
     * **为什么必须串行**：注入的手势会把用户"正在进行"的触摸取消掉（真机日志确认：上一次回放还没结束时
     * 用户又开始滑，我们只收到 DOWN、收不到 UP/CANCEL）。所以只要还有在途触摸或正在注入，
     * 新的回放就排队等着 —— 绝不在用户手指按着的时候注入。
     */
    private val pendingForwards = ArrayDeque<() -> Unit>()

    /** 注入的事件带有 `deviceId < 0`（无障碍注入的虚拟设备）；用来识别"这是我自己注入回来的"。 */
    private fun isInjectedEvent(event: MotionEvent): Boolean = event.deviceId < 0

    val isShowing: Boolean get() = rootView != null

    fun show() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show() }
            return
        }
        if (rootView != null) return
        val manager = hostContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = manager
        val root = FrameLayout(hostContext)
        root.setOnTouchListener { _, event ->
            when {
                // 注入回来的事件绝不能记录/转发，否则会形成回环。
                isInjectedEvent(event) -> {
                    if (!injecting) Log.w(TAG, "注入事件落回本层（窗口标志生效晚了）action=${event.actionMasked}，已忽略")
                    true
                }

                injecting -> true

                else -> {
                    handleTouch(event)
                    true
                }
            }
        }

        // 优先用应用层悬浮窗（`TYPE_APPLICATION_OVERLAY`）：
        // ① 部分 OEM（Flyme）会给无障碍浮层强制叠 80% alpha，画面发灰；
        // ② 应用层悬浮窗算"可见窗口"，后台启动目标 App 才不会被系统拦（真机见过"正在尝试打开…"弹窗）。
        // 拿不到悬浮窗权限时回退无障碍浮层。
        val preferredType = OverlayWindowTypes.fullscreenLauncherWindowType(hostContext)
        var params = buildParams(preferredType)
        var added = runCatching { manager.addView(root, params) }
        if (added.isFailure && preferredType != WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
            Log.w(TAG, "应用层悬浮窗不可用，回退无障碍浮层", added.exceptionOrNull())
            params = buildParams(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            added = runCatching { manager.addView(root, params) }
        }
        if (added.isFailure) {
            Log.w(TAG, "无法添加录制捕获层", added.exceptionOrNull())
            return
        }
        rootView = root
        layoutParams = params
        attached = true
        Log.i(TAG, "捕获层已添加 flags=${params.flags} size=${params.width}x${params.height}")
    }

    private fun buildParams(type: Int): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    )

    fun dismiss() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { dismiss() }
            return
        }
        val view = rootView ?: return
        rootView = null
        layoutParams = null
        capturing = false
        injecting = false
        // 让所有还没触发的兜底定时器失效。
        injectionGeneration++
        injectionFailures = 0
        // 只有还挂在窗口上才需要移除（注入期间可能正被摘除）。
        if (attached) {
            runCatching { windowManager?.removeView(view) }
        }
        attached = false
        windowManager = null
        Log.i(TAG, "捕获层已移除")
    }

    private fun handleTouch(event: MotionEvent) {
        val action = event.actionMasked
        val x = event.rawX
        val y = event.rawY
        val time = event.eventTime
        // 诊断：按下/抬起/取消全打（取消以前没打，导致"UP 丢了"这类问题无法定位）。
        if (action != MotionEvent.ACTION_MOVE) {
            Log.i(
                TAG,
                "touch action=${actionName(action)} (${x.toInt()},${y.toInt()}) device=${event.deviceId} flags=${event.flags}",
            )
        }
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (capturing) {
                    // 上一次触摸没有正常结束就又来一次按下：说明那一次被系统取消了，这里按"丢弃"处理。
                    Log.w(TAG, "上一次触摸没有 UP/CANCEL 就收到新的 DOWN，丢弃上一次")
                    listener.onCancel()
                }
                capturing = true
                downX = x
                downY = y
                downTimeMs = time
                sampleXs.clear()
                sampleYs.clear()
                sample(x, y, force = true)
                listener.onDown(x, y, time)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!capturing) return
                sample(x, y, force = false)
                listener.onMove(x, y, time)
            }

            MotionEvent.ACTION_UP -> {
                if (!capturing) return
                capturing = false
                sample(x, y, force = true)
                listener.onUp(x, y, time)
                requestForward { performForward(x, y, time) }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (!capturing) return
                capturing = false
                sampleXs.clear()
                sampleYs.clear()
                listener.onCancel()
                Log.i(TAG, "触摸被系统取消（本次不记录、也不回放）")
            }
        }
        drainForwards()
    }

    private fun actionName(action: Int): String = when (action) {
        MotionEvent.ACTION_DOWN -> "DOWN"
        MotionEvent.ACTION_UP -> "UP"
        MotionEvent.ACTION_CANCEL -> "CANCEL"
        else -> action.toString()
    }

    /**
     * 排队注入：只要有在途触摸或正在注入，就等它结束再注入。
     *
     * 目的：绝不在用户手指按着的时候注入 —— 注入会把用户正在进行的触摸取消掉。
     */
    private fun requestForward(task: () -> Unit) {
        pendingForwards.addLast(task)
        drainForwards()
    }

    private fun drainForwards() {
        if (capturing || injecting) return
        val task = pendingForwards.removeFirstOrNull() ?: return
        task()
    }

    /** 采样：相邻采样点距离 ≥ [SAMPLE_MIN_DISTANCE_PX] 才记；按下 / 抬起强制记录（但仍要求不重合）。 */
    private fun sample(x: Float, y: Float, force: Boolean) {
        if (sampleXs.isEmpty()) {
            sampleXs.add(x)
            sampleYs.add(y)
            return
        }
        val distance = hypot(x - sampleXs.last(), y - sampleYs.last())
        if (distance < MIN_DISTINCT_PX) return
        if (!force && distance < SAMPLE_MIN_DISTANCE_PX) return
        sampleXs.add(x)
        sampleYs.add(y)
    }

    /**
     * 把刚结束的手势注入回系统（否则触摸被本层吃掉，用户在目标 App 里点哪都没反应）。
     *
     * 位移 ≤ [TAP_SLOP_PX] 按"点击"处理（只有 moveTo 的单点），否则按"滑动"处理。
     */
    private fun performForward(upX: Float, upY: Float, upTimeMs: Long) {
        val service = SlideIndexAccessibilityService.accessibilityInstance()
        if (service == null) {
            Log.w(TAG, "无障碍服务不可用：无法回注，结束录制")
            onInjectionUnavailable()
            return
        }
        val pressMs = (upTimeMs - downTimeMs).coerceAtLeast(1L)
        val moved = hypot(upX - downX, upY - downY) > TAP_SLOP_PX
        val path = Path().apply { moveTo(downX, downY) }
        if (moved) {
            for (index in 1 until sampleXs.size) {
                path.lineTo(sampleXs[index], sampleYs[index])
            }
            path.lineTo(upX, upY)
        }
        val durationMs = if (moved) {
            pressMs.coerceIn(SWIPE_MIN_MS, SWIPE_MAX_MS)
        } else {
            pressMs.coerceIn(TAP_MIN_MS, TAP_MAX_MS)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()

        injecting = true
        val generation = ++injectionGeneration
        // 摘掉整屏窗：注入的事件就不会落回我们自己（等价于"引擎回放"那套已验证可行的环境）。
        detachForInjection()

        // 等窗口真正摘除后再注入。
        mainHandler.postDelayed({
            if (generation != injectionGeneration) return@postDelayed
            val result = runCatching {
                service.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            Log.i(TAG, "注入完成 moved=$moved dur=${durationMs}ms points=${sampleXs.size}")
                            endInjection(generation)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            Log.w(TAG, "注入被取消 moved=$moved dur=${durationMs}ms")
                            endInjection(generation)
                        }
                    },
                    null,
                )
            }
            val accepted = result.getOrDefault(false)
            if (result.isFailure) {
                Log.w(TAG, "注入抛异常", result.exceptionOrNull())
            }
            Log.i(TAG, "注入受理 accepted=$accepted moved=$moved dur=${durationMs}ms points=${sampleXs.size}")
            if (!accepted) {
                injectionFailures++
                endInjection(generation)
                // 连续失败说明这台机器上"吞触摸 + 回注"这条路走不通：
                // 宁可结束录制把屏幕还给用户，也不要让触摸一直被吞。
                if (injectionFailures >= MAX_INJECTION_FAILURES) {
                    Log.w(TAG, "注入连续失败 $injectionFailures 次，结束录制以恢复触摸")
                    onInjectionUnavailable()
                }
            }
        }, INJECTION_APPLY_DELAY_MS)

        // 兜底：万一回调没来，也别把触摸永久关掉（按代次判定，不会误伤下一次注入）。
        mainHandler.postDelayed(
            { endInjection(generation) },
            INJECTION_APPLY_DELAY_MS + durationMs + INJECTION_TIMEOUT_EXTRA_MS,
        )
    }

    private fun endInjection(generation: Int) {
        if (generation != injectionGeneration) return
        if (!injecting) return
        injecting = false
        reattachAfterInjection()
        // 队列里可能还压着"用户已经做完、但当时正在注入"的手势，现在补注入。
        drainForwards()
    }

    /** 注入期间把整屏窗摘掉：这样注入的事件不会落回我们自己，环境与"引擎回放"一致。 */
    private fun detachForInjection() {
        if (!attached) return
        val view = rootView ?: return
        attached = false
        runCatching { windowManager?.removeView(view) }
            .onFailure { Log.w(TAG, "摘除捕获层失败", it) }
        Log.i(TAG, "捕获层已摘除（准备注入）")
    }

    /** 注入结束后把整屏窗装回去。 */
    private fun reattachAfterInjection() {
        if (attached) return
        val view = rootView ?: return
        val params = layoutParams ?: return
        attached = true
        runCatching { windowManager?.addView(view, params) }
            .onFailure {
                Log.w(TAG, "装回捕获层失败", it)
                attached = false
            }
        Log.i(TAG, "捕获层已装回")
    }

    private companion object {
        const val TAG = "MacroTouchCapture"

        /** 采样抽稀阈值（与录制器一致）。 */
        const val SAMPLE_MIN_DISTANCE_PX = 24f

        /** 完全重合的点不重复记。 */
        const val MIN_DISTINCT_PX = 1f

        /** 位移不超过这个值就算"点击"。 */
        const val TAP_SLOP_PX = 40f

        const val TAP_MIN_MS = 30L
        const val TAP_MAX_MS = 2_000L
        const val SWIPE_MIN_MS = 50L
        const val SWIPE_MAX_MS = 5_000L

        /** 注入前等 WMS 应用窗口标志的时间。 */
        const val INJECTION_APPLY_DELAY_MS = 80L

        /** 注入回调的兜底等待余量。 */
        const val INJECTION_TIMEOUT_EXTRA_MS = 800L

        /** 连续这么多次注入失败就结束录制（把触摸还给用户）。 */
        const val MAX_INJECTION_FAILURES = 2
    }
}
