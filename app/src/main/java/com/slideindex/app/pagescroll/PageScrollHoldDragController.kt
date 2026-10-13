package com.slideindex.app.pagescroll

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.slideindex.app.R
import com.slideindex.app.service.SlideIndexAccessibilityService
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max

/**
 * 「页面滚动」的按住拖动执行方式。
 *
 * 手指按住边缘上下移动，每过一拍就给**可滚动节点**发一次"按页滚动"动作
 * （优先方向动作，平台较旧时退回前/后翻页动作）；节奏由拖动距离决定：
 * 移动得多就滚得快（260ms 一拍），移动得少就滚得慢（680ms 一拍）。
 *
 * 由手势会话驱动：开始 / 移动 / 抬起三个时机分别调用 [begin] / [update] / [end]。
 */
internal class PageScrollHoldDragController(
    private val context: Context,
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = max(0.1f, context.resources.displayMetrics.density)

    private var tickTask: Runnable? = null
    private var active = false

    private var directionDown = true
    private var intervalMs = SLOW_INTERVAL_MS
    private var anchorRawY = 0f
    private var noTargetStrikes = 0

    fun begin(rawY: Float) {
        if (active) return
        active = true
        anchorRawY = rawY
        directionDown = true
        intervalMs = SLOW_INTERVAL_MS
        noTargetStrikes = 0
        scheduleTick(0L)
    }

    /** 手指移动：按本次位移决定方向与节奏（位移不够就不动）。 */
    fun update(rawY: Float) {
        if (!active) return
        val dy = rawY - anchorRawY
        val distanceDp = abs(dy) / density
        if (distanceDp < MIN_STEP_DP) return
        directionDown = dy < 0f
        intervalMs = if (distanceDp >= FAST_STEP_DP) FAST_INTERVAL_MS else SLOW_INTERVAL_MS
        // 重新锚定，避免"拖一次就一直滚"。
        anchorRawY = rawY
    }

    fun end() {
        if (!active) return
        active = false
        tickTask?.let { mainHandler.removeCallbacks(it) }
        tickTask = null
        noTargetStrikes = 0
    }

    val isActive: Boolean get() = active

    private fun scheduleTick(delayMs: Long) {
        if (!active) return
        val task = Runnable { runTick() }
        tickTask = task
        mainHandler.postDelayed(task, delayMs)
    }

    private fun runTick() {
        if (!active) return
        val service = SlideIndexAccessibilityService.accessibilityInstance()
        if (service == null) {
            end()
            return
        }
        val performed = performPageScroll(service, directionDown)
        if (performed) {
            noTargetStrikes = 0
            scheduleTick(intervalMs)
        } else {
            noTargetStrikes++
            if (noTargetStrikes >= MAX_NO_TARGET_STRIKES) {
                end()
                runCatching {
                    Toast.makeText(
                        context,
                        context.getString(R.string.page_scroll_unavailable),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } else {
                scheduleTick(RETRY_DELAY_MS)
            }
        }
    }

    /** 从当前窗口的根节点广度优先找一个能滚动的节点，发一次按页滚动。 */
    private fun performPageScroll(service: AccessibilityService, down: Boolean): Boolean {
        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return false
        // 方向动作是 AccessibilityAction 上的对象（API 29+），低版本没有，用 0 表示不可用。
        val preferred = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (down) {
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
            } else {
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            }
        } else {
            0
        }
        val fallback = if (down) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }

        val queue = ArrayDeque<NodeDepth>()
        queue.addLast(NodeDepth(root, 0))
        var visited = 0
        var best: AccessibilityNodeInfo? = null
        var bestAction = 0
        var bestScore = Int.MIN_VALUE
        try {
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val (node, depth) = queue.removeFirst()
                visited++
                val actions = node.actionList
                val action = when {
                    preferred != 0 && actions.any { it.id == preferred } -> preferred
                    actions.any { it.id == fallback } -> fallback
                    else -> 0
                }
                if (action != 0 && runCatching { node.isVisibleToUser }.getOrDefault(false)) {
                    val bounds = Rect()
                    runCatching { node.getBoundsInScreen(bounds) }
                    val score = (if (runCatching { node.isScrollable }.getOrDefault(false)) 100 else 0) +
                        (if (bounds.height() > bounds.width()) 20 else 0) - depth
                    if (score > bestScore) {
                        bestScore = score
                        best = node
                        bestAction = action
                    }
                }
                if (depth < MAX_DEPTH) {
                    for (index in 0 until node.childCount) {
                        if (queue.size + visited >= MAX_NODES) break
                        val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                        queue.addLast(NodeDepth(child, depth + 1))
                    }
                }
            }
        } catch (throwable: Throwable) {
            Log.w(TAG, "遍历节点失败", throwable)
        } finally {
            // 只回收没被选中的节点，避免把要用的节点提前回收。
            queue.forEach { frame -> if (frame.node !== best) runCatching { frame.node.recycle() } }
        }

        val target = best ?: return false
        return runCatching { target.performAction(bestAction) }.getOrDefault(false).also {
            runCatching { target.recycle() }
        }
    }

    private data class NodeDepth(val node: AccessibilityNodeInfo, val depth: Int)

    private companion object {
        const val TAG = "PageScrollHoldDrag"

        /** 一次拖动至少要走这么多才算有效。 */
        const val MIN_STEP_DP = 8f

        /** 走得比这个多就切到快档。 */
        const val FAST_STEP_DP = 160f

        const val SLOW_INTERVAL_MS = 680L
        const val FAST_INTERVAL_MS = 260L
        const val RETRY_DELAY_MS = 260L

        const val MAX_NODES = 160
        const val MAX_DEPTH = 24
        const val MAX_NO_TARGET_STRIKES = 2
    }
}
