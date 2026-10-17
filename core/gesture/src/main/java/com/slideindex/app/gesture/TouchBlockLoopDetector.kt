package com.slideindex.app.gesture

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 「禁止触摸」的解除手势判定：在屏幕上画一个**闭合的圈**。
 *
 * 为什么不用 `android.gesture` 那套模板匹配：它的公开面只有
 * `GestureLibrary.recognize()` 返回的 `Prediction(name, score)` —— 真正的距离算法
 * （`Learner` / `InstanceLearner` / `GestureUtils`）全是 package-private，
 * 判定语义是「像不像我录过的那条轨迹」，不是「这条轨迹是不是闭合环」；
 * 分数没有文档化的绝对阈值，只能拿真机样本标定，而且误判时说不出是哪一步不对。
 *
 * 这里用三个都能直接写进日志的几何量：
 *
 * - [Result.turnsDeg]：按弧长重采样后，逐个折点的**有符号转角**绝对值累加。完整一圈 ≈ 360°，
 *   直线 ≈ 0°，来回折返（Z 字、八字节）正负抵消后也 ≈ 0°；
 * - [Result.closeGapRatio]：起点到终点的直线距离 / 平均半径。画满一圈才会小；
 *   只画 3/4 圈时这个值 ≈ 1.4；
 * - [Result.radiusCv]：各采样点到质心的距离的**变异系数**（std / mean），
 *   用来排除螺旋、来回乱涂这种「转角够了但根本不是圆」的轨迹。
 *
 * 判定方向刻意偏**宽松**：画不出圈被锁住比误解除难受得多（误解除只是提前放开触摸），
 * 所以阈值默认留了 ~25% 余量（[Config.minTurnsDeg] = 250°、[Config.maxCloseGapRatio] = 0.5）。
 *
 * 纯 Kotlin，不依赖 Android：可以直接跑 JVM 单测（见 `TouchBlockLoopDetectorTest`）。
 */
object TouchBlockLoopDetector {

    /** 少于这么多点不可能画出闭环（一次点击、两三个 MOVE 都不算）。 */
    private const val MIN_POINTS = 6

    /** 一个触摸采样点。 */
    data class Sample(val x: Float, val y: Float, val timeMs: Long)

    /**
     * 阈值。默认值是「宽松档」；[minSampleDistancePx] / [minRadiusPx] 由调用方按 dp 换算成 px 传入。
     */
    data class Config(
        /** 相邻采样点的最小间距（px）：触摸事件约 120Hz，小于它的位移是手抖，不入账。 */
        val minSampleDistancePx: Float = 0f,
        /** 至少要转过的累计角度（度）。 */
        val minTurnsDeg: Float = 250f,
        /** 首尾间距 / 平均半径 的上限。 */
        val maxCloseGapRatio: Float = 0.5f,
        /** 半径变异系数上限。 */
        val maxRadiusCv: Float = 0.5f,
        /** 平均半径下限（px），过滤小到不像故意的圈（手指轻颤）。 */
        val minRadiusPx: Float = 0f,
        /** 轨迹总时长上限（ms），超时说明不是一口气画的。 */
        val maxDurationMs: Long = 12_000L,
        /** 重采样点数：等弧长插值后做角度/半径统计，天然容忍采样率差异。 */
        val resampleCount: Int = 64,
    )

    /** 判定结果；无论通过与否都返回，方便调用方直接打日志。 */
    data class Result(
        val accepted: Boolean,
        val turnsDeg: Float,
        val closeGapRatio: Float,
        val radiusCv: Float,
        val meanRadiusPx: Float,
        val pathLengthPx: Float,
        val durationMs: Long,
        val pointCount: Int,
    ) {
        /** 一行日志文本：`accepted=true turns=352.1° gap=0.12 cv=0.08 radius=219.4px …` */
        fun describe(): String = buildString {
            append("accepted=").append(accepted)
            append(" turns=").append(round1(turnsDeg)).append('°')
            append(" gap=").append(round2(closeGapRatio))
            append(" cv=").append(round2(radiusCv))
            append(" radius=").append(round1(meanRadiusPx)).append("px")
            append(" len=").append(round1(pathLengthPx)).append("px")
            append(" ms=").append(durationMs)
            append(" pts=").append(pointCount)
        }
    }

    /**
     * 判定一条轨迹是不是「画了一个圈」。
     *
     * @param samples 从 `ACTION_DOWN` 到 `ACTION_UP` 的采样点（按时间顺序）。
     * @return 永远返回结果对象（不返回 null），[Result.accepted] 才是判定。
     */
    fun detect(samples: List<Sample>, config: Config = Config()): Result {
        val points = thin(samples, config.minSampleDistancePx)
        val durationMs = if (points.size >= 2) {
            (points.last().timeMs - points.first().timeMs).coerceAtLeast(0L)
        } else {
            0L
        }
        val length = pathLength(points)
        if (points.size < MIN_POINTS) {
            return Result(false, 0f, 1f, 1f, 0f, length, durationMs, points.size)
        }

        val resampled = resample(points, config.resampleCount)
            ?: return Result(false, 0f, 1f, 1f, 0f, length, durationMs, points.size)

        // 顺时针与逆时针的符号相反，判定只看"一共转了多少度"，所以取绝对值。
        val turnsDeg = abs(signedTurnsDeg(resampled))
        val (meanRadius, radiusCv) = radiusStats(resampled)
        val closeGapRatio = if (meanRadius > 0f) {
            distance(points.first(), points.last()) / meanRadius
        } else {
            Float.MAX_VALUE
        }

        val accepted = meanRadius > 0f &&
            durationMs <= config.maxDurationMs &&
            meanRadius >= config.minRadiusPx &&
            turnsDeg >= config.minTurnsDeg &&
            closeGapRatio <= config.maxCloseGapRatio &&
            radiusCv <= config.maxRadiusCv

        return Result(
            accepted = accepted,
            turnsDeg = turnsDeg,
            closeGapRatio = closeGapRatio,
            radiusCv = radiusCv,
            meanRadiusPx = meanRadius,
            pathLengthPx = length,
            durationMs = durationMs,
            pointCount = points.size,
        )
    }

    /** 相邻两点间距小于 [minDistance] 的重复采样丢弃；首尾点必须保留（闭合度靠它们算）。 */
    private fun thin(samples: List<Sample>, minDistance: Float): List<Sample> {
        if (samples.size <= 2 || minDistance <= 0f) return samples
        val minSq = minDistance * minDistance
        val out = ArrayList<Sample>(samples.size)
        out.add(samples.first())
        for (index in 1 until samples.size - 1) {
            val sample = samples[index]
            val last = out.last()
            val dx = sample.x - last.x
            val dy = sample.y - last.y
            if (dx * dx + dy * dy >= minSq) out.add(sample)
        }
        out.add(samples.last())
        return out
    }

    private fun pathLength(points: List<Sample>): Float {
        if (points.size < 2) return 0f
        var length = 0f
        for (index in 1 until points.size) {
            length += distance(points[index - 1], points[index])
        }
        return length
    }

    /** 等弧长重采样到 [count] 个点；点数不足或轨迹长度为零时返回 null。 */
    private fun resample(points: List<Sample>, count: Int): List<Sample>? {
        if (points.size < 2 || count < 3) return null
        val cumulative = FloatArray(points.size)
        for (index in 1 until points.size) {
            cumulative[index] = cumulative[index - 1] + distance(points[index - 1], points[index])
        }
        val total = cumulative.last()
        if (total <= 0f) return null

        val out = ArrayList<Sample>(count)
        var segment = 1
        for (index in 0 until count) {
            val target = total * index / (count - 1).toFloat()
            while (segment < points.size - 1 && cumulative[segment] < target) segment++
            val previous = segment - 1
            val span = cumulative[segment] - cumulative[previous]
            val fraction = if (span <= 1e-6f) 0f else ((target - cumulative[previous]) / span).coerceIn(0f, 1f)
            val from = points[previous]
            val to = points[segment]
            out.add(
                Sample(
                    x = from.x + (to.x - from.x) * fraction,
                    y = from.y + (to.y - from.y) * fraction,
                    timeMs = from.timeMs + ((to.timeMs - from.timeMs) * fraction).toLong(),
                )
            )
        }
        return out
    }

    /**
     * 逐个折点的有符号转角（度）取绝对值之和。
     *
     * 顺/逆时针的符号相反，所以「绕过去又绕回来」（八字节、来回涂）会正负抵消；
     * 完整一圈无论顺逆都落在 ±360° 附近。用 [atan2] 而不是 acos 保证每步落在 (-180, 180]。
     */
    private fun signedTurnsDeg(points: List<Sample>): Float {
        if (points.size < 3) return 0f
        var radians = 0.0
        for (index in 1 until points.size - 1) {
            val ax = (points[index].x - points[index - 1].x).toDouble()
            val ay = (points[index].y - points[index - 1].y).toDouble()
            val bx = (points[index + 1].x - points[index].x).toDouble()
            val by = (points[index + 1].y - points[index].y).toDouble()
            val cross = ax * by - ay * bx
            val dot = ax * bx + ay * by
            radians += atan2(cross, dot)
        }
        return (radians * 180.0 / PI).toFloat()
    }

    /** 返回 (平均半径, 半径变异系数)；点数不足时 (0, MAX)。 */
    private fun radiusStats(points: List<Sample>): Pair<Float, Float> {
        if (points.size < 2) return 0f to Float.MAX_VALUE
        var sumX = 0.0
        var sumY = 0.0
        for (point in points) {
            sumX += point.x.toDouble()
            sumY += point.y.toDouble()
        }
        val centerX = sumX / points.size
        val centerY = sumY / points.size
        var sum = 0.0
        var sumSquares = 0.0
        for (point in points) {
            val dx = point.x - centerX
            val dy = point.y - centerY
            val radius = sqrt(dx * dx + dy * dy)
            sum += radius
            sumSquares += radius * radius
        }
        val mean = sum / points.size
        if (mean <= 1e-3) return 0f to Float.MAX_VALUE
        val variance = (sumSquares / points.size - mean * mean).coerceAtLeast(0.0)
        return mean.toFloat() to (sqrt(variance) / mean).toFloat()
    }

    private fun distance(from: Sample, to: Sample): Float {
        val dx = to.x - from.x
        val dy = to.y - from.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun round1(value: Float): String = (Math.round(value * 10f) / 10f).toString()

    private fun round2(value: Float): String = (Math.round(value * 100f) / 100f).toString()
}
