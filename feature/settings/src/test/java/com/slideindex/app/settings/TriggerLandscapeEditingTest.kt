package com.slideindex.app.settings

import com.slideindex.app.gesture.TriggerHandle
import com.slideindex.app.overlay.PanelSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 横屏触钮编辑态的"空列表 ≠ 未配置"回归。
 *
 * 曾经的 bug：`forLandscapeHandleEditing()` 无条件 `stored.ifEmpty { 竖屏 }`，于是用户在横屏里把某侧
 * 触钮**删光**之后，下一次任何读改写又会把竖屏那两个回填进去并落盘 —— 表现是"横屏触钮删不干净，
 * 删掉的两个过一会又回来"。修法：只有尚未初始化时才回填。
 *
 * ⚠️ Robolectric：`AppSettings()` 会在 `FreeWindowSettings` 里读 `Build.MANUFACTURER`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class TriggerLandscapeEditingTest {

    private fun portraitHandles(count: Int): List<TriggerHandle> =
        List(count) { TriggerHandle(id = "p$it", topFraction = 0.1f * it, heightFraction = 0.2f) }

    @Test
    fun initializedLandscape_keepsDeliberatelyEmptySide() {
        val portrait = portraitHandles(2)
        val settings = AppSettings().copy(
            edgeTrigger = AppSettings().edgeTrigger.copy(
                leftTriggerHandles = portrait,
                landscapeTriggersInitialized = true,
                leftTriggerHandlesLandscape = emptyList(),
            ),
        )

        val editing = settings.forLandscapeHandleEditing()

        // 用户要的就是"横屏左缘没有触钮"：编辑态不能再把竖屏那两个搬回来。
        assertTrue(
            "已初始化的横屏空列表必须保持为空（否则删除会被回填）",
            editing.leftTriggerHandles.isEmpty(),
        )
    }

    @Test
    fun uninitializedLandscape_stillFallsBackToPortrait() {
        val portrait = portraitHandles(2)
        val settings = AppSettings().copy(
            edgeTrigger = AppSettings().edgeTrigger.copy(
                leftTriggerHandles = portrait,
                landscapeTriggersInitialized = false,
                leftTriggerHandlesLandscape = emptyList(),
            ),
        )

        val editing = settings.forLandscapeHandleEditing()

        // 老数据（横屏还没初始化）仍要能看到竖屏那套，否则编辑页会是空的。
        assertEquals(portrait, editing.leftTriggerHandles)
    }

    @Test
    fun initializedLandscape_prefersStoredHandlesOverPortrait() {
        val landscape = listOf(TriggerHandle(id = "l0", topFraction = 0.4f, heightFraction = 0.2f))
        val settings = AppSettings().copy(
            edgeTrigger = AppSettings().edgeTrigger.copy(
                leftTriggerHandles = portraitHandles(3),
                landscapeTriggersInitialized = true,
                leftTriggerHandlesLandscape = landscape,
            ),
        )

        assertEquals(landscape, settings.forLandscapeHandleEditing().leftTriggerHandles)
    }
}
