package com.slideindex.app.ui.viewmodel

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slideindex.app.data.AppInfo
import com.slideindex.app.data.AppRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 「扩展 → 图标库」的**应用列表**与图标加载。
 *
 * 背景（真机实测 2026-10-23）：这个页面原来在组合期同步跑
 * `queryIntentActivities` + 每包 `loadLabel` + `loadIcon`（本机 192 个启动器应用），
 * 首次进入时主线程出现 1.3–1.6 秒的帧、3 秒级冻结，日志里 3 秒内 156 条
 * `HWUI: Image decoding logging dropped`，紧随其后一次 30MB / 135 个大对象的 GC；
 * 而且那段代码写在 `if (tab == 0)` 之前，停在「动作」tab 也照跑。
 *
 * 现在的约定：
 * - 列表来自 [AppRepository] 的进程级缓存（扩展页打开时已加载过，通常直接命中），
 *   只有切到「应用」tab 才补加载；
 * - 图标按行 **peek 命中即用 / 未命中提交后台补齐**，主线程零解码、零 PackageManager 调用。
 */
@HiltViewModel
class GlobalIconLibraryViewModel @Inject constructor(
    private val appRepository: AppRepository,
) : ViewModel() {

    private var loadingJob: Job? = null

    /** 已按拼音排序的启动器应用（与桌面/应用列表顺序一致）。 */
    val apps: StateFlow<List<AppInfo>> = appRepository.apps
        .map { list -> if (list.isEmpty()) emptyList() else list.sortedBy { it.pinyinKey } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = appRepository.getCachedApps().sortedBy { it.pinyinKey },
        )

    /** 行内图标：按包名缓存，Compose 只重组读到该 key 的那一行。 */
    val icons = mutableStateMapOf<String, ImageBitmap>()

    init {
        // 装/卸/更新应用后缓存会失效，行内图标也要跟着重新取。
        viewModelScope.launch {
            appRepository.appsRevision.collect { icons.clear() }
        }
    }

    /**
     * 只在切到「应用」tab 时调用。
     *
     * 已有缓存时（扩展页打开时 [AppRepository] 已经加载过）连协程都不起，直接返回。
     */
    fun ensureAppsLoaded() {
        if (appRepository.hasCachedApps()) return
        if (loadingJob?.isActive == true) return
        loadingJob = viewModelScope.launch {
            runCatching { appRepository.loadApps() }
        }
    }

    /**
     * 取一行的图标：命中即用，未命中提交一次后台加载（同一包名同尺寸的重复请求会被合并）。
     *
     * 主线程绝不能同步调 `PackageManager` —— Flyme/MIUI 的主题图标重建会卡到 ANR。
     */
    fun requestIcon(packageName: String) {
        if (icons.containsKey(packageName)) return
        val cached = appRepository.peekLaunchIconBitmap(packageName, ICON_PX)
        if (cached != null) {
            icons[packageName] = cached.asImageBitmap()
            return
        }
        appRepository.requestLaunchIconBitmapAsync(packageName, ICON_PX) {
            val ready = appRepository.peekLaunchIconBitmap(packageName, ICON_PX)
                ?: return@requestLaunchIconBitmapAsync
            icons[packageName] = ready.asImageBitmap()
        }
    }

    companion object {
        /** 行内图标边长（40dp 容器在最高密度下也不超过这个值）。 */
        const val ICON_PX = 128
    }
}
