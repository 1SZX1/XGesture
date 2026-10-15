package com.slideindex.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 合成触发带：**该侧一个触钮都没有**时，用它替代空矩形。
 *
 * 关键契约是"贴触发侧那条边"与"零高 = 锚点恒等于触发高度"——
 * 面板布局引擎拿它当贴边基准 + 锚点夹取区间，空矩形会把面板顶到屏幕最上沿。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PanelTriggerBandTest {

    private val width = 1080
    private val height = 1920
    private val thickness = 24f

    @Test
    fun `left band hugs left edge and locks anchor to trigger row`() {
        val band = PanelTriggerBand.synthetic(PanelSide.LEFT, 600f, width, height, thickness)

        assertEquals(0f, band.left, 0.01f)
        assertEquals(thickness, band.right, 0.01f)
        assertEquals(600f, band.top, 0.01f)
        assertEquals(600f, band.bottom, 0.01f)
    }

    @Test
    fun `right band hugs right edge and locks anchor to trigger row`() {
        val band = PanelTriggerBand.synthetic(PanelSide.RIGHT, 600f, width, height, thickness)

        assertEquals(width - thickness, band.left, 0.01f)
        assertEquals(width.toFloat(), band.right, 0.01f)
        assertEquals(600f, band.top, 0.01f)
        assertEquals(600f, band.bottom, 0.01f)
    }

    @Test
    fun `anchor outside the view is clamped inside`() {
        assertEquals(
            0f,
            PanelTriggerBand.synthetic(PanelSide.LEFT, -120f, width, height, thickness).top,
            0.01f,
        )
        assertEquals(
            height.toFloat(),
            PanelTriggerBand.synthetic(PanelSide.LEFT, 5000f, width, height, thickness).top,
            0.01f,
        )
    }

    @Test
    fun `oversized thickness never inverts the band`() {
        val left = PanelTriggerBand.synthetic(PanelSide.LEFT, 600f, width, height, 4000f)
        assertEquals(width.toFloat(), left.right, 0.01f)

        val right = PanelTriggerBand.synthetic(PanelSide.RIGHT, 600f, width, height, 4000f)
        assertEquals(0f, right.left, 0.01f)
        assertEquals(width.toFloat(), right.right, 0.01f)
    }

    /**
     * 顶/底不会成为轮盘或悬浮球的触发侧（它们只有左右），这里只锁兜底形状。
     *
     * 注意左右带的"零高"与顶底带的"零宽"是**刻意的**：`RectF.isEmpty` 为 true 不代表退化，
     * 引擎只读边线与锚点，不读面积。
     */
    @Test
    fun `vertical sides get a fallback band that names the edge`() {
        val bottom = PanelTriggerBand.synthetic(PanelSide.BOTTOM, 600f, width, height, thickness)
        val top = PanelTriggerBand.synthetic(PanelSide.TOP, 600f, width, height, thickness)

        assertEquals(height - thickness, bottom.top, 0.01f)
        assertEquals(height.toFloat(), bottom.bottom, 0.01f)
        assertEquals(0f, top.top, 0.01f)
        assertEquals(thickness, top.bottom, 0.01f)
    }
}
