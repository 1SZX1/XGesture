package com.slideindex.app.quickmenu

import com.slideindex.app.quickmenu.StopwatchLogic.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StopwatchLogicTest {

    private val start = 1_000L

    // ---------- 起停 ----------

    @Test
    fun start_setsRunningWithoutChangingElapsed() {
        val state = StopwatchLogic.start(State(), start)
        assertTrue(state.running)
        assertEquals(0L, state.elapsedMs)
        assertEquals(start, state.startedAtMs)
    }

    @Test
    fun start_whenAlreadyRunning_isNoOp() {
        val running = StopwatchLogic.start(State(), start)
        assertEquals(running, StopwatchLogic.start(running, start + 5_000))
    }

    @Test
    fun elapsedAt_whileRunning_tracksClock() {
        val state = StopwatchLogic.start(State(), start)
        assertEquals(2_500L, StopwatchLogic.elapsedAt(state, start + 2_500))
    }

    @Test
    fun pause_freezesElapsed() {
        val paused = StopwatchLogic.pause(StopwatchLogic.start(State(), start), start + 3_000)
        assertFalse(paused.running)
        assertNull(paused.startedAtMs)
        assertEquals(3_000L, paused.elapsedMs)
        // 暂停后即使时钟继续走，读数也不再变化
        assertEquals(3_000L, StopwatchLogic.elapsedAt(paused, start + 99_000))
    }

    @Test
    fun pause_whenNotRunning_isNoOp() {
        assertEquals(State(), StopwatchLogic.pause(State(), start))
    }

    @Test
    fun start_afterPause_resumesFromFrozenElapsed() {
        val paused = StopwatchLogic.pause(StopwatchLogic.start(State(), start), start + 3_000)
        val resumed = StopwatchLogic.start(paused, start + 10_000)
        // 暂停期间（7 秒）不计入
        assertEquals(3_000L, StopwatchLogic.elapsedAt(resumed, start + 10_000))
        assertEquals(4_000L, StopwatchLogic.elapsedAt(resumed, start + 11_000))
    }

    // ---------- 计次 ----------

    @Test
    fun lap_recordsSegmentDuration() {
        val running = StopwatchLogic.start(State(), start)
        val first = StopwatchLogic.lap(running, start + 2_000)
        assertEquals(listOf(2_000L), first.laps)
        assertEquals(2_000L, first.lapAnchorMs)

        val second = StopwatchLogic.lap(first, start + 5_000)
        assertEquals(listOf(2_000L, 3_000L), second.laps)
        assertEquals(5_000L, second.lapAnchorMs)
    }

    @Test
    fun lap_whenNotRunning_isNoOp() {
        assertEquals(State(), StopwatchLogic.lap(State(), start + 1_000))
    }

    @Test
    fun lap_withoutElapsedProgress_isIgnored() {
        val running = StopwatchLogic.start(State(), start)
        val first = StopwatchLogic.lap(running, start + 1_000)
        // 同一时刻再点一次不应产生 0ms 的圈
        assertEquals(first, StopwatchLogic.lap(first, start + 1_000))
    }

    @Test
    fun reset_clearsElapsedAndLaps() {
        val running = StopwatchLogic.start(State(), start)
        val withLap = StopwatchLogic.lap(running, start + 2_000)
        assertEquals(State(), StopwatchLogic.reset())
        assertTrue(withLap.laps.isNotEmpty())
    }

    // ---------- 最快 / 最慢圈 ----------

    @Test
    fun bestAndWorstLap_areNullWithFewerThanTwoLaps() {
        assertNull(StopwatchLogic.bestLapIndex(State()))
        assertNull(StopwatchLogic.worstLapIndex(State()))
        assertNull(StopwatchLogic.bestLapIndex(State(laps = listOf(1_000L))))
    }

    @Test
    fun bestAndWorstLap_identifyExtremes() {
        val state = State(laps = listOf(5_000L, 3_000L, 7_000L))
        assertEquals(1, StopwatchLogic.bestLapIndex(state))
        assertEquals(2, StopwatchLogic.worstLapIndex(state))
    }

    @Test
    fun bestAndWorstLap_keepFirstIndexOnTie() {
        val state = State(laps = listOf(4_000L, 4_000L))
        assertEquals(0, StopwatchLogic.bestLapIndex(state))
        assertEquals(0, StopwatchLogic.worstLapIndex(state))
    }

    // ---------- 格式化 ----------

    @Test
    fun format_zero_isAllZeros() {
        assertEquals("00:00.00", StopwatchLogic.format(0L))
    }

    @Test
    fun format_usesMinutesSecondsHundredths() {
        assertEquals("00:01.23", StopwatchLogic.format(1_234L))
        assertEquals("01:01.00", StopwatchLogic.format(61_000L))
        assertEquals("59:59.99", StopwatchLogic.format(3_599_990L))
    }

    @Test
    fun format_switchesToHoursAtOneHour() {
        assertEquals("1:00:00.00", StopwatchLogic.format(3_600_000L))
        assertEquals("2:03:04.05", StopwatchLogic.format(7_384_050L))
    }

    @Test
    fun format_negative_isClampedToZero() {
        assertEquals("00:00.00", StopwatchLogic.format(-5L))
    }
}
