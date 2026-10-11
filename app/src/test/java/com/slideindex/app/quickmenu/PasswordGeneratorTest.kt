package com.slideindex.app.quickmenu

import com.slideindex.app.quickmenu.PasswordGenerator.Options
import com.slideindex.app.quickmenu.PasswordGenerator.Strength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PasswordGeneratorTest {

    private val defaults = Options()

    // ---------- 长度 ----------

    @Test
    fun generate_returnsRequestedLength() {
        assertEquals(16, PasswordGenerator.generate(defaults, Random(1)).length)
        assertEquals(32, PasswordGenerator.generate(defaults.copy(length = 32), Random(1)).length)
    }

    @Test
    fun generate_lengthBelowMinimum_isClampedUp() {
        assertEquals(
            PasswordGenerator.MIN_LENGTH,
            PasswordGenerator.generate(defaults.copy(length = 1), Random(1)).length,
        )
    }

    @Test
    fun generate_lengthAboveMaximum_isClampedDown() {
        assertEquals(
            PasswordGenerator.MAX_LENGTH,
            PasswordGenerator.generate(defaults.copy(length = 999), Random(1)).length,
        )
    }

    // ---------- 字符类保证（参考实现缺的那一环） ----------

    @Test
    fun generate_containsEveryEnabledClass() {
        repeat(30) { seed ->
            val password = PasswordGenerator.generate(defaults, Random(seed))
            assertTrue("seed=$seed 缺大写: $password", password.any { it.isUpperCase() })
            assertTrue("seed=$seed 缺小写: $password", password.any { it.isLowerCase() })
            assertTrue("seed=$seed 缺数字: $password", password.any { it.isDigit() })
        }
    }

    @Test
    fun generate_whenLengthEqualsClassCount_stillCoversEveryClass() {
        val options = Options(length = 4, uppercase = true, lowercase = true, digits = true, symbols = true)
        repeat(30) { seed ->
            val password = PasswordGenerator.generate(options, Random(seed))
            assertEquals(4, password.length)
            assertTrue(password.any { it.isUpperCase() })
            assertTrue(password.any { it.isLowerCase() })
            assertTrue(password.any { it.isDigit() })
            assertTrue(password.any { !it.isLetterOrDigit() })
        }
    }

    @Test
    fun generate_symbolsDisabled_producesAlphanumericOnly() {
        repeat(20) { seed ->
            val password = PasswordGenerator.generate(defaults, Random(seed))
            assertTrue("seed=$seed 出现了非字母数字: $password", password.all { it.isLetterOrDigit() })
        }
    }

    @Test
    fun generate_disabledClasses_areAbsent() {
        val options = Options(length = 24, uppercase = false, digits = false)
        repeat(20) { seed ->
            val password = PasswordGenerator.generate(options, Random(seed))
            assertTrue(password.all { it.isLowerCase() })
        }
    }

    @Test
    fun generate_noCharacterClassEnabled_returnsEmpty() {
        val options = Options(uppercase = false, lowercase = false, digits = false, symbols = false)
        assertEquals("", PasswordGenerator.generate(options, Random(1)))
    }

    @Test
    fun generate_avoidAmbiguous_excludesLookAlikeCharacters() {
        val options = defaults.copy(length = 32, avoidAmbiguous = true)
        val forbidden = "0Oo1lI5S2Z8B".toSet()
        repeat(20) { seed ->
            val password = PasswordGenerator.generate(options, Random(seed))
            val hits = password.filter { it in forbidden }
            assertTrue("seed=$seed 出现形近字符 $hits: $password", hits.isEmpty())
        }
    }

    // ---------- 随机性 ----------

    @Test
    fun generate_sameSeed_isReproducible() {
        assertEquals(
            PasswordGenerator.generate(defaults, Random(42)),
            PasswordGenerator.generate(defaults, Random(42)),
        )
    }

    @Test
    fun generate_differentSeeds_produceDifferentPasswords() {
        assertNotEquals(
            PasswordGenerator.generate(defaults, Random(1)),
            PasswordGenerator.generate(defaults, Random(2)),
        )
    }

    // ---------- 熵与强度 ----------

    @Test
    fun poolSize_disablingClasses_shrinksPool() {
        val full = PasswordGenerator.poolSize(defaults)
        val lowercaseOnly = PasswordGenerator.poolSize(defaults.copy(uppercase = false, digits = false))
        assertTrue(full > lowercaseOnly)
        assertEquals(26, lowercaseOnly)
    }

    @Test
    fun poolSize_enablingSymbols_growsPool() {
        assertTrue(PasswordGenerator.poolSize(defaults.copy(symbols = true)) > PasswordGenerator.poolSize(defaults))
    }

    @Test
    fun poolSize_noClass_isZero() {
        val none = Options(uppercase = false, lowercase = false, digits = false, symbols = false)
        assertEquals(0, PasswordGenerator.poolSize(none))
        assertEquals(0.0, PasswordGenerator.entropyBits(none), 0.0)
    }

    @Test
    fun entropyBits_growsWithLength() {
        val short = PasswordGenerator.entropyBits(defaults.copy(length = 8))
        val long = PasswordGenerator.entropyBits(defaults.copy(length = 24))
        assertTrue(long > short)
    }

    @Test
    fun strengthOf_followsEntropyThresholds() {
        // 小写 8 位 ≈ 37.6 bit
        assertEquals(
            Strength.WEAK,
            PasswordGenerator.strengthOf(defaults.copy(length = 8, uppercase = false, digits = false)),
        )
        // 全类 10 位 ≈ 59.5 bit
        assertEquals(Strength.FAIR, PasswordGenerator.strengthOf(defaults.copy(length = 10)))
        // 全类 13 位 ≈ 77.4 bit
        assertEquals(Strength.STRONG, PasswordGenerator.strengthOf(defaults.copy(length = 13)))
        // 全类 16 位 ≈ 95.3 bit
        assertEquals(Strength.VERY_STRONG, PasswordGenerator.strengthOf(defaults.copy(length = 16)))
    }

    @Test
    fun options_hasNoCharacterClass_isTrueOnlyWhenAllDisabled() {
        assertFalse(defaults.hasNoCharacterClass)
        assertTrue(Options(uppercase = false, lowercase = false, digits = false, symbols = false).hasNoCharacterClass)
    }
}
