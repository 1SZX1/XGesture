package com.slideindex.app.overlay.quickmenu

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「最近使用」序列化规则的测试。
 *
 * 用 Robolectric 是因为 [QuickMenuTool] 的枚举项持有 Compose 的 `ImageVector`，
 * 静态初始化会碰到 Android 侧类；这些用例本身是纯逻辑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class QuickMenuRecentsCodecTest {

    private val unit = QuickMenuTool.UNIT_CONVERTER
    private val qr = QuickMenuTool.QR_GENERATOR
    private val password = QuickMenuTool.PASSWORD
    private val stopwatch = QuickMenuTool.STOPWATCH
    private val currency = QuickMenuTool.CURRENCY_CONVERTER
    private val scan = QuickMenuTool.SCAN_DECODER

    // ---------- encode ----------

    @Test
    fun encode_usesEnumNamesNotOrdinals() {
        assertEquals(listOf("UNIT_CONVERTER", "QR_GENERATOR"), QuickMenuRecentsCodec.encode(listOf(unit, qr)))
    }

    // ---------- decode ----------

    @Test
    fun decode_keepsKnownNamesInOrder() {
        assertEquals(
            listOf(unit, stopwatch),
            QuickMenuRecentsCodec.decode(listOf("UNIT_CONVERTER", "STOPWATCH")),
        )
    }

    @Test
    fun decode_dropsUnknownNames() {
        // 工具被删除或改名后，旧数据不能让面板打不开。
        assertEquals(
            listOf(qr),
            QuickMenuRecentsCodec.decode(listOf("SOME_REMOVED_TOOL", "QR_GENERATOR", "ANOTHER_GONE")),
        )
    }

    @Test
    fun decode_allUnknown_returnsEmpty() {
        assertTrue(QuickMenuRecentsCodec.decode(listOf("X", "Y")).isEmpty())
    }

    @Test
    fun decode_empty_returnsEmpty() {
        assertTrue(QuickMenuRecentsCodec.decode(emptyList()).isEmpty())
    }

    @Test
    fun decode_deduplicatesWhileKeepingFirstOccurrence() {
        assertEquals(
            listOf(qr, unit),
            QuickMenuRecentsCodec.decode(listOf("QR_GENERATOR", "UNIT_CONVERTER", "QR_GENERATOR")),
        )
    }

    @Test
    fun decode_truncatesToMaxEntries() {
        val names = listOf(
            "UNIT_CONVERTER",
            "CURRENCY_CONVERTER",
            "QR_GENERATOR",
            "SCAN_DECODER",
            "PASSWORD",
            "STOPWATCH",
        )
        assertEquals(QuickMenuRecentsCodec.MAX_ENTRIES, QuickMenuRecentsCodec.decode(names).size)
    }

    // ---------- record ----------

    @Test
    fun record_putsNewToolFirst() {
        assertEquals(
            listOf(password, unit),
            QuickMenuRecentsCodec.record(listOf(unit), password),
        )
    }

    @Test
    fun record_existingTool_movesToFrontWithoutDuplicating() {
        assertEquals(
            listOf(unit, stopwatch),
            QuickMenuRecentsCodec.record(listOf(stopwatch, unit), unit),
        )
    }

    @Test
    fun record_beyondMax_evictsOldest() {
        val current = listOf(stopwatch, password, scan, currency)
        val result = QuickMenuRecentsCodec.record(current, unit)
        assertEquals(QuickMenuRecentsCodec.MAX_ENTRIES, result.size)
        assertEquals(unit, result.first())
        // 最旧的（列表末尾的 currency）应被挤掉
        assertTrue(currency !in result)
    }

    @Test
    fun record_fromEmpty_yieldsSingleEntry() {
        assertEquals(listOf(unit), QuickMenuRecentsCodec.record(emptyList(), unit))
    }

    // ---------- 往返 ----------

    @Test
    fun encodeThenDecode_isStable() {
        val tools = listOf(stopwatch, unit, qr)
        assertEquals(tools, QuickMenuRecentsCodec.decode(QuickMenuRecentsCodec.encode(tools)))
    }
}
