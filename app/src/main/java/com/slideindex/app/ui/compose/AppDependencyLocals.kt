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
 * 带分身的可启动应用列表（选择器用）。
 *
 * 分身枚举要走 LauncherApps / 特权 shell，所以放到 IO 线程异步补；
 * 首帧先给主列表，避免选择器打开被枚举阻塞。
 */
@Composable
fun collectLaunchableAppsWithClonesAsState(): State<List<AppInfo>> {
    val repository = rememberAppRepository()
    val apps by repository.apps.collectAsState(initial = repository.getCachedApps())
    val state = remember(apps) { mutableStateOf(apps) }
    LaunchedEffect(apps) {
        state.value = withContext(Dispatchers.IO) {
            com.slideindex.app.overlay.CloneAppsIndex.enrich(apps)
        }
    }
    return state
}

@Composable
fun rememberSettingsRepository(): SettingsRepository = rememberAppDependencies().settingsRepository

