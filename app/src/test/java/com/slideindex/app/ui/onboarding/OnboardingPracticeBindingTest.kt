package com.slideindex.app.ui.onboarding

import com.slideindex.app.gesture.GestureAction
import com.slideindex.app.gesture.GestureTriggerType
import com.slideindex.app.gesture.SideGestureDefaults
import com.slideindex.app.overlay.PanelSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 引导的"绑定悬浮指针"这一步只允许**建议**配置，三条约束：
 * 1. 已经绑好的直接复用；
 * 2. 只覆盖空位或**仍是出厂默认值**的位，用户自己改过的一律不碰；
 * 3. 顶掉出厂动作时必须把它搬到空位（斜下滑原本是任务切换器，不能白丢）。
 *
 * 这里刻意用真实的 [SideGestureDefaults] 当"出厂默认"：出厂表以后改了，这些断言会跟着报警。
 */
class OnboardingPracticeBindingTest {

    private val factoryActions: Map<GestureTriggerType, GestureAction> =
        SideGestureDefaults.rulesFor(PanelSide.RIGHT).associate { it.trigger to it.action }

    private fun planWith(
        vararg overrides: Pair<GestureTriggerType, GestureAction>,
    ): FloatingPointerBindPlan {
        val actions = factoryActions + overrides.toMap()
        return planFloatingPointerBinding(
            actionOf = { actions[it] ?: GestureAction.None },
            factoryActionOf = { factoryActions[it] ?: GestureAction.None },
        )
    }

    @Test
    fun untouchedDefaultsRebindTheLongDiagonalAndMoveItsActionToTheShortOne() {
        val plan = planWith()

        assertEquals(FloatingPointerBindOutcome.BIND_NEW, plan.outcome)
        assertEquals(GestureTriggerType.LONG_SWIPE_DOWN_RIGHT, plan.trigger)
        // 出厂这根是任务切换器，覆盖它必须把它搬到空着的短斜下滑
        assertEquals(GestureAction.TaskSwitcher, plan.replacesAction)
        assertEquals(GestureTriggerType.SHORT_SWIPE_DOWN_RIGHT, plan.relocateTo)
    }

    @Test
    fun existingPointerBindingIsReusedWithoutTouchingAnything() {
        val plan = planWith(GestureTriggerType.LONG_SWIPE_DOWN_RIGHT to GestureAction.FloatingPointer)

        assertEquals(FloatingPointerBindOutcome.ALREADY_BOUND, plan.outcome)
        assertEquals(GestureTriggerType.LONG_SWIPE_DOWN_RIGHT, plan.trigger)
        assertNull(plan.replacesAction)
    }

    @Test
    fun userCustomisedPreferredSlotFallsBackToTheNextFactoryDefaultSlot() {
        // 用户把长斜下滑改成了别的东西 → 不能动它，退到长距离内滑（出厂是返回）
        val plan = planWith(GestureTriggerType.LONG_SWIPE_DOWN_RIGHT to GestureAction.Recents)

        assertEquals(FloatingPointerBindOutcome.BIND_NEW, plan.outcome)
        assertEquals(GestureTriggerType.LONG_SWIPE_IN, plan.trigger)
        assertEquals(GestureAction.Back, plan.replacesAction)
        assertEquals(GestureTriggerType.SHORT_SWIPE_DOWN_RIGHT, plan.relocateTo)
    }

    @Test
    fun fullyCustomisedCandidatesMeanNoBindingAtAll() {
        val plan = planWith(
            GestureTriggerType.LONG_SWIPE_DOWN_RIGHT to GestureAction.Recents,
            GestureTriggerType.LONG_SWIPE_IN to GestureAction.Home,
            GestureTriggerType.SHORT_SWIPE_UP to GestureAction.OpenQuickSettings,
            GestureTriggerType.SHORT_SWIPE_DOWN to GestureAction.VoiceSearch,
        )

        assertEquals(FloatingPointerBindOutcome.NO_SLOT, plan.outcome)
        assertNull(plan.trigger)
    }

    @Test
    fun displacedActionHasNowhereToGoWhenEveryRelocationSlotIsTaken() {
        val plan = planWith(
            GestureTriggerType.SHORT_SWIPE_DOWN_RIGHT to GestureAction.Home,
            GestureTriggerType.SHORT_SWIPE_UP_RIGHT to GestureAction.Recents,
            GestureTriggerType.SHORT_SWIPE_IN_HOVER to GestureAction.Back,
        )

        assertEquals(FloatingPointerBindOutcome.BIND_NEW, plan.outcome)
        assertEquals(GestureAction.TaskSwitcher, plan.replacesAction)
        assertNull(plan.relocateTo)
    }
}
