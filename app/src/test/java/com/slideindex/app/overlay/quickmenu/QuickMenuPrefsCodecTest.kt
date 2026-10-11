package com.slideindex.app.overlay.quickmenu

import com.slideindex.app.quickmenu.CurrencyRateEngine
import com.slideindex.app.quickmenu.PasswordGenerator
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「快捷菜单」偏好（记住上次选择）的收敛规则与存储格式测试。
 *
 * 用 Robolectric：同文件里还有基于 DataStore 的 [QuickMenuPreferencesStore]，这些用例本身是纯逻辑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class QuickMenuPrefsCodecTest {

    // ---------- 默认值 ----------

    @Test
    fun defaults_areSensible() {
        val prefs = QuickMenuPrefs()
        assertEquals(16, prefs.passwordLength)
        assertTrue(prefs.passwordDigits)
        assertTrue(!prefs.passwordSymbols)
        assertTrue(!prefs.passwordAvoidAmbiguous)
        assertEquals("USD", prefs.currencyFrom)
        assertEquals("CNY", prefs.currencyTo)
    }

    // ---------- 密码长度 ----------

    @Test
    fun sanitize_clampsPasswordLengthIntoEngineRange() {
        assertEquals(
            PasswordGenerator.MIN_LENGTH,
            QuickMenuPrefsCodec.sanitize(QuickMenuPrefs(passwordLength = 0)).passwordLength,
        )
        assertEquals(
            PasswordGenerator.MIN_LENGTH,
            QuickMenuPrefsCodec.sanitize(QuickMenuPrefs(passwordLength = -5)).passwordLength,
        )
        assertEquals(
            PasswordGenerator.MAX_LENGTH,
            QuickMenuPrefsCodec.sanitize(QuickMenuPrefs(passwordLength = 9999)).passwordLength,
        )
        assertEquals(
            24,
            QuickMenuPrefsCodec.sanitize(QuickMenuPrefs(passwordLength = 24)).passwordLength,
        )
    }

    // ---------- 货币 ----------

    @Test
    fun sanitize_keepsKnownCurrencyCodes() {
        val prefs = QuickMenuPrefsCodec.sanitize(
            QuickMenuPrefs(currencyFrom = "JPY", currencyTo = "EUR"),
        )
        assertEquals("JPY", prefs.currencyFrom)
        assertEquals("EUR", prefs.currencyTo)
    }

    @Test
    fun sanitize_replacesUnknownCurrencyCodesWithDefaults() {
        val prefs = QuickMenuPrefsCodec.sanitize(
            QuickMenuPrefs(currencyFrom = "XXX", currencyTo = ""),
        )
        assertEquals("USD", prefs.currencyFrom)
        assertEquals("CNY", prefs.currencyTo)
    }

    @Test
    fun sanitize_replacesNullCurrencyCodesWithDefaults() {
        val prefs = QuickMenuPrefsCodec.sanitize(QuickMenuPrefs(currencyFrom = null, currencyTo = null))
        assertEquals("USD", prefs.currencyFrom)
        assertEquals("CNY", prefs.currencyTo)
    }

    @Test
    fun sanitize_usesEveryRealCurrencyCodeAsIs() {
        // 引擎里的每个代码都必须原样保留，否则"记住了却用不上"。
        for (code in CurrencyRateEngine.currencies) {
            assertEquals(code, QuickMenuPrefsCodec.sanitize(QuickMenuPrefs(currencyFrom = code)).currencyFrom)
        }
    }

    // ---------- 其余字段不做判断（由界面按类别兜底） ----------

    @Test
    fun sanitize_leavesUnitAndTextModeUntouched() {
        val prefs = QuickMenuPrefsCodec.sanitize(
            QuickMenuPrefs(unitCategory = "NOT_A_CATEGORY", unitFromId = "nope", textToolMode = "NOPE"),
        )
        assertEquals("NOT_A_CATEGORY", prefs.unitCategory)
        assertEquals("nope", prefs.unitFromId)
        assertEquals("NOPE", prefs.textToolMode)
    }

    // ---------- 存储格式 ----------

    @Test
    fun prefs_surviveJsonRoundTrip() {
        val prefs = QuickMenuPrefs(
            passwordLength = 24,
            passwordDigits = false,
            passwordSymbols = true,
            passwordAvoidAmbiguous = true,
            unitCategory = "WEIGHT",
            unitFromId = "kg",
            unitToId = "jin",
            currencyFrom = "JPY",
            currencyTo = "USD",
            textToolMode = "AES",
        )
        val restored = Json.decodeFromString<QuickMenuPrefs>(Json.encodeToString(prefs))
        assertEquals(prefs, restored)
    }

    @Test
    fun prefs_defaults_surviveJsonRoundTrip() {
        val restored = Json.decodeFromString<QuickMenuPrefs>(Json.encodeToString(QuickMenuPrefs()))
        assertEquals(QuickMenuPrefs(), restored)
    }

    @Test
    fun decode_toleratesUnknownKeys_soOlderBuildsKeepWorking() {
        // 以后加了字段，旧版本读新数据也不该整份失败（Store 用的就是 ignoreUnknownKeys 的 Json）。
        val json = """{"passwordLength":20,"someFutureField":123}"""
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<QuickMenuPrefs>(json)
        assertEquals(20, decoded.passwordLength)
    }
}
