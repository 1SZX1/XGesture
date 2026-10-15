package com.slideindex.app.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮球手势浮层锚点策略：只对「快速启动器 / 圆环启动器」生效，
 * 关掉设置或拿不到按下坐标时一律回落历史行为（当前手指位置）。
 */
class FloatBallOverlayAnchorPolicyTest {
    @Test
    fun `quick launcher anchors at touch down when enabled`() {
        assertTrue(
            FloatBallOverlayAnchorPolicy.anchorsAtTouchDown(
                action = GestureAction.QuickLauncher("panel-1"),
                enabled = true,
            ),
        )
    }

    @Test
    fun `ring launcher anchors at touch down when enabled`() {
        assertTrue(
            FloatBallOverlayAnchorPolicy.anchorsAtTouchDown(
                action = GestureAction.RingLauncher,
                enabled = true,
            ),
        )
    }

    @Test
    fun `disabled setting keeps every action on the current finger position`() {
        assertFalse(
            FloatBallOverlayAnchorPolicy.anchorsAtTouchDown(
                action = GestureAction.QuickLauncher("panel-1"),
                enabled = false,
            ),
        )
        assertFalse(
            FloatBallOverlayAnchorPolicy.anchorsAtTouchDown(
                action = GestureAction.RingLauncher,
                enabled = false,
            ),
        )
        assertEquals(500f, FloatBallOverlayAnchorPolicy.resolve(false, 500f, 300f))
    }

    /** 任务切换器、索引、音量/亮度等一律保持原样：它们要么跟手，要么不在本次范围内。 */
    @Test
    fun `other actions keep the current finger position`() {
        listOf(
            GestureAction.TaskSwitcher,
            GestureAction.OpenIndex,
            GestureAction.AdjustVolume,
            GestureAction.AdjustBrightness,
            GestureAction.HoneycombLauncher,
            GestureAction.FloatingPointer,
        ).forEach { action ->
            assertFalse(
                "action=$action 不该走按下位置锚点",
                FloatBallOverlayAnchorPolicy.anchorsAtTouchDown(action = action, enabled = true),
            )
            assertEquals(700f, FloatBallOverlayAnchorPolicy.resolve(false, 700f, 300f))
        }
    }

    @Test
    fun `enabled but missing touch down coordinates falls back to current position`() {
        assertEquals(500f, FloatBallOverlayAnchorPolicy.resolve(true, 500f, null))
        assertEquals(300f, FloatBallOverlayAnchorPolicy.resolve(true, 500f, 300f))
        assertEquals(null, FloatBallOverlayAnchorPolicy.resolve(true, null, null))
    }

    /** 默认沿用触钮区间；命中按下位置策略后只保留屏幕边距，球停在触钮外也不再被夹回去。 */
    @Test
    fun `anchor range keeps trigger band by default and screen margins for touch down anchor`() {
        val defaultRange = FloatBallOverlayAnchorPolicy.anchorRange(
            anchorsAtTouchDown = false,
            viewHeightPx = 2400f,
            marginPx = 48f,
            triggerTopPx = 720f,
            triggerBottomPx = 1632f,
        )
        assertEquals(720f, defaultRange.start)
        assertEquals(1632f, defaultRange.endInclusive)
        // 球停在 0.8 屏高、往下滑：按下点 1920 在触钮区间外，默认行为被夹到 1632。
        assertEquals(1632f, 1920f.coerceIn(defaultRange))

        val touchDownRange = FloatBallOverlayAnchorPolicy.anchorRange(
            anchorsAtTouchDown = true,
            viewHeightPx = 2400f,
            marginPx = 48f,
            triggerTopPx = 720f,
            triggerBottomPx = 1632f,
        )
        assertEquals(48f, touchDownRange.start)
        assertEquals(2352f, touchDownRange.endInclusive)
        assertEquals(1920f, 1920f.coerceIn(touchDownRange))
    }

    @Test
    fun `anchor range survives degenerate view height and reversed trigger bounds`() {
        val degenerate = FloatBallOverlayAnchorPolicy.anchorRange(
            anchorsAtTouchDown = false,
            viewHeightPx = 0f,
            marginPx = 48f,
            triggerTopPx = 500f,
            triggerBottomPx = 100f,
        )
        assertEquals(100f, degenerate.start)
        assertEquals(500f, degenerate.endInclusive)

        val tiny = FloatBallOverlayAnchorPolicy.anchorRange(
            anchorsAtTouchDown = true,
            viewHeightPx = 40f,
            marginPx = 48f,
            triggerTopPx = 0f,
            triggerBottomPx = 40f,
        )
        assertEquals(48f, tiny.start)
        assertEquals(48f, tiny.endInclusive)
    }

    /**
     * 该侧一个触钮都没有时触发带是空矩形 (0,0,0,0)：照夹会把面板顶到屏幕最上沿，
     * 所以必须退回"只按屏幕边距"。
     */
    @Test
    fun `empty trigger band falls back to screen margins`() {
        val empty = FloatBallOverlayAnchorPolicy.anchorRange(
            anchorsAtTouchDown = false,
            viewHeightPx = 2400f,
            marginPx = 48f,
            triggerTopPx = 0f,
            triggerBottomPx = 0f,
        )
        assertEquals(48f, empty.start)
        assertEquals(2352f, empty.endInclusive)
        assertEquals(1200f, 1200f.coerceIn(empty))

        val zeroHeightBand = FloatBallOverlayAnchorPolicy.anchorRange(
            anchorsAtTouchDown = false,
            viewHeightPx = 2400f,
            marginPx = 48f,
            triggerTopPx = 1200f,
            triggerBottomPx = 1200f,
        )
        assertEquals(48f, zeroHeightBand.start)
        assertEquals(2352f, zeroHeightBand.endInclusive)
    }
}
