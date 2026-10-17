package com.slideindex.app.ui.navigation

import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slideindex.app.ui.FvCustomGestureScreen
import com.slideindex.app.ui.viewmodel.ExtensionSettingsViewModel
import top.yukonga.miuix.kmp.nav.core.NavEntryBuilder

/**
 * FooView「自定义手势」移植版的导航入口。
 *
 * 只注册**列表页**一个目的地：录入弹窗是 View 版 `Dialog`，动作选择页及其子选择页都在列表页内部
 * 按阶段切换（草稿里有尚未落盘的手绘轨迹，塞不进 NavKey；原因写在 `ui/FvCustomGestureScreen.kt`）。
 *
 * 入口在「悬浮球 → 手势」页（FV 里那一行叫 `v_setting_custom_gesture`，紧跟在 8 个方向手势之后）。
 */
fun NavEntryBuilder.fvCustomGestureNavEntries(ctx: MainNavContext) {
    hiltEntry<AppNavKey.FvCustomGesture> {
        val viewModel: ExtensionSettingsViewModel = hiltViewModel()
        val appSettings by viewModel.settings.collectAsStateWithLifecycle()
        FvCustomGestureScreen(
            // 动作选择里的「快捷方式 / shell / 模拟按键」子页要读这些设置切片
            appSettings = appSettings,
            onBack = { ctx.navigateBackTo(AppNavKey.FloatBallGesture) },
        )
    }
}
