package com.slideindex.app.gesture

/**
 * 悬浮球手势打开浮层时的锚点策略。
 *
 * 悬浮球的滑动类手势要到 **松手** 才判定（见 `FloatBallGestureDetector.onTouchEvent` 的 ACTION_UP 分支），
 * 判定成立时上报的手指位置就是松手点；而「长距离滑动」只要求越过阈值，
 * "这次多滑了多远"完全看手感，于是浮层出现的位置每次都不同，靠底部时还会被版面夹取顶回来。
 *
 * 因此 [GestureAction.QuickLauncher] / [GestureAction.RingLauncher] 改用手指**按下**位置：
 * 位置稳定可复现，且与悬浮球本身重合。其余动作一律保持原样——
 * 音量/亮度要跟手，悬浮指针、指针滑动要落在指尖，任务切换器/索引/蜂窝等不在本次范围内。
 *
 * 只有传入了按下坐标的调用点（悬浮球手势派发）才可能命中策略，
 * 边滑手势、快捷键等其它入口不传按下坐标，因此行为完全不变。
 */
internal object FloatBallOverlayAnchorPolicy {
    /** 悬浮球手势里只有这两个浮层的锚点跟随按下位置才有意义。 */
    fun anchorsAtTouchDown(action: GestureAction, enabled: Boolean): Boolean =
        enabled && (action is GestureAction.QuickLauncher || action == GestureAction.RingLauncher)

    /** 命中策略且拿得到按下坐标时优先用按下坐标，否则回落当前手指位置（历史行为）。 */
    fun resolve(anchorsAtTouchDown: Boolean, current: Float?, touchDown: Float?): Float? =
        if (anchorsAtTouchDown && touchDown != null) touchDown else current

    /**
     * 锚点允许的纵向区间（浮层 view 局部坐标）。
     *
     * 默认沿用触钮区间：面板始终贴着触钮出现。命中按下位置策略时只保留屏幕边距约束——
     * 悬浮球可以停靠在触钮范围之外，夹进触钮区间会让面板离开手指按下的位置；
     * 面板本身仍会被 [com.slideindex.app.overlay.layout.QuickLauncherPanelLayoutEngine] 夹在屏幕内。
     *
     * **触发带为空**（该侧没有任何触钮，例如用角轮盘 / 悬浮球线条在无触钮的侧触发）时同样只保留
     * 屏幕边距：空矩形的区间是 `0..0`，照夹会把面板顶到屏幕最上沿。
     */
    fun anchorRange(
        anchorsAtTouchDown: Boolean,
        viewHeightPx: Float,
        marginPx: Float,
        triggerTopPx: Float,
        triggerBottomPx: Float,
    ): ClosedFloatingPointRange<Float> {
        if (!anchorsAtTouchDown) {
            val top = minOf(triggerTopPx, triggerBottomPx)
            val bottom = maxOf(triggerTopPx, triggerBottomPx)
            if (bottom > top) return top..bottom
        }
        val maxY = (viewHeightPx - marginPx).coerceAtLeast(marginPx)
        return marginPx..maxY
    }
}
