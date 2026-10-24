package com.slideindex.app.widget

import kotlin.math.roundToInt

object WidgetPanelLayoutMetrics {
    data class Result(
        val panelWidthPx: Int,
        /** Distance between adjacent grid dots; one span equals this width. */
        val gridStepPx: Int,
        val gridPadPx: Int,
        val viewportHeightPx: Int,
    )

    /** 一个页面在屏幕上占用的整块面板尺寸（含指示器/空态提示与上/左边距）。 */
    data class PanelBox(
        val widthPx: Int,
        val heightPx: Int,
        val topMarginPx: Int,
        /** 距屏幕左边的边距；0 表示水平居中。 */
        val leftMarginPx: Int = 0,
    )

    fun compute(
        screenWidthPx: Int,
        page: WidgetPanelPage,
        density: Float,
        panelPaddingDp: Float = 12f,
        panelInnerPaddingDp: Float = 4f,
        horizontalInsetDp: Float = 16f,
        leftInsetDp: Float = 0f,
    ): Result {
        val horizontalInsetPx = (horizontalInsetDp * density).roundToInt()
        val leftInsetPx = (leftInsetDp * density).roundToInt().coerceAtLeast(0)
        val panelPaddingPx = (panelPaddingDp * density).roundToInt()
        val gridPadPx = (panelInnerPaddingDp * density).roundToInt()
        val columnCount = page.columnCount.coerceAtLeast(1)
        val visibleRows = page.visibleRowCount.coerceAtLeast(1)

        val maxPanelWidthPx = (screenWidthPx - horizontalInsetPx * 2 - leftInsetPx).coerceAtLeast(1)
        val desiredCellWidthPx = if (page.cellWidthDp > 0) (page.cellWidthDp * density).roundToInt() else 0

        val (panelWidthPx, gridStepPx) = if (desiredCellWidthPx > 0) {
            val step = desiredCellWidthPx
            val panelW = step * columnCount + panelPaddingPx * 2 + gridPadPx * 2
            panelW to step
        } else {
            val innerForCellsPx = (maxPanelWidthPx - panelPaddingPx * 2 - gridPadPx * 2).coerceAtLeast(columnCount)
            val step = WidgetGridMetrics.computeGridStepPx(innerForCellsPx, columnCount)
            maxPanelWidthPx to step
        }

        val viewportInnerPx = visibleRows * gridStepPx
        val viewportHeightPx = viewportInnerPx + gridPadPx * 2

        return Result(
            panelWidthPx = panelWidthPx,
            gridStepPx = gridStepPx,
            gridPadPx = gridPadPx,
            viewportHeightPx = viewportHeightPx,
        )
    }

    /**
     * 面板的窗口尺寸。页面的列数/单元宽度/可见行数都可以逐页不同，
     * 所以切页或设置变化时必须按「当前页」重算，否则内容宽于旧尺寸就会被裁掉一列。
     */
    fun computePanelBox(
        screenWidthPx: Int,
        page: WidgetPanelPage,
        density: Float,
        pageCount: Int,
        panelPaddingDp: Float = DEFAULT_PANEL_PADDING_DP,
        panelInnerPaddingDp: Float = DEFAULT_PANEL_INNER_PADDING_DP,
        horizontalInsetDp: Float = DEFAULT_HORIZONTAL_INSET_DP,
        indicatorHeightDp: Float = DEFAULT_INDICATOR_HEIGHT_DP,
        hintHeightDp: Float = DEFAULT_HINT_HEIGHT_DP,
    ): PanelBox {
        val leftMarginPx = (page.resolvedMarginLeftDp * density).roundToInt().coerceAtLeast(0)
        val metrics = compute(
            screenWidthPx = screenWidthPx,
            page = page,
            density = density,
            panelPaddingDp = panelPaddingDp,
            panelInnerPaddingDp = panelInnerPaddingDp,
            horizontalInsetDp = horizontalInsetDp,
            leftInsetDp = page.resolvedMarginLeftDp.toFloat(),
        )
        val panelPaddingPx = (panelPaddingDp * density).roundToInt() * 2
        val indicatorHeightPx = if (pageCount > 1) (indicatorHeightDp * density).roundToInt() else 0
        val hintHeightPx = if (page.items.isEmpty()) (hintHeightDp * density).roundToInt() else 0
        return PanelBox(
            widthPx = metrics.panelWidthPx,
            heightPx = panelPaddingPx + metrics.viewportHeightPx + indicatorHeightPx + hintHeightPx,
            topMarginPx = (page.marginTopDp * density).roundToInt(),
            leftMarginPx = leftMarginPx,
        )
    }

    const val DEFAULT_PANEL_PADDING_DP = 12f
    const val DEFAULT_PANEL_INNER_PADDING_DP = 4f
    const val DEFAULT_HORIZONTAL_INSET_DP = 16f
    const val DEFAULT_INDICATOR_HEIGHT_DP = 14f
    const val DEFAULT_HINT_HEIGHT_DP = 20f
}
