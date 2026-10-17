/*
 * FV「自定义手势」↔ 本项目（悬浮球）的接线层
 *
 * 对应 FV 的哪段逻辑（证据见 docs/reverse/fv-custom-gesture/03-runtime-pipeline.md §2.3）：
 *   FV 在抬手时对「本次拖动」的原始轨迹跑一次 q1.o.h(1).G(gesture)，返回值三态：
 *     > 0  命中     -> 执行该手势绑定的动作
 *     == 0 直线手势 -> 什么都不改，继续执行该方向原本配置的动作（FV 的 input_gesture_error_1 语义）
 *     < 0  曲线未命中 -> 本次什么都不做（不执行原生动作）
 *
 * 本文件把这套三态判定与本项目的悬浮球手势对接：
 *   - 悬浮球检测到「非直线」轨迹且本库里有自定义手势时，才介入；
 *   - 干预后的行为与 FV 完全一致（见 [FvCustomGestureOutcome] 的注释）；
 *   - 库里没有任何自定义手势时**完全不介入**，悬浮球现有手势行为一字不变。
 */
package com.slideindex.app.fvgesture

import android.gesture.Gesture
import android.gesture.GesturePoint
import android.gesture.GestureStroke
import com.slideindex.app.gesture.GestureAction

/** 一次手绘轨迹的判定结果，语义与 FV 的 `H()` 返回值一一对应。 */
sealed interface FvCustomGestureOutcome {
    /** 直线手势（FV `H() == 0`）：交给原生手势，行为不变。 */
    data object Native : FvCustomGestureOutcome

    /** 曲线但没命中任何自定义手势（FV `H() < 0`）：本次什么都不做。 */
    data object Consumed : FvCustomGestureOutcome

    /** 命中：执行该手势绑定的动作（FV `H() > 0`）。 */
    data class Matched(val gestureId: Int, val action: GestureAction) : FvCustomGestureOutcome
}

/**
 * 把「x,y 交替的屏幕 raw 坐标数组」转成平台 Gesture。
 *
 * 与 FV 的差异（有意）：FV 用 `System.currentTimeMillis()` 当点时间戳，我们同样用墙钟毫秒；
 * 但 FV 的 DOWN 点复用缓存字段 E，我们不区分——时间戳对识别**无影响**（AOSP 的
 * `Instance.createInstance` 只对弧长抽样，不使用时间戳；见 06-android-gesture-framework-spec.md）。
 */
fun gestureFromRawPath(path: FloatArray): Gesture? {
    if (path.size < 6) return null
    val now = System.currentTimeMillis()
    val points = ArrayList<GesturePoint>(path.size / 2)
    var i = 0
    while (i + 1 < path.size) {
        points.add(GesturePoint(path[i], path[i + 1], now))
        i += 2
    }
    return Gesture().apply { addStroke(GestureStroke(points)) }
}

/**
 * FV: `q1.m.w()` —— 生成「对称手势」：把第 1 笔每个点的 x 换成 `面板宽 - x`（水平镜像）。
 *
 * 与 FV 一致：只处理第 1 笔；只在图标库（type == 1）路径上使用（通用库没有对称开关）。
 * FV 原文见 docs/reverse/fv-custom-gesture/01-core-algorithm.md §4。
 */
fun mirroredGesture(gesture: Gesture?, panelWidthPx: Int): Gesture? {
    if (gesture == null || panelWidthPx <= 0) return null
    if (gesture.strokesCount <= 0) return null
    val stroke = gesture.strokes[0] ?: return null
    if ((stroke.points?.size ?: 0) < 2) return null
    val cloned = (stroke.clone() as? GestureStroke) ?: return null
    var i = 0
    while (i < cloned.points.size) {
        cloned.points[i] = panelWidthPx - cloned.points[i]
        i += 2
    }
    return Gesture().apply { addStroke(cloned) }
}

/**
 * FV 三态判定的纯函数版本（不执行任何动作，便于单测）。
 *
 * @param libraryType [FvGestureLibrary.TYPE_ICON]（图标库，对应本项目悬浮球）或 `TYPE_GENERIC`
 */
fun resolveCustomGesture(path: FloatArray, libraryType: Int = FvGestureLibrary.TYPE_ICON): FvCustomGestureOutcome {
    val library = FvGestureLibrary.of(libraryType)
    // 没有任何自定义手势时完全不介入：保证未使用该功能的用户行为零变化。
    if (library.userCount == 0) return FvCustomGestureOutcome.Native
    val gesture = gestureFromRawPath(path) ?: return FvCustomGestureOutcome.Native
    return when (val id = library.recognize(gesture, rawId = false)) {
        // FV: probe.b()（直线）-> H() 返回 0 -> 原生手势继续
        0 -> FvCustomGestureOutcome.Native
        // FV: 命中（G() 已经把镜像 ID 归一为 base ID）
        else -> if (id > 0) {
            library.actionOf(id)?.let { FvCustomGestureOutcome.Matched(id, it) }
                ?: FvCustomGestureOutcome.Consumed
        } else {
            // FV: 曲线但没命中 -> 本次什么都不做
            FvCustomGestureOutcome.Consumed
        }
    }
}
