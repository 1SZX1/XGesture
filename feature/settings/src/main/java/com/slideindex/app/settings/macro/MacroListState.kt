package com.slideindex.app.settings.macro

import com.slideindex.app.macro.Macro

/**
 * 宏列表页的状态：全量列表 + 搜索关键字 → 可见列表。
 *
 * `MacroStore` 不是可观察流，所以列表数据由界面层自己持有：每次进入页面、
 * 以及增 / 删 / 改之后重新读一遍 `MacroStore.all(context)`，再交给这里过滤。
 * 过滤规则只有一条：**按宏名做忽略大小写的包含匹配**（空关键字 = 全都要）。
 */
class MacroListState(
    /** 当前全量宏（顺序即展示顺序）。 */
    val macros: List<Macro> = emptyList(),
    /** 搜索框内容。 */
    val query: String = "",
) {

    /** 命中搜索关键字的宏。 */
    val visible: List<Macro> = filter(macros, query)

    /**
     * 空态判定：看**过滤结果**。一条宏都没有 → 提示"还没有宏"；
     * 有宏但没搜到 → 由界面层显示"没有匹配的宏"。
     */
    val isEmpty: Boolean get() = visible.isEmpty()

    /** 全量列表本身就是空的（决定空态该说"还没有宏"还是"没搜到"）。 */
    val sourceIsEmpty: Boolean get() = macros.isEmpty()

    fun withMacros(next: List<Macro>): MacroListState = MacroListState(next, query)

    fun withQuery(next: String): MacroListState = MacroListState(macros, next)

    companion object {

        /** 纯过滤函数：按名字忽略大小写包含匹配，保持原顺序。 */
        fun filter(macros: List<Macro>, query: String): List<Macro> {
            val keyword = query.trim()
            if (keyword.isEmpty()) return macros
            return macros.filter { it.name.contains(keyword, ignoreCase = true) }
        }
    }
}

