package com.slideindex.app.overlay.quickmenu

import com.slideindex.app.barcode.BarcodePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 相机定格的两段纯计算测试：坐标旋转映射 + YUV→ARGB。
 *
 * 存在意义：这两处出错都**不会抛异常**，只会让"标记和图对不上"或"画面颜色发绿"——
 * 靠肉眼很难定位，必须靠用例钉住。
 */
class QuickMenuScanFrameTest {

    // ---------------------------------------------------------------- 坐标旋转

    @Test
    fun rotate_identityForZeroAndFullTurn() {
        val point = BarcodePoint(0.25f, 0.75f)
        assertEquals(point, QuickMenuScanFrame.rotatePoint(point, 0))
        assertEquals(point, QuickMenuScanFrame.rotatePoint(point, 360))
    }

    @Test
    fun rotate_90DegreesClockwise_movesCornersAsExpected() {
        // 左上角转到右上角
        assertEquals(
            BarcodePoint(1f, 0f),
            QuickMenuScanFrame.rotatePoint(BarcodePoint(0f, 0f), 90),
        )
        // 左下角转到左上角
        assertEquals(
            BarcodePoint(0f, 0f),
            QuickMenuScanFrame.rotatePoint(BarcodePoint(0f, 1f), 90),
        )
        // 右上角转到右下角
        assertEquals(
            BarcodePoint(1f, 1f),
            QuickMenuScanFrame.rotatePoint(BarcodePoint(1f, 0f), 90),
        )
    }

    @Test
    fun rotate_180Degrees_flipsBothAxes() {
        assertEquals(
            BarcodePoint(0.75f, 0.25f),
            QuickMenuScanFrame.rotatePoint(BarcodePoint(0.25f, 0.75f), 180),
        )
    }

    @Test
    fun rotate_270Degrees_movesCornersAsExpected() {
        assertEquals(
            BarcodePoint(0f, 1f),
            QuickMenuScanFrame.rotatePoint(BarcodePoint(0f, 0f), 270),
        )
        assertEquals(
            BarcodePoint(0f, 0f),
            QuickMenuScanFrame.rotatePoint(BarcodePoint(1f, 0f), 270),
        )
    }

    @Test
    fun rotate_negativeIsNormalized() {
        val point = BarcodePoint(0.2f, 0.3f)
        assertEquals(
            QuickMenuScanFrame.rotatePoint(point, 270),
            QuickMenuScanFrame.rotatePoint(point, -90),
        )
    }

    @Test
    fun rotate_nonRightAngle_isTreatedAsNoRotation() {
        // 相机方向永远是 90 的倍数；其它值按不旋转处理（显式写出来，避免"以为它会插值"）。
        val point = BarcodePoint(0.2f, 0.3f)
        assertEquals(point, QuickMenuScanFrame.rotatePoint(point, 45))
    }

    @Test
    fun rotatePoints_mapsEveryPoint() {
        val points = listOf(BarcodePoint(0f, 0f), BarcodePoint(1f, 1f))
        assertEquals(
            listOf(BarcodePoint(1f, 0f), BarcodePoint(0f, 1f)),
            QuickMenuScanFrame.rotatePoints(points, 90),
        )
    }

    @Test
    fun swapsDimensions_onlyForQuarterTurns() {
        assertTrue(QuickMenuScanFrame.swapsDimensions(90))
        assertTrue(QuickMenuScanFrame.swapsDimensions(270))
        assertTrue(QuickMenuScanFrame.swapsDimensions(-90))
        assertFalse(QuickMenuScanFrame.swapsDimensions(0))
        assertFalse(QuickMenuScanFrame.swapsDimensions(180))
        assertFalse(QuickMenuScanFrame.swapsDimensions(360))
    }

    // ---------------------------------------------------------------- 颜色换算

    @Test
    fun yuv_blackAndWhiteAndGray() {
        assertEquals(0xFF000000.toInt(), QuickMenuScanFrame.yuvToArgb(y = 0, u = 128, v = 128))
        assertEquals(0xFFFFFFFF.toInt(), QuickMenuScanFrame.yuvToArgb(y = 255, u = 128, v = 128))
        // 灰色：三个通道相等（无色度差时不偏色）
        val gray = QuickMenuScanFrame.yuvToArgb(y = 128, u = 128, v = 128)
        assertEquals(0xFF808080.toInt(), gray)
    }

    @Test
    fun yuv_alphaIsAlwaysOpaque() {
        for (y in listOf(0, 1, 128, 254, 255)) {
            val argb = QuickMenuScanFrame.yuvToArgb(y, 128, 128)
            assertEquals(0xFF, (argb ushr 24) and 0xFF)
        }
    }

    @Test
    fun yuv_extremeChroma_isClampedNotWrapped() {
        // 极端色度：算出来会超出 0..255，必须夹住而不是溢出成反色（溢出会让画面出现奇怪的彩点）。
        val argb = QuickMenuScanFrame.yuvToArgb(y = 255, u = 0, v = 255)
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        assertTrue(r in 0..255 && g in 0..255 && b in 0..255)
        assertEquals(255, (argb ushr 24) and 0xFF)
    }

    @Test
    fun yuv_outOfRangeInputs_areCoerced() {
        // 上层读到的是无符号字节，理论上不会越界；这里钉住"越界也不炸、不产生乱色"。
        assertEquals(
            QuickMenuScanFrame.yuvToArgb(y = 0, u = 128, v = 128),
            QuickMenuScanFrame.yuvToArgb(y = -5, u = 128, v = 128),
        )
        assertEquals(
            QuickMenuScanFrame.yuvToArgb(y = 255, u = 128, v = 128),
            QuickMenuScanFrame.yuvToArgb(y = 999, u = 128, v = 128),
        )
    }

    @Test
    fun yuv_greenShiftWhenChromaIsOneSided() {
        // 只把 U 拉高应当偏蓝（BT.601 里 U 是蓝差）；这条用来防止 u/v 接反 ——
        // 接反的典型症状就是"人脸发绿"，肉眼很容易误判成相机问题。
        val bluish = QuickMenuScanFrame.yuvToArgb(y = 128, u = 255, v = 128)
        val b = bluish and 0xFF
        val r = (bluish ushr 16) and 0xFF
        assertTrue("U 拉高应让蓝色通道大于红色通道", b > r)
    }

    // ---------------------------------------------------------------- 显示朝向与取景对齐

    /** 4×2 的合成帧：y=0 行是 0,1,2,3；y=1 行是 4,5,6,7。 */
    private fun smallFrame(rowStride: Int = 4): ByteArray = ByteArray(rowStride * 2) { index ->
        val row = index / rowStride
        val column = index % rowStride
        if (column < 4) (row * 4 + column).toByte() else 99.toByte()
    }

    private fun QuickMenuScanFrame.DisplayLuminance.values(): List<Int> = pixels.map { it.toInt() and 0xFF }

    @Test
    fun displaySize_swapsForQuarterTurns() {
        assertEquals(
            QuickMenuScanFrame.FrameSize(960, 1280),
            QuickMenuScanFrame.displaySize(1280, 960, 90),
        )
        assertEquals(
            QuickMenuScanFrame.FrameSize(960, 1280),
            QuickMenuScanFrame.displaySize(1280, 960, 270),
        )
        assertEquals(
            QuickMenuScanFrame.FrameSize(1280, 960),
            QuickMenuScanFrame.displaySize(1280, 960, 0),
        )
        assertEquals(
            QuickMenuScanFrame.FrameSize(1280, 960),
            QuickMenuScanFrame.displaySize(1280, 960, 180),
        )
    }

    @Test
    fun displayCrop_exactAspectNeedsNoCrop() {
        assertEquals(
            QuickMenuScanFrame.FrameRect(0, 0, 1080, 2340),
            QuickMenuScanFrame.displayCrop(1080, 2340, 1080f / 2340f),
        )
    }

    @Test
    fun displayCrop_narrowScreenCropsWidthCentrally() {
        // 真实场景：分析流 4:3 旋转后是 960×1280，而屏幕是 1080×2340（很窄）→ 只保留中间一条
        val crop = QuickMenuScanFrame.displayCrop(960, 1280, 1080f / 2340f)
        assertEquals(0, crop.y)
        assertEquals(1280, crop.height)
        assertEquals(591, crop.width)
        assertEquals((960 - 591) / 2, crop.x)
    }

    @Test
    fun displayCrop_wideScreenCropsHeightCentrally() {
        // 屏幕比画面更"方"时裁高度：1000×500 要按 1:1 显示 → 中间 500×500
        val crop = QuickMenuScanFrame.displayCrop(1000, 500, 1f)
        assertEquals((1000 - 500) / 2, crop.x)
        assertEquals(0, crop.y)
        assertEquals(500, crop.width)
        assertEquals(500, crop.height)
    }

    @Test
    fun displayCrop_degenerateInputsAreSafe() {
        assertEquals(QuickMenuScanFrame.FrameRect(0, 0, 0, 0), QuickMenuScanFrame.displayCrop(0, 0, 1f))
        assertEquals(QuickMenuScanFrame.FrameRect(0, 0, 4, 2), QuickMenuScanFrame.displayCrop(4, 2, 0f))
    }

    @Test
    fun displayLuminance_noRotationKeepsPixels() {
        val display = QuickMenuScanFrame.displayLuminance(
            luminance = smallFrame(),
            width = 4,
            height = 2,
            rowStride = 4,
            rotationDegrees = 0,
            displayAspect = 2f,
        )
        assertEquals(4, display.width)
        assertEquals(2, display.height)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), display.values())
    }

    @Test
    fun displayLuminance_rotatesClockwise() {
        // 原图（4 宽 2 高）:      顺时针 90° 后（2 宽 4 高）:
        //   0 1 2 3                 4 0
        //   4 5 6 7                 5 1
        //                           6 2
        //                           7 3
        // 左上角来自原图左下角，所以行主序平铺是 4,0 / 5,1 / 6,2 / 7,3
        val display = QuickMenuScanFrame.displayLuminance(
            luminance = smallFrame(),
            width = 4,
            height = 2,
            rowStride = 4,
            rotationDegrees = 90,
            displayAspect = 0.5f,
        )
        assertEquals(2, display.width)
        assertEquals(4, display.height)
        assertEquals(listOf(4, 0, 5, 1, 6, 2, 7, 3), display.values())
    }

    @Test
    fun displayLuminance_rotation270IsInverseOf90() {
        // 逆时针 90°（即顺时针 270°）：
        //   3 7
        //   2 6
        //   1 5
        //   0 4
        val display = QuickMenuScanFrame.displayLuminance(
            luminance = smallFrame(),
            width = 4,
            height = 2,
            rowStride = 4,
            rotationDegrees = 270,
            displayAspect = 0.5f,
        )
        assertEquals(listOf(3, 7, 2, 6, 1, 5, 0, 4), display.values())
    }

    @Test
    fun displayLuminance_rotates180() {
        val display = QuickMenuScanFrame.displayLuminance(
            luminance = smallFrame(),
            width = 4,
            height = 2,
            rowStride = 4,
            rotationDegrees = 180,
            displayAspect = 2f,
        )
        assertEquals(listOf(7, 6, 5, 4, 3, 2, 1, 0), display.values())
    }

    @Test
    fun displayLuminance_cropsToDisplayAspect() {
        // 4×2 的画面要按 1:1 显示 → 只保留中间两列
        val display = QuickMenuScanFrame.displayLuminance(
            luminance = smallFrame(),
            width = 4,
            height = 2,
            rowStride = 4,
            rotationDegrees = 0,
            displayAspect = 1f,
        )
        assertEquals(2, display.width)
        assertEquals(2, display.height)
        assertEquals(listOf(1, 2, 5, 6), display.values())
    }

    @Test
    fun displayLuminance_sampleStepDownsamples() {
        val display = QuickMenuScanFrame.displayLuminance(
            luminance = smallFrame(),
            width = 4,
            height = 2,
            rowStride = 4,
            rotationDegrees = 0,
            displayAspect = 1f,
            sampleStep = 2,
        )
        assertEquals(1, display.width)
        assertEquals(1, display.height)
        assertEquals(listOf(1), display.values())
    }

    @Test
    fun displayLuminance_respectsRowStridePadding() {
        // 行距 6、真实宽度 4：末尾两字节是硬件对齐的填充，绝不能被当成像素读进来
        val display = QuickMenuScanFrame.displayLuminance(
            luminance = smallFrame(rowStride = 6),
            width = 4,
            height = 2,
            rowStride = 6,
            rotationDegrees = 0,
            displayAspect = 2f,
        )
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), display.values())
    }

    @Test
    fun displayLuminance_reusesProvidedBuffer() {
        // 预览每帧都要刷新，复用缓冲是"不制造每秒十几 MB 垃圾"的关键
        val reuse = ByteArray(8)
        val display = QuickMenuScanFrame.displayLuminance(
            luminance = smallFrame(),
            width = 4,
            height = 2,
            rowStride = 4,
            rotationDegrees = 0,
            displayAspect = 2f,
            into = reuse,
        )
        assertTrue("复用时应返回同一个数组", display.pixels === reuse)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), display.values())

        // 尺寸不符时必须新建，绝不越界写
        val wrongSize = ByteArray(3)
        val fresh = QuickMenuScanFrame.displayLuminance(
            luminance = smallFrame(),
            width = 4,
            height = 2,
            rowStride = 4,
            rotationDegrees = 0,
            displayAspect = 2f,
            into = wrongSize,
        )
        assertTrue("尺寸不符时应新建", fresh.pixels !== wrongSize)
        assertEquals(8, fresh.pixels.size)
    }

    @Test
    fun displayLuminance_degenerateInputsReturnZeroFilledBuffer() {
        val noLuminance = QuickMenuScanFrame.displayLuminance(
            luminance = ByteArray(0),
            width = 4,
            height = 2,
            rowStride = 4,
            rotationDegrees = 0,
            displayAspect = 2f,
        )
        assertEquals(4, noLuminance.width)
        assertTrue(noLuminance.pixels.all { it == 0.toByte() })

        // 行距小于宽度 = 布局不合法，不能越界读
        val badStride = QuickMenuScanFrame.displayLuminance(
            luminance = smallFrame(),
            width = 4,
            height = 2,
            rowStride = 2,
            rotationDegrees = 0,
            displayAspect = 2f,
        )
        assertTrue(badStride.pixels.all { it == 0.toByte() })
    }

    @Test
    fun gridSignature_averagesUniformImageToItsValue() {
        val width = 16
        val height = 8
        val luminance = ByteArray(width * height) { 200.toByte() }
        val grid = QuickMenuScanFrame.gridSignature(luminance, width, height, gridSize = 4)
        assertEquals(16, grid.size)
        assertTrue("均匀图每格都应等于该值", grid.all { it == 200 })
    }

    @Test
    fun gridSignature_separatesBrightAndDarkHalves() {
        val width = 8
        val height = 8
        val luminance = ByteArray(width * height) { index ->
            if (index / width < height / 2) 0 else 255.toByte()
        }
        val grid = QuickMenuScanFrame.gridSignature(luminance, width, height, gridSize = 2)
        assertEquals(0, grid[0])     // 左上
        assertEquals(0, grid[1])     // 右上
        assertEquals(255, grid[2])   // 左下
        assertEquals(255, grid[3])   // 右下
    }

    @Test
    fun gridSignature_degenerateInputsReturnZeros() {
        // 退化输入必须返回**空数组**（而不是"全 0 网格"）：见下面那条用例——全 0 网格会被
        // meanAbsDiff 判成"完全静止"，坏帧反而能通过稳定门槛。
        assertEquals(0, QuickMenuScanFrame.gridSignature(ByteArray(0), 0, 0).size)
        assertEquals(0, QuickMenuScanFrame.gridSignature(ByteArray(10), 4, 4).size)
        assertEquals(0, QuickMenuScanFrame.gridSignature(ByteArray(16), 4, 4, gridSize = 0).size)
    }

    @Test
    fun gridSignature_degenerateInput_isTreatedAsUnstable() {
        // 这条用例保护的是"坏帧绝不能通过稳定门槛"这个安全属性。
        val bad = QuickMenuScanFrame.gridSignature(ByteArray(0), 0, 0)
        val good = QuickMenuScanFrame.gridSignature(ByteArray(64) { 100 }, 8, 8)
        assertEquals(Float.MAX_VALUE, QuickMenuScanFrame.meanAbsDiff(bad, good), 0f)
        assertEquals(Float.MAX_VALUE, QuickMenuScanFrame.meanAbsDiff(bad, bad), 0f)
    }

    @Test
    fun meanAbsDiff_zeroForIdenticalSignatures() {
        val grid = intArrayOf(10, 20, 30, 40)
        assertEquals(0f, QuickMenuScanFrame.meanAbsDiff(grid, grid), 1e-6f)
    }

    @Test
    fun meanAbsDiff_averagesOverCells() {
        assertEquals(
            10f,
            QuickMenuScanFrame.meanAbsDiff(intArrayOf(0, 0, 0, 0), intArrayOf(10, 10, 10, 10)),
            1e-6f,
        )
        assertEquals(
            5f,
            QuickMenuScanFrame.meanAbsDiff(intArrayOf(0, 0, 20, 0), intArrayOf(0, 0, 0, 0)),
            1e-6f,
        )
    }

    @Test
    fun meanAbsDiff_mismatchedOrEmpty_isTreatedAsUnstable() {
        // 判不了就当作"不稳"：宁可多等一帧，也不要在没对准时触发。
        assertEquals(
            Float.MAX_VALUE,
            QuickMenuScanFrame.meanAbsDiff(intArrayOf(1, 2), intArrayOf(1, 2, 3)),
            0f,
        )
        assertEquals(Float.MAX_VALUE, QuickMenuScanFrame.meanAbsDiff(IntArray(0), intArrayOf(1)), 0f)
    }
}
