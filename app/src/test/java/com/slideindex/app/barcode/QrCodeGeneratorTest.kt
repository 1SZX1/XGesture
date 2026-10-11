package com.slideindex.app.barcode

import android.app.Application
import android.graphics.Bitmap
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class QrCodeGeneratorTest {

    private val content = "https://example.com/quick-menu"
    private val foreground = 0xFF102030.toInt()
    private val background = 0xFFF0E0D0.toInt()
    private val logoColor = 0xFFFF00FF.toInt()

    // ---------- 尺寸：修掉参考实现"宽高被硬编码 512 忽略"的问题 ----------

    @Test
    fun generatePixels_usesRequestedSizeExactly() {
        assertEquals(256 * 256, requireNotNull(pixels(sizePx = 256)).size)
        assertEquals(320 * 320, requireNotNull(pixels(sizePx = 320)).size)
    }

    @Test
    fun generatePixels_differentSizes_produceDifferentRasterLengths() {
        val small = requireNotNull(pixels(sizePx = 256))
        val large = requireNotNull(pixels(sizePx = 512))
        assertNotEquals(small.size, large.size)
    }

    @Test
    fun generatePixels_belowMinimumSize_isClampedUp() {
        val pixels = requireNotNull(pixels(sizePx = 1))
        assertEquals(QrCodeGenerator.MIN_SIZE_PX * QrCodeGenerator.MIN_SIZE_PX, pixels.size)
    }

    @Test
    fun generatePixels_aboveMaximumSize_isClampedDown() {
        val pixels = requireNotNull(pixels(sizePx = 99_999))
        assertEquals(QrCodeGenerator.MAX_SIZE_PX * QrCodeGenerator.MAX_SIZE_PX, pixels.size)
    }

    // ---------- 颜色：修掉参考实现"渐变 shader 从未生效"这一类问题 ----------

    @Test
    fun generatePixels_containsBothForegroundAndBackground() {
        val pixels = requireNotNull(pixels())
        assertTrue("应包含模块色", pixels.contains(foreground))
        assertTrue("应包含底色", pixels.contains(background))
    }

    @Test
    fun generatePixels_customColors_areApplied() {
        val pixels = requireNotNull(pixels())
        assertTrue(pixels.contains(foreground))
        assertTrue(pixels.contains(background))
        assertFalse("不应残留默认黑色", pixels.contains(0xFF000000.toInt()))
        assertFalse("不应残留默认白色", pixels.contains(0xFFFFFFFF.toInt()))
    }

    // ---------- 静区 ----------

    @Test
    fun generatePixels_quietZone_rendersBackgroundAtCorners() {
        val pixels = requireNotNull(pixels(sizePx = 512, quietZone = 2))
        assertEquals("左上角应落在静区内", background, pixels[0])
        assertEquals("右下角应落在静区内", background, pixels[512 * 512 - 1])
    }

    // ---------- 入参校验 ----------

    @Test
    fun generatePixels_emptyContent_returnsNull() {
        assertNull(QrCodeGenerator.generatePixels("", style()))
        assertNull(QrCodeGenerator.generatePixels("   ", style()))
    }

    @Test
    fun generatePixels_contentBeyondCapacity_returnsNull() {
        assertNull(QrCodeGenerator.generatePixels("x".repeat(8000), style(sizePx = 256)))
    }

    @Test
    fun generatePixels_isDeterministic() {
        assertArrayEquals(requireNotNull(pixels(sizePx = 256)), requireNotNull(pixels(sizePx = 256)))
    }

    // ---------- 纠错级别 ----------

    @Test
    fun generatePixels_errorCorrection_isHonouredWithoutLogo() {
        val low = requireNotNull(pixels(sizePx = 256, level = ErrorCorrectionLevel.L))
        val quartile = requireNotNull(pixels(sizePx = 256, level = ErrorCorrectionLevel.Q))
        assertFalse("不同纠错级别应产出不同图形", low.contentEquals(quartile))
    }

    @Test
    fun generatePixelsWithLogo_errorCorrection_isForcedToHighest() {
        val low = requireNotNull(logoPixels(logoScale = 0.22f, level = ErrorCorrectionLevel.L))
        val quartile = requireNotNull(logoPixels(logoScale = 0.22f, level = ErrorCorrectionLevel.Q))
        assertArrayEquals("叠加徽章时应强制最高纠错，忽略调用方设置", low, quartile)
    }

    // ---------- 中央徽章 ----------

    @Test
    fun generatePixelsWithLogo_centerIsCoveredByLogo() {
        val pixels = requireNotNull(logoPixels(logoScale = 0.22f))
        val center = pixels[CENTER * 512 + CENTER]
        assertEquals(logoColor, center)
    }

    @Test
    fun generatePixelsWithLogo_oversizedScale_isClampedToSafeMaximum() {
        val pixels = requireNotNull(logoPixels(logoScale = 0.9f))
        // 允许的最大半径 = MAX_LOGO_SCALE / 2 * 边长
        val safeRadius = (512 * QrCodeGenerator.MAX_LOGO_SCALE / 2f).toInt()
        val probeX = CENTER + safeRadius + 4
        assertNotEquals("超出安全半径的位置不应被徽章覆盖", logoColor, pixels[CENTER * 512 + probeX])
    }

    @Test
    fun generatePixelsWithLogo_tinyScale_isClampedToMinimum() {
        val pixels = requireNotNull(logoPixels(logoScale = 0.0001f))
        assertEquals("夹取到下限后徽章仍应可见", logoColor, pixels[CENTER * 512 + CENTER])
    }

    @Test
    fun generatePixelsWithLogo_keepsRequestedSize() {
        val pixels = requireNotNull(logoPixels(logoScale = 0.22f, sizePx = 320))
        assertEquals(320 * 320, pixels.size)
    }

    // ---------- Bitmap 出口 ----------

    @Test
    fun generate_returnsBitmapWithRequestedSize() {
        val bitmap = requireNotNull(QrCodeGenerator.generate(content, style(sizePx = 320)))
        assertEquals(320, bitmap.width)
        assertEquals(320, bitmap.height)
    }

    @Test
    fun generateWithLogo_returnsBitmapWithRequestedSize() {
        val bitmap = requireNotNull(
            QrCodeGenerator.generateWithLogo(content, logo(), style(sizePx = 320)),
        )
        assertEquals(320, bitmap.width)
        assertEquals(320, bitmap.height)
    }

    @Test
    fun generate_blankContent_returnsNull() {
        assertNull(QrCodeGenerator.generate("   ", style()))
    }

    private fun style(
        sizePx: Int = 512,
        quietZone: Int = 2,
        level: ErrorCorrectionLevel = ErrorCorrectionLevel.H,
    ) = QrCodeGenerator.Style(
        sizePx = sizePx,
        foregroundColor = foreground,
        backgroundColor = background,
        errorCorrectionLevel = level,
        quietZoneModules = quietZone,
    )

    private fun pixels(
        sizePx: Int = 512,
        quietZone: Int = 2,
        level: ErrorCorrectionLevel = ErrorCorrectionLevel.H,
    ): IntArray? = QrCodeGenerator.generatePixels(content, style(sizePx, quietZone, level))

    private fun logoPixels(
        logoScale: Float,
        level: ErrorCorrectionLevel = ErrorCorrectionLevel.H,
        sizePx: Int = 512,
    ): IntArray? = QrCodeGenerator.generatePixelsWithLogo(
        content = content,
        logo = logo(),
        style = style(sizePx = sizePx, level = level),
        logoScale = logoScale,
    )

    /** 纯色徽章，便于对像素做颜色断言。 */
    private fun logo(): Bitmap {
        val size = 64
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(size * size) { logoColor }, 0, size, 0, 0, size, size)
        return bitmap
    }

    private companion object {
        const val CENTER = 512 / 2
    }
}
