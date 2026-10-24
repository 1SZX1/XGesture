package com.slideindex.app.ui.picker

import android.content.Context
import com.slideindex.app.data.AppInfo
import com.slideindex.app.data.AppRepository
import com.slideindex.app.freezer.FreezerOperations
import com.slideindex.app.overlay.CloneAppsIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「挑一个应用」类页面（动作选择、圆环槽位、快捷启动器添加、展开面板……）的统一数据源。
 *
 * 以前这些页面各自拿 `loadApps()` / `getCachedApps()`，结果同一个应用在不同页面里长得不一样：
 * 冰箱和索引里有分身条目、有冻结/暂停角标，挑应用的地方没有。
 *
 * 统一成三步：
 * 1. 取可启动列表；
 * 2. `CloneAppsIndex.enrich` 补分身条目（标签带「· 分身」，状态按分身自己的 user 查）；
 * 3. `FreezerOperations.applyFreezerStates` 给冰箱成员补实时状态。
 *
 * 都在 IO 线程执行（`LauncherApps` / shell 查询不应压主线程）。
 */
object PickerApps {

    suspend fun load(
        context: Context,
        appRepository: AppRepository,
        freezerTargets: Set<String>,
        force: Boolean = false,
        includeClones: Boolean = true,
    ): List<AppInfo> = withContext(Dispatchers.IO) {
        decorate(context, appRepository.loadApps(force = force), freezerTargets, includeClones)
    }

    /** 缓存优先；缓存为空（冷启动）时退回一次真实加载。 */
    suspend fun fromCached(
        context: Context,
        appRepository: AppRepository,
        freezerTargets: Set<String>,
        includeClones: Boolean = true,
    ): List<AppInfo> = withContext(Dispatchers.IO) {
        val cached = appRepository.getCachedApps()
        if (cached.isEmpty()) {
            decorate(context, appRepository.loadApps(), freezerTargets, includeClones)
        } else {
            decorate(context, cached, freezerTargets, includeClones)
        }
    }

    /**
     * [includeClones] = false 用于「按包名」的配置页（隐藏应用、黑名单、按应用分档、图标覆盖……）：
     * 那些页面的存储键是包名，列出分身会让人以为能给分身单独设置，实际会作用到主实例；
     * 但它们仍然需要冻结/暂停角标，所以只补状态、不补分身条目。
     */
    fun decorate(
        context: Context,
        base: List<AppInfo>,
        freezerTargets: Set<String>,
        includeClones: Boolean = true,
    ): List<AppInfo> {
        if (base.isEmpty()) return base
        val enriched = if (includeClones) {
            CloneAppsIndex.enrich(base) { label, kind ->
                context.getString(CloneAppsIndex.labelRes(kind), label)
            }
        } else {
            base
        }
        return FreezerOperations.applyFreezerStates(context, enriched, freezerTargets)
            .sortedBy { it.pinyinKey }
    }
}
