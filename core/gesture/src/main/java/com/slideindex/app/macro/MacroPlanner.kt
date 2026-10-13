package com.slideindex.app.macro

/**
 * 宏执行期的纯计算部分（不依赖 Android），单独抽出来是为了能单元测试：
 * 这两段是行为保真的关键 —— 「启动耗时抵扣」和「滑动轨迹点」。
 */
object MacroPlanner {

    /**
     * 计算每一步的**实际等待时长**（毫秒）。
     *
     * 规则：宏开头连续的等待步会被"启动耗时 + 界面稳定时间"（[startupCostMs]）抵扣 ——
     * 因为拉起目标 App 本身已经花掉了一部分时间，用户写的"等 2 秒"不该再等满 2 秒。
     * **一旦执行过真正的点/滑，就不再抵扣**（后面的等待是用户明确要求的节奏）。
     *
     * 返回值的下标与 [steps] 一一对应；非等待步返回 0。
     */
    fun plannedWaitDurations(steps: List<MacroStep>, startupCostMs: Long): List<Long> {
        var budget = startupCostMs.coerceAtLeast(0L)
        var discountFinished = false
        return steps.map { step ->
            if (step !is MacroStep.Wait) {
                discountFinished = true
                0L
            } else {
                val effective = if (discountFinished) {
                    step.durationMs
                } else {
                    val remaining = (step.durationMs - budget).coerceAtLeast(0L)
                    budget = (budget - step.durationMs).coerceAtLeast(0L)
                    remaining
                }
                MacroLimits.clampWait(effective)
            }
        }
    }

    /**
     * 一条滑动在屏幕上实际连线的点（像素）。
     *
     * - `points.size < 2` → 退化成「起点 → 终点」两点直线
     * - 否则 → 起点 + `points.drop(1)`：`points[0]` 就是按下点（与起点重复）所以跳过，
     *   之后逐点连线，**最后落到 `points` 的末点**（它才是抬起点）
     */
    fun swipePixelPath(
        step: MacroStep.Swipe,
        screenWidth: Float,
        screenHeight: Float,
        maxPoints: Int = DEFAULT_MAX_PATH_POINTS,
    ): List<Pair<Float, Float>> {
        val start = toPixels(step.startX, step.startY, screenWidth, screenHeight)
        if (step.points.size < 2) {
            val end = toPixels(step.endX, step.endY, screenWidth, screenHeight)
            return listOf(start, end)
        }
        val path = ArrayList<Pair<Float, Float>>(minOf(step.points.size, maxPoints))
        path += start
        for (point in step.points.drop(1)) {
            if (path.size >= maxPoints) break
            path += toPixels(point.x, point.y, screenWidth, screenHeight)
        }
        return path
    }

    /** 比例 → 像素（比例先收进 0..1）。 */
    fun toPixels(x: Float, y: Float, screenWidth: Float, screenHeight: Float): Pair<Float, Float> =
        MacroLimits.clampFraction(x) * screenWidth to MacroLimits.clampFraction(y) * screenHeight

    /**
     * 单条手势路径的点数上限。
     *
     * 无障碍注入的单条路径越长越容易被系统丢弃，而且录制时的抽稀已经保证点不会太密；
     * 这里给一个宽松的上限，只为拦住"导入的异常数据"。
     */
    const val DEFAULT_MAX_PATH_POINTS = 256
}
