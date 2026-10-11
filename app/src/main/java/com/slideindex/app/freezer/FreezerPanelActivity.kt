package com.slideindex.app.freezer

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slideindex.app.di.AppDependencies
import com.slideindex.app.ui.compose.LocalAppDependencies
import com.slideindex.app.ui.miuix.theme.ModuleTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class FreezerPanelActivity : ComponentActivity() {
    @Inject lateinit var deps: AppDependencies

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContent {
            val appSettings by deps.settingsRepository.settings.collectAsStateWithLifecycle(
                initialValue = deps.settingsRepository.readSnapshot()
            )
            CompositionLocalProvider(LocalAppDependencies provides deps) {
                ModuleTheme(settings = appSettings) {
                    FreezerPanelContent(
                        settingsRepository = deps.settingsRepository,
                        title = getString(com.slideindex.app.R.string.freezer_shortcut_label),
                        // 必须显式给 onBack：`SettingsLazyScreenScaffold` 只按它决定挂不挂返回处理，
                        // 不给的话返回键走系统默认直接 finish —— 多选态和展开的搜索都退不掉。
                        onBack = { finish() },
                        onManageApps = {
                            startActivity(FreezerPanelIntents.manageApps(this@FreezerPanelActivity))
                        },
                        onAppLaunched = { finish() }
                    )
                }
            }
        }
    }
}
