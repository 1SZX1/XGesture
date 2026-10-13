package com.slideindex.app.pagescroll

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.slideindex.app.R
import com.slideindex.app.service.SlideIndexAccessibilityService
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 「页面滚动」的自动滚动引擎（续接式）。
 *
 * 一次拖动 = **一条连续手势**，内部按 [SEGMENT_MS] 切成小段，用 `StrokeDescription.continueStroke`
 * 一段段接下去；**段边界就是响应点**：
 * - 切方向 / 调速：下一段立刻按新参数走（最多等一段 ≈150ms）
 * - 停止 / 暂停：**不再续接**，当前段自然收尾（手势以真实位移结束，不是"半截"→ 不会被当成单击）
 *
 * 路径只在**可滚动列表内部**（实测 QQ 聊天页列表 y 227..2076、输入框 2093..2206）：
 * - 按下点与抬起点都在列表里 → 触摸流归列表所有、列表会在拖动中拦截 → 不会误点、不会聚焦输入框
 * - 方向：箭头 ↓（看下面内容）= 手指从列表下方**往上划**；↑ 相反
 *
 * 平台差异：`continueStroke` 需要 API 26+；更低版本退化成"一轮一整段"（同样只走列表内部）。
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

    // ---- 一条手势（一个"轮次"）的状态 ----

    /** 当前这段的 StrokeDescription：下一段必须由它 continueStroke 出来。 */
    private var activeStroke: GestureDescription.StrokeDescription? = null

    /** 手指当前所在的 y（跨段累计）。 */
    private var currentY = 0f

    private var cycleActive = false
    private var segmentIndex = 0

    // ---- 诊断（只打日志，不改行为） ----

    private var cycleIndex = 0
    private var cycleStartedAt = 0L
    private var lastCycleFinishedAt = 0L
    private var segmentStartedAt = 0L
    private var scrollEventsInCycle = 0

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
        generation++
        val controlBar = bar ?: PageScrollControlBar(context, { settings }, barListener).also { bar = it }
        if (!controlBar.show(side, anchorRawY)) {
            toast(R.string.page_scroll_unavailable)
            return false
        }
        controlBar.refresh(settings, paused = false, directionDown = directionDown)
        PageScrollPrefs.saveLastSide(context, side.name)
        Log.d(
            TAG,
            "开始滚动 side=$side 触发点Y=${anchorRawY.roundToInt()} 方向=DOWN " +
                "档位=${settings.speedLevel} 屏幕=${realScreenSize()} 段长=${SEGMENT_MS}ms",
        )
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
        paused = false
        consecutiveFailures = 0
        cancelStreak = 0
        bar?.dismiss()
        Log.d(TAG, "停止（当前段会自然收尾，最多 ${SEGMENT_MS}ms）")
        onStateChanged()
    }

    private fun cycleElapsedMs(): Long =
        if (cycleStartedAt > 0L) SystemClock.uptimeMillis() - cycleStartedAt else -1L

    /** 无障碍服务收到系统滚动事件时回调：说明这一轮真的把页面滚动了。 */
    fun onPageScrolled() {
        scrollEventsInCycle++
    }

    /** 无障碍服务收到窗口变化时回调：用来定位"弹键盘"发生在哪一拍之后。 */
    fun onWindowChanged(packageName: String?, className: String?) {
        if (!isRunning) return
        Log.d(TAG, "窗口变化 包=$packageName 类=$className（距本轮开始 ${cycleElapsedMs()}ms）")
    }

    private val barListener = object : PageScrollControlBar.Listener {
        override fun onReverseDirection() {
            directionDown = !directionDown
            // 不需要打断手势：下一段的段边界会按新方向走。
            Log.d(TAG, "用户切换方向 → ${if (directionDown) "DOWN" else "UP"}（下一段生效）")
            bar?.refresh(settings, paused, directionDown)
        }

        override fun onSpeedDown() = changeSpeed(-1)

        override fun onSpeedUp() = changeSpeed(+1)

        override fun onTogglePause() {
            paused = !paused
            Log.d(TAG, if (paused) "用户暂停（当前段后停）" else "用户继续")
            bar?.refresh(settings, paused, directionDown)
            if (paused) {
                cycleTask?.let { mainHandler.removeCallbacks(it) }
                cycleTask = null
            } else {
                scheduleNextCycle(0L)
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
        Log.d(TAG, "用户调速 → 档位 $next（下一段生效）")
        bar?.refresh(settings, paused, directionDown)
    }

    private fun scheduleNextCycle(delayMs: Long) {
        if (!isRunning || paused) return
        val token = generation
        val task = Runnable { startCycle(token) }
        cycleTask = task
        mainHandler.postDelayed(task, delayMs)
    }

    /** 开始一条新的连续手势（从当前方向的"按下锚点"起手）。 */
    private fun startCycle(token: Int) {
        if (token != generation || !isRunning || paused) return
        if (cycleActive || userTouchingControls) {
            Log.d(TAG, "跳过本轮 手势在飞=$cycleActive 手指在控制条上=$userTouchingControls")
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
        cycleIndex++
        val now = SystemClock.uptimeMillis()
        val gapMs = if (lastCycleFinishedAt > 0L) now - lastCycleFinishedAt else -1L
        cycleStartedAt = now
        scrollEventsInCycle = 0
        Log.d(
            TAG,
            "cycle#$cycleIndex 起手 dir=${if (directionDown) "DOWN" else "UP"} " +
                "x=${(screen.x * X_RATIO_CENTER).roundToInt()} 按下Y=${currentY.roundToInt()} " +
                "列表带=[${band.first.roundToInt()},${band.second.roundToInt()}] " +
                "level=${settings.speedLevel} 距上轮=${gapMs}ms",
        )
        logTouchDownTarget(service, screen.x * X_RATIO_CENTER, currentY)
        dispatchSegment(service, token)
    }

    /** 列表内可用的一段竖直范围（上、下锚点，按屏幕高比例取）。 */
    private fun bandOf(height: Float): Pair<Float, Float> =
        height * PRESS_TOP_RATIO to height * PRESS_BOTTOM_RATIO

    /**
     * 下发下一段（150ms 左右），并在完成回调里决定"要不要继续接"。
     *
     * 关键点：
     * - 每一段都重新读 `directionDown` / `settings` → 段边界即响应点
     * - 到达锚点、或收到停止/暂停 → 这一段用 `willContinue=false` 收尾（真实位移结束，不做 1px 微步）
     * - 续接失败（拿不到 continuation）→ 补一整段真实行程，绝不发微小位移
     */
    private fun dispatchSegment(service: AccessibilityService, token: Int) {
        if (token != generation) return
        val screen = realScreenSize()
        if (screen.x <= 0 || screen.y <= 0) {
            finishCycle(token)
            return
        }
        val x = screen.x * X_RATIO_CENTER
        val band = bandOf(screen.y.toFloat())
        val speed = settings.speedDpPerSecond
        val dir = if (directionDown) -1f else 1f

        // 一段要走的距离（dp→px）。第一段保证超过 touchSlop，让列表尽快接管。
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

        val wantsStop = paused || !isRunning
        val willContinue = !reachedEnd && !wantsStop

        val path = Path().apply {
            moveTo(x, currentY)
            lineTo(x, nextY)
        }
        val stroke = if (segmentIndex == 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                GestureDescription.StrokeDescription(path, 0L, durationMs, willContinue)
            } else {
                GestureDescription.StrokeDescription(path, 0L, durationMs)
            }
        } else {
            val previous = activeStroke
            if (previous != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { previous.continueStroke(path, 0L, durationMs, willContinue) }.getOrNull()
            } else {
                null
            }
        }
        if (stroke == null) {
            // 续接失败：补一整段真实行程（从当前位置直接走到锚点），保证有真实位移、不是小点。
            Log.w(TAG, "cycle#$cycleIndex 第${segmentIndex + 1}段续接失败 → 补一整段完成本轮")
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
        segmentStartedAt = SystemClock.uptimeMillis()
        Log.d(
            TAG,
            "cycle#$cycleIndex 第${segmentIndex}段 从Y=${currentY.roundToInt()} 到Y=${nextY.roundToInt()} " +
                "(${travelPx.roundToInt()}px, ${durationMs}ms, 继续=$willContinue)",
        )
        val previousY = currentY
        currentY = nextY
        dispatchStrokeDescription(service, stroke, token, previousY)
    }

    /** 派发一段手势，并处理完成/取消。 */
    private fun dispatchStrokeDescription(
        service: AccessibilityService,
        stroke: GestureDescription.StrokeDescription,
        token: Int,
        fromY: Float = currentY,
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
        }.getOrElse {
            Log.w(TAG, "手势派发异常", it)
            false
        }
        if (!accepted) {
            Log.w(TAG, "cycle#$cycleIndex 第${segmentIndex}段派发被拒 (fromY=${fromY.roundToInt()})")
            onSegmentCancelled(token)
        }
    }

    private fun onSegmentCompleted(token: Int) {
        if (token != generation) {
            // 已停止/重启：不再续接，手势到这里自然结束。
            endCycleState()
            Log.d(TAG, "cycle#$cycleIndex 段完成但已过期 → 不续接，收尾")
            return
        }
        val wantsStop = paused || !isRunning
        val screen = realScreenSize()
        val band = bandOf(screen.y.toFloat())
        val atEnd = if (directionDown) currentY <= band.first + 0.5f else currentY >= band.second - 0.5f
        if (wantsStop || atEnd) {
            endCycleState()
            Log.d(
                TAG,
                "cycle#$cycleIndex 结束 段数=$segmentIndex 总耗时=${cycleElapsedMs()}ms " +
                    "系统滚动事件=$scrollEventsInCycle 次" + if (wantsStop) "（因停止/暂停）" else "（到达锚点）",
            )
            if (scrollEventsInCycle == 0) {
                Log.w(TAG, "⚠ cycle#$cycleIndex 没有任何滚动事件：起点不可滚动 / 被遮挡 / 被当成点击")
            }
            lastCycleFinishedAt = SystemClock.uptimeMillis()
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
        lastCycleFinishedAt = SystemClock.uptimeMillis()
        Log.w(
            TAG,
            "cycle#$cycleIndex 被取消 段数=$segmentIndex 已过=${cycleElapsedMs()}ms 连续第${cancelStreak}次",
        )
        if (cancelStreak >= MAX_CANCEL_STREAK) {
            cancelStreak = 0
            paused = true
            Log.w(TAG, "连续被取消 $MAX_CANCEL_STREAK 次，先暂停滚动")
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
        lastCycleFinishedAt = SystemClock.uptimeMillis()
        if (token == generation && isRunning && !paused) scheduleNextCycle(0L)
    }

    private fun endCycleState() {
        cycleActive = false
        activeStroke = null
        segmentIndex = 0
    }

    /**
     * 打出"落点压在哪个节点上"以及从根到它的节点链（定位误点/弹键盘用）。
     */
    private fun logTouchDownTarget(service: AccessibilityService, x: Float, y: Float) {
        val root = runCatching { service.rootInActiveWindow }.getOrNull()
        if (root == null) {
            Log.d(TAG, "落点(${x.roundToInt()},${y.roundToInt()}) 拿不到根节点")
            return
        }
        val report = StringBuilder()
        var current: AccessibilityNodeInfo? = root
        var depth = 0
        while (current != null && depth < 24) {
            val bounds = Rect()
            runCatching { current.getBoundsInScreen(bounds) }
            val contains = bounds.contains(x.roundToInt(), y.roundToInt())
            report.append("\n    ").append(" ".repeat(depth * 2))
                .append(if (contains) "└ " else "× ").append(current.className ?: "?")
                .append(" [").append(bounds.toShortString()).append("]")
                .append(" id=").append(current.viewIdResourceName ?: "-")
                .append(" click=").append(current.isClickable)
                .append(" editable=").append(runCatching { current.isEditable }.getOrDefault(false))
                .append(" scrollable=").append(current.isScrollable)
            if (!contains) {
                if (current !== root) runCatching { current.recycle() }
                break
            }
            var next: AccessibilityNodeInfo? = null
            for (index in 0 until current.childCount) {
                val child = runCatching { current.getChild(index) }.getOrNull() ?: continue
                val childBounds = Rect()
                runCatching { child.getBoundsInScreen(childBounds) }
                if (childBounds.contains(x.roundToInt(), y.roundToInt())) {
                    next = child
                    break
                }
                runCatching { child.recycle() }
            }
            if (current !== root) runCatching { current.recycle() }
            current = next
            depth++
        }
        Log.d(TAG, "落点(${x.roundToInt()},${y.roundToInt()}) 命中链：$report")
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

        /** 手势路径的横向位置：走屏幕中线。 */
        const val X_RATIO_CENTER = 0.5f

        const val MAX_CONSECUTIVE_FAILURES = 3
        const val MAX_CANCEL_STREAK = 3
    }
}
