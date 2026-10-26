package com.slideindex.app.overlay

import com.slideindex.app.message.MessageSettings
import kotlin.math.abs

/**
 * 贴边通知卡的两种形态。
 *
 * [Collapsed] 半透明、只露头像和未读数；[Expanded] 白底、露出标题与摘要。
 */
internal enum class EdgeCardPhase { Collapsed, Expanded }

/** 四向手势的归一化结果。 */
internal enum class EdgeCardSwipe { None, Expand, Collapse, Dismiss }

/**
 * 贴边通知卡的状态机（纯函数，窗口与手势只负责翻译事件）。
 *
 * - 左滑 = 展开；右滑 = 收起；上下滑 = 消失
 * - 收起态点击 = 展开；展开态点击 = 打开会话（由窗口回调处理）
 * - 新消息到达自动展开，并把未读数折算成头像上的角标
 */
internal data class EdgeCardUiState(
    val phase: EdgeCardPhase = EdgeCardPhase.Collapsed,
    val unreadCount: Int = 0,
) {
    val isExpanded: Boolean get() = phase == EdgeCardPhase.Expanded

    fun withUnread(count: Int): EdgeCardUiState =
        copy(unreadCount = count.coerceIn(0, MAX_UNREAD))

    fun expand(): EdgeCardUiState =
        if (isExpanded) this else copy(phase = EdgeCardPhase.Expanded)

    fun collapse(): EdgeCardUiState =
        if (isExpanded) copy(phase = EdgeCardPhase.Collapsed) else this

    fun onSwipeLeft(): EdgeCardUiState = expand()

    fun onSwipeRight(): EdgeCardUiState = collapse()

    fun onTap(): EdgeCardUiState = expand()

    /** 新消息：更新未读数，并按是否允许自动展开决定形态。 */
    fun onNewMessage(unread: Int, autoExpand: Boolean): EdgeCardUiState =
        withUnread(unread).let { if (autoExpand) it.expand() else it }

    /** 角标文案：只有多于一条才显示，避免单个会话也挂红点。 */
    fun badgeText(): String? {
        val count = unreadCount
        if (count <= 1) return null
        return if (count > 99) "99+" else count.toString()
    }

    /** 展开宽度随未读数变化：单条窄、多条宽。 */
    fun expandedWidthDp(): Float =
        if (unreadCount > 1) WIDE_WIDTH_DP else EXPANDED_WIDTH_DP

    fun widthDp(): Float = if (isExpanded) expandedWidthDp() else COMPACT_WIDTH_DP

    /** 底板不透明度：随展开进度从收起透明度线性升到 1。 */
    fun boardAlpha(settings: MessageSettings, progress: Float): Float {
        val collapsed = settings.edgeCardOpacity.coerceIn(0f, 1f)
        val p = progress.coerceIn(0f, 1f)
        return collapsed + (1f - collapsed) * p
    }

    companion object {
        const val HEIGHT_DP = 48f
        const val COMPACT_WIDTH_DP = 48f
        const val EXPANDED_WIDTH_DP = 126f
        const val WIDE_WIDTH_DP = 184f

        /** 头像与白底同心：48 - 40 = 8，左右各 4dp。 */
        const val ICON_DP = 40f
        const val ICON_PADDING_DP = (COMPACT_WIDTH_DP - ICON_DP) / 2f
        const val BADGE_DP = 13f
        const val BADGE_TEXT_SP = 8.5f
        const val CARD_GAP_DP = 8f
        const val CORNER_DP = HEIGHT_DP / 2f
        const val MAX_UNREAD = 999
        const val EXPAND_MS = 250
        const val MIN_COUNT = 1
        const val MAX_COUNT = 5
        const val DEFAULT_COUNT = 3

        /** 拖过这个进度才开始淡入文字，避免收起态漏字。 */
        const val TEXT_FADE_START = 0f

        fun widthForProgress(expandedWidthDp: Float, progress: Float): Float =
            COMPACT_WIDTH_DP + (expandedWidthDp - COMPACT_WIDTH_DP) * progress.coerceIn(0f, 1f)

        /** 文字透明度直接等于展开进度：拖多少露多少，没有独立的时间节奏。 */
        fun textAlpha(progress: Float): Float = progress.coerceIn(0f, 1f)

        /** 抬手时按主轴与位移判定动作；未过阈值返回 [EdgeCardSwipe.None]（视作点击）。 */
        fun resolveSwipe(
            totalX: Float,
            totalY: Float,
            horizontalThresholdPx: Float,
            verticalThresholdPx: Float,
        ): EdgeCardSwipe {
            val absX = abs(totalX)
            val absY = abs(totalY)
            if (absX >= absY && absX >= horizontalThresholdPx) {
                return if (totalX < 0f) EdgeCardSwipe.Expand else EdgeCardSwipe.Collapse
            }
            if (absY > absX && absY >= verticalThresholdPx) {
                return EdgeCardSwipe.Dismiss
            }
            return EdgeCardSwipe.None
        }

        fun countFor(settings: MessageSettings): Int =
            settings.edgeCardMaxCount.coerceIn(MIN_COUNT, MAX_COUNT)
    }
}
