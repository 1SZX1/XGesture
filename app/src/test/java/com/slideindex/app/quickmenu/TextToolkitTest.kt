package com.slideindex.app.quickmenu

import com.slideindex.app.quickmenu.TextToolkit.AesResult
import com.slideindex.app.quickmenu.TextToolkit.DecodeResult
import com.slideindex.app.quickmenu.TextToolkit.RegexResult
import com.slideindex.app.quickmenu.TextToolkit.TextCompareResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextToolkitTest {

    // ---------------------------------------------------------------- URL

    @Test
    fun urlEncode_escapesSpaceAndReservedChars() {
        assertEquals("a+b%26c%3Dd", TextToolkit.urlEncode("a b&c=d"))
    }

    @Test
    fun urlDecode_reversesEncoding() {
        assertEquals(DecodeResult.Ok("a b&c=d"), TextToolkit.urlDecode("a+b%26c%3Dd"))
    }

    @Test
    fun urlDecode_incompleteEscape_returnsMalformed() {
        assertEquals(DecodeResult.Malformed, TextToolkit.urlDecode("abc%"))
        assertEquals(DecodeResult.Malformed, TextToolkit.urlDecode("%zz"))
    }

    @Test
    fun urlEncodeDecode_roundTripsCjkAndEmoji() {
        val text = "中文 空格 & 符号 🚀"
        assertEquals(DecodeResult.Ok(text), TextToolkit.urlDecode(TextToolkit.urlEncode(text)))
    }

    // ---------------------------------------------------------------- Base64

    @Test
    fun base64Encode_knownValue() {
        assertEquals("aGVsbG8=", TextToolkit.base64Encode("hello"))
    }

    @Test
    fun base64Decode_reversesEncoding() {
        assertEquals(DecodeResult.Ok("hello"), TextToolkit.base64Decode("aGVsbG8="))
    }

    @Test
    fun base64Decode_toleratesSurroundingWhitespace() {
        assertEquals(DecodeResult.Ok("hello"), TextToolkit.base64Decode("  aGVsbG8= \n"))
    }

    @Test
    fun base64Decode_illegalCharacters_returnsMalformed() {
        assertEquals(DecodeResult.Malformed, TextToolkit.base64Decode("!!!not-base64!!!"))
    }

    @Test
    fun base64Decode_binaryPayload_returnsNotText() {
        // 0xFF 在 UTF-8 里永远非法：解出来回读不一致 → 判定为"不是文本"而不是塞一堆替换字符。
        val binary = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0xFD.toByte())
        val encoded = java.util.Base64.getEncoder().encodeToString(binary)
        assertEquals(DecodeResult.NotText, TextToolkit.base64Decode(encoded))
    }

    @Test
    fun base64EncodeDecode_roundTripsCjk() {
        val text = "中文文本 base64 往返"
        assertEquals(DecodeResult.Ok(text), TextToolkit.base64Decode(TextToolkit.base64Encode(text)))
    }

    // ---------------------------------------------------------------- 文本对比

    @Test
    fun compare_identicalTexts_isIdentical() {
        assertEquals(TextCompareResult.Identical, TextToolkit.compareText("哈哈啊哈", "哈哈啊哈"))
        assertEquals(
            TextCompareResult.Identical,
            TextToolkit.compareText("a\nb", "a\nb"),
        )
    }

    @Test
    fun compare_emptySide_isReportedInsteadOfClaimingIdentical() {
        assertEquals(TextCompareResult.EmptySide, TextToolkit.compareText("", "abc"))
        assertEquals(TextCompareResult.EmptySide, TextToolkit.compareText("abc", ""))
        assertEquals(TextCompareResult.EmptySide, TextToolkit.compareText("", ""))
    }

    @Test
    fun compare_pointsAtFirstDifferingCharacter() {
        val result = TextToolkit.compareText("abcdef", "abcXef") as TextCompareResult.Different
        assertEquals(3, result.index)
        assertEquals(0, result.lengthDelta)
        assertFalse(result.identicalIgnoringWhitespace)
        assertFalse(result.identicalIgnoringCase)
    }

    @Test
    fun compare_reportsLengthDeltaForBothDirections() {
        val shorterB = TextToolkit.compareText("abcdef", "abc") as TextCompareResult.Different
        assertEquals(3, shorterB.index)
        assertEquals(3, shorterB.lengthDelta)

        val longerB = TextToolkit.compareText("abc", "abcdef") as TextCompareResult.Different
        assertEquals(3, longerB.index)
        assertEquals(-3, longerB.lengthDelta)
    }

    @Test
    fun compare_whitespaceOnlyDifference_flagsIt() {
        // "看着一样其实尾部多个换行"——复制粘贴最常见的坑，这条提示是纯对比比哈希强的地方。
        val trailingNewline = TextToolkit.compareText("abc", "abc\n") as TextCompareResult.Different
        assertTrue(trailingNewline.identicalIgnoringWhitespace)

        val innerSpace = TextToolkit.compareText("a b", "ab") as TextCompareResult.Different
        assertTrue(innerSpace.identicalIgnoringWhitespace)
    }

    @Test
    fun compare_ignoreWhitespace_makesWhitespaceOnlyDifferenceIdentical() {
        assertEquals(
            TextCompareResult.Identical,
            TextToolkit.compareText("a b\nc", "abc", ignoreWhitespace = true),
        )
    }

    @Test
    fun compare_ignoreWhitespace_stillReportsRealDifference_withRawIndex() {
        val result = TextToolkit.compareText("a b", "a c", ignoreWhitespace = true)
            as TextCompareResult.Different
        // 位置基于原文（用户要能在屏幕上对上），不是基于去空白后的串
        assertEquals(2, result.index)
        assertFalse(result.identicalIgnoringWhitespace)
    }

    @Test
    fun compare_caseOnlyDifference_flagsIt() {
        val result = TextToolkit.compareText("Hello", "hello") as TextCompareResult.Different
        assertTrue(result.identicalIgnoringCase)
        assertFalse(result.identicalIgnoringWhitespace)
    }

    @Test
    fun compare_positionIsCountedInUtf16Units() {
        // 把"位置按 UTF-16 计"这个行为钉住：emoji 占 2 个位置。
        val result = TextToolkit.compareText("中文🚀", "中文🚀🚀") as TextCompareResult.Different
        assertEquals(4, result.index)
        assertEquals(-2, result.lengthDelta)
    }

    @Test
    fun compare_ignoresNothingByDefault() {
        // 默认口径是"逐字符严格比较"：空白与大小写差异都算不同。
        assertTrue(TextToolkit.compareText("A", "a") is TextCompareResult.Different)
        assertTrue(TextToolkit.compareText("a", "a ") is TextCompareResult.Different)
    }

    // ---------------------------------------------------------------- 正则

    @Test
    fun regexFind_returnsAllMatchesWithRanges() {
        val result = TextToolkit.regexFind("\\d+", "a1b22c333")
        val matches = (result as RegexResult.Matches).matches
        assertEquals(listOf("1", "22", "333"), matches.map { it.value })
        assertEquals(1..1, matches[0].range)
        assertEquals(3..4, matches[1].range)
        assertEquals(6..8, matches[2].range)
    }

    @Test
    fun regexFind_returnsCaptureGroups() {
        val result = TextToolkit.regexFind("(\\w+)@(\\w+)", "me@example")
        val match = (result as RegexResult.Matches).matches.single()
        assertEquals(listOf("me", "example"), match.groups)
    }

    @Test
    fun regexFind_noMatch() {
        assertEquals(RegexResult.NoMatch, TextToolkit.regexFind("\\d+", "no digits here"))
    }

    @Test
    fun regexFind_emptyPattern_isNoMatch() {
        assertEquals(RegexResult.NoMatch, TextToolkit.regexFind("", "anything"))
    }

    @Test
    fun regexFind_invalidPattern_reportsInvalid() {
        val result = TextToolkit.regexFind("[unclosed", "anything")
        assertTrue(result is RegexResult.InvalidPattern)
    }

    @Test
    fun regexFind_ignoreCase() {
        val result = TextToolkit.regexFind("abc", "ABC abc", ignoreCase = true)
        assertEquals(2, (result as RegexResult.Matches).matches.size)
    }

    @Test
    fun regexFind_truncatesAtMaxAndFlagsIt() {
        val input = "a".repeat(TextToolkit.MAX_REGEX_MATCHES + 10)
        val result = TextToolkit.regexFind("a", input) as RegexResult.Matches
        assertEquals(TextToolkit.MAX_REGEX_MATCHES, result.matches.size)
        assertTrue(result.truncated)
    }

    @Test
    fun regexFind_exactlyMaxMatches_isNotTruncated() {
        val input = "a".repeat(TextToolkit.MAX_REGEX_MATCHES)
        val result = TextToolkit.regexFind("a", input) as RegexResult.Matches
        assertEquals(TextToolkit.MAX_REGEX_MATCHES, result.matches.size)
        assertTrue(!result.truncated)
    }

    // ---------------------------------------------------------------- AES

    private fun encrypted(
        password: String = "hunter2",
        plaintext: String = "机密内容 🚀",
    ) = TextToolkit.aesEncrypt(password, plaintext)

    @Test
    fun aes_roundTripsIncludingCjkAndEmoji() {
        val plaintext = "机密内容 🚀 with ascii"
        val payload = TextToolkit.aesEncrypt("hunter2", plaintext)
        assertEquals(AesResult.Ok(plaintext), TextToolkit.aesDecrypt("hunter2", payload))
    }

    @Test
    fun aes_emptyPlaintext_roundTrips() {
        val payload = TextToolkit.aesEncrypt("pw", "")
        assertEquals(AesResult.Ok(""), TextToolkit.aesDecrypt("pw", payload))
    }

    @Test
    fun aes_payloadCarriesVersionPrefix() {
        assertTrue(encrypted().startsWith("v1:"))
    }

    @Test
    fun aes_wrongPassword_fails() {
        val payload = encrypted(password = "right")
        assertEquals(AesResult.WrongPasswordOrTampered, TextToolkit.aesDecrypt("wrong", payload))
    }

    @Test
    fun aes_tamperedCiphertext_failsAuthentication() {
        val payload = encrypted()
        assertEquals(AesResult.WrongPasswordOrTampered, TextToolkit.aesDecrypt("hunter2", tamper(payload)))
    }

    @Test
    fun aes_malformedPayload_isReportedAsMalformed() {
        assertEquals(AesResult.Malformed, TextToolkit.aesDecrypt("pw", "plain text"))
        assertEquals(AesResult.Malformed, TextToolkit.aesDecrypt("pw", "v1:!!!not-base64!!!"))
        // 合法 base64 但长度不够放 salt + iv + tag
        assertEquals(AesResult.Malformed, TextToolkit.aesDecrypt("pw", "v1:aGVsbG8="))
        assertEquals(AesResult.Malformed, TextToolkit.aesDecrypt("pw", "v2:aGVsbG8="))
    }

    @Test
    fun aes_randomIv_makesCiphertextDifferBetweenCalls() {
        assertNotEquals(encrypted(), encrypted())
    }

    /**
     * 固定 salt 与 iv 时输出必须稳定 —— 这条用来**锁住存储格式**：以后谁改了格式（顺序、
     * 前缀、tag 长度）都会在这里失败，而不是等到用户解不开旧数据才发现。
     */
    @Test
    fun aes_withFixedSaltAndIv_isDeterministic() {
        val salt = ByteArray(16) { it.toByte() }
        val iv = ByteArray(12) { (it + 1).toByte() }
        val first = TextToolkit.aesEncrypt("pw", "hello", salt = salt, iv = iv)
        val second = TextToolkit.aesEncrypt("pw", "hello", salt = salt, iv = iv)
        assertEquals(first, second)
        // 而且这份固定输出能被自己的解密读回来
        assertEquals(AesResult.Ok("hello"), TextToolkit.aesDecrypt("pw", first))
    }

    @Test
    fun aes_samePlaintextDifferentPassword_differsInCiphertext() {
        val salt = ByteArray(16) { it.toByte() }
        val iv = ByteArray(12) { (it + 1).toByte() }
        assertNotEquals(
            TextToolkit.aesEncrypt("pw1", "hello", salt = salt, iv = iv),
            TextToolkit.aesEncrypt("pw2", "hello", salt = salt, iv = iv),
        )
    }

    /** 改动正文中间的一个 base64 字符（保持长度与合法性），模拟"密文被人动过"。 */
    private fun tamper(payload: String): String {
        val prefix = "v1:"
        val body = payload.removePrefix(prefix)
        val index = body.indices.first { it > 4 && body[it] != '=' }
        val replacement = if (body[index] == 'A') 'B' else 'A'
        return prefix + body.substring(0, index) + replacement + body.substring(index + 1)
    }
}
