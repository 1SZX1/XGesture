package com.slideindex.app.overlay.layout

import com.slideindex.app.overlay.PanelSide
import kotlin.math.ceil

data class GridLayoutInfo(
    val appsPerRow: Int,
    val panelColumns: Int,
    val rows: Int,
    val panelWidth: Float,
)

fun gridLayoutInfo(appCount: Int, appsPerRow: Int, cellWidth: Float, gridPadding: Float): GridLayoutInfo {
    val m = appsPerRow.coerceAtLeast(1)
    if (appCount <= 0) {
        return GridLayoutInfo(m, 0, 0, 0f)
    }
    val panelColumns = minOf(appCount, m)
    val rows = ceil(appCount / m.toFloat()).toInt()
    val panelWidth = panelColumns * cellWidth + gridPadding * 2
    return GridLayoutInfo(m, panelColumns, rows, panelWidth)
}

fun visualColumn(index: Int, m: Int, appCount: Int, side: PanelSide): Int {
    val colInRow = index % m
    val panelColumns = minOf(appCount, m)
    return when (side) {
        PanelSide.RIGHT -> panelColumns - 1 - colInRow
        PanelSide.LEFT, PanelSide.BOTTOM, PanelSide.TOP -> colInRow
    }
}

/**
 * 面板内容的「排版侧」：决定格子从哪一端开始铺、翻页往哪边翻。
 *
 * 与**物理侧**严格区分：面板贴着屏幕哪一边、从哪边滑出来、触钮在哪，永远看真实 side；
 * 只有排版相关的地方（[visualColumn] / 翻页方向 / 页码圆点）才用这个结果。
 *
 * [mirrorRightSide] 为 false 时，右侧面板也按 LEFT 的 LTR 语义排版，
 * 于是渲染和手势会一起变成「从左往右」，不需要第二套翻页分支。
 */
fun resolveLayoutSide(side: PanelSide, mirrorRightSide: Boolean): PanelSide =
    if (!mirrorRightSide && side == PanelSide.RIGHT) PanelSide.LEFT else side
