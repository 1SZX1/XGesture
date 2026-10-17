/*
 * FV「自定义手势」参考实现 —— 第 1 层：分类器与纯数学函数
 *
 * 与 FooView 1.6.4 的对应关系（逐行等价）：
 *   q1.o.f(int, Gesture, q1.o$a)   ->  FvGestureClassifier.classify()
 *   q1.o.C(int)                    ->  dirCodeOfAngle()
 *   q1.o.D(int)                    ->  mirrorDir()
 *   n5.x2.m1 / B1 / n1 / X0        ->  同名的顶层函数
 *   n5.q.a / b / c                 ->  dp() / dp(ctx) / touchSlop()
 *
 * 逆向依据见 docs/reverse/fv-custom-gesture/01-core-algorithm.md。
 */
package com.slideindex.app.fvgesture

import android.content.Context
import android.gesture.Gesture
import android.util.TypedValue
import android.view.ViewConfiguration
import com.slideindex.app.gesture.GestureAction
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.sqrt

/** 方向码。与 FV 的取值完全一致：1=上 2=下 3=左 4=右 */
object FvDir {
    const val UP = 1
    const val DOWN = 2
    const val LEFT = 3
    const val RIGHT = 4
}

/**
 * 手势元数据。对应 FooView 的 q1.o$a。
 *
 * 注意：FV 里字段名是 a/b/c/d/e/f，其中 `c` 既是「是否简单方向手势」的字段名，
 * 又有一个同名方法 `c()` = `(id % 1000) >= 500`（判定镜像 ID）。移植时**必须**改名，
 * 这里拆成 isSimple / id / dir1 / dir2 / splitIndex。
 *
 * 与 FV 的一处**有意差异**：FV 把「手势 → 动作」的绑定放在独立的动作表 `i0.g`（按 id 索引），
 * 这里改成**手势自带动作负载**（[action]），随 `icon_gestures_info` 一起落盘。
 * 镜像 ID（+500）依旧不单独存动作：`mirror()` 不带 action，读取时由
 * [FvGestureLibrary.actionOf] 先做 `-500` 归一——与 FV 的 `q1.o.k()` 语义一致。
 */
data class FvGestureInfo(
    /** GestureStore 的 entryName，也是手势 ID 的十进制字符串 */
    var name: String = "",
    /** 手势 ID（FV 里它同时是动作 ID；本实现里动作负载在 [action] 上） */
    var id: Int = 0,
    /** true = 两段折线（方向手势）；false = 曲线（走识别器） */
    var isSimple: Boolean = true,
    /** 第 1 段方向码 */
    var dir1: Int = FvDir.RIGHT,
    /** 第 2 段方向码 */
    var dir2: Int = FvDir.RIGHT,
    /** 拐点（points 数组下标） */
    var splitIndex: Int = 0,
    /** 该手势触发的动作；只在基 ID 条目上有意义（镜像条目复用基条目的动作） */
    var action: GestureAction = GestureAction.None
) {
    /** FV: q1.o$a.b() —— 「无效简单手势」：一条直线（两段方向相同） */
    val isStraightLine: Boolean get() = isSimple && dir1 == dir2

    /** FV: q1.o$a.a() —— 生成镜像记录（左右互换，ID +500）。镜像不携带动作。 */
    fun mirror(): FvGestureInfo = FvGestureInfo(
        name = (id + MIRROR_OFFSET).toString(),
        id = id + MIRROR_OFFSET,
        isSimple = isSimple,
        dir1 = mirrorDir(dir1),
        dir2 = mirrorDir(dir2),
        splitIndex = splitIndex
    )

    companion object {
        /** FV: 镜像手势 ID 偏移 */
        const val MIRROR_OFFSET = 500
    }
}

object FvGestureClassifier {

    /**
     * FV: q1.o.f(int id, Gesture gesture, q1.o$a out)  —— 逐行等价。
     *
     * 语义：
     *  - 空手势 / 点数 <= 4  ->  isSimple=true, dir1=dir2=RIGHT（于是 isStraightLine == true）
     *  - 整条是直线           ->  同上（isStraightLine == true，调用方据此判为「直线手势」）
     *  - 两段折线             ->  isSimple=true, dir1/dir2 = 两段方向码
     *  - 其它（曲线）         ->  isSimple=false
     */
    fun classify(id: Int, gesture: Gesture?, out: FvGestureInfo, tol: Int) {
        out.id = id
        out.name = id.toString()
        out.isSimple = true
        out.dir1 = FvDir.RIGHT
        out.dir2 = FvDir.RIGHT

        if (gesture == null || gesture.strokesCount == 0) return
        // ★ FV 只用第 1 笔
        val pts = gesture.strokes[0].points ?: return
        if (pts.size <= 4) return

        if (isLine(pts, 0, pts.size, tol)) return          // 整条是直线

        // 找拐点：第一个 i 使 [0,i) 与 [i,len) 都是直线
        var found = false
        var i = 2
        while (!found && i < pts.size - 4) {
            i += 2
            found = isLine(pts, 0, i, tol) && isLine(pts, i, pts.size, tol)
        }
        if (!found) {
            out.isSimple = false
            return
        }

        // 拐点微调：把仍属第二段的点并进来
        while (i < pts.size - 4) {
            val x = pts[i]
            val y = pts[i + 1]
            val j = i + 2
            if (!isLine(pts, j, pts.size, tol) ||
                pointLineDistance(pts[0], pts[1], pts[i - 2], pts[i - 1], x, y) >
                pointLineDistance(pts[j], pts[j + 1], pts[pts.size - 2], pts[pts.size - 1], x, y) ||
                !isLine(pts, 0, j, tol)
            ) break
            i = j
        }
        out.splitIndex = i
        out.dir1 = dirCodeOfAngle(angleOf(pts[0], pts[1], pts[i - 2], pts[i - 1]))
        out.dir2 = dirCodeOfAngle(angleOf(pts[i], pts[i + 1], pts[pts.size - 2], pts[pts.size - 1]))
    }

    /** FV: q1.o.C(int angle) */
    fun dirCodeOfAngle(angle: Int): Int = when {
        angle < -135 -> FvDir.LEFT
        angle < -45 -> FvDir.UP
        angle < 45 -> FvDir.RIGHT
        angle < 135 -> FvDir.DOWN
        else -> FvDir.LEFT
    }
}

/** FV: q1.o.D(int) —— 镜像方向码：3<->4，1/2 不变 */
fun mirrorDir(d: Int): Int = when (d) {
    FvDir.LEFT -> FvDir.RIGHT
    FvDir.RIGHT -> FvDir.LEFT
    else -> d
}

/**
 * FV: n5.x2.m1(float x1, float y1, float x2, float y2)
 * 两点方向角（度），屏幕坐标系（y 向下）：右=0，下=90，上=-90，左=±180。
 */
fun angleOf(x1: Float, y1: Float, x2: Float, y2: Float): Int {
    if (x1.toInt() == x2.toInt()) return if (y2 > y1) 90 else -90
    val atan = (atan(((y2 - y1) / (x2 - x1)).toDouble()) * 180.0 / Math.PI).toInt()
    return when {
        atan >= 0 -> if (x2 <= x1) atan - 180 else atan
        else -> if (x2 <= x1) atan + 180 else atan
    }
}

/**
 * FV: n5.x2.B1(x1,y1,x2,y2,x3,y3)
 * 点 (x3,y3) 到「过 (x1,y1)、(x2,y2) 的直线」的垂距（取整）。
 */
fun pointLineDistance(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): Int {
    val vertical = x1.toInt() == x2.toInt()
    val k = if (vertical) 0.0 else ((y1 - y2) / (x1 - x2)).toDouble()
    return if (vertical) {
        abs(x3 - x1).toInt()
    } else {
        (abs(k * x3 - y3 + (y2 - x2 * k)) / sqrt(k * k + 1.0)).toInt()
    }
}

/** FV: n5.x2.n1 —— 两点欧氏距离（取整） */
fun distanceOf(x1: Float, y1: Float, x2: Float, y2: Float): Int {
    val dx = x1 - x2
    val dy = y1 - y2
    return sqrt(dx * dx + dy * dy).toInt()
}

/**
 * FV: n5.x2.X0(float[] pts, int start, int end)  —— smali 逐行还原（jadx 反编译失败）。
 *
 * 「这段点是不是一条直线」：点沿首尾连线方向单调前进，且每点到首尾连线的垂距、
 * 以及偶发反向跳变的点距都不超过 tol = 4 × getScaledTouchSlop()。
 * 异常时返回 true（FV 原样行为）。
 *
 * @param tol 传入 4 * touchSlop(context)，由调用方缓存，避免每次进 ViewConfiguration。
 */
fun isLine(pts: FloatArray, start: Int, end: Int, tol: Int): Boolean {
    try {
        if (pts.size <= 4) return true
        var s = start
        var e = end
        if (s < 0 || s >= pts.size) s = 0
        if (e < 0 || e > pts.size) e = pts.size
        if (e - s <= 4) return true

        val x1 = pts[s].toInt(); val y1 = pts[s + 1].toInt()
        val x2 = pts[e - 2].toInt(); val y2 = pts[e - 1].toInt()

        val vertical = x1 == x2
        val k: Double
        val b: Double
        if (vertical) {
            k = 0.0; b = 0.0
        } else {
            k = (y1 - y2).toDouble() / (x1 - x2).toDouble()
            b = y2 - k * x2
        }
        val norm = if (vertical) 0.0 else sqrt(k * k + 1.0)

        val dirX = x2 > x1
        val dirY = y2 > y1
        var prevX = x1
        var prevY = y1

        var i = s + 2
        while (i < e - 2) {
            val px = pts[i].toInt()
            val py = pts[i + 1].toInt()
            val okX = dirX == (px > prevX)
            val okY = dirY == (py > prevY)

            if (okX && okY) {
                prevX = px
                prevY = py
            } else {
                if (okX && abs(px - prevX) >= tol && distanceOf(prevX.toFloat(), prevY.toFloat(), px.toFloat(), py.toFloat()) > tol) {
                    return false
                }
                if (okY && abs(py - prevY) >= tol && distanceOf(prevX.toFloat(), prevY.toFloat(), px.toFloat(), py.toFloat()) > tol) {
                    return false
                }
            }

            val dist = if (vertical) abs(px - x1).toDouble()
            else abs(px * k - py + b) / norm
            if (dist.toInt() > tol) return false

            i += 2
        }
        return true
    } catch (e: Exception) {
        return true
    }
}

/** 便捷重载：按 context 计算 tol（4 × touchSlop） */
fun lineTolerance(context: Context): Int = touchSlop(context) * 4

// ---------------------------------------------------------------- dp / 阈值工具

/** FV: n5.q.b(Context, int) —— 注意 FV 忽略传入的 context，这里按正常语义实现 */
fun dp(context: Context, value: Int): Int =
    TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
    ).toInt()

/** FV: n5.q.c() —— ViewConfiguration.getScaledTouchSlop() */
fun touchSlop(context: Context): Int = ViewConfiguration.get(context).scaledTouchSlop
