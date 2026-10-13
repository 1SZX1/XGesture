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
 * 「页面滚动」的自动滚动引擎。
 *
 * 每一轮 = **一条完整手势**：从起点按下、匀速划到终点、抬手，结束后立刻接下一轮。
 *
 * 起终点都取在**可滚动列表内部**（实测 QQ 聊天页列表是 y 227..2076）：
 * - 起点落在列表里 → 触摸流归列表所有，列表会在拖动中拦截，子项（消息/图片/输入框）收不到抬手 → 不会误点；
 * - 终点也落在列表里 → 抬手不会落在底部输入栏上 → 不会弹键盘；
 * - 单段匀速（不再拆"起步/巡航/1px 收尾"） → 没有急冲，也没有"只移 1px"那种必然被当成单击的段。
 *
 * 方向：箭头 ↓（看下面内容）= 手指**从列表下方往上划**；↑ 则相反。
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
    private var strokeInFlight = false
    private var userTouchingControls = false
    private var consecutiveFailures = 0

    // ---- 诊断（只打日志，不改行为）：定位"被当成点击"与"误触发输入法" ----

    private var cycleIndex = 0
    private var cycleStartedAt = 0L
    private var lastCycleFinishedAt = 0L
    private var scrollEventsInCycle = 0
    private var cancelStreak = 0

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
        val service = SlideIndexAccessibilityService.accessibilityInstance()
        if (service == null) {
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
                "档位=${settings.speedLevel} 屏幕=${realScreenSize()}",
        )
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
        cancelStreak = 0
        bar?.dismiss()
        Log.d(TAG, "停止")
        onStateChanged()
    }

    private fun cycleElapsedMs(): Long =
        if (cycleStartedAt > 0L) SystemClock.uptimeMillis() - cycleStartedAt else -1L

    /** 无障碍服务收到系统滚动事件时回调：说明这一轮真的把页面滚动了。 */
    fun onPageScrolled() {
        scrollEventsInCycle++
        Log.d(TAG, "系统滚动事件 #$scrollEventsInCycle（cycle#$cycleIndex 手势在飞=$strokeInFlight）")
    }

    /** 无障碍服务收到窗口变化时回调：用来定位"弹键盘"发生在哪一拍之后。 */
    fun onWindowChanged(packageName: String?, className: String?) {
        if (!isRunning) return
        Log.d(
            TAG,
            "窗口变化 包=$packageName 类=$className（距本轮开始 ${cycleElapsedMs()}ms）",
        )
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
        Log.d(TAG, "用户调速 → 档位 $next")
        bar?.refresh(settings, paused, directionDown)
        restartNow()
    }

    private fun restartNow() {
        if (!isRunning || paused) return
        // 当前这段还在飞就别另起（否则会把它切成"半截手势"，目标 App 可能当成单击）。
        if (strokeInFlight) {
            Log.d(TAG, "有手势在飞，等它走完再重启")
            return
        }
        generation++
        cycleTask?.let { mainHandler.removeCallbacks(it) }
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
            Log.d(TAG, "跳过本轮 strokeInFlight=$strokeInFlight 手指在控制条上=$userTouchingControls")
            scheduleNextCycle(RETRY_DELAY_MS)
            return
        }
        val service = SlideIndexAccessibilityService.accessibilityInstance()
        if (service == null) {
            stop()
            toast(R.string.page_scroll_service_unavailable)
            return
        }
        val plan = planStroke(realScreenSize()) ?: run {
            stop()
            toast(R.string.page_scroll_unavailable)
            return
        }

        strokeInFlight = true
        cycleIndex++
        val now = SystemClock.uptimeMillis()
        val gapMs = if (lastCycleFinishedAt > 0L) now - lastCycleFinishedAt else -1L
        cycleStartedAt = now
        scrollEventsInCycle = 0
        Log.d(
            TAG,
            "cycle#$cycleIndex 开始 dir=${if (directionDown) "DOWN" else "UP"} " +
                "x=${plan.x.roundToInt()} 按下Y=${plan.startY.roundToInt()} 抬起Y=${plan.endY.roundToInt()} " +
                "行程=${abs(plan.endY - plan.startY).roundToInt()}px 时长=${plan.durationMs}ms " +
                "level=${settings.speedLevel} 距上轮=${gapMs}ms",
        )
        // 关键：落点到底压在谁身上（这一条就能解释"为什么会被当成点击/弹键盘"）。
        logTouchDownTarget(service, plan.x, plan.startY)

        val path = Path().apply {
            moveTo(plan.x, plan.startY)
            lineTo(plan.x, plan.endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, plan.durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val accepted = runCatching {
            service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        onCycleFinished(token)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        onCycleCancelled(token)
                    }
                },
                null,
            )
        }.getOrElse {
            Log.w(TAG, "手势派发异常", it)
            false
        }
        if (!accepted) {
            Log.w(TAG, "cycle#$cycleIndex 派发被拒")
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

    private fun onCycleFinished(token: Int) {
        strokeInFlight = false
        consecutiveFailures = 0
        cancelStreak = 0
        lastCycleFinishedAt = SystemClock.uptimeMillis()
        Log.d(
            TAG,
            "cycle#$cycleIndex 正常结束 总耗时=${cycleElapsedMs()}ms " +
                "系统滚动事件=$scrollEventsInCycle 次",
        )
        if (scrollEventsInCycle == 0) {
            Log.w(
                TAG,
                "⚠ cycle#$cycleIndex 没有滚动事件：落点不可滚动 / 被弹层遮挡 / 被当成点击（看上面的命中链）",
            )
        }
        if (token == generation && isRunning && !paused) scheduleNextCycle(0L)
    }

    /**
     * 一轮手势被系统取消（用户摸屏幕、切窗口等）。
     *
     * 退避后再来，避免"取消 → 立刻重发"造成连续短手势（那就是偶发单击的来源）；
     * 连续被取消就先暂停，不硬顶。
     */
    private fun onCycleCancelled(token: Int) {
        strokeInFlight = false
        cancelStreak++
        lastCycleFinishedAt = SystemClock.uptimeMillis()
        Log.w(
            TAG,
            "cycle#$cycleIndex 被取消 已过=${cycleElapsedMs()}ms 连续第${cancelStreak}次",
        )
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

    /** 一次滚动的行程：起点（按下）在列表里、终点（抬起）也在列表里。 */
    private data class StrokePlan(
        val x: Float,
        val startY: Float,
        val endY: Float,
        val durationMs: Long,
    )

    private fun planStroke(screen: Point): StrokePlan? {
        val width = screen.x.toFloat()
        val height = screen.y.toFloat()
        if (width <= 0f || height <= 0f) return null

        // 箭头 ↓（看下面内容）= 从列表下方按下、往上划；↑ 相反。
        val pressY = if (directionDown) height * PRESS_BOTTOM_RATIO else height * PRESS_TOP_RATIO
        val liftY = if (directionDown) height * PRESS_TOP_RATIO else height * PRESS_BOTTOM_RATIO
        val travel = abs(liftY - pressY)
        if (travel <= 0f) return null

        val speed = settings.speedDpPerSecond
        val durationMs = ((travel / density) / speed * 1000f)
            .roundToInt().toLong()
            .coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)

        return StrokePlan(
            x = width * X_RATIO_CENTER,
            startY = pressY,
            endY = liftY,
            durationMs = durationMs,
        )
    }

    /**
     * 打出"落点压在哪个节点上"以及从根到它的节点链。
     *
     * 这条日志是定位"被当成点击""弹键盘"的关键：如果落点命中的是 `EditText`
     * 或 `clickable=true` 的节点，就能直接看出是谁吃掉了这次触摸。
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

        /** 显示控制条之后多久开始滚动（给用户一点反应时间）。 */
        const val START_DELAY_MS = 320L

        /** 一轮结束后 / 重试的间隔。 */
        const val RETRY_DELAY_MS = 100L

        /** 被取消后的退避，避免连续重发短手势。 */
        const val CANCEL_RETRY_MS = 350L

        /**
         * 按下 / 抬起的锚点（× 屏幕高）。
         *
         * 取在可滚动列表内部：实测 QQ 聊天页列表 y = 227..2076（0.097h..0.887h）、
         * 输入框 2093..2206（0.894h..0.943h）。这里 0.78h≈1825、0.16h≈374，
         * 两端各留 ≥140px 余量，既不碰顶部标题栏，也不碰底部输入栏。
         */
        const val PRESS_BOTTOM_RATIO = 0.78f
        const val PRESS_TOP_RATIO = 0.16f

        /** 手势路径的横向位置：走屏幕中线。 */
        const val X_RATIO_CENTER = 0.5f

        const val MIN_DURATION_MS = 80L
        const val MAX_DURATION_MS = 6000L

        const val MAX_CONSECUTIVE_FAILURES = 3
        const val MAX_CANCEL_STREAK = 3
    }
}
