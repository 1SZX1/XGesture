package com.slideindex.app.overlay.quickmenu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.slideindex.app.di.OverlayDependencyAccess
import com.slideindex.app.freezer.FreezerOverlayWindow
import com.slideindex.app.overlay.ForegroundActivityInspectorOverlayWindow
import com.slideindex.app.service.ShellCommandPanelTrampoline

/**
 * 「快捷菜单」的**预置入口**执行函数（前台活动探测、Shell 命令面板、冰箱…）。
 *
 * 这些入口的共同点：**不切页**。点击处（`QuickMenuOverlayWindow`）先调本函数，返回 true 就收起菜单，
 * 由被调起的面板/悬浮窗自己承载界面——所以它们不需要在 `QuickMenuPanel` 里渲染任何屏幕内容。
 *
 * 原则：**复用已有的正确性，不重写引擎**。前台活动探测用 `ForegroundActivityInspectorOverlayWindow`，
 * 冰箱用 `FreezerOverlayWindow`（动作列表里的 FREEZER_PANEL 就是它），Shell 命令面板走
 * `ShellCommandPanelTrampoline`（透明 Activity，避免与无障碍悬浮窗抢 z-order）。
 */

/** 面板内的复制：直接走系统剪贴板（不依赖别处的 helper，避免循环依赖）。 */
private fun copyToClipboard(context: Context, label: String, text: String) {
    runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        manager.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}

internal fun quickMenuRunEntry(context: Context, tool: QuickMenuTool): Boolean = when (tool) {
    QuickMenuTool.ENTRY_FOREGROUND_ACTIVITY -> runCatching {
        ForegroundActivityInspectorOverlayWindow.show(context)
        true
    }.getOrDefault(false)

    QuickMenuTool.ENTRY_FREEZER -> runCatching {
        // 用**冰箱悬浮窗**（动作列表里的 FREEZER_PANEL 就是它），而不是打开一个 Activity 页面
        FreezerOverlayWindow.show(context)
    }.getOrDefault(false)

    QuickMenuTool.ENTRY_SHELL_PANEL -> runCatching {
        // 面板的保存回调是**唯一**的落盘路径，所以先拿到 DI，拿不到就别假装能打开
        // （否则用户改完命令退出来什么都没存下）。
        val deps = OverlayDependencyAccess.overlayDependencies(context) ?: return@runCatching false
        ShellCommandPanelTrampoline.launch(
            context = context,
            continuousPick = false,
            onPrepare = {},
            onDismiss = {},
            // setShellCommands 是 suspend，所以走与组合无关的 QuickMenuWork（跨外部 Activity 存活）。
            onPersist = { commands -> QuickMenuWork.launch { deps.settingsRepository.setShellCommands(commands) } },
        )
        true
    }.getOrDefault(false)

    else -> false
}
