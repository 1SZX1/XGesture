package com.slideindex.app.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slideindex.app.data.AppInfo
import com.slideindex.app.data.AppRepository
import com.slideindex.app.di.AppDependencies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import com.slideindex.app.settings.SettingsRepository

val LocalAppDependencies = compositionLocalOf<AppDependencies> {
    error("AppDependencies not provided. Wrap content in CompositionLocalProvider(LocalAppDependencies provides deps).")
}

@Composable
fun rememberAppDependencies(): AppDependencies = LocalAppDependencies.current

@Composable
fun rememberAppRepository(): AppRepository = rememberAppDependencies().appRepository

@Composable
fun collectLaunchableAppsAsState(): State<List<AppInfo>> {
    val repository = rememberAppRepository()
    return repository.apps.collectAsState(initial = repository.getCachedApps())
}

/**
 * 挑应用页面统一的数据源（状态版）：可启动列表 + 冻结/暂停状态，按需补分身条目。
 *
 * - `includeClones = true`：目标按 `(包名, userId)` 存（动作、快捷启动器、蜂窝、小组件面板、
 *   展开面板、圆环槽位……），列出分身有意义；
 * - `includeClones = false`：页面按**包名**存（隐藏应用、黑名单、按应用分档、图标覆盖、
 *   消息提醒、全息桌面……）。仍然需要冻结/暂停角标，但不能列分身——否则用户会以为
 *   能给分身单独设置，实际却作用在主实例上。
 */
@Composable
fun collectPickerAppsAsState(includeClones: Boolean = true): State<List<AppInfo>> {
    val repository = rememberAppRepository()
    val settingsRepository = rememberSettingsRepository()
    val appContext = LocalContext.current.applicationContext
    val apps by repository.apps.collectAsState(initial = repository.getCachedApps())
    val settings by settingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = settingsRepository.readSnapshot(),
    )
    val freezerTargets = settings.freezerAppPackages
    val state = remember(apps, freezerTargets, includeClones) { mutableStateOf(apps) }
    LaunchedEffect(apps, freezerTargets, includeClones) {
        state.value = withContext(Dispatchers.IO) {
            com.slideindex.app.ui.picker.PickerApps.decorate(
                appContext,
                apps,
                freezerTargets,
                includeClones,
            )
        }
    }
    return state
}

/** 带分身的可启动列表（目标型选择器用），语义等同 [collectPickerAppsAsState]。 */
@Composable
fun collectLaunchableAppsWithClonesAsState(): State<List<AppInfo>> =
    collectPickerAppsAsState(includeClones = true)
@Composable
fun rememberSettingsRepository(): SettingsRepository = rememberAppDependencies().settingsRepository

