package com.slideindex.app.settings

/**
 * 收纳 / 剪贴板侧拉面板的宽度档位。
 *
 * [STANDARD] 的 78% 是复刻 demo 面板设计稿时定下的规格（卡片操作行、圆角、缩略图都按它调过），
 * 但在窄屏手机上它占掉将近四分之三的屏幕，把底下的应用盖得只剩一条 —— 用户反馈"以前是 50%，
 * 现在太宽"就是这一档。
 *
 * [COMPACT] 给到 62% 而不是直接回到 50%：50% 只有约 205dp，4 列图标格子会被挤坏；
 * 62% 在 411dp 屏上约 255dp，明显收窄又还放得下卡片操作行。
 */
enum class StashPanelWidthMode(val fraction: Float) {
    /** 设计稿规格：占可用宽度的 78%。 */
    STANDARD(0.78f),

    /** 紧凑：占可用宽度的 62%。 */
    COMPACT(0.62f),
}
