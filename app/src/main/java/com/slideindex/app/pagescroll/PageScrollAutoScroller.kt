package com.slideindex.app.pagescroll

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import com.slideindex.app.R
import com.slideindex.app.service.SlideIndexAccessibilityService
import kotlin.math.abs
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
    private var barSide = PageScrollControlBar.Side.RIGHT
    private var paused = false
    private var strokeInFlight = false
    private var userTouchingControls = false
    private var consecutiveFailures = 0

    /** 有一段手势还在飞时，收到"改参数/重开"请求先记下来，等这段走完再执行。 */
    private var restartRequested = false

    /** 当前这一轮手势里"最近一段"的 StrokeDescription：下一段必须由它 continueStroke 出来。 */
    private var activeStroke: GestureDescription.StrokeDescription? = null

    /** 连续被系统取消的次数：用户正在摸屏幕时会被取消，这时候要退避，否则会一直注入短手势（看起来像单击）。 */
    private var cancelStreak = 0

    // ---- 诊断用（只打日志，不影响行为）：定位"停顿感"与误点需要每轮的时长与间隔 ----

    /** 当前轮次序号 / 本轮开始时间 / 上一轮结束时间（uptimeMillis）。 */
    private var cycleIndex = 0
    private var cycleStartedAt = 0L
    private var lastCycleFinishedAt = 0L

    /** 当前处于哪一段（primer / cruise / settle），取消时用来说明卡在哪。 */
    private var currentStage = "idle"

    private fun logStage(stage: String) {
        Log.d(
            TAG,
            "$stage 完成 (+${SystemClock.uptimeMillis() - cycleStartedAt}ms) cycle#$cycleIndex",
        )
    }

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
        barSide = side
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
        restartRequested = false
        cancelStreak = 0
        bar?.dismiss()
        onStateChanged()
        // 引擎自己从无障碍服务取，无需额外释放。
        @Suppress("UNUSED_EXPRESSION")
        SlideIndexAccessibilityService.accessibilityInstance()
    }

    private val barListener = object : PageScrollControlBar.Listener {
        override fun onReverseDirection() {
            directionDown = !directionDown
            Log.d(TAG, "用户切换方向 → ${if (directionDown) "DOWN" else "UP"}")
            bar?.refresh(settings, paused, directionDown)
            restartNow()
        }

        override fun onSpeedDown() = changeSpeed(-1)

        override fun onSpeedUp() = changeSpeed(+1)

        override fun onTogglePause() {
            paused = !paused
            Log.d(TAG, if (paused) "用户暂停" else "用户继续")
            bar?.refresh(settings, paused, directionDown)
            if (paused) {
                // 只标记暂停：让正在飞的那段自然走完（切掉它就会变成"半截手势"→ 单击）。
                restartRequested = false
                cycleTask?.let { mainHandler.removeCallbacks(it) }
                cycleTask = null
            } else {
                restartNow()
            }
        }

        override fun onClose() {
            Log.d(TAG, "用户关闭")
            stop()
        }

        override fun onUserTouchingControls(touching: Boolean) {
            if (touching != userTouchingControls) {
                Log.d(TAG, if (touching) "手指按在控制条上" else "手指离开控制条")
            }
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
        if (strokeInFlight) {
            // 关键：不要在有手势在飞时另起一段（会把手势切断成"半截"，目标 App 会当成单击）。
            restartRequested = true
            return
        }
        generation++
        cycleTask?.let { mainHandler.removeCallbacks(it) }
        scheduleNextCycle(delayMs = 0L)
    }

    /** 一段手势正常走完（primer→cruise→settle）。 */
    private fun onCycleFinished(token: Int) {
        strokeInFlight = false
        cancelStreak = 0
        activeStroke = null
        currentStage = "idle"
        val now = SystemClock.uptimeMillis()
        Log.d(TAG, "cycle#$cycleIndex 正常结束 总耗时=${now - cycleStartedAt}ms")
        lastCycleFinishedAt = now
        if (restartRequested) {
            restartRequested = false
            Log.d(TAG, "有排队中的重启请求，立即开下一轮")
            if (token == generation && isRunning && !paused) scheduleNextCycle(0L)
            return
        }
        if (token == generation && isRunning && !paused) scheduleNextCycle(0L)
    }

    /** 一段手势被系统取消：退避重试，连续被取消就先停，避免不停注入短手势。 */
    private fun onCycleCancelled(token: Int) {
        strokeInFlight = false
        restartRequested = false
        activeStroke = null
        cancelStreak++
        val now = SystemClock.uptimeMillis()
        Log.w(
            TAG,
            "cycle#$cycleIndex 被取消 卡在=$currentStage 已过=${now - cycleStartedAt}ms " +
                "连续第${cancelStreak}次",
        )
        currentStage = "idle"
        lastCycleFinishedAt = now
        if (cancelStreak >= MAX_CANCEL_STREAK) {
            cancelStreak = 0
            paused = true
            Log.w(TAG, "连续被取消 $MAX_CANCEL_STREAK 次，先暂停滚动")
            bar?.refresh(settings, paused = true, directionDown = directionDown)
            onStateChanged()
            return
        }
        if (token == generation && isRunning && !paused) scheduleNextCycle(CANCEL_RETRY_MS)
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
            Log.d(
                TAG,
                "跳过本轮：strokeInFlight=$strokeInFlight userTouchingControls=$userTouchingControls",
            )
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
        cycleIndex++
        val nowUptime = SystemClock.uptimeMillis()
        val gapMs = if (lastCycleFinishedAt > 0L) nowUptime - lastCycleFinishedAt else -1L
        cycleStartedAt = nowUptime
        currentStage = "primer"
        Log.d(
            TAG,
            "cycle#$cycleIndex 开始 dir=${if (directionDown) "DOWN" else "UP"} side=$barSide " +
                "x=${plan.x.roundToInt()} startY=${plan.startY.roundToInt()} endY=${plan.endY.roundToInt()} " +
                "primer=${PRIMER_DURATION_MS}ms cruise=${plan.cruiseDurationMs}ms legacy=${plan.legacyDurationMs}ms " +
                "level=${settings.speedLevel} 距上轮结束=${gapMs}ms",
        )
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
        // 起终点只落在**可滚动区域**内：实测 QQ 聊天页列表是 0.097h..0.887h、输入框上沿 0.894h，
        // 所以向下滚的起点取 0.85h（而不是贴着底边的 0.9h，那会落到输入框上 → 松手弹键盘）。
        val startY = if (directionDown) height * BOTTOM_ANCHOR_RATIO else height * TOP_ANCHOR_RATIO
        val endAnchorY = if (directionDown) height * TOP_ANCHOR_RATIO else height * BOTTOM_ANCHOR_RATIO
        val travel = abs(endAnchorY - startY)
        if (travel <= 0f) return null

        val primer = min(max(PRIMER_MIN_DP * density, touchSlop * 1.5f), travel / 3f)
        val cruise = min(travel - primer, speed * density * CRUISE_SECONDS)
        val total = primer + cruise
        val sign = if (directionDown) -1f else 1f

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

    /** 一段手势的起点：按下并推动起步段（willContinue=true，接下来要续接它）。 */
    private fun dispatchStroke(service: AccessibilityService, plan: StrokePlan, token: Int): Boolean {
        val primerPath = Path().apply {
            moveTo(plan.x, plan.startY)
            lineTo(plan.x, plan.primerEndY)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // 没有续接能力就只发一整段（一次按下、一次抬起）；绝不发"移 1px"那种手势。
            val fullPath = Path().apply {
                moveTo(plan.x, plan.startY)
                lineTo(plan.x, plan.endY)
            }
            return dispatchStrokeDescription(
                service = service,
                stroke = GestureDescription.StrokeDescription(fullPath, 0L, plan.legacyDurationMs),
            ) { ok -> if (ok) onCycleFinished(token) else onCycleCancelled(token) }
        }

        val primer = GestureDescription.StrokeDescription(primerPath, 0L, PRIMER_DURATION_MS, true)
        activeStroke = primer
        return dispatchStrokeDescription(service, primer) { ok ->
            if (ok) {
                logStage("primer")
                currentStage = "cruise"
                // 不管中途有没有收到"停/暂停/改参数"，都把这一轮走完（一次真实的滑动），
                // 否则手势会在半截结束 —— 那才是被 App 当成单击的原因。
                continueCruise(service, plan, token)
            } else {
                onCycleCancelled(token)
            }
        }
    }

    private fun continueCruise(service: AccessibilityService, plan: StrokePlan, token: Int) {
        val cruisePath = Path().apply {
            moveTo(plan.x, plan.primerEndY)
            lineTo(plan.x, plan.endY)
        }
        val next = runCatching {
            activeStroke?.continueStroke(cruisePath, 0L, plan.cruiseDurationMs, true)
        }.getOrNull()
        if (next == null) {
            Log.w(TAG, "cruise 续接失败，补一整段完成本轮 cycle#$cycleIndex")
            val restPath = Path().apply {
                moveTo(plan.x, plan.primerEndY)
                lineTo(plan.x, plan.endY)
            }
            val ok = dispatchStrokeDescription(
                service = service,
                stroke = GestureDescription.StrokeDescription(restPath, 0L, plan.legacyDurationMs),
            ) { finished -> if (finished) onCycleFinished(token) else onCycleCancelled(token) }
            if (!ok) {
                strokeInFlight = false
                scheduleNextCycle(RETRY_DELAY_MS)
            }
            return
        }
        activeStroke = next
        val accepted = dispatchStrokeDescription(service, next) { ok ->
            if (ok) {
                logStage("cruise")
                currentStage = "settle"
                continueSettle(service, plan, token)
            } else {
                onCycleCancelled(token)
            }
        }
        if (!accepted) {
            Log.w(TAG, "cruise 派发失败 cycle#$cycleIndex")
            strokeInFlight = false
            scheduleNextCycle(RETRY_DELAY_MS)
        }
    }

    private fun continueSettle(service: AccessibilityService, plan: StrokePlan, token: Int) {
        // 末端再走 1px 收尾（willContinue=false），让这条手势以"滑动结束"收场。
        val settlePath = Path().apply {
            moveTo(plan.x, plan.endY)
            lineTo(plan.x, plan.endY + if (directionDown) 1f else -1f)
        }
        val next = runCatching {
            activeStroke?.continueStroke(settlePath, 0L, SETTLE_DURATION_MS, false)
        }.getOrNull()
        if (next == null) {
            // 收尾续接失败也没关系：手势已经走过真实行程，自然结束即可。
            Log.d(TAG, "settle 续接失败，本轮就此结束 cycle#$cycleIndex")
            activeStroke = null
            onCycleFinished(token)
            return
        }
        activeStroke = next
        val accepted = dispatchStrokeDescription(service, next) { ok ->
            if (ok) {
                logStage("settle")
                onCycleFinished(token)
            } else {
                onCycleCancelled(token)
            }
        }
        if (!accepted) {
            Log.w(TAG, "settle 派发失败 cycle#$cycleIndex")
            strokeInFlight = false
            scheduleNextCycle(RETRY_DELAY_MS)
        }
    }

    /** 派发一个具体的 StrokeDescription；`onFinish` 只在主线程回调一次（完成或取消）。 */
    private fun dispatchStrokeDescription(
        service: AccessibilityService,
        stroke: GestureDescription.StrokeDescription,
        onFinish: (Boolean) -> Unit,
    ): Boolean {
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

        /** 起终点（× 屏幕高）：只落在可滚动区域内，避开底部输入栏/顶栏。 */
        const val BOTTOM_ANCHOR_RATIO = 0.85f
        const val TOP_ANCHOR_RATIO = 0.12f

        /** 手势路径的横向位置：走屏幕中线（竖向滚动时横向位置基本无影响）。 */
        const val X_RATIO_CENTER = 0.5f

        const val MAX_CONSECUTIVE_FAILURES = 3

        /** 手势被系统取消后的退避时间（用户多半正在摸屏幕）。 */
        const val CANCEL_RETRY_MS = 400L

        /** 连续被取消这么多次就先停下来。 */
        const val MAX_CANCEL_STREAK = 3
    }
}
