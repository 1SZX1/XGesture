package com.slideindex.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.slideindex.app.overlay.PanelSide
import com.slideindex.app.settings.GestureProfileSnapshot
import com.slideindex.app.settings.handleDiffersFromGlobal
import com.slideindex.app.settings.handleGeometryDiffersFromGlobal
import com.slideindex.app.ui.compose.collectLaunchableAppsAsState
import com.slideindex.app.ui.compose.rememberAppRepository

/**
 * 「按应用分档」档内编辑的作用域（GitHub issue #26 v2）。
 *
 * 档内页 = 与**触钮集合完全一样的整页**：页面、行结构、编辑逻辑全部复用全局那套，
 * 只是多了「我现在正在编哪个档」这层上下文。所以这个对象只携带
 * 「我是谁 + 全局长什么样 + 本档长什么样 + 几个档管理动作」，不携带任何触钮编辑逻辑。
 *
 * `null`（不构造本对象）表示全局作用域 —— 现有调用点一行不用改。
 */
data class GestureProfileScopeUi(
    val profileId: String,
    val profileName: String,
    /** 全局那整片快照（判定「已覆盖 / 跟随全局」、以及「恢复为全局」的来源）。 */
    val globalSnapshot: GestureProfileSnapshot,
    /** 本档那整片快照。 */
    val profileSnapshot: GestureProfileSnapshot,
    /** 本档绑定的应用（已解析成可直接渲染的行数据）。 */
    val boundAppEntries: List<AppPackageEntry> = emptyList(),
    val onOpenBindApp: () -> Unit = {},
    val onUnbindApp: (String) -> Unit = {},
    /** 「恢复为全局」：整档按当前全局重新复制一份。 */
    val onRestoreFromGlobal: () -> Unit = {},
    /** 「该触钮组恢复为全局」：把全局那一组写回本档。[Boolean] = 是否横屏那套。 */
    val onRestoreGroupFromGlobal: (PanelSide, String, Boolean) -> Unit = { _, _, _ -> },
) {
    /**
     * 某个触钮组是否已被本档改过。
     *
     * 两个判定都来自数据层：动作按「侧 + 触钮组 + 竖/横屏」逐项比，
     * 几何/外观按「侧 + 竖/横屏」整体比（触钮几何在存储里就是按侧一份数组）。
     */
    fun groupOverridden(side: PanelSide, handleId: String, landscape: Boolean): Boolean =
        profileSnapshot.handleDiffersFromGlobal(globalSnapshot, side, handleId, landscape) ||
            profileSnapshot.handleGeometryDiffersFromGlobal(globalSnapshot, side, landscape)
}

/**
 * 把一组包名解析成可直接渲染的应用行（系统应用/已卸载回退按包名查仓库缓存）。
 *
 * 与摇一摇的「按应用」页同一套做法，避免档内页自己再写一份 app 解析。
 */
@Composable
fun rememberAppPackageEntries(packageNames: Collection<String>): List<AppPackageEntry> {
    val appRepository = rememberAppRepository()
    val allApps by collectLaunchableAppsAsState()
    val sorted = remember(packageNames) { packageNames.sorted() }
    val appsByPackage = remember(allApps) { allApps.associateBy { it.packageName } }
    return remember(sorted, appsByPackage) {
        sorted.map { packageName ->
            (appsByPackage[packageName] ?: appRepository.lookupApp(packageName))
                ?.let { AppPackageEntry.Installed(it) }
                ?: AppPackageEntry.Missing(packageName)
        }
    }
}
