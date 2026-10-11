package com.slideindex.app.quickmenu

import com.slideindex.app.quickmenu.CurrencyRateEngine.Table
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrencyRateEngineTest {

    private val offline = CurrencyRateEngine.offlineTable

    // ---------- 结构一致性 ----------

    @Test
    fun currencies_everySupportedCode_hasOfflineRate() {
        val missing = CurrencyRateEngine.currencies.filterNot { offline.rates.containsKey(it) }
        assertTrue("以下币种缺少离线汇率: $missing", missing.isEmpty())
    }

    @Test
    fun offlineRates_haveNoOrphanCode() {
        val orphans = offline.rates.keys - CurrencyRateEngine.currencies.toSet()
        assertTrue("离线汇率表里有未列入 currencies 的币种: $orphans", orphans.isEmpty())
    }

    @Test
    fun offlineRates_baseUsd_isExactlyOne() {
        assertEquals(1.0, offline.rates.getValue("USD"), 0.0)
    }

    @Test
    fun offlineTable_isNotLive() {
        assertFalse(offline.live)
        assertNull(offline.source)
    }

    // ---------- 换算 ----------

    @Test
    fun convert_sameCurrency_returnsAmountUnchanged() {
        assertEquals(123.45, requireNotNull(CurrencyRateEngine.convert(123.45, "CNY", "CNY")), 1e-9)
    }

    @Test
    fun convert_usdToCny_usesOfflineRate() {
        assertEquals(720.0, requireNotNull(CurrencyRateEngine.convert(100.0, "USD", "CNY")), 1e-9)
    }

    @Test
    fun convert_cnyToUsd_isInverseOfUsdToCny() {
        assertEquals(100.0, requireNotNull(CurrencyRateEngine.convert(720.0, "CNY", "USD")), 1e-9)
    }

    @Test
    fun convert_crossCurrency_goesThroughUsdBase() {
        // 100 CNY → USD(100/7.2) → EUR(*0.92)
        val expected = 100.0 / 7.20 * 0.92
        assertEquals(expected, requireNotNull(CurrencyRateEngine.convert(100.0, "CNY", "EUR")), 1e-9)
    }

    @Test
    fun convert_unknownCurrency_returnsNull() {
        assertNull(CurrencyRateEngine.convert(1.0, "CNY", "XYZ"))
        assertNull(CurrencyRateEngine.convert(1.0, "XYZ", "CNY"))
    }

    @Test
    fun convert_zeroOrNegativeRate_returnsNull() {
        val zeroTarget = Table(rates = mapOf("USD" to 1.0, "BAD" to 0.0), live = true)
        assertNull(CurrencyRateEngine.convert(1.0, "USD", "BAD", zeroTarget))

        val negativeSource = Table(rates = mapOf("BAD" to -2.0, "USD" to 1.0), live = true)
        assertNull(CurrencyRateEngine.convert(1.0, "BAD", "USD", negativeSource))
    }

    @Test
    fun convert_honoursProvidedLiveTable() {
        val live = Table(rates = mapOf("USD" to 1.0, "CNY" to 8.0), live = true, source = "test")
        assertEquals(800.0, requireNotNull(CurrencyRateEngine.convert(100.0, "USD", "CNY", live)), 1e-9)
    }

    // ---------- 汇率提示 ----------

    @Test
    fun rateHint_stripsTrailingZeros() {
        assertEquals("1 USD = 7.2 CNY", CurrencyRateEngine.rateHint("USD", "CNY"))
    }

    @Test
    fun rateHint_unknownCurrency_returnsNull() {
        assertNull(CurrencyRateEngine.rateHint("USD", "XYZ"))
    }

    // ---------- 报文解析（三种在线源的字段布局） ----------

    @Test
    fun parseRates_openErApiShape() {
        val body = """{"result":"success","base_code":"USD","rates":{"CNY":7.25,"EUR":0.93}}"""
        val rates = CurrencyRateEngine.parseRates(body)
        assertEquals(7.25, rates.getValue("CNY"), 1e-9)
        assertEquals(0.93, rates.getValue("EUR"), 1e-9)
    }

    @Test
    fun parseRates_frankfurterShape() {
        val body = """{"amount":1.0,"base":"USD","date":"2026-01-01","rates":{"CNY":7.31,"JPY":151.2}}"""
        val rates = CurrencyRateEngine.parseRates(body)
        assertEquals(7.31, rates.getValue("CNY"), 1e-9)
        assertEquals(151.2, rates.getValue("JPY"), 1e-9)
    }

    @Test
    fun parseRates_currencyApiLowercaseKeys_areUppercased() {
        val body = """{"date":"2026-01-01","usd":{"cny":7.28,"eur":0.94}}"""
        val rates = CurrencyRateEngine.parseRates(body)
        assertEquals(7.28, rates.getValue("CNY"), 1e-9)
        assertEquals(0.94, rates.getValue("EUR"), 1e-9)
    }

    @Test
    fun parseRates_skipsInvalidValues() {
        val body = """{"rates":{"CNY":7.2,"BAD":"oops","ZERO":0,"NEG":-1}}"""
        val rates = CurrencyRateEngine.parseRates(body)
        assertEquals(setOf("CNY"), rates.keys)
    }

    @Test
    fun parseRates_malformedJson_returnsEmpty() {
        assertTrue(CurrencyRateEngine.parseRates("not json at all").isEmpty())
        assertTrue(CurrencyRateEngine.parseRates("").isEmpty())
    }

    @Test
    fun parseRates_unrecognizedShape_returnsEmpty() {
        assertTrue(CurrencyRateEngine.parseRates("""{"foo":{"bar":1}}""").isEmpty())
        assertTrue(CurrencyRateEngine.parseRates("""[1,2,3]""").isEmpty())
    }
}
