package com.slideindex.app.overlay.quickmenu

import com.slideindex.app.service.ScreenshotCaptureOutcome
import com.slideindex.app.service.SlideIndexAccessibilityService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 「快捷菜单 → 识码解析 → 截屏识别」用的一次性整屏截图。
 *
 * ## 为什么不用 MediaProjection
 *
 * 早先这里走的是 `ScreenCaptureHelper`（MediaProjection），代价是**首次必须让用户过一遍系统录屏
 * 授权**，而且那条实现没有会话时只会拉起授权并返回 null —— 表现为"点了没反应"。
 *
 * 而本仓库的悬浮球截图走的是**无障碍截图**（`AccessibilityService.takeScreenshot`，配合
 * `accessibility_service_config.xml` 里的 `canTakeScreenshot="true"`），**不弹任何授权**。
 * 快捷菜单要的是"一次性截一屏"，正落在它的适用面内，因此改用它。
 *
 * 需要连续取帧的场景（悬浮球区域选择、屏幕翻译）仍然用 MediaProjection —— 无障碍截图一次一张
 * 且被系统限流，做不了连续取帧。
 */
internal object QuickMenuScreenshot {

    /**
     * 截一屏。
     *
     * 调用前必须已经收起浮层（见 [QuickMenuOverlayWindow.suspendForExternalActivity]），
     * 否则浮层本身会被截进图里。
     */
    suspend fun capture(): ScreenshotCaptureOutcome =
        suspendCancellableCoroutine { continuation ->
            val started = runCatching {
                SlideIndexAccessibilityService.captureDisplayBitmap { outcome ->
                    if (continuation.isActive) continuation.resume(outcome)
                }
            }.getOrDefault(false)

            // 没能发起（服务未连接 / 系统版本不够）时**不会**有回调，必须在这里补一个结果，
            // 否则协程会永远挂着、浮层也永远不恢复。
            if (!started && continuation.isActive) {
                continuation.resume(ScreenshotCaptureOutcome.ServiceUnavailable)
            }
        }
}
