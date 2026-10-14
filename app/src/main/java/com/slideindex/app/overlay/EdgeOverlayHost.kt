package com.slideindex.app.overlay

/*
 * Portions derived from SideGesture (https://github.com/aaronzzx/gulugulu)
 * Licensed under Apache-2.0. Modified for com.slideindex.app.
 */

import android.content.Context
import android.view.MotionEvent
import com.slideindex.app.di.AppDependencies
import com.slideindex.app.diagnostic.EdgeDiag
import com.slideindex.app.gesture.GestureAnglesPreviewStore
import com.slideindex.app.monitoring.OverlayPerformanceMonitorBinding
import com.slideindex.app.settings.AppSettings
import android.util.Log
import com.slideindex.app.overlay.corner.CornerGestureHost
import com.slideindex.app.service.OverlayService
import com.slideindex.app.service.SlideIndexAccessibilityService
import com.slideindex.app.util.OverlaySnoozeController
import com.slideindex.app.util.PermissionHelper
import com.slideindex.app.util.TaskManagerUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Hosts edge gesture overlays on the accessibility service process (SideGesture-style).
 */
class EdgeOverlayHost(
    private val context: Context,
    private val scope: CoroutineScope,
    private val deps: AppDependencies
) {
    private var overlayManager: OverlayManager? = null
    private var floatBallController: FloatBallController? = null
    private var cornerGestureHost: CornerGestureHost? = null
    private var settingsJob: Job? = null
    private var appsJob: Job? = null
    private var previewActive = false
    private var previewContent: LayoutPreviewContent = LayoutPreviewContent.TRIGGER_ONLY
    private var previewFocus: LayoutPreviewFocus? = null
    private var displayRotationMonitor: OverlayDisplayRotationMonitor? = null
    private var triggerHealJob: Job? = null
    /** 宿主被显式 stop 后不再自动重建；租约兜底拆卸不属于此列。 */
    private var stopRequested = false

    fun start() {
        stopRequested = false
        startCollectorsAndLease()
        ensureStarted()
    }

    /**
     * 幂等创建窗口宿主。
     *
     * 租约兜底或"服务未连接"拆掉窗口后，下一次设置回流 / 预览入口会调到这里重建，
     * 避免出现"窗口被拆掉但没有任何路径重挂"的半死状态（表现为触钮、悬浮球、
     * 边角轮盘全部失灵，只能靠用户手动开关或重启恢复）。
     */
    private fun ensureStarted() {
        if (stopRequested || overlayManager != null) return
        if (!PermissionHelper.isAccessibilityServiceEnabled(context)) return
        OverlayPerformanceMonitorBinding.onOverlayShown(
            deps.settingsRepository.readSnapshot(),
            context
        )
        overlayManager = OverlayManager(
            context = context,
            appRepository = deps.appRepository,
            scope = scope,
            onShellCommandsPersist = { commands ->
                scope.launch { deps.settingsRepository.setShellCommands(commands) }
            },
            onQuickLauncherPanelItemsPersist = { panelId, items ->
                scope.launch { deps.settingsRepository.updateQuickLauncherPanelItems(panelId, items) }
            }
        )
        scope.launch(Dispatchers.Default) {
            deps.appRepository.loadApps()
        }
        // 启动期只做被动探测：不因为"预热"就把 Shizuku binder 抢过来。
        // 取 binder 会顺带拉起主进程，并让 :overlay 依赖主进程里的 provider ——
        // 覆盖安装后主进程启动慢被判死时，:overlay 会被系统连带杀掉（悬浮球消失）。
        if (TaskManagerUtil.peekPrivilegedAccess()) {
            TaskManagerUtil.warmUpPrivilegedBackend()
        }
        floatBallController = FloatBallController(context, scope, deps.settingsRepository)
        cornerGestureHost = CornerGestureHost(context, scope, deps).also { it.start() }
        displayRotationMonitor = OverlayDisplayRotationMonitor(context) {
            overlayManager?.relayoutTriggersForDisplayRotation()
            refreshOverlaySuppression()
        }.also { it.start() }
        OverlaySnoozeController.onStateChanged = {
            refreshOverlaySuppression()
        }
    }

    /** 一次性资源：设置/应用订阅与失联租约。窗口宿主拆掉后这些订阅仍需存活，用于触发重建。 */
    private fun startCollectorsAndLease() {
        if (triggerHealJob == null) {
            // 周期自愈：触钮"设置里开着、屏幕上没有"时自己挂回来。
            // 只发生在事件丢失/半重建之后，正常路径下是一次很便宜的判断。
            triggerHealJob = scope.launch {
                while (isActive) {
                    delay(TRIGGER_HEAL_INTERVAL_MS)
                    if (stopRequested) return@launch
                    if (!PermissionHelper.isAccessibilityServiceEnabled(context)) continue
                    ensureStarted()
                    runCatching { overlayManager?.healTriggerAttachments() }
                }
            }
        }
        if (appsJob == null) {
            appsJob = scope.launch {
                deps.appRepository.apps.collectLatest { apps ->
                    overlayManager?.syncApps(apps)
                }
            }
        }
        if (settingsJob == null) {
            settingsJob = scope.launch {
                combine(
                    deps.settingsRepository.gestureSettings,
                    deps.settingsRepository.overlaySettings
                ) { _, _ ->
                    deps.settingsRepository.readSnapshot()
                }.collectLatest { settings ->
                    if (!PermissionHelper.isAccessibilityServiceEnabled(context)) {
                        // 服务不可用：拆掉窗口，但保留订阅，恢复后由 ensureStarted() 重建。
                        teardownOverlayWindows()
                        return@collectLatest
                    }
                    ensureStarted()
                    val effectiveSettings = settings
                        .withGestureAnglesPreview()
                        .withOverlayLayoutPreview()
                    floatBallController?.apply(effectiveSettings)
                    updatePerformanceMonitor(effectiveSettings.debugPerformanceMonitorEnabled)
                    overlayManager?.applySettings(effectiveSettings)
                    if (KeyboardTriggerImeState.imeVisible) {
                        FloatBallOverlay.onKeyboardImeChanged()
                    }
                    if (previewActive) {
                        overlayManager?.setPreviewMode(true, previewContent, previewFocus)
                    }
                }
            }
        }
        // 租约兜底：无障碍服务被系统解绑/回收时，即使 stop() 没走到，
        // 也会由 OverlayHostLease 把这里的窗口摘掉，避免全屏直触窗留下吞触摸。
        OverlayHostLease.register(
            key = HOST_LEASE_KEY,
            // 同进程内直接看服务实例：比读 Settings.Secure 更准，也不会在
            // 覆盖安装/系统重绑的窗口期被一次性误判成"服务已死"而拆家。
            isOwnerAlive = { SlideIndexAccessibilityService.accessibilityInstance() != null },
            teardown = ::teardownOverlayWindows,
        )
    }

    fun stop() {
        stopRequested = true
        triggerHealJob?.cancel()
        triggerHealJob = null
        OverlaySnoozeController.onStateChanged = null
        OverlaySnoozeController.cancel()
        OverlayPerformanceMonitorBinding.onOverlayHidden(context)
        settingsJob?.cancel()
        settingsJob = null
        appsJob?.cancel()
        appsJob = null
        teardownOverlayWindows()
        OverlayCompose.clearWindowContextCache()
        // 放在最后：万一上面的拆卸半途失败，租约超时还能兜底把残留窗口再摘一次。
        OverlayHostLease.unregister(HOST_LEASE_KEY)
    }

    /**
     * 幂等：把本宿主创建的所有浮层窗口摘掉。
     *
     * 正常拆卸由 [stop] 调用；服务失联时由 [OverlayHostLease] 兜底调用，
     * 所以每一步都必须自己吞异常，不能因为某一步失败就漏掉后面的窗口。
     */
    private fun teardownOverlayWindows() {
        EdgeDiag.logStack(
            "host",
            "teardownOverlayWindows：拆掉整个浮层宿主（面板/触钮全没，且在重挂前不会再出现）"
        )
        runCatching { floatBallController?.stop() }
        floatBallController = null
        runCatching { cornerGestureHost?.stop() }
        cornerGestureHost = null
        runCatching { displayRotationMonitor?.stop() }
        displayRotationMonitor = null
        runCatching { overlayManager?.destroy() }
        overlayManager = null
        OverlayService.foregroundPackage = null
        previewActive = false
    }

    /** 输入层接管转发的触摸事件入口（system_server 模块 → app 现有手势引擎）。 */
    fun handleForwardedTouch(side: PanelSide, event: MotionEvent): Boolean =
        overlayManager?.handleForwardedTouch(side, event) ?: false

    fun cancelForwardedTouch(side: PanelSide) {
        overlayManager?.cancelForwardedTouch(side)
    }

    fun isForwardingCapable(side: PanelSide): Boolean =
        overlayManager?.isForwardingCapable(side) == true

    fun recoverTriggerInteraction(forceReAddChrome: Boolean = false) {
        overlayManager?.recoverTriggerInteraction(forceReAddChrome)
    }

    /** 硬复位取消所有侧边交互：熄屏/锁屏等硬边界与失联兜底使用。 */
    fun forceRecoverInteractionState() {
        overlayManager?.forceRecoverInteractionState()
    }

    fun onKeyboardImeChanged(visibilityChanged: Boolean = false) {
        overlayManager?.onKeyboardImeChanged(visibilityChanged)
    }

    fun onConfigurationChanged() {
        floatBallController?.onConfigurationChanged()
        RegionalPickOverlay.onConfigurationChanged()
        overlayManager?.relayoutTriggersForConfigurationChange()
        cornerGestureHost?.onConfigurationChanged()
        refreshOverlaySuppression()
    }

    fun reloadApps() {
        overlayManager?.reloadApps()
    }

    fun setPreviewMode(
        enabled: Boolean,
        content: LayoutPreviewContent = LayoutPreviewContent.TRIGGER_ONLY,
        focus: LayoutPreviewFocus? = null
    ) {
        previewActive = enabled
        previewContent = content
        previewFocus = if (enabled) focus else null
        overlayManager?.setPreviewMode(enabled, content, previewFocus)
    }

    fun setGestureAnglesPreview(angles: com.slideindex.app.gesture.GestureAngles?) {
        GestureAnglesPreviewStore.current = angles
        val settings = deps.settingsRepository.readSnapshot()
            .withGestureAnglesPreview()
            .withOverlayLayoutPreview()
        overlayManager?.applySettings(settings)
    }

    /** Immediate overlay response for QS tile toggles; does not wait for DataStore propagation. */
    fun applyServiceEnabledImmediate(enabled: Boolean) {
        if (overlayManager == null) {
            Log.w(TAG, "applyServiceEnabledImmediate: overlayManager null, enabled=$enabled")
            return
        }
        Log.i(TAG, "applyServiceEnabledImmediate: enabled=$enabled")
        val settings = deps.settingsRepository.readSnapshot()
            .copy(serviceEnabled = enabled)
            .withGestureAnglesPreview()
            .withOverlayLayoutPreview()
        floatBallController?.apply(settings)
        updatePerformanceMonitor(settings.debugPerformanceMonitorEnabled)
        overlayManager?.applySettings(settings)
        cornerGestureHost?.applySettings(settings)
    }

    fun setCornerZonePreviewActive(active: Boolean) {
        ensureStarted()
        cornerGestureHost?.setZonePreviewActive(active)
    }

    fun applyCornerZonePreviewDimensions(
        verticalEdgeWidthDp: Float,
        verticalEdgeHeightDp: Float,
        horizontalEdgeWidthDp: Float,
        horizontalEdgeHeightDp: Float
    ) {
        ensureStarted()
        cornerGestureHost?.applyZonePreviewDimensions(
            verticalEdgeWidthDp,
            verticalEdgeHeightDp,
            horizontalEdgeWidthDp,
            horizontalEdgeHeightDp
        )
    }

    fun updateForegroundPackage(packageName: String?) {
        onForegroundPackageChanged(packageName)
    }

    private fun onForegroundPackageChanged(packageName: String?) {
        if (packageName.isNullOrBlank()) return
        OverlayService.foregroundPackage = packageName
        overlayManager?.updateForegroundPackage(packageName)
        refreshOverlaySuppression()
    }

    fun refreshOverlaySuppression() {
        val settings = deps.settingsRepository.readSnapshot()
            .withGestureAnglesPreview()
            .withOverlayLayoutPreview()
        cornerGestureHost?.refreshSuppression()
        if (!FloatBallPickResultPanel.isShowing) {
            floatBallController?.apply(settings)
        }
        overlayManager?.onEnvironmentChanged()
        // 环境（前台应用/熄屏/桌面）变化是触钮最容易被收掉的时机：顺手检查有没有该挂没挂的边。
        overlayManager?.healTriggerAttachments()
    }

    fun recoverOverlaysIfIdle() {
        overlayManager?.recoverOverlaysIfIdle()
    }

    fun refreshTriggerVisibility() {
        overlayManager?.onEnvironmentChanged()
    }

    fun refreshTriggerVisuals() {
        overlayManager?.refreshTriggerVisuals()
    }

    fun bringEdgeChromeAbovePanels(forceReAdd: Boolean = true, sides: Set<PanelSide>? = null) {
        overlayManager?.bringEdgeChromeAbovePanels(forceReAdd, sides)
    }

    fun edgePresentationNeedsChromeRaise(): Boolean =
        overlayManager?.edgePresentationNeedsChromeRaise() == true

    fun notifyEdgeChromeBelowPanel() {
        overlayManager?.notifyEdgeChromeBelowPanel()
    }

    fun suspendAllEdgeOverlays() {
        overlayManager?.suspendAllEdgeOverlays()
    }

    fun resumeAllEdgeOverlays() {
        overlayManager?.resumeAllEdgeOverlays()
    }

    fun suppressCaptureVisuals() {
        overlayManager?.suppressCaptureVisuals()
    }

    fun resumeCaptureVisuals() {
        overlayManager?.resumeCaptureVisuals()
    }

    fun suspendEdgeCapturesForPassthrough() {
        overlayManager?.suspendEdgeCapturesForPassthrough()
    }

    fun resumeEdgeCapturesAfterPassthrough() {
        overlayManager?.resumeEdgeCapturesAfterPassthrough()
    }

    fun dispatchExternalGestureAction(
        action: com.slideindex.app.gesture.GestureAction,
        anchorRawY: Float,
        panelSide: com.slideindex.app.overlay.PanelSide? = null
    ): Boolean =
        overlayManager?.dispatchExternalGestureAction(action, anchorRawY, panelSide) == true

    private fun updatePerformanceMonitor(enabled: Boolean) {
        OverlayPerformanceMonitorBinding.syncUserPreference(enabled, context)
    }

    fun applyOverlayLayoutPreviewSettings() {
        if (!PermissionHelper.isAccessibilityServiceEnabled(context)) return
        ensureStarted()
        val settings = deps.settingsRepository.readSnapshot()
            .withGestureAnglesPreview()
            .withOverlayLayoutPreview()
        overlayManager?.applySettings(settings)
        overlayManager?.refreshTriggerVisuals()
    }

    fun previewIndexHeightFraction(fraction: Float) {
        OverlayLayoutPreviewStore.previewIndexHeightFraction(fraction)
        applyOverlayLayoutPreviewSettings()
    }

    fun clearIndexHeightPreview() {
        OverlayLayoutPreviewStore.clearIndexSliderPreview()
        applyOverlayLayoutPreviewSettings()
    }

    /**
     * 松手提交索引高度预览：把临时值转成"待确认"，等设置落盘回流追上再撤，
     * 避免松手瞬间先按旧值重画一次。
     */
    fun commitIndexHeightPreview() {
        OverlayLayoutPreviewStore.commitIndexHeightPreview()
        applyOverlayLayoutPreviewSettings()
    }

    fun previewIndexVerticalPositionFraction(fraction: Float) {
        OverlayLayoutPreviewStore.previewIndexVerticalPositionFraction(fraction)
        applyOverlayLayoutPreviewSettings()
    }

    fun clearIndexVerticalPositionPreview() {
        OverlayLayoutPreviewStore.clearIndexSliderPreview()
        applyOverlayLayoutPreviewSettings()
    }

    /** 松手提交索引位置预览：语义同 [commitIndexHeightPreview]。 */
    fun commitIndexVerticalPositionPreview() {
        OverlayLayoutPreviewStore.commitIndexVerticalPositionPreview()
        applyOverlayLayoutPreviewSettings()
    }

    /** 索引页滑条松手（长度/位置共用）：只提交最近拖过的那一个。 */
    fun commitIndexSliderPreviews() {
        OverlayLayoutPreviewStore.commitIndexSliderPreviews()
        applyOverlayLayoutPreviewSettings()
    }

    fun mergeTriggerHandleLayoutPreview(
        side: PanelSide,
        handleId: String,
        edgeWidthDp: Float? = null,
        topFraction: Float? = null,
        bottomFraction: Float? = null,
        shortSwipeDistanceDp: Float? = null,
        longSwipeDistanceDp: Float? = null,
        design: com.slideindex.app.gesture.TriggerHandleDesign? = null
    ) {
        OverlayLayoutPreviewStore.mergeTriggerHandlePreview(
            side = side,
            handleId = handleId,
            edgeWidthDp = edgeWidthDp,
            topFraction = topFraction,
            bottomFraction = bottomFraction,
            shortSwipeDistanceDp = shortSwipeDistanceDp,
            longSwipeDistanceDp = longSwipeDistanceDp,
            design = design
        )
        applyOverlayLayoutPreviewSettings()
    }

    fun clearTriggerHandleLayoutPreview() {
        OverlayLayoutPreviewStore.clearTriggerHandlePreview()
        applyOverlayLayoutPreviewSettings()
    }

    /** 松手提交触钮预览：同 [commitIndexHeightPreview]。 */
    fun commitTriggerHandleLayoutPreview() {
        OverlayLayoutPreviewStore.commitTriggerHandlePreview()
        applyOverlayLayoutPreviewSettings()
    }

    fun clearOverlayLayoutPreview() {
        OverlayLayoutPreviewStore.clear()
        applyOverlayLayoutPreviewSettings()
    }

    private fun AppSettings.withGestureAnglesPreview(): AppSettings {
        val preview = GestureAnglesPreviewStore.current ?: return this
        return copy(edgeTrigger = edgeTrigger.copy(gestureAngles = preview))
    }

    private companion object {
        const val TAG = "EdgeOverlayHost"
        const val HOST_LEASE_KEY = "edge-overlay-host"

        /** 触钮自愈巡检间隔：足够快（用户几乎察觉不到）又足够便宜。 */
        const val TRIGGER_HEAL_INTERVAL_MS = 12_000L
    }
}
