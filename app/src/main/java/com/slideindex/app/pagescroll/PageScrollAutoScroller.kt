package com.slideindex.app.pagescroll

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import com.slideindex.app.R
import com.slideindex.app.service.SlideIndexAccessibilityService
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 「页面滚动」的自动滚动引擎。
 *
 * 做法：**注入一条划动手势，然后不断把它续接下去** ——
 * 1. 起步段（48ms，`willContinue = true`）先把"手指"按下并推动一点；
 * 2. 巡航段（时长按速度档算）走完主要行程；
 * 3. 收尾微步（16ms、末端 ±1px、不再续接）让惯性收敛；
 * 4. 上一轮结束后再发下一轮 —— 于是页面持续平滑滚动，直到用户暂停/关闭。
 *
 * 平台差异：`continueStroke` 需要 API 26+；更低版本退化成"每一轮发一整段"（同样能滚，只是不够连续）。
 */
internal class PageScrollAutoScroller(
    private val context: Context,
    private val onStateChanged: () -> Unit,
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = max(0.1f, context.resources.displayMetrics.density)
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    private var bar: PageScrollControlBar? = null
    private var cycleTask: Runnable? = null
    private var generation = 0

    private var settings: PageScrollSettings = PageScrollPrefs.read(context)
    private var directionDown = true
    private var paused = false
    private var strokeInFlight = false
    private var userTouchingControls = false
    private var consecutiveFailures = 0

    val isRunning: Boolean get() = bar?.isShowing == true

    /** 当前是否处于暂停（控制条显示 ▶）。 */
    val isPaused: Boolean get() = paused

    fun toggle(side: PageScrollControlBar.Side, anchorRawY: Float): Boolean {
        if (isRunning) {
            stop()
            return true
        }
        return start(side, anchorRawY)
    }

    fun start(side: PageScrollControlBar.Side, anchorRawY: Float): Boolean {
        val service = SlideIndexAccessibilityService.accessibilityInstance() ?: run {
            toast(R.string.page_scroll_service_unavailable)
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            toast(R.string.page_scroll_requires_android_n)
            return false
        }
        settings = PageScrollPrefs.read(context)
        paused = false
        consecutiveFailures = 0
        directionDown = true
        generation++
        val controlBar = bar ?: PageScrollControlBar(context, { settings }, barListener).also { bar = it }
        if (!controlBar.show(side, anchorRawY)) {
            toast(R.string.page_scroll_unavailable)
            return false
        }
        controlBar.refresh(settings, paused = false, directionDown = directionDown)
        PageScrollPrefs.saveLastSide(context, side.name)
        scheduleNextCycle(delayMs = START_DELAY_MS)
        onStateChanged()
        return true
    }

    fun stop() {
        generation++
        cycleTask?.let { mainHandler.removeCallbacks(it) }
        cycleTask = null
        strokeInFlight = false
        paused = false
        consecutiveFailures = 0
        bar?.dismiss()
        onStateChanged()
        // 引擎自己从无障碍服务取，无需额外释放。
        @Suppress("UNUSED_EXPRESSION")
        SlideIndexAccessibilityService.accessibilityInstance()
    }

    private val barListener = object : PageScrollControlBar.Listener {
        override fun onReverseDirection() {
            directionDown = !directionDown
            bar?.refresh(settings, paused, directionDown)
            restartNow()
        }

        override fun onSpeedDown() = changeSpeed(-1)

        override fun onSpeedUp() = changeSpeed(+1)

        override fun onTogglePause() {
            paused = !paused
            bar?.refresh(settings, paused, directionDown)
            if (paused) {
                cycleTask?.let { mainHandler.removeCallbacks(it) }
                cycleTask = null
            } else {
                restartNow()
            }
        }

        override fun onClose() = stop()

        override fun onUserTouchingControls(touching: Boolean) {
            userTouchingControls = touching
        }
    }

    private fun changeSpeed(delta: Int) {
        val next = (settings.speedLevel + delta).coerceIn(
            PageScrollSettings.MIN_SPEED_LEVEL,
            PageScrollSettings.MAX_SPEED_LEVEL,
        )
        if (next == settings.speedLevel) return
        settings = settings.copy(speedLevel = next)
        PageScrollPrefs.saveSpeedLevel(context, next)
        bar?.refresh(settings, paused, directionDown)
        restartNow()
    }

    private fun restartNow() {
        if (!isRunning || paused) return
        generation++
        cycleTask?.let { mainHandler.removeCallbacks(it) }
        strokeInFlight = false
        scheduleNextCycle(delayMs = 0L)
    }

    private fun scheduleNextCycle(delayMs: Long) {
        val token = generation
        val task = Runnable { runCycle(token) }
        cycleTask = task
        mainHandler.postDelayed(task, delayMs)
    }

    private fun runCycle(token: Int) {
        if (token != generation || !isRunning || paused) return
        if (strokeInFlight || userTouchingControls) {
            // 上一段还在飞 / 用户正按着控制条：稍后再试。
            scheduleNextCycle(RETRY_DELAY_MS)
            return
        }
        val service = SlideIndexAccessibilityService.accessibilityInstance()
        if (service == null) {
            stop()
            toast(R.string.page_scroll_service_unavailable)
            return
        }
        val screen = realScreenSize()
        val plan = planStroke(screen) ?: run {
            stop()
            toast(R.string.page_scroll_unavailable)
            return
        }

        strokeInFlight = true
        val dispatched = dispatchStroke(service, plan, token)
        if (!dispatched) {
            strokeInFlight = false
            consecutiveFailures++
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                stop()
                toast(R.string.page_scroll_unavailable)
            } else {
                scheduleNextCycle(RETRY_DELAY_MS)
            }
        }
    }

    /** 一段滚动行程的几何。 */
    private data class StrokePlan(
        val x: Float,
        val startY: Float,
        val primerEndY: Float,
        val endY: Float,
        val cruiseDurationMs: Long,
        val legacyDurationMs: Long,
    )

    private fun planStroke(screen: Point): StrokePlan? {
        val width = screen.x.toFloat()
        val height = screen.y.toFloat()
        if (width <= 0f || height <= 0f) return null

        val speed = settings.speedDpPerSecond
        // 向上滚：从屏幕偏下往上划；向下滚：从偏上往下划。
        val startY = if (directionDown) height * DOWN_START_RATIO else height * UP_START_RATIO
        val travel = if (directionDown) {
            height * DOWN_TRAVEL_RATIO
        } else {
            height * UP_TRAVEL_RATIO
        }
        if (travel <= 0f) return null

        val primer = min(max(PRIMER_MIN_DP * density, touchSlop * 1.5f), travel / 3f)
        val cruise = min(travel - primer, speed * density * CRUISE_SECONDS)
        val total = primer + cruise
        val sign = if (directionDown) 1f else -1f

        val cruiseMs = (((cruise / density) / speed) * 1000f).roundToInt().toLong().coerceIn(1L, 6000L)
        val legacyMs = (((total / density) / max(speed, LEGACY_SPEED_FLOOR)) * 1000f)
            .roundToInt().toLong().coerceIn(48L, 6000L)

        return StrokePlan(
            x = width * X_RATIO_CENTER,
            startY = startY,
            primerEndY = startY + sign * primer,
            endY = startY + sign * total,
            cruiseDurationMs = cruiseMs,
            legacyDurationMs = legacyMs,
        )
    }

    private fun dispatchStroke(service: AccessibilityService, plan: StrokePlan, token: Int): Boolean {
        val primerPath = Path().apply {
            moveTo(plan.x, plan.startY)
            lineTo(plan.x, plan.primerEndY)
        }
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dispatchGesture(service, primerPath, PRIMER_DURATION_MS, willContinue = true) { ok ->
                if (!ok || token != generation || !isRunning || paused) {
                    strokeInFlight = false
                    if (ok && token == generation && isRunning && !paused) scheduleNextCycle(0L)
                } else {
                    continueCruise(service, plan, token)
                }
            }
        } else {
            dispatchGesture(service, primerPath, plan.legacyDurationMs, willContinue = false) { ok ->
                strokeInFlight = false
                if (ok && token == generation && isRunning && !paused) scheduleNextCycle(RETRY_DELAY_MS)
            }
        }
        return accepted
    }

    private fun continueCruise(service: AccessibilityService, plan: StrokePlan, token: Int) {
        val cruisePath = Path().apply {
            moveTo(plan.x, plan.primerEndY)
            lineTo(plan.x, plan.endY)
        }
        val accepted = runCatching {
            dispatchGesture(service, cruisePath, plan.cruiseDurationMs, willContinue = true) { ok ->
                if (!ok || token != generation || !isRunning || paused) {
                    strokeInFlight = false
                    if (ok && token == generation && isRunning && !paused) scheduleNextCycle(0L)
                } else {
                    continueSettle(service, plan, token)
                }
            }
        }.getOrDefault(false)
        if (!accepted) {
            strokeInFlight = false
            scheduleNextCycle(RETRY_DELAY_MS)
        }
    }

    private fun continueSettle(service: AccessibilityService, plan: StrokePlan, token: Int) {
        val settlePath = Path().apply {
            moveTo(plan.x, plan.endY)
            lineTo(plan.x, plan.endY + if (directionDown) 1f else -1f)
        }
        val accepted = runCatching {
            dispatchGesture(service, settlePath, SETTLE_DURATION_MS, willContinue = false) { _ ->
                strokeInFlight = false
                if (token == generation && isRunning && !paused) scheduleNextCycle(0L)
            }
        }.getOrDefault(false)
        if (!accepted) {
            strokeInFlight = false
            scheduleNextCycle(RETRY_DELAY_MS)
        }
    }

    /** 派发一段手势；`onFinish` 只在主线程回调一次（完成或取消）。 */
    private fun dispatchGesture(
        service: AccessibilityService,
        path: Path,
        durationMs: Long,
        willContinue: Boolean,
        onFinish: (Boolean) -> Unit,
    ): Boolean {
        val stroke = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            GestureDescription.StrokeDescription(path, 0L, durationMs, willContinue)
        } else {
            GestureDescription.StrokeDescription(path, 0L, durationMs)
        }
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        var finished = false
        return runCatching {
            service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (finished) return
                        finished = true
                        onFinish(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (finished) return
                        finished = true
                        onFinish(false)
                    }
                },
                null,
            )
        }.getOrElse {
            Log.w(TAG, "手势派发失败", it)
            false
        }
    }

    private fun realScreenSize(): Point {
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        manager?.defaultDisplay?.getRealMetrics(metrics)
        return Point(max(1, metrics.widthPixels), max(1, metrics.heightPixels))
    }

    private fun toast(resId: Int) {
        runCatching { Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show() }
    }

    private companion object {
        const val TAG = "PageScrollAuto"

        /** 显示控制条之后多久开始滚动（给用户一点反应时间）。 */
        const val START_DELAY_MS = 320L

        /** 一轮结束后 / 重试的间隔。 */
        const val RETRY_DELAY_MS = 100L

        const val PRIMER_DURATION_MS = 48L
        const val SETTLE_DURATION_MS = 16L

        /** 起步段最少 12dp，并且不小于 1.5 倍 touchSlop。 */
        const val PRIMER_MIN_DP = 12f

        /** 巡航段最多覆盖"6 秒按当前速度能走的距离"。 */
        const val CRUISE_SECONDS = 6f

        /** 低版本整段时长用的速度下限（避免除零/过短）。 */
        const val LEGACY_SPEED_FLOOR = 120f

        const val DOWN_START_RATIO = 0.1f
        const val DOWN_TRAVEL_RATIO = 0.8f
        const val UP_START_RATIO = 0.9f
        const val UP_TRAVEL_RATIO = 0.68f

        /** 手势路径的横向位置：走屏幕中线。 */
        const val X_RATIO_CENTER = 0.5f

        const val MAX_CONSECUTIVE_FAILURES = 3
    }
}
