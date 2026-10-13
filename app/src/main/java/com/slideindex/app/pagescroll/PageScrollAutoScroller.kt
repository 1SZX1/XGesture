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
import android.view.WindowManager
import android.widget.Toast
import com.slideindex.app.R
import com.slideindex.app.service.SlideIndexAccessibilityService
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 「页面滚动」的自动滚动引擎。
 *
 * 一次拖动按 [SEGMENT_MS] 切成小段下发，段边界就是响应点：
 * - 切方向 / 调速：下一段立刻按新参数走（最多等一段）
 * - 停止 / 暂停：不再续接，当前段以真实位移自然收尾（不做 1px 微步，避免被当成单击）
 *
 * 路径只在**可滚动列表内部**（实测 QQ 聊天页：列表 y 227..2076、输入框 2093..2206）：
 * 按下点与抬起点都在列表里 → 触摸流归列表所有，拖动中会被列表拦截 → 不会误点、不会聚焦输入框。
 * 方向：箭头 ↓（看下面内容）= 手指从列表下方往上划；↑ 相反。
 *
 * 平台差异：`continueStroke` 需要 API 26+；更低版本每段独立下发（同样只走列表内部）。
 */
internal class PageScrollAutoScroller(
    private val context: Context,
    private val onStateChanged: () -> Unit,
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = max(0.1f, context.resources.displayMetrics.density)

    private var bar: PageScrollControlBar? = null
    private var cycleTask: Runnable? = null
    private var generation = 0

    private var settings: PageScrollSettings = PageScrollPrefs.read(context)
    private var directionDown = true
    private var paused = false
    private var userTouchingControls = false
    private var consecutiveFailures = 0
    private var cancelStreak = 0

    /** 当前这段的 StrokeDescription：下一段必须由它 continueStroke 出来。 */
    private var activeStroke: GestureDescription.StrokeDescription? = null

    /** 手指当前所在的 y（跨段累计）。 */
    private var currentY = 0f

    private var cycleActive = false
    private var segmentIndex = 0

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
        if (SlideIndexAccessibilityService.accessibilityInstance() == null) {
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
        cancelStreak = 0
        directionDown = true
        cycleActive = false
        activeStroke = null
        // 上一轮若在"手指按在控制条上"状态里被收尾（抬手事件没到），这个标记会一直是 true，
        // 之后每一轮都会"跳过本轮" → 滑动彻底停住。起始时复位。
        userTouchingControls = false
        generation++
        val controlBar = bar ?: PageScrollControlBar(context, { settings }, barListener).also { bar = it }
        if (!controlBar.show(side, anchorRawY)) {
            toast(R.string.page_scroll_unavailable)
            return false
        }
        controlBar.refresh(settings, paused = false, directionDown = directionDown)
        PageScrollPrefs.saveLastSide(context, side.name)
        scheduleNextCycle(START_DELAY_MS)
        onStateChanged()
        return true
    }

    fun stop() {
        generation++
        cycleTask?.let { mainHandler.removeCallbacks(it) }
        cycleTask = null
        // 不再续接即可：当前这段会自然走完（真实位移收尾），随后手势结束。
        cycleActive = false
        activeStroke = null
        paused = false
        consecutiveFailures = 0
        cancelStreak = 0
        userTouchingControls = false
        bar?.dismiss()
        onStateChanged()
    }

    private val barListener = object : PageScrollControlBar.Listener {
        override fun onReverseDirection() {
            directionDown = !directionDown
            // 不需要打断手势：下一段的段边界会按新方向走。
            bar?.refresh(settings, paused, directionDown)
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
                scheduleNextCycle(0L)
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
        setSpeed(next)
    }

    /** 直接切到某一档并落盘。 */
    private fun setSpeed(level: Int) {
        val next = level.coerceIn(PageScrollSettings.MIN_SPEED_LEVEL, PageScrollSettings.MAX_SPEED_LEVEL)
        if (next == settings.speedLevel) return
        settings = settings.copy(speedLevel = next)
        PageScrollPrefs.saveSpeedLevel(context, next)
        bar?.refresh(settings, paused, directionDown)
    }

    private fun scheduleNextCycle(delayMs: Long) {
        if (!isRunning || paused) return
        val token = generation
        val task = Runnable { startCycle(token) }
        cycleTask = task
        mainHandler.postDelayed(task, delayMs)
    }

    /** 开始一条新的拖动（从当前方向的"按下锚点"起手）。 */
    private fun startCycle(token: Int) {
        if (token != generation || !isRunning || paused) return
        if (cycleActive || userTouchingControls) {
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
        if (screen.x <= 0 || screen.y <= 0) {
            stop()
            toast(R.string.page_scroll_unavailable)
            return
        }
        val band = bandOf(screen.y.toFloat())
        currentY = if (directionDown) band.second else band.first
        cycleActive = true
        segmentIndex = 0
        dispatchSegment(service, token)
    }

    /** 列表内可用的一段竖直范围（上、下锚点，按屏幕高比例取）。 */
    private fun bandOf(height: Float): Pair<Float, Float> =
        height * PRESS_TOP_RATIO to height * PRESS_BOTTOM_RATIO

    /**
     * 下发下一段，并在完成回调里决定"要不要继续接"。
     *
     * - 每段都重新读 `directionDown` / `settings` → 段边界即响应点
     * - 到达锚点或收到停止/暂停 → 这一段用 `willContinue=false` 收尾
     * - 续接失败 → 补一整段真实行程，绝不发微小位移
     */
    private fun dispatchSegment(service: AccessibilityService, token: Int) {
        if (token != generation) {
            endCycleState()
            return
        }
        val screen = realScreenSize()
        if (screen.x <= 0 || screen.y <= 0) {
            finishCycle(token)
            return
        }
        val x = screen.x * X_RATIO_CENTER
        val band = bandOf(screen.y.toFloat())
        val speed = settings.speedDpPerSecond
        val dir = if (directionDown) -1f else 1f

        val stepPx = speed * density * SEGMENT_MS / 1000f
        val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        val wanted = if (segmentIndex == 0) max(stepPx, touchSlop * 2f) else stepPx
        val limit = if (directionDown) band.first else band.second
        val rawNext = currentY + dir * wanted
        val reachedEnd = if (directionDown) rawNext <= limit else rawNext >= limit
        val nextY = if (reachedEnd) limit else rawNext
        val travelPx = abs(nextY - currentY)
        if (travelPx <= 0.5f) {
            finishCycle(token)
            return
        }
        val durationMs = ((travelPx / density) / speed * 1000f)
            .roundToInt().toLong()
            .coerceIn(MIN_SEGMENT_MS, MAX_SEGMENT_MS)

        val willContinue = !reachedEnd && !paused && isRunning

        val path = Path().apply {
            moveTo(x, currentY)
            lineTo(x, nextY)
        }
        val canContinue = segmentIndex > 0 &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            activeStroke != null
        val stroke = if (canContinue) {
            runCatching {
                activeStroke!!.continueStroke(path, 0L, durationMs, willContinue)
            }.getOrNull()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            GestureDescription.StrokeDescription(path, 0L, durationMs, willContinue)
        } else {
            GestureDescription.StrokeDescription(path, 0L, durationMs)
        }
        if (stroke == null) {
            // 续接失败：从当前位置直接走到锚点，保证有真实位移。
            val restPath = Path().apply {
                moveTo(x, currentY)
                lineTo(x, if (directionDown) band.first else band.second)
            }
            val restStroke = GestureDescription.StrokeDescription(restPath, 0L, LEGACY_REST_MS)
            activeStroke = restStroke
            segmentIndex++
            dispatchStrokeDescription(service, restStroke, token)
            return
        }

        activeStroke = stroke
        segmentIndex++
        currentY = nextY
        dispatchStrokeDescription(service, stroke, token)
    }

    private fun dispatchStrokeDescription(
        service: AccessibilityService,
        stroke: GestureDescription.StrokeDescription,
        token: Int,
    ) {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val accepted = runCatching {
            service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        onSegmentCompleted(token)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        onSegmentCancelled(token)
                    }
                },
                null,
            )
        }.getOrDefault(false)
        if (!accepted) onSegmentCancelled(token)
    }

    private fun onSegmentCompleted(token: Int) {
        if (token != generation) {
            // 已停止/重启：不再续接，手势到这里自然结束。
            endCycleState()
            return
        }
        val screen = realScreenSize()
        val band = bandOf(screen.y.toFloat())
        val atEnd = if (directionDown) currentY <= band.first + 0.5f else currentY >= band.second - 0.5f
        val wantsStop = paused || !isRunning
        if (wantsStop || atEnd) {
            endCycleState()
            if (!wantsStop) scheduleNextCycle(0L) else onStateChanged()
            return
        }
        val service = SlideIndexAccessibilityService.accessibilityInstance()
        if (service == null) {
            endCycleState()
            stop()
            return
        }
        dispatchSegment(service, token)
    }

    private fun onSegmentCancelled(token: Int) {
        endCycleState()
        cancelStreak++
        if (cancelStreak >= MAX_CANCEL_STREAK) {
            cancelStreak = 0
            paused = true
            bar?.refresh(settings, paused = true, directionDown = directionDown)
            onStateChanged()
            return
        }
        consecutiveFailures++
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            consecutiveFailures = 0
            stop()
            toast(R.string.page_scroll_unavailable)
            return
        }
        if (token == generation) scheduleNextCycle(CANCEL_RETRY_MS)
    }

    private fun finishCycle(token: Int) {
        endCycleState()
        if (token == generation && isRunning && !paused) scheduleNextCycle(0L)
    }

    private fun endCycleState() {
        cycleActive = false
        activeStroke = null
        segmentIndex = 0
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
        const val START_DELAY_MS = 320L
        const val RETRY_DELAY_MS = 100L
        const val CANCEL_RETRY_MS = 350L

        /** 一段的长度（响应粒度就是它）。 */
        const val SEGMENT_MS = 150f

        const val MIN_SEGMENT_MS = 30L
        const val MAX_SEGMENT_MS = 1000L

        /** 续接失败时补的那一整段时长。 */
        const val LEGACY_REST_MS = 800L

        /**
         * 列表内的按下/抬起锚点（× 屏幕高）。
         *
         * 实测 QQ 聊天页：列表 227..2076（0.097h..0.887h）、输入框 2093..2206（0.894h..0.943h）。
         * 0.16h≈374、0.78h≈1825，两端各留 ≥140px，避开标题栏与输入栏。
         */
        const val PRESS_TOP_RATIO = 0.16f
        const val PRESS_BOTTOM_RATIO = 0.78f

        const val X_RATIO_CENTER = 0.5f

        const val MAX_CONSECUTIVE_FAILURES = 3
        const val MAX_CANCEL_STREAK = 3
    }
}
