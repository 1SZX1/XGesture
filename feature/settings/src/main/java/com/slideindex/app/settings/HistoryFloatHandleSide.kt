package com.slideindex.app.settings

/**
 * 贴边把手贴在哪一侧。
 *
 * 触摸区已经收到"竖条真实宽度"（见 `HistoryFloatContent`），所以横向那点空间不再够用户
 * 去点一个"左右切换"按钮 —— 换边靠把手指示条拖过屏幕中线（见 `HistoryFloatService.moveHandle`）。
 */
enum class HistoryFloatHandleSide(val storageKey: String) {
    /** 屏幕右缘（历史行为，默认）。 */
    RIGHT("right"),

    /** 屏幕左缘。 */
    LEFT("left"),
    ;

    /** 对侧。拖动越线时用它决定目标边。 */
    val opposite: HistoryFloatHandleSide
        get() = if (this == RIGHT) LEFT else RIGHT

    companion object {
        val DEFAULT = RIGHT

        fun fromStorageKey(key: String?): HistoryFloatHandleSide =
            entries.firstOrNull { it.storageKey == key } ?: DEFAULT
    }
}
