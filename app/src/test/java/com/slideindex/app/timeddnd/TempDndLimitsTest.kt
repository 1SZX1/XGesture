package com.slideindex.app.timeddnd

import org.junit.Assert.assertEquals
import org.junit.Test

class TempDndLimitsTest {

    @Test
    fun `clamp keeps values inside the range`() {
        assertEquals(1, TempDndLimits.clampMinutes(1))
        assertEquals(15, TempDndLimits.clampMinutes(15))
        assertEquals(120, TempDndLimits.clampMinutes(120))
        assertEquals(1440, TempDndLimits.clampMinutes(1440))
    }

    @Test
    fun `clamp raises non-positive and too small values to the minimum`() {
        assertEquals(TempDndLimits.MIN_MINUTES, TempDndLimits.clampMinutes(0))
        assertEquals(TempDndLimits.MIN_MINUTES, TempDndLimits.clampMinutes(-5))
        assertEquals(TempDndLimits.MIN_MINUTES, TempDndLimits.clampMinutes(Int.MIN_VALUE))
    }

    @Test
    fun `clamp lowers values beyond 24 hours`() {
        assertEquals(TempDndLimits.MAX_MINUTES, TempDndLimits.clampMinutes(1441))
        assertEquals(TempDndLimits.MAX_MINUTES, TempDndLimits.clampMinutes(99999))
        assertEquals(TempDndLimits.MAX_MINUTES, TempDndLimits.clampMinutes(Int.MAX_VALUE))
    }

    @Test
    fun `range is one minute to 24 hours`() {
        assertEquals(1, TempDndLimits.MIN_MINUTES)
        assertEquals(24 * 60, TempDndLimits.MAX_MINUTES)
    }
}
