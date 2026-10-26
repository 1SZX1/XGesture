package com.slideindex.app.overlay

import com.slideindex.app.message.MessageSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeCardUiStateTest {

    private val defaultSettings = MessageSettings()

    @Test
    fun `swipe left expands and swipe right collapses`() {
        val collapsed = EdgeCardUiState(unreadCount = 1)
        val expanded = collapsed.onSwipeLeft()
        assertTrue(expanded.isExpanded)
        assertEquals(EdgeCardPhase.Expanded, expanded.phase)

        val collapsedAgain = expanded.onSwipeRight()
        assertFalse(collapsedAgain.isExpanded)
    }

    @Test
    fun `tap only expands, never collapses`() {
        val collapsed = EdgeCardUiState(unreadCount = 2)
        assertTrue(collapsed.onTap().isExpanded)
        assertTrue(collapsed.onTap().onTap().isExpanded)
    }

    @Test
    fun `new message auto expands and stores unread count`() {
        val state = EdgeCardUiState().onNewMessage(unread = 4, autoExpand = true)
        assertTrue(state.isExpanded)
        assertEquals(4, state.unreadCount)

        val quiet = EdgeCardUiState().onNewMessage(unread = 2, autoExpand = false)
        assertFalse(quiet.isExpanded)
        assertEquals(2, quiet.unreadCount)
    }

    @Test
    fun `badge only shows for more than one unread`() {
        assertNull(EdgeCardUiState(unreadCount = 0).badgeText())
        assertNull(EdgeCardUiState(unreadCount = 1).badgeText())
        assertEquals("4", EdgeCardUiState(unreadCount = 4).badgeText())
        assertEquals("99+", EdgeCardUiState(unreadCount = 120).badgeText())

        val expandedSingle = EdgeCardUiState(unreadCount = 1).expand()
        assertNull(expandedSingle.badgeText())
        val expandedMulti = EdgeCardUiState(unreadCount = 3).expand()
        assertEquals("3", expandedMulti.badgeText())
    }

    @Test
    fun `expanded width grows with unread count`() {
        val single = EdgeCardUiState(unreadCount = 1).expand()
        assertEquals(EdgeCardUiState.EXPANDED_WIDTH_DP, single.widthDp(), 0.01f)
        val multi = EdgeCardUiState(unreadCount = 2).expand()
        assertEquals(EdgeCardUiState.WIDE_WIDTH_DP, multi.widthDp(), 0.01f)
        val collapsed = EdgeCardUiState(unreadCount = 5)
        assertEquals(EdgeCardUiState.COMPACT_WIDTH_DP, collapsed.widthDp(), 0.01f)
    }

    @Test
    fun `width interpolation clamps progress`() {
        assertEquals(
            EdgeCardUiState.COMPACT_WIDTH_DP,
            EdgeCardUiState.widthForProgress(EdgeCardUiState.EXPANDED_WIDTH_DP, -1f),
            0.01f,
        )
        assertEquals(
            EdgeCardUiState.EXPANDED_WIDTH_DP,
            EdgeCardUiState.widthForProgress(EdgeCardUiState.EXPANDED_WIDTH_DP, 2f),
            0.01f,
        )
        assertEquals(
            (EdgeCardUiState.COMPACT_WIDTH_DP + EdgeCardUiState.EXPANDED_WIDTH_DP) / 2f,
            EdgeCardUiState.widthForProgress(EdgeCardUiState.EXPANDED_WIDTH_DP, 0.5f),
            0.01f,
        )
    }

    @Test
    fun `resolve swipe prefers the dominant axis`() {
        assertEquals(
            EdgeCardSwipe.Expand,
            EdgeCardUiState.resolveSwipe(-40f, 2f, horizontalThresholdPx = 20f, verticalThresholdPx = 20f),
        )
        assertEquals(
            EdgeCardSwipe.Collapse,
            EdgeCardUiState.resolveSwipe(40f, 2f, horizontalThresholdPx = 20f, verticalThresholdPx = 20f),
        )
        assertEquals(
            EdgeCardSwipe.Dismiss,
            EdgeCardUiState.resolveSwipe(3f, -40f, horizontalThresholdPx = 20f, verticalThresholdPx = 20f),
        )
        assertEquals(
            EdgeCardSwipe.None,
            EdgeCardUiState.resolveSwipe(5f, -5f, horizontalThresholdPx = 20f, verticalThresholdPx = 20f),
        )
    }

    @Test
    fun `board opacity follows the drag progress`() {
        val settings = defaultSettings.copy(edgeCardOpacity = 0.55f)
        assertEquals(0.55f, EdgeCardUiState().boardAlpha(settings, 0f), 0.001f)
        assertEquals(1f, EdgeCardUiState().expand().boardAlpha(settings, 1f), 0.001f)
        assertEquals(0.775f, EdgeCardUiState().boardAlpha(settings, 0.5f), 0.01f)
    }

    @Test
    fun `unread count is clamped to a sane range`() {
        assertEquals(0, EdgeCardUiState().withUnread(-3).unreadCount)
        assertEquals(EdgeCardUiState.MAX_UNREAD, EdgeCardUiState().withUnread(100_000).unreadCount)
    }

    @Test
    fun `text alpha equals the drag progress`() {
        assertEquals(0f, EdgeCardUiState.textAlpha(0f), 0.001f)
        assertEquals(0.25f, EdgeCardUiState.textAlpha(0.25f), 0.001f)
        assertEquals(0.75f, EdgeCardUiState.textAlpha(0.75f), 0.001f)
        assertEquals(1f, EdgeCardUiState.textAlpha(1f), 0.001f)
        assertEquals(1f, EdgeCardUiState.textAlpha(2f), 0.001f)
        assertEquals(0f, EdgeCardUiState.textAlpha(-1f), 0.001f)
    }

    @Test
    fun `card count setting is clamped`() {
        assertEquals(1, EdgeCardUiState.countFor(defaultSettings.copy(edgeCardMaxCount = 0)))
        assertEquals(3, EdgeCardUiState.countFor(defaultSettings.copy(edgeCardMaxCount = 3)))
        assertEquals(5, EdgeCardUiState.countFor(defaultSettings.copy(edgeCardMaxCount = 99)))
    }
}
