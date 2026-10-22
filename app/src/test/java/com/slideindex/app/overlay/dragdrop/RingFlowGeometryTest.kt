package com.slideindex.app.overlay.dragdrop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 环形流转栏的几何回归测试。
 *
 * 这里的每个断言都对应一个真实的手势位置：算错的表现是“拖到微信却发给了 QQ”，
 * 或者“拖到圆外还亮着某个应用”，而且偶发、难复现，所以边界必须靠测试钉死。
 */
class RingFlowGeometryTest {

    private val iconRadius = 100f
    private val bandInner = 60f
    private val bandOuter = 144f

    private fun indexAtAngle(degrees: Float, count: Int, radius: Float = iconRadius): Int? {
        val radians = Math.toRadians(degrees.toDouble())
        return RingFlowGeometry.targetIndexAt(
            x = (cos(radians) * radius).toFloat(),
            y = (sin(radians) * radius).toFloat(),
            centerX = 0f,
            centerY = 0f,
            count = count,
            innerRadiusPx = bandInner,
            outerRadiusPx = bandOuter,
        )
    }

    @Test
    fun twelveItemsPlaceFirstItemAtTopAndGoClockwise() {
        // 屏幕坐标系里 -90° 是正上方，之后 0°/90°/180° 依次是右、下、左。
        assertEquals(0, indexAtAngle(-90f, 12) ?: -1)
        assertEquals(1, indexAtAngle(-60f, 12) ?: -1)
        assertEquals(3, indexAtAngle(0f, 12) ?: -1)
        assertEquals(6, indexAtAngle(90f, 12) ?: -1)
        assertEquals(9, indexAtAngle(180f, 12) ?: -1)
        assertEquals(11, indexAtAngle(-120f, 12) ?: -1)
    }

    @Test
    fun eightItemsHalveTheSectorStep() {
        assertEquals(0, indexAtAngle(-90f, 8) ?: -1)
        assertEquals(2, indexAtAngle(0f, 8) ?: -1)
        assertEquals(4, indexAtAngle(90f, 8) ?: -1)
        assertEquals(6, indexAtAngle(180f, 8) ?: -1)
    }

    @Test
    fun nearestItemWinsInsideTheSector() {
        // 12 个目标时每格 30°：偏 14° 还算正上方那个，偏 16° 就归下一个。
        assertEquals(0, indexAtAngle(-76f, 12) ?: -1)
        assertEquals(1, indexAtAngle(-74f, 12) ?: -1)
        assertEquals(0, indexAtAngle(-104f, 12) ?: -1)
        assertEquals(11, indexAtAngle(-106f, 12) ?: -1)
    }

    @Test
    fun holeInTheMiddleHasNoTarget() {
        // 圆心那块是空的：手指停在这里松手什么都不发。
        assertNull(RingFlowGeometry.targetIndexAt(0f, -50f, 0f, 0f, 12, bandInner, bandOuter))
        assertNull(RingFlowGeometry.targetIndexAt(0f, 0f, 0f, 0f, 12, bandInner, bandOuter))
        // 刚好压到环带内沿就算命中。
        assertEquals(0, RingFlowGeometry.targetIndexAt(0f, -60f, 0f, 0f, 12, bandInner, bandOuter) ?: -1)
    }

    @Test
    fun outsideTheBandHasNoTarget() {
        // 环带外沿之外：这就是“拖到圆外还亮着某个 App”的那条漏判。
        assertNull(RingFlowGeometry.targetIndexAt(0f, -150f, 0f, 0f, 12, bandInner, bandOuter))
        // 窗口是方块，四个角离圆心更远，同样必须落空。
        assertNull(RingFlowGeometry.targetIndexAt(120f, -120f, 0f, 0f, 12, bandInner, bandOuter))
        assertNull(RingFlowGeometry.targetIndexAt(-120f, 120f, 0f, 0f, 12, bandInner, bandOuter))
        // 刚好在外沿上仍算命中。
        assertEquals(0, RingFlowGeometry.targetIndexAt(0f, -144f, 0f, 0f, 12, bandInner, bandOuter) ?: -1)
    }

    @Test
    fun invalidGeometryNeverThrows() {
        assertNull(RingFlowGeometry.targetIndexAt(0f, -100f, 0f, 0f, 12, bandOuter, bandInner))
        assertNull(RingFlowGeometry.targetIndexAt(0f, -100f, 0f, 0f, 0, bandInner, bandOuter))
    }

    @Test
    fun itemOffsetsSitOnTheIconCircle() {
        val (topX, topY) = RingFlowGeometry.offsetFor(0, 12, 100f)
        assertEquals(0f, topX, 0.001f)
        assertEquals(-100f, topY, 0.001f)

        val (rightX, rightY) = RingFlowGeometry.offsetFor(3, 12, 100f)
        assertEquals(100f, rightX, 0.001f)
        assertEquals(0f, rightY, 0.001f)

        // 每个目标都必须落在圆的半径上，否则圆盘会“偏心”。
        RingFlowGeometry.offsetFor(5, 12, 100f).let { (dx, dy) ->
            assertEquals(100f, hypot(dx, dy), 0.01f)
        }
    }
}
