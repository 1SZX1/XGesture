package com.slideindex.app.macro

import org.junit.Assert.assertEquals
import org.junit.Test

/** 执行期的两段纯逻辑：启动耗时抵扣、滑动像素轨迹。 */
class MacroPlannerTest {

    @Test
    fun startupCost_isDeductedFromLeadingWaitsOnly() {
        val steps = listOf(
            MacroStep.Wait(300L),
            MacroStep.Wait(900L),
            MacroStep.Tap(),
            MacroStep.Wait(500L),
        )

        // 启动花了 1000ms：前两条等待被依次抵扣（300 全部吃掉、900 里还剩 200），
        // 中间执行过点击之后，第三条等待恢复原值。
        assertEquals(
            listOf(0L, 200L, 0L, 500L),
            MacroPlanner.plannedWaitDurations(steps, startupCostMs = 1000L),
        )
    }

    @Test
    fun startupCost_neverMakesWaitNegative() {
        val steps = listOf(MacroStep.Wait(100L), MacroStep.Wait(100L))

        assertEquals(
            listOf(0L, 0L),
            MacroPlanner.plannedWaitDurations(steps, startupCostMs = 5_000L),
        )
    }

    @Test
    fun waitsAfterFirstGesture_areKeptVerbatim() {
        val steps = listOf(
            MacroStep.Tap(),
            MacroStep.Wait(120L),
            MacroStep.Swipe(),
            MacroStep.Wait(340L),
        )

        assertEquals(
            listOf(0L, 120L, 0L, 340L),
            MacroPlanner.plannedWaitDurations(steps, startupCostMs = 4_500L),
        )
    }

    @Test
    fun waitIsClampedToSpecRange() {
        val steps = listOf(MacroStep.Wait(999_999L))

        assertEquals(
            listOf(MacroLimits.WAIT_MAX_MS),
            MacroPlanner.plannedWaitDurations(steps, startupCostMs = 0L),
        )
    }

    @Test
    fun swipeWithEnoughPoints_skipsFirstPointAndEndsAtLastPoint() {
        val swipe = MacroStep.Swipe(
            startX = 0.5f,
            startY = 0.8f,
            endX = 0.9f,
            endY = 0.9f, // 故意与 points 末点不同：末点才生效
            points = listOf(
                MacroStep.Swipe.PathPoint(0.5f, 0.8f),
                MacroStep.Swipe.PathPoint(0.51f, 0.5f),
                MacroStep.Swipe.PathPoint(0.5f, 0.2f),
            ),
        )

        assertEquals(
            listOf(500f to 1600f, 510f to 1000f, 500f to 400f),
            MacroPlanner.swipePixelPath(swipe, screenWidth = 1000f, screenHeight = 2000f),
        )
    }

    @Test
    fun swipeWithTooFewPoints_fallsBackToStartEndLine() {
        val swipe = MacroStep.Swipe(
            startX = 0.25f,
            startY = 0.5f,
            endX = 0.75f,
            endY = 0.5f,
            points = listOf(MacroStep.Swipe.PathPoint(0.25f, 0.5f)),
        )

        assertEquals(
            listOf(250f to 1000f, 750f to 1000f),
            MacroPlanner.swipePixelPath(swipe, screenWidth = 1000f, screenHeight = 2000f),
        )
    }

    @Test
    fun pixelsAreClampedIntoFractionRange() {
        // 起点比例是负数 → 收进 0；点数不足 2 → 退化成「起点 → 终点」两点直线。
        assertEquals(
            listOf(0f to 0f, 500f to 400f),
            MacroPlanner.swipePixelPath(
                MacroStep.Swipe(startX = -5f, startY = -9f),
                screenWidth = 1000f,
                screenHeight = 2000f,
            ),
        )
    }

    @Test
    fun pathPointCount_isCapped() {
        val swipe = MacroStep.Swipe(
            points = List(100) { MacroStep.Swipe.PathPoint(0.5f, 0.5f) },
        )

        assertEquals(
            10,
            MacroPlanner.swipePixelPath(
                swipe,
                screenWidth = 1000f,
                screenHeight = 2000f,
                maxPoints = 10,
            ).size,
        )
    }
}
