package com.slideindex.app.quickmenu

import com.slideindex.app.quickmenu.UnitConversionEngine.Category
import com.slideindex.app.quickmenu.UnitConversionEngine.UnitDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitConversionEngineTest {

    // ---------- 结构 ----------

    @Test
    fun units_everyCategory_hasAtLeastTwoUnits() {
        for ((category, list) in UnitConversionEngine.units) {
            assertTrue("$category 至少要有两个单位", list.size >= 2)
        }
    }

    @Test
    fun units_unitIds_areGloballyUnique() {
        val ids = UnitConversionEngine.units.values.flatten().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun categoryOf_knownUnit_returnsItsCategory() {
        assertEquals(Category.LENGTH, UnitConversionEngine.categoryOf("km"))
        assertEquals(Category.TEMPERATURE, UnitConversionEngine.categoryOf("fahrenheit"))
        assertEquals(Category.DATA, UnitConversionEngine.categoryOf("gib"))
    }

    @Test
    fun unitOf_unknownId_returnsNull() {
        assertNull(UnitConversionEngine.unitOf("furlong"))
    }

    // ---------- 长度 ----------

    @Test
    fun convert_length_kilometreToMetre() {
        assertConvert(1000.0, 1.0, "km", "m")
    }

    @Test
    fun convert_length_mileToKilometre() {
        assertConvert(1.609344, 1.0, "mi", "km")
    }

    @Test
    fun convert_length_inchToCentimetre() {
        assertConvert(2.54, 1.0, "in", "cm")
    }

    @Test
    fun convert_length_footToMetre() {
        assertConvert(0.3048, 1.0, "ft", "m")
    }

    // ---------- 重量 ----------

    @Test
    fun convert_weight_poundToKilogram() {
        assertConvert(0.45359237, 1.0, "lb", "kg")
    }

    @Test
    fun convert_weight_kilogramToPound() {
        assertConvert(1.0 / 0.45359237, 1.0, "kg", "lb")
    }

    @Test
    fun convert_weight_ounceToGram() {
        assertConvert(28.349523125, 1.0, "oz", "g")
    }

    // ---------- 温度（仿射换算） ----------

    @Test
    fun convert_temperature_freezingPoint() {
        assertConvert(32.0, 0.0, "celsius", "fahrenheit")
    }

    @Test
    fun convert_temperature_boilingPoint() {
        assertConvert(212.0, 100.0, "celsius", "fahrenheit")
    }

    @Test
    fun convert_temperature_fahrenheitToCelsius() {
        assertConvert(0.0, 32.0, "fahrenheit", "celsius")
    }

    @Test
    fun convert_temperature_minusForty_matchesInBothScales() {
        assertConvert(-40.0, -40.0, "celsius", "fahrenheit")
        assertConvert(-40.0, -40.0, "fahrenheit", "celsius")
    }

    @Test
    fun convert_temperature_celsiusToKelvin() {
        assertConvert(273.15, 0.0, "celsius", "kelvin")
    }

    @Test
    fun convert_temperature_kelvinToCelsius() {
        assertConvert(26.85, 300.0, "kelvin", "celsius")
    }

    // ---------- 面积 / 容积 ----------

    @Test
    fun convert_area_hectareToSquareMetre() {
        assertConvert(10000.0, 1.0, "hectare", "sqm")
    }

    @Test
    fun convert_area_acreToSquareMetre() {
        assertConvert(4046.8564224, 1.0, "acre", "sqm")
    }

    @Test
    fun convert_area_squareFootToSquareMetre() {
        assertConvert(0.09290304, 1.0, "sqft", "sqm")
    }

    @Test
    fun convert_volume_usGallonToLitre() {
        assertConvert(3.785411784, 1.0, "gal_us", "l")
    }

    @Test
    fun convert_volume_usCupToMillilitre() {
        assertConvert(236.5882365, 1.0, "cup_us", "ml")
    }

    @Test
    fun convert_volume_usFluidOunceToMillilitre() {
        assertConvert(29.5735295625, 1.0, "floz_us", "ml")
    }

    // ---------- 速度 ----------

    @Test
    fun convert_speed_metrePerSecondToKilometrePerHour() {
        assertConvert(3.6, 1.0, "mps", "kph")
    }

    @Test
    fun convert_speed_knotToKilometrePerHour() {
        assertConvert(1.852, 1.0, "knot", "kph")
    }

    @Test
    fun convert_speed_milePerHourToKilometrePerHour() {
        assertConvert(96.56064, 60.0, "mph", "kph")
    }

    @Test
    fun convert_speed_footPerSecondToKilometrePerHour() {
        assertConvert(1.09728, 1.0, "fps", "kph")
    }

    // ---------- 数据（1024 进制） ----------

    @Test
    fun convert_data_kibibyteToByte() {
        assertConvert(1024.0, 1.0, "kib", "byte")
    }

    @Test
    fun convert_data_gibibyteToMebibyte() {
        assertConvert(1024.0, 1.0, "gib", "mib")
    }

    @Test
    fun convert_data_tebibyteToGibibyte() {
        assertConvert(1024.0, 1.0, "tib", "gib")
    }

    // ---------- 边界 ----------

    @Test
    fun convert_sameUnit_returnsValueUnchanged() {
        assertConvert(42.5, 42.5, "km", "km")
    }

    @Test
    fun convert_zeroValue_returnsZeroForLinearCategories() {
        assertConvert(0.0, 0.0, "m", "km")
        assertConvert(0.0, 0.0, "lb", "kg")
    }

    @Test
    fun convert_temperature_zeroIsNotZero_acrossScales() {
        // 温度是仿射换算：0 °C ≠ 0 K，这条用来锁住偏移不被误当成纯系数。
        assertConvert(273.15, 0.0, "celsius", "kelvin")
        assertConvert(-273.15, 0.0, "kelvin", "celsius")
    }

    @Test
    fun convert_crossCategory_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            UnitConversionEngine.convert(1.0, unit("m"), unit("kg"))
        }
    }

    // ---------- 结果格式化 ----------

    @Test
    fun format_integerValue_doesNotUseScientificNotation() {
        assertEquals("100", UnitConversionEngine.format(100.0))
    }

    @Test
    fun format_largeValue_doesNotUseScientificNotation() {
        assertEquals("1000000000000000", UnitConversionEngine.format(1e15))
    }

    @Test
    fun format_trailingZeros_areRemoved() {
        assertEquals("2.5", UnitConversionEngine.format(2.5))
        assertEquals("3", UnitConversionEngine.format(3.0))
    }

    @Test
    fun format_repeatingDecimal_respectsDefaultDecimals() {
        assertEquals("0.333333", UnitConversionEngine.format(1.0 / 3.0))
    }

    @Test
    fun format_customMaxDecimals_isHonoured() {
        assertEquals("0.33", UnitConversionEngine.format(1.0 / 3.0, maxDecimals = 2))
    }

    @Test
    fun format_floatingPointNoise_isRoundedAway() {
        assertEquals("0.3", UnitConversionEngine.format(0.1 + 0.2))
    }

    @Test
    fun format_nonFiniteValues_returnEmpty() {
        assertEquals("", UnitConversionEngine.format(Double.NaN))
        assertEquals("", UnitConversionEngine.format(Double.POSITIVE_INFINITY))
        assertEquals("", UnitConversionEngine.format(Double.NEGATIVE_INFINITY))
    }

    private fun assertConvert(expected: Double, value: Double, fromId: String, toId: String) {
        val actual = UnitConversionEngine.convert(value, unit(fromId), unit(toId))
        assertEquals("$value $fromId -> $toId", expected, actual, 1e-9)
    }

    private fun unit(id: String): UnitDef =
        requireNotNull(UnitConversionEngine.unitOf(id)) { "unknown unit: $id" }
}
