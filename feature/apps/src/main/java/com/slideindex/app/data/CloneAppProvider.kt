package com.slideindex.app.data

/**
 * 搜索列表的分身来源。
 *
 * `AppRepository` 在 `feature` 模块、分身枚举在 `app` 模块（要碰 LauncherApps / 特权 shell），
 * 所以由 app 侧启动时注入实现；未注入时行为与旧版一致（搜索里没有分身）。
 */
fun interface CloneAppProvider {
    /** 基于当前可搜索列表追加分身条目（`cloneUserId > 0`）。 */
    fun cloneApps(base: List<AppInfo>): List<AppInfo>
}
