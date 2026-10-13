package com.slideindex.app.settings.macro

import com.slideindex.app.macro.Macro

/**
 * 宏界面的**纯展示逻辑**（不依赖 Android，可在 JVM 单测里直接跑）。
 *
 * 这里刻意不碰任何资源 / Context：摘要的最终文案由界面层套字符串资源
 * （`macro_row_summary`），这样"摘要算几个动作 / 名字怎么兜底"
 * 这两件事能独立测，也不会把 `R` 引进本模块。
 */
object MacroPresentation {

    /** 摘要里的动作数：只数会真正产生手势的步（等待步不算）。 */
    fun actionCount(macro: Macro): Int = macro.actionCount

    /** 名字为空时的兜底显示名。 */
    fun displayName(name: String): String =
        name.trim().ifBlank { Macro.FALLBACK_NAME }
}
