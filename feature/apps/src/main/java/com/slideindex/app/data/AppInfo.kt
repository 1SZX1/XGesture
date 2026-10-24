package com.slideindex.app.data

data class AppInfo(
    val packageName: String,
    val label: String,
    val letter: Char,
    val pinyinKey: String,
    val isSystem: Boolean = false,
    /** 已被冰箱冻结（`pm disable` 语义）：普通启动会失败，需要先解冻。 */
    val isFrozen: Boolean = false,
    /** 已被挂起（`suspend` 语义）：仍启用，但启动会被系统拦。 */
    val isPaused: Boolean = false,
    /**
     * 分身实例所属 user id（例如 Flyme 平行空间的 999）；0 = 主实例。
     *
     * 分身与主实例同包名、同图标，靠这个字段区分「启动到哪个用户」。
     */
    val cloneUserId: Int = 0,
) {
    val isClone: Boolean get() = cloneUserId > 0
}

data class AppGroup(
    val letter: Char,
    val apps: List<AppInfo>,
)

sealed class AppListItem {
    data class Header(val letter: Char) : AppListItem()
    data class App(val info: AppInfo) : AppListItem()
}
