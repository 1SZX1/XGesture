package com.slideindex.app.ui

import androidx.compose.runtime.Composable
import com.slideindex.app.freezer.FreezerBackChain
import com.slideindex.app.freezer.FreezerPanelContent
import com.slideindex.app.settings.SettingsRepository

@Composable
fun FreezerHomeScreen(
    settingsRepository: SettingsRepository,
    onBack: () -> Unit,
    onOpenManageApps: () -> Unit
) {
    FreezerPanelContent(
        settingsRepository = settingsRepository,
        onBack = onBack,
        onManageApps = onOpenManageApps,
        // 这个界面在 NavDisplay 子页里：返回键走 navigationevent 那条链，
        // 面板自己挂的返回处理必须跟着走同一条，否则会吞掉系统预测性返回（见 FreezerBackChain）。
        backChain = FreezerBackChain.NAVIGATION_EVENT,
    )
}
