package com.slideindex.app.overlay

import android.graphics.RectF
import android.view.MotionEvent
import com.slideindex.app.diagnostic.EdgeDiag

internal class QuickLauncherTouchHandler(
    internal val ctrl: QuickLauncherOverlayController
) {
    internal val host get() = ctrl.host
    internal val pickResolver = QuickLauncherPickResolver(this)
    internal val scrollHandler = QuickLauncherScrollHandler(this)
    internal val managementHandler = QuickLauncherManagementTouchHandler(this)
    private var lastToolbarSwitchHoverTime: Long = 0L

    fun handleTouch(event: MotionEvent, localX: Float, localY: Float): Boolean {
        if (ctrl.quickLauncherExiting) return true
        val panelRect = ctrl.quickLauncherPanelRect()
        val contentRect = ctrl.quickLauncherPanelController.combinedContentRect(panelRect)
        val touchX = host.panelEnterAdjustedX(localX, contentRect)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            ctrl.quickLauncherPageSwipeStartX = touchX
            ctrl.quickLauncherPageSwipeStartY = localY
            val toolbarDown = ctrl.quickLauncherPanelController.toolbarContains(touchX, localY)
            ctrl.quickLauncherToolbarTouchActive = toolbarDown
            managementHandler.beginGesture(toolbarDown)
        }
        val toolbarTouchThisGesture = when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val armed = ctrl.quickLauncherToolbarTouchActive
                ctrl.quickLauncherToolbarTouchActive = false
                armed
            }
            else -> ctrl.quickLauncherToolbarTouchActive
        }
        // 「长按空白进编辑」已经生效时，这次松手不能再当成"点空白 → 关闭面板"。
        // 判定放在后面（子面板/翻页快照释放等各自的短路径之后），否则会连它们的收尾一起吃掉。
        val blankLongPressConsumed = event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        val continuousPick = host.gestureSession().quickLauncherContinuousPickActive()
        EdgeDiag.log(
            "qlTouch",
            "面板收到触摸 action=${MotionEvent.actionToString(event.actionMasked)} " +
                "continuousPick=$continuousPick 高亮=${host.panelGridSession().highlightedIndex} " +
                "local=($localX,$localY) adjustedX=$touchX"
        )
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            if (ctrl.folderOpen && ctrl.folderHandler.consumePageRelease()) {
                return true
            }
            if (!continuousPick && scrollHandler.consumePageRelease()) {
                return true
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            scrollHandler.cancelPageSnapAnimation()
            ctrl.quickLauncherPageChangedThisGesture = false
            ctrl.quickLauncherPageDragOffset = 0f
            ctrl.quickLauncherPageSwipeLocked = false
            ctrl.quickLauncherEdgePageZone = 0
            ctrl.quickLauncherEdgeAutoPageSeeded = false
            if (!host.gestureSession().isMoveTimeActionLocked()) {
                ctrl.quickLauncherOpeningGestureActive = false
            }
            ctrl.quickLauncherPageSwipeStartX = touchX
            ctrl.quickLauncherPageSwipeStartY = localY
            ctrl.quickLauncherPageSwipeTracking = ctrl.quickLauncherPageCount > 1 &&
                host.panelContentRect().contains(touchX, localY)
        }
        val tapGesture = (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL) &&
            managementHandler.isTapGesture(touchX, localY)
        val toolbarCommitAllowed = managementHandler.toolbarCommitAllowed()
        if (ctrl.folderOpen || ctrl.folderGestureActive) {
            if (ctrl.folderHandler.handleTouch(event, touchX, localX, localY)) {
                return true
            }
        }
        if (!continuousPick && managementHandler.handleManagementTouch(
                event,
                touchX,
                localY,
                panelRect,
                tapGesture = tapGesture,
                toolbarCommitAllowed = toolbarCommitAllowed
            )
        ) {
            return true
        }
        if (ctrl.quickLauncherPanelController.editMode && !continuousPick) {
            if (ctrl.quickLauncherPanelController.isDragging()) {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    host.invalidate()
                }
                return true
            }
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (continuousPick) {
                    if (pickResolver.continuousPickReady() &&
                        pickResolver.isSelectableTouch(localX, localY, panelRect)
                    ) {
                        pickResolver.updateHighlight(
                            localX,
                            localY,
                            touchX,
                            event.eventTime,
                            haptic = true
                        )
                    }
                    host.invalidate()
                    return true
                }
                if (ctrl.quickLauncherPanelController.editMode) {
                    host.invalidate()
                    return true
                }
                if (!pickResolver.isSelectableTouch(localX, localY, panelRect)) {
                    endQuickLauncherSessionAnimated()
                    host.invalidate()
                    return true
                }
                host.panelGridSession().updateHighlight(touchX, localY)
                pickResolver.syncPressTracking(event.eventTime)
                if (host.panelGridSession().highlightedIndex >= 0) {
                    host.hapticTick()
                } else {
                    // 空白处（没有任何格子被高亮）：起长按计时。到点进编辑态，
                    // 短按松手仍是原来的"关闭面板"（见 ACTION_UP 分支）。
                    ctrl.scheduleBlankLongPress(event.eventTime)
                }
                host.invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (continuousPick && pickResolver.continuousPickReady()) {
                    ctrl.quickLauncherOpeningGestureActive = false
                }
                // 只有移动超过 10dp 才算"不是按住空白"，避免长按时手指的细微抖动把计时取消掉。
                if (ctrl.quickLauncherBlankPressDownTime > 0L) {
                    val dx = localX - ctrl.quickLauncherPageSwipeStartX
                    val dy = localY - ctrl.quickLauncherPageSwipeStartY
                    val slop = host.dp(10f)
                    if (dx * dx + dy * dy > slop * slop) {
                        ctrl.cancelBlankLongPress()
                    }
                }
                if (host.gestureSession().isMoveTimeActionLocked()) {
                    ctrl.quickLauncherPageSwipeStartX = touchX
                    ctrl.quickLauncherPageSwipeStartY = localY
                    ctrl.quickLauncherPageSwipeLocked = false
                }
                if (scrollHandler.consumePageSwipeMove(touchX, localY)) {
                    host.invalidate()
                    return true
                }
                if (host.gestureSession().isMoveTimeActionLocked() && !continuousPick) {
                    if (pickResolver.updateEdgeTracking(event.rawY, localX, localY)) return true
                    return true
                }
                if (ctrl.quickLauncherOpeningGestureActive &&
                    host.gestureSession().isMoveTimeActionLocked() &&
                    !continuousPick
                ) {
                    host.invalidate()
                    return true
                }
                if (pickResolver.updateEdgeTracking(event.rawY, localX, localY)) return true
                if (continuousPick) {
                    if (!pickResolver.continuousPickReady()) {
                        host.invalidate()
                        return true
                    }
                    if (ctrl.quickLauncherPanelController.toolbarContains(touchX, localY)) {
                        pickResolver.clearHighlight()
                        val action = ctrl.quickLauncherPanelController.resolveToolbarAction(touchX, localY, panelRect)
                        if (action == QuickLauncherPanelToolbar.ToolbarAction.SWITCH) {
                            val now = event.eventTime
                            if (now - lastToolbarSwitchHoverTime > 500L) {
                                lastToolbarSwitchHoverTime = now
                                ctrl.switchToNextPanel()
                                host.invalidate()
                                return true
                            }
                        }
                        host.invalidate()
                        return true
                    }
                    if (scrollHandler.pageInteractionActive()) {
                        host.invalidate()
                        return true
                    }
                    if (!pickResolver.isSelectableTouch(localX, localY, panelRect)) {
                        pickResolver.clearHighlight()
                        host.invalidate()
                        return true
                    }
                    scrollHandler.applyEdgeAutoPage(touchX)
                    pickResolver.updateHighlight(localX, localY, touchX, event.eventTime, haptic = true)
                    host.invalidate()
                    return true
                }
                val prev = host.panelGridSession().highlightedIndex
                if (pickResolver.isSelectableTouch(localX, localY, panelRect)) {
                    host.panelGridSession().updateHighlight(touchX, localY)
                    if (host.panelGridSession().highlightedIndex != prev) {
                        pickResolver.syncPressTracking(event.eventTime)
                    }
                    if (host.panelGridSession().highlightedIndex != prev &&
                        host.panelGridSession().highlightedIndex >= 0
                    ) {
                        host.hapticTick()
                    }
                } else {
                    pickResolver.clearHighlight()
                }
                host.invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 长按空白进编辑已经消费掉这次手势 → 只收尾，不关闭面板。
                if (blankLongPressConsumed && ctrl.consumeBlankLongPress()) {
                    ctrl.quickLauncherOpeningGestureActive = false
                    ctrl.quickLauncherPageSwipeTracking = false
                    ctrl.quickLauncherPageSwipeLocked = false
                    ctrl.quickLauncherPageChangedThisGesture = false
                    host.invalidate()
                    return true
                }
                if (host.gestureSession().releaseImmediateGestureLock()) {
                    ctrl.quickLauncherOpeningGestureActive = false
                    host.onInitiatingEdgeGestureReleased()
                    host.setPanelPresentationFocus(true)
                    host.invalidate()
                    return true
                }
                ctrl.quickLauncherOpeningGestureActive = false
                if (continuousPick) {
                    if (ctrl.quickLauncherPageSwipeLocked) {
                        scrollHandler.finishPageDrag()
                        ctrl.quickLauncherPageSwipeLocked = false
                        ctrl.quickLauncherPageSwipeTracking = false
                        ctrl.quickLauncherPageChangedThisGesture = false
                        host.invalidate()
                        return true
                    }
                    if (ctrl.quickLauncherPageSnapMotion.isRunning) {
                        host.invalidate()
                        return true
                    }
                    if (managementHandler.tryCommitToolbarOnContinuousPickUp(touchX, localY, panelRect)) {
                        host.gestureSession().clearQuickLauncherContinuousPick()
                        host.gestureSession().finishLeaveOpenFingerTracking()
                        host.notifyPresentationTouchRequirementChanged()
                        host.onInitiatingEdgeGestureReleased()
                        host.setPanelPresentationFocus(true)
                        ctrl.quickLauncherPageSwipeLocked = false
                        ctrl.quickLauncherPageChangedThisGesture = false
                        host.invalidate()
                        return true
                    }
                    if (pickResolver.continuousPickReady() &&
                        pickResolver.isSelectableTouch(localX, localY, panelRect)
                    ) {
                        pickResolver.updateHighlight(localX, localY, touchX, event.eventTime, haptic = false)
                        val panelModeBeforeAction = host.gestureSession().panelMode()
                        if (host.panelGridSession().highlightedIndex >= 0 &&
                            pickResolver.performUpAction(event, touchX, localX, localY)
                        ) {
                            endQuickLauncherAfterLaunch(ctrl.quickLauncherLaunchEndDeferMs)
                            ctrl.quickLauncherLaunchEndDeferMs = 0L
                        } else if (!ctrl.quickLauncherPanelController.editMode &&
                            !ctrl.quickLauncherOverlayDialogHost.isShowing &&
                            host.panelGridSession().highlightedIndex < 0 &&
                            !toolbarTouchThisGesture &&
                            host.gestureSession().panelMode() == panelModeBeforeAction
                        ) {
                            endQuickLauncherSessionAnimated()
                        }
                    } else if (!ctrl.quickLauncherPanelController.editMode &&
                        !ctrl.quickLauncherOverlayDialogHost.isShowing &&
                        !toolbarTouchThisGesture &&
                        ctrl.quickLauncherPageSnapMotion.isRunning != true
                    ) {
                        endQuickLauncherSessionAnimated()
                    }
                    ctrl.quickLauncherPageSwipeLocked = false
                    ctrl.quickLauncherPageChangedThisGesture = false
                    host.invalidate()
                    return true
                }
                if (ctrl.quickLauncherPageSwipeLocked) {
                    scrollHandler.finishPageDrag()
                    ctrl.quickLauncherPageSwipeLocked = false
                    ctrl.quickLauncherPageSwipeTracking = false
                    ctrl.quickLauncherPageChangedThisGesture = false
                    host.invalidate()
                    return true
                }
                if (ctrl.quickLauncherPageSnapMotion.isRunning) {
                    host.invalidate()
                    return true
                }
                if (pickResolver.isSelectableTouch(localX, localY, panelRect)) {
                    host.panelGridSession().updateHighlight(touchX, localY)
                } else {
                    pickResolver.clearHighlight()
                }
                val panelModeBeforeAction = host.gestureSession().panelMode()
                if (!ctrl.quickLauncherPageChangedThisGesture &&
                    pickResolver.performUpAction(event, touchX, localX, localY)
                ) {
                    endQuickLauncherAfterLaunch(ctrl.quickLauncherLaunchEndDeferMs)
                    ctrl.quickLauncherLaunchEndDeferMs = 0L
                } else if (!ctrl.quickLauncherPanelController.editMode &&
                    !ctrl.quickLauncherOverlayDialogHost.isShowing &&
                    host.panelGridSession().highlightedIndex < 0 &&
                    !ctrl.quickLauncherPageChangedThisGesture &&
                    !toolbarTouchThisGesture &&
                    host.gestureSession().panelMode() == panelModeBeforeAction
                ) {
                    endQuickLauncherSessionAnimated()
                }
                ctrl.quickLauncherPageSwipeTracking = false
                ctrl.quickLauncherPageSwipeLocked = false
                ctrl.quickLauncherPageChangedThisGesture = false
                return true
            }
        }
        return false
    }

    fun applyEditDragAutoPage(touchX: Float, panelRect: RectF): Boolean =
        scrollHandler.applyEditDragAutoPage(touchX, panelRect)

    private fun endQuickLauncherAfterLaunch(deferMs: Long) {
        if (deferMs > 0L) {
            host.postDelayed({ host.gestureSession().endSession() }, deferMs)
        } else {
            host.post { host.gestureSession().endSession() }
        }
    }

    private fun endQuickLauncherSessionAnimated() {
        EdgeDiag.logStack("qlTouch", "endQuickLauncherSessionAnimated：关闭快速启动器面板")
        if (ctrl.quickLauncherExiting) return
        if (host.gestureSession().panelMode() != OverlayPanelMode.QUICK_LAUNCHER) {
            host.gestureSession().endSession()
            return
        }
        ctrl.quickLauncherExiting = true
        host.notifyPresentationTouchRequirementChanged()
        host.startPanelExitAnimation {
            ctrl.quickLauncherExiting = false
            host.gestureSession().endSession()
        }
    }
}
