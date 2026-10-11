package com.slideindex.app.quickmenu

import com.slideindex.app.quickmenu.CountdownLogic.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CountdownLogicTest {

    private val start = 1_000L
    private val oneMinute = 60_000L

    // ---------- 预设 ----------

    @Test
    fun presetMinutes_clampsIntoRange() {
        assertEquals(CountdownLogic.MIN_MINUTES, CountdownLogic.clampMinutes(0))
        assertEquals(CountdownLogic.MIN_MINUTES, CountdownLogic.clampMinutes(-5))
        assertEquals(CountdownLogic.MAX_MINUTES, CountdownLogic.clampMinutes(999))
        assertEquals(7, CountdownLogic.clampMinutes(7))
    }

    @Test
    fun preset_setsRemainingEqualToDuration_andNotRunning() {
        val state = CountdownLogic.preset(oneMinute)
        assertEquals(oneMinute, state.durationMs)
        assertEquals(oneMinute, state.remainingMs)
        assertFalse(state.running)
        assertNull(state.endsAtMs)
    }

    @Test
    fun presetMinutes_convertsMinutesToMillis() {
        assertEquals(5 * oneMinute, CountdownLogic.presetMinutes(5).durationMs)
    }

    @Test
    fun preset_negativeDuration_isClampedToZero() {
        assertEquals(0L, CountdownLogic.preset(-1L).durationMs)
    }

    // ---------- 起停 ----------

    @Test
    fun start_setsRunningAndEndsAt() {
        val state = CountdownLogic.start(CountdownLogic.preset(oneMinute), start)
        assertTrue(state.running)
        assertEquals(start + oneMinute, state.endsAtMs)
    }

    @Test
    fun start_whenAlreadyRunning_isNoOp() {
        val running = CountdownLogic.start(CountdownLogic.preset(oneMinute), start)
        assertEquals(running, CountdownLogic.start(running, start + 5_000))
    }

    @Test
    fun start_whenNothingRemaining_isNoOp() {
        val expired = State(durationMs = oneMinute, remainingMs = 0L)
        assertFalse(CountdownLogic.start(expired, start).running)
    }

    @Test
    fun pause_freezesRemaining_andClearsEndsAt() {
        val running = CountdownLogic.start(CountdownLogic.preset(oneMinute), start)
        val paused = CountdownLogic.pause(running, start + 20_000)
        assertFalse(paused.running)
        assertNull(paused.endsAtMs)
        assertEquals(oneMinute - 20_000, paused.remainingMs)
    }

    @Test
    fun pause_whenNotRunning_isNoOp() {
        val state = CountdownLogic.preset(oneMinute)
        assertEquals(state, CountdownLogic.pause(state, start))
    }

    @Test
    fun start_afterPause_resumesFromRemaining() {
        val running = CountdownLogic.start(CountdownLogic.preset(oneMinute), start)
        val paused = CountdownLogic.pause(running, start + 20_000)
        val resumed = CountdownLogic.start(paused, start + 90_000)
        // 暂停期间（70 秒）不计入
        assertEquals(40_000L, CountdownLogic.remainingAt(resumed, start + 90_000))
        assertEquals(0L, CountdownLogic.remainingAt(resumed, start + 130_000))
    }

    @Test
    fun reset_restoresDurationAndStops() {
        val running = CountdownLogic.start(CountdownLogic.preset(oneMinute), start)
        val reset = CountdownLogic.reset(CountdownLogic.pause(running, start + 30_000))
        assertEquals(oneMinute, reset.remainingMs)
        assertFalse(reset.running)
        assertNull(reset.endsAtMs)
    }

    // ---------- 剩余量与到点 ----------

    @Test
    fun remainingAt_countsDown() {
        val state = CountdownLogic.start(CountdownLogic.preset(oneMinute), start)
        assertEquals(45_000L, CountdownLogic.remainingAt(state, start + 15_000))
    }

    @Test
    fun remainingAt_neverGoesNegative() {
        val state = CountdownLogic.start(CountdownLogic.preset(oneMinute), start)
        assertEquals(0L, CountdownLogic.remainingAt(state, start + oneMinute + 999_999))
    }

    @Test
    fun remainingAt_whenPaused_returnsFrozenValue() {
        val paused = CountdownLogic.pause(
            CountdownLogic.start(CountdownLogic.preset(oneMinute), start),
            start + 10_000,
        )
        assertEquals(50_000L, CountdownLogic.remainingAt(paused, start + 999_999))
    }

    @Test
    fun isExpired_trueOnlyWhileRunningAtZero() {
        val running = CountdownLogic.start(CountdownLogic.preset(oneMinute), start)
        assertFalse(CountdownLogic.isExpired(running, start + 59_999))
        assertTrue(CountdownLogic.isExpired(running, start + oneMinute))

        // 暂停在 0 剩余时不算"到点"：到点判定要求仍在运行，避免暂停状态被反复记为一次完成。
        val pausedAtZero = State(durationMs = oneMinute, remainingMs = 0L, running = false)
        assertFalse(CountdownLogic.isExpired(pausedAtZero, start))
    }

    // ---------- 进度 ----------

    @Test
    fun progress_isZeroAtStartAndOneAtEnd() {
        val state = CountdownLogic.start(CountdownLogic.preset(oneMinute), start)
        assertEquals(0f, CountdownLogic.progress(state, start), 1e-4f)
        assertEquals(0.5f, CountdownLogic.progress(state, start + 30_000), 1e-4f)
        assertEquals(1f, CountdownLogic.progress(state, start + oneMinute), 1e-4f)
    }

    @Test
    fun progress_withZeroDuration_isZero() {
        assertEquals(0f, CountdownLogic.progress(State(), start), 1e-4f)
    }
}
