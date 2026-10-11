package com.slideindex.app.barcode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「把定位点收成图上标记的框」的测试。
 *
 * 这一层是"画在图上"最容易出错的地方：二维码给 3~4 个定位点（能围出框），一维条码只给 2 个端点
 * （高度为 0、画不出可点的标记），所以最小尺寸兜底必须有效；同时兜底不能把框推出图片外。
 */
class BarcodeScanResultTest {

    private fun point(x: Float, y: Float) = BarcodePoint(x, y)

    @Test
    fun fewerThanTwoPoints_hasNoBox() {
        assertNull(emptyList<BarcodePoint>().boundingBoxOrNull())
        assertNull(listOf(point(0.5f, 0.5f)).boundingBoxOrNull())
    }

    @Test
    fun threeFinderPoints_produceBoxFromExtremes() {
        val box = listOf(point(0.2f, 0.3f), point(0.5f, 0.3f), point(0.2f, 0.6f))
            .boundingBoxOrNull()
        assertEquals(0.2f, box!!.left, 1e-6f)
        assertEquals(0.3f, box.top, 1e-6f)
        assertEquals(0.5f, box.right, 1e-6f)
        assertEquals(0.6f, box.bottom, 1e-6f)
    }

    @Test
    fun oneDimensionalTwoPoints_getMinimumSizeInBothDirections() {
        // 一维条码：两个端点在同一条水平线上 → 原始框高度为 0，必须被兜底撑开，否则不可点。
        val box = listOf(point(0.2f, 0.5f), point(0.8f, 0.5f)).boundingBoxOrNull(minSize = 0.1f)!!
        assertEquals(0.2f, box.left, 1e-6f)
        assertEquals(0.8f, box.right, 1e-6f)
        assertEquals(0.1f, box.bottom - box.top, 1e-6f)
        // 以原中心（0.5）向两边补足
        assertEquals(0.45f, box.top, 1e-6f)
        assertEquals(0.55f, box.bottom, 1e-6f)
    }

    @Test
    fun minimumSize_doesNotShrinkAlreadyLargeBox() {
        val box = listOf(point(0.1f, 0.1f), point(0.9f, 0.9f)).boundingBoxOrNull(minSize = 0.2f)!!
        assertEquals(0.1f, box.left, 1e-6f)
        assertEquals(0.9f, box.right, 1e-6f)
    }

    @Test
    fun minimumSize_appliesPerAxisIndependently() {
        // 宽够大、高为 0：只该补高度。
        val box = listOf(point(0.1f, 0.5f), point(0.9f, 0.5f)).boundingBoxOrNull(minSize = 0.2f)!!
        assertEquals(0.1f, box.left, 1e-6f)
        assertEquals(0.9f, box.right, 1e-6f)
        assertEquals(0.2f, box.bottom - box.top, 1e-6f)
    }

    @Test
    fun minimumSize_isClampedToImageBounds() {
        // 码贴在上边缘：补足尺寸会把框推到图外，于是**逐边**夹回 0..1。
        // 代价是这里的框比 minSize 矮一半（0.1 而非 0.2），但绝不会画到图外——这是有意的取舍：
        // 画到图片外的标记既看不见也点不到。
        val box = listOf(point(0.5f, 0.0f), point(0.5f, 0.0f)).boundingBoxOrNull(minSize = 0.2f)!!
        assertEquals(0.4f, box.left, 1e-6f)
        assertEquals(0.6f, box.right, 1e-6f)
        assertEquals(0f, box.top, 1e-6f)
        assertEquals(0.1f, box.bottom, 1e-6f)
    }

    @Test
    fun zeroMinimumSize_keepsDegenerateBox() {
        // 不传 minSize 时保持原样（纯提取，不做兜底）——兜底是界面的选择。
        val box = listOf(point(0.2f, 0.5f), point(0.8f, 0.5f)).boundingBoxOrNull()!!
        assertEquals(0f, box.bottom - box.top, 1e-6f)
    }

    @Test
    fun pointsAreNotMutated() {
        val points = listOf(point(0.2f, 0.5f), point(0.8f, 0.5f))
        points.boundingBoxOrNull(minSize = 0.5f)
        assertEquals(0.2f, points[0].x, 1e-6f)
        assertEquals(0.5f, points[0].y, 1e-6f)
    }
}
