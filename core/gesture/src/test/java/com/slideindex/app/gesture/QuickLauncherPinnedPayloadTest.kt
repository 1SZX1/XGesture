package com.slideindex.app.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「快速启动器 · 钉住」的载荷兼容性。
 *
 * 关键约束：**不钉住时载荷必须与旧版逐字节一致**（只有 panelId），否则老用户所有已保存的
 * 手势绑定会在升级后被判定成"改过"；钉住时才追加第二段。
 */
class QuickLauncherPinnedPayloadTest {

    @Test
    fun plainActionPayloadStaysLegacy() {
        val action = GestureAction.QuickLauncher("panel-1")
        assertEquals("panel-1", action.payload)
        assertEquals(GestureAction.QuickLauncher("panel-1"), decode("panel-1"))
    }

    @Test
    fun pinnedPayloadRoundTrips() {
        val pinned = GestureAction.QuickLauncher("panel-1", pinned = true)
        assertEquals("panel-1\u00011", pinned.payload)
        assertEquals(pinned, decode(pinned.payload))

        val pinnedCurrentApp = GestureAction.QuickLauncher(
            panelId = "panel-1",
            pinned = true,
            pinCurrentApp = true,
        )
        assertEquals("panel-1\u00012", pinnedCurrentApp.payload)
        assertEquals(pinnedCurrentApp, decode(pinnedCurrentApp.payload))
    }

    @Test
    fun pinCurrentAppWithoutPinnedIsIgnored() {
        // 「只在本应用」脱离「钉住」没有意义：载荷里不体现，解析回来也必须回到不钉住。
        val action = GestureAction.QuickLauncher(
            panelId = "panel-1",
            pinned = false,
            pinCurrentApp = true,
        )
        assertEquals("panel-1", action.payload)
        val decoded = decode(action.payload)
        assertFalse(decoded.pinned)
        assertFalse(decoded.pinCurrentApp)
    }

    @Test
    fun unknownFlagsFallBackToPlain() {
        val decoded = decode("panel-1\u00019")
        assertEquals("panel-1", decoded.panelId)
        assertFalse(decoded.pinned)
    }

    @Test
    fun remappingPanelKeepsPinnedFlags() {
        // 面板被删 → 绑定改投到默认面板；钉住设置必须跟着走（否则用户会莫名失去钉住）。
        val remapped = decode("panel-a\u00012").copy(panelId = "panel-b")
        assertTrue(remapped.pinned)
        assertTrue(remapped.pinCurrentApp)
        assertEquals("panel-b\u00012", remapped.payload)
    }

    private fun decode(payload: String): GestureAction.QuickLauncher =
        GestureAction.from(GestureActionType.QUICK_LAUNCHER, payload) as GestureAction.QuickLauncher
}
