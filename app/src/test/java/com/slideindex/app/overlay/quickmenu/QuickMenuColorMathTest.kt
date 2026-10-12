package com.slideindex.app.overlay.quickmenu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickMenuColorMathTest {

    @Test
    fun hex_isUppercaseSixDigits() {
        assertEquals("#FFFFFF", QuickMenuColorMath.toHex(0xFFFFFFFF.toInt()))
        assertEquals("#000000", QuickMenuColorMath.toHex(0xFF000000.toInt()))
        assertEquals("#0A0B0C", QuickMenuColorMath.toHex(0xFF0A0B0C.toInt()))
    }

    @Test
    fun hexWithAlpha_keepsAlpha() {
        assertEquals("#80FF0000", QuickMenuColorMath.toHexWithAlpha(0x80FF0000.toInt()))
    }

    @Test
    fun rgb_splitsChannels() {
        assertEquals("rgb(255, 0, 0)", QuickMenuColorMath.toRgb(0xFFFF0000.toInt()))
        assertEquals("rgb(0, 128, 255)", QuickMenuColorMath.toRgb(0xFF0080FF.toInt()))
    }

    @Test
    fun hsl_matchesKnownValues() {
        // 纯红：h=0, s=100%, l=50%
        assertEquals("hsl(0, 100%, 50%)", QuickMenuColorMath.toHsl(0xFFFF0000.toInt()))
        // 纯绿：h=120
        assertEquals("hsl(120, 100%, 50%)", QuickMenuColorMath.toHsl(0xFF00FF00.toInt()))
        // 纯蓝：h=240
        assertEquals("hsl(240, 100%, 50%)", QuickMenuColorMath.toHsl(0xFF0000FF.toInt()))
    }

    @Test
    fun hsl_grayHasZeroSaturation() {
        // 灰阶没有色相：h 记 0，s 记 0%（不能出现 NaN）
        assertEquals("hsl(0, 0%, 50%)", QuickMenuColorMath.toHsl(0xFF808080.toInt()))
        assertEquals("hsl(0, 0%, 0%)", QuickMenuColorMath.toHsl(0xFF000000.toInt()))
        assertEquals("hsl(0, 0%, 100%)", QuickMenuColorMath.toHsl(0xFFFFFFFF.toInt()))
    }

    @Test
    fun luminance_extremesAndOrdering() {
        assertEquals(1.0, QuickMenuColorMath.relativeLuminance(0xFFFFFFFF.toInt()), 1e-6)
        assertEquals(0.0, QuickMenuColorMath.relativeLuminance(0xFF000000.toInt()), 1e-6)
        assertTrue(
            QuickMenuColorMath.relativeLuminance(0xFFFFFFFF.toInt()) >
                QuickMenuColorMath.relativeLuminance(0xFF808080.toInt()),
        )
        assertTrue(
            QuickMenuColorMath.relativeLuminance(0xFF808080.toInt()) >
                QuickMenuColorMath.relativeLuminance(0xFF000000.toInt()),
        )
    }

    @Test
    fun prefersDarkText_onLightAndDark() {
        assertTrue(QuickMenuColorMath.prefersDarkText(0xFFFFFFFF.toInt()))
        assertFalse(QuickMenuColorMath.prefersDarkText(0xFF000000.toInt()))
    }
}
