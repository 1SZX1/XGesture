package com.slideindex.app.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * [TouchBlockLoopDetector] 的 JVM 单测：用合成轨迹覆盖"该解除"和"不该解除"两类。
 *
 * 这里刻意不跑 Robolectric —— 检测器是纯 Kotlin，没有 Android 依赖，
 * 用普通 JUnit 就能在几十毫秒内回归完。
 */
class TouchBlockLoopDetectorTest {

    private val loose = TouchBlockLoopDetector.Config()

    private fun circle(
        centerX: Float = 540f,
        centerY: Float = 1200f,
        radius: Float = 240f,
        sweepDeg: Float = 360f,
        clockwise: Boolean = true,
        steps: Int = 120,
        radiusNoiseRatio: Float = 0f,
        random: Random = Random(7),
    ): List<TouchBlockLoopDetector.Sample> {
        val samples = ArrayList<TouchBlockLoopDetector.Sample>(steps + 1)
        val sweepRad = Math.toRadians(sweepDeg.toDouble())
        val sign = if (clockwise) 1.0 else -1.0
        val startRad = -Math.PI / 2.0
        for (index in 0..steps) {
            val fraction = index / steps.toDouble()
            val angle = startRad + sign * sweepRad * fraction
            val noise = 1.0 + radiusNoiseRatio * (random.nextFloat() * 2f - 1f)
            val r = radius.toDouble() * noise
            samples.add(
                TouchBlockLoopDetector.Sample(
                    x = (centerX + r * cos(angle)).toFloat(),
                    y = (centerY + r * sin(angle)).toFloat(),
                    timeMs = index * 8L,
                )
            )
        }
        return samples
    }

    @Test
    fun fullCircle_clockwise_isAccepted() {
        val result = TouchBlockLoopDetector.detect(circle(), loose)

        assertTrue(result.describe(), result.accepted)
        assertEquals(360.0, result.turnsDeg.toDouble(), 12.0)
        assertTrue(result.describe(), result.closeGapRatio < 0.05f)
        assertTrue(result.describe(), result.radiusCv < 0.05f)
    }

    @Test
    fun fullCircle_counterClockwise_isAccepted() {
        val result = TouchBlockLoopDetector.detect(circle(clockwise = false), loose)

        assertTrue(result.describe(), result.accepted)
    }

    @Test
    fun handDrawnNoisyCircle_isAccepted() {
        val result = TouchBlockLoopDetector.detect(
            circle(radiusNoiseRatio = 0.12f, random = Random(42)),
            loose,
        )

        assertTrue(result.describe(), result.accepted)
    }

    @Test
    fun oval_isAccepted() {
        val points = circle().map { sample ->
            // 横向拉到 1.6 倍：椭圆是正常人画出来的"圈"，不该被拒。
            sample.copy(x = 540f + (sample.x - 540f) * 1.6f)
        }

        val result = TouchBlockLoopDetector.detect(points, loose)

        assertTrue(result.describe(), result.accepted)
    }

    @Test
    fun threeQuarterArc_isRejectedByClosure() {
        val result = TouchBlockLoopDetector.detect(circle(sweepDeg = 270f), loose)

        assertFalse(result.describe(), result.accepted)
        // 转角是够的（270° > 250°），真正拦住它的是首尾没接上。
        assertTrue(result.describe(), result.turnsDeg > loose.minTurnsDeg)
        assertTrue(result.describe(), result.closeGapRatio > loose.maxCloseGapRatio)
    }

    @Test
    fun halfCircle_isRejected() {
        val result = TouchBlockLoopDetector.detect(circle(sweepDeg = 180f), loose)

        assertFalse(result.describe(), result.accepted)
    }

    @Test
    fun straightSwipe_isRejected() {
        val points = (0..80).map { index ->
            TouchBlockLoopDetector.Sample(
                x = 100f + index * 8f,
                y = 1200f + (index % 2) * 2f,
                timeMs = index * 8L,
            )
        }

        val result = TouchBlockLoopDetector.detect(points, loose)

        assertFalse(result.describe(), result.accepted)
        assertTrue(result.describe(), result.turnsDeg < 90f)
    }

    @Test
    fun zigzag_isRejected() {
        val points = (0..60).map { index ->
            TouchBlockLoopDetector.Sample(
                x = 200f + index * 6f,
                y = 800f + if (index % 2 == 0) -40f else 40f,
                timeMs = index * 8L,
            )
        }

        val result = TouchBlockLoopDetector.detect(points, loose)

        assertFalse(result.describe(), result.accepted)
    }

    @Test
    fun figureEight_isRejected() {
        // 双纽线：绕过去又绕回来，有符号转角正负抵消，不是"一个圈"。
        val points = (0..160).map { index ->
            val t = index / 160.0 * 2.0 * Math.PI
            TouchBlockLoopDetector.Sample(
                x = (540.0 + 300.0 * sin(t)).toFloat(),
                y = (1200.0 + 300.0 * sin(t) * cos(t)).toFloat(),
                timeMs = index * 8L,
            )
        }

        val result = TouchBlockLoopDetector.detect(points, loose)

        assertFalse(result.describe(), result.accepted)
    }

    @Test
    fun tinyCircle_isRejectedByMinRadius() {
        val strict = TouchBlockLoopDetector.Config(minRadiusPx = 60f)

        val result = TouchBlockLoopDetector.detect(circle(radius = 12f), strict)

        assertFalse(result.describe(), result.accepted)
        assertTrue(result.describe(), result.meanRadiusPx < 60f)
    }

    @Test
    fun slowTrace_isRejectedByDuration() {
        val strict = TouchBlockLoopDetector.Config(maxDurationMs = 2_000L)
        val slow = circle().mapIndexed { index, sample -> sample.copy(timeMs = index * 100L) }

        val result = TouchBlockLoopDetector.detect(slow, strict)

        assertFalse(result.describe(), result.accepted)
    }

    @Test
    fun tapOnly_isRejected() {
        val points = listOf(
            TouchBlockLoopDetector.Sample(500f, 900f, 0L),
            TouchBlockLoopDetector.Sample(501f, 901f, 8L),
            TouchBlockLoopDetector.Sample(501f, 902f, 20L),
        )

        val result = TouchBlockLoopDetector.detect(points, loose)

        assertFalse(result.describe(), result.accepted)
        assertEquals(3, result.pointCount)
    }

    @Test
    fun emptyTrace_isRejectedWithoutCrash() {
        val result = TouchBlockLoopDetector.detect(emptyList(), loose)

        assertFalse(result.describe(), result.accepted)
        assertEquals(0, result.pointCount)
    }

    @Test
    fun minSampleDistance_dropsJitterButKeepsEndpoints() {
        val config = TouchBlockLoopDetector.Config(minSampleDistancePx = 12f)

        val result = TouchBlockLoopDetector.detect(circle(), config)

        assertTrue(result.describe(), result.accepted)
        // 一圈周长 ≈ 1508px，12px 抽稀后应远小于原始 121 个点。
        assertTrue(result.describe(), result.pointCount in 13..200)
    }

    @Test
    fun touchBlockAction_roundTripsThroughCodec() {
        assertEquals(GestureActionType.TOUCH_BLOCK, GestureActionType.fromId(98))
        assertEquals(GestureAction.TouchBlock, GestureAction.from(GestureActionType.TOUCH_BLOCK, ""))
        assertEquals(GestureActionType.TOUCH_BLOCK, GestureAction.TouchBlock.type)
    }
}
