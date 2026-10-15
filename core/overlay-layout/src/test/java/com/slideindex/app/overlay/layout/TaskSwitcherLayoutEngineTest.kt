package com.slideindex.app.overlay.layout

import android.graphics.RectF
import com.slideindex.app.overlay.PanelSide
import com.slideindex.app.overlay.PanelSide.LEFT
import com.slideindex.app.overlay.PanelSide.RIGHT
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class TaskSwitcherLayoutEngineTest {

    @Test
    fun compute_placesPanelToRightOfLeftTrigger() {
        val host = fakeHost(
            side = LEFT,
            trigger = RectF(0f, 400f, 24f, 800f),
        )
        val rows = listOf(
            TaskSwitcherRowEntry("com.example.a", 1),
            TaskSwitcherRowEntry("com.example.b", 2),
        )
        val (layout, _) = TaskSwitcherLayoutEngine.compute(
            host = host,
            rows = rows,
            scrollOffset = 0f,
            anchorLocalY = 600f,
        )
        assertEquals(24f + host.dp(10f), layout.panelRect.left, 0.01f)
        assertEquals(2, layout.rows.size)
        assertEquals("com.example.a", layout.rows[0].entry.packageName)
    }

    @Test
    fun compute_placesPanelToLeftOfRightTrigger() {
        val host = fakeHost(
            side = RIGHT,
            viewWidth = 1080,
            trigger = RectF(1056f, 400f, 1080f, 800f),
        )
        val (layout, _) = TaskSwitcherLayoutEngine.compute(
            host = host,
            rows = listOf(TaskSwitcherRowEntry("com.example.a")),
            scrollOffset = 0f,
            anchorLocalY = 600f,
        )
        assertEquals(1056f - host.dp(10f) - layout.panelRect.width(), layout.panelRect.left, 0.01f)
    }

    @Test
    fun compute_clampsScrollOffset() {
        val host = fakeHost(
            side = LEFT,
            viewHeight = 1920,
            trigger = RectF(0f, 0f, 24f, 400f),
        )
        val rowHeight = host.dp(42f)
        val rows = (1..20).map { TaskSwitcherRowEntry("com.example.$it", it) }
        val (layout, offset) = TaskSwitcherLayoutEngine.compute(
            host = host,
            rows = rows,
            scrollOffset = 9999f,
            anchorLocalY = 200f,
        )
        val peek = TaskSwitcherLayoutEngine.scrollPeekHeight(host, rowHeight)
        val expectedVisible = rowHeight * TaskSwitcherLayoutEngine.MAX_VISIBLE_ROWS + peek
        assertEquals(expectedVisible, layout.listRect.height(), 0.01f)
        assertEquals(peek, layout.listScrollPeekHeight, 0.01f)
        assertEquals(20 * rowHeight - expectedVisible, layout.maxScrollOffset, 0.01f)
        assertEquals(layout.maxScrollOffset, offset, 0.01f)
    }

    @Test
    fun compute_atMostNineRows_noScrollPeek() {
        val host = fakeHost(side = LEFT)
        val rowHeight = host.dp(42f)
        val rows = (1..9).map { TaskSwitcherRowEntry("com.example.$it", it) }
        val (layout, _) = TaskSwitcherLayoutEngine.compute(
            host = host,
            rows = rows,
            scrollOffset = 0f,
            anchorLocalY = 600f,
        )
        assertEquals(0f, layout.listScrollPeekHeight, 0.01f)
        assertEquals(9 * rowHeight, layout.listRect.height(), 0.01f)
        assertEquals(0f, layout.maxScrollOffset, 0.01f)
    }

    /**
     * 该侧一个触钮都没有时，`activeTriggerZoneRect()` 返回的是**零高**合成带
     *（见 app 侧 `PanelTriggerBand`：拿本次触发点合成"贴本侧那条边"的窄带）。
     *
     * 锁两条契约：面板贴触发侧那条边、锚点落在触发高度而不是被夹到屏幕顶端。
     */
    @Test
    fun zeroHeightTriggerBand_keepsPanelOnTriggerSideAtFingerHeight() {
        val rows = listOf(
            TaskSwitcherRowEntry("com.example.a", 1),
            TaskSwitcherRowEntry("com.example.b", 2),
        )
        val leftHost = fakeHost(side = LEFT, trigger = RectF(0f, 600f, 24f, 600f))
        val (leftLayout, _) = TaskSwitcherLayoutEngine.compute(
            host = leftHost,
            rows = rows,
            scrollOffset = 0f,
            anchorLocalY = 600f,
        )
        assertEquals(24f + leftHost.dp(10f), leftLayout.panelRect.left, 0.01f)
        // 首行中心对准触发高度：600 - rowHeight/2（42 / 2）。
        assertEquals(579f, leftLayout.panelRect.top, 0.01f)

        val rightHost = fakeHost(side = RIGHT, trigger = RectF(1056f, 600f, 1080f, 600f))
        val (rightLayout, _) = TaskSwitcherLayoutEngine.compute(
            host = rightHost,
            rows = listOf(TaskSwitcherRowEntry("com.example.a", 1)),
            scrollOffset = 0f,
            anchorLocalY = 600f,
        )
        assertEquals(
            1056f - rightHost.dp(10f) - rightHost.dp(226f),
            rightLayout.panelRect.left,
            0.01f,
        )
        assertEquals(579f, rightLayout.panelRect.top, 0.01f)
    }

    /** 对照：空触发带（没触钮又没有合成带）会把面板夹到顶端 —— 这正是合成带要解决的问题。 */
    @Test
    fun emptyTriggerBand_pinsPanelToTop() {
        val host = fakeHost(side = LEFT, trigger = RectF())
        val (layout, _) = TaskSwitcherLayoutEngine.compute(
            host = host,
            rows = listOf(TaskSwitcherRowEntry("com.example.a", 1)),
            scrollOffset = 0f,
            anchorLocalY = 600f,
        )
        assertEquals(host.dp(16f), layout.panelRect.top, 0.01f)
    }

    private fun fakeHost(
        side: PanelSide,
        viewWidth: Int = 1080,
        viewHeight: Int = 1920,
        trigger: RectF = RectF(0f, 400f, 24f, 800f),
    ): TaskSwitcherLayoutHost = object : TaskSwitcherLayoutHost {
        override fun side(): PanelSide = side
        override fun activeTriggerZoneRect(): RectF = trigger
        override fun viewWidth(): Int = viewWidth
        override fun viewHeight(): Int = viewHeight
        override fun dp(value: Float): Float = value
    }
}
