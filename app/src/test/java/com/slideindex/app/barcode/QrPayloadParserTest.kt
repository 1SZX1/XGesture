package com.slideindex.app.barcode

import com.slideindex.app.barcode.QrPayloadParser.BarcodeSymbology
import com.slideindex.app.barcode.QrPayloadParser.FieldId
import com.slideindex.app.barcode.QrPayloadParser.Kind
import com.slideindex.app.barcode.QrPayloadParser.WifiSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrPayloadParserTest {

    // ---------- Wi-Fi ----------

    @Test
    fun parse_wifi_extractsSsidSecurityAndPassword() {
        val result = QrPayloadParser.parse("WIFI:S:MyHome_5G;T:WPA;P:abc12345;;")
        val wifi = result as QrPayloadParser.Wifi
        assertEquals(Kind.WIFI, wifi.kind)
        assertEquals("MyHome_5G", wifi.ssid)
        assertEquals(WifiSecurity.WPA, wifi.security)
        assertEquals("WPA", wifi.securityToken)
        assertEquals("abc12345", wifi.password)
        assertEquals("MyHome_5G", wifi.summary)
    }

    @Test
    fun parse_wifi_escapedSemicolonInSsid_isUnescaped() {
        val result = QrPayloadParser.parse("""WIFI:S:My\;Home;T:WPA;P:p\;w;;""")
        val wifi = result as QrPayloadParser.Wifi
        assertEquals("My;Home", wifi.ssid)
        assertEquals("p;w", wifi.password)
    }

    @Test
    fun parse_wifi_escapedColonAndBackslash_areUnescaped() {
        val result = QrPayloadParser.parse("""WIFI:S:a\:b\\c;T:WPA;P:x;;""")
        val wifi = result as QrPayloadParser.Wifi
        assertEquals("""a:b\c""", wifi.ssid)
    }

    @Test
    fun parse_wifi_nopass_isOpenNetwork() {
        val result = QrPayloadParser.parse("WIFI:S:Guest;T:nopass;;")
        val wifi = result as QrPayloadParser.Wifi
        assertEquals(WifiSecurity.OPEN, wifi.security)
        assertNull(wifi.password)
    }

    @Test
    fun parse_wifi_missingSecurityToken_isUnknown() {
        val result = QrPayloadParser.parse("WIFI:S:Only;P:p;;")
        val wifi = result as QrPayloadParser.Wifi
        assertEquals(WifiSecurity.UNKNOWN, wifi.security)
        assertNull(wifi.securityToken)
    }

    @Test
    fun parse_wifi_wpa3TakesPrecedenceOverWpa() {
        val result = QrPayloadParser.parse("WIFI:S:N;T:WPA3;P:p;;")
        assertEquals(WifiSecurity.WPA3, (result as QrPayloadParser.Wifi).security)
    }

    @Test
    fun parse_wifi_wpa2Eap_isRecognized() {
        val result = QrPayloadParser.parse("WIFI:S:N;T:WPA2-EAP;P:p;;")
        assertEquals(WifiSecurity.WPA2_EAP, (result as QrPayloadParser.Wifi).security)
    }

    @Test
    fun parse_wifi_hiddenFlag_isParsed() {
        val result = QrPayloadParser.parse("WIFI:S:Hidden;T:WPA;P:p;H:true;;")
        assertTrue((result as QrPayloadParser.Wifi).hidden)
    }

    @Test
    fun parse_wifi_lowercasePrefix_isAccepted() {
        val result = QrPayloadParser.parse("wifi:S:Lower;T:WEP;P:p;;")
        val wifi = result as QrPayloadParser.Wifi
        assertEquals("Lower", wifi.ssid)
        assertEquals(WifiSecurity.WEP, wifi.security)
    }

    @Test
    fun parse_wifi_fields_omitAbsentValues() {
        val result = QrPayloadParser.parse("WIFI:S:Only;T:nopass;;")
        val ids = (result as QrPayloadParser.Wifi).fields.map { it.id }
        assertEquals(listOf(FieldId.SSID, FieldId.SECURITY), ids)
    }

    // ---------- 名片 ----------

    @Test
    fun parse_vcard_extractsNamePhoneEmail() {
        val raw = "BEGIN:VCARD\nVERSION:3.0\nFN:张三\nTEL:+8613800138000\nEMAIL:z@x.com\nEND:VCARD"
        val contact = QrPayloadParser.parse(raw) as QrPayloadParser.Contact
        assertEquals(Kind.CONTACT, contact.kind)
        assertEquals("张三", contact.name)
        assertEquals("+8613800138000", contact.phone)
        assertEquals("z@x.com", contact.email)
        assertEquals("张三", contact.summary)
    }

    @Test
    fun parse_vcard_groupedTelPrefix_isStripped() {
        val raw = "BEGIN:VCARD\nFN:A\nitem1.TEL:123\nitem1.EMAIL:a@b.com\nEND:VCARD"
        val contact = QrPayloadParser.parse(raw) as QrPayloadParser.Contact
        assertEquals("123", contact.phone)
        assertEquals("a@b.com", contact.email)
    }

    @Test
    fun parse_vcard_typedTelParameter_isStripped() {
        val raw = "BEGIN:VCARD\nFN:A\nTEL;TYPE=CELL:456\nEND:VCARD"
        assertEquals("456", (QrPayloadParser.parse(raw) as QrPayloadParser.Contact).phone)
    }

    @Test
    fun parse_vcard_nFieldUsedWhenFnAbsent() {
        val raw = "BEGIN:VCARD\nN:Doe;John;;;\nEND:VCARD"
        assertEquals("Doe John", (QrPayloadParser.parse(raw) as QrPayloadParser.Contact).name)
    }

    @Test
    fun parse_mecard_isRecognized() {
        val contact = QrPayloadParser.parse("MECARD:N:Li;TEL:10086;EMAIL:l@x.cn;;") as QrPayloadParser.Contact
        assertEquals("Li", contact.name)
        assertEquals("10086", contact.phone)
        assertEquals("l@x.cn", contact.email)
    }

    @Test
    fun parse_vcard_withoutUsefulFields_hasEmptySummary() {
        val contact = QrPayloadParser.parse("BEGIN:VCARD\nVERSION:3.0\nEND:VCARD") as QrPayloadParser.Contact
        assertEquals("", contact.summary)
        assertTrue(contact.fields.isEmpty())
    }

    // ---------- 电话 / 邮箱 / 网址 ----------

    @Test
    fun parse_tel_returnsPhoneNumber() {
        val phone = QrPayloadParser.parse("tel:+8613800138000") as QrPayloadParser.PhoneNumber
        assertEquals(Kind.PHONE, phone.kind)
        assertEquals("+8613800138000", phone.number)
    }

    @Test
    fun parse_tel_uppercasePrefix_isAccepted() {
        assertEquals("10086", (QrPayloadParser.parse("TEL:10086") as QrPayloadParser.PhoneNumber).number)
    }

    @Test
    fun parse_mailto_decodesEncodedSubject() {
        val mail = QrPayloadParser.parse("mailto:a@b.com?subject=Hello%20World") as QrPayloadParser.EmailAddress
        assertEquals("a@b.com", mail.address)
        assertEquals("Hello World", mail.subject)
    }

    @Test
    fun parse_mailto_withoutQuery_hasNullSubject() {
        assertNull((QrPayloadParser.parse("mailto:a@b.com") as QrPayloadParser.EmailAddress).subject)
    }

    @Test
    fun parse_webUrl_wwwPrefixGetsHttps() {
        val url = QrPayloadParser.parse("www.bilibili.com") as QrPayloadParser.WebUrl
        assertEquals(Kind.WEB_URL, url.kind)
        assertEquals("https://www.bilibili.com", url.url)
        assertEquals("www.bilibili.com", url.host)
    }

    @Test
    fun parse_webUrl_hostIgnoresPathAndQuery() {
        val url = QrPayloadParser.parse("https://example.com/a/b?q=1#frag") as QrPayloadParser.WebUrl
        assertEquals("example.com", url.host)
        assertEquals("https://example.com/a/b?q=1#frag", url.url)
    }

    @Test
    fun parse_httpsUrl_isNotTreatedAsWifi() {
        assertEquals(Kind.WEB_URL, QrPayloadParser.parse("https://wifi.example.com").kind)
    }

    // ---------- 商品条码 ----------

    @Test
    fun parse_gtin_symbologyFollowsDigitCount() {
        assertEquals(BarcodeSymbology.EAN_8, barcodeOf("12345678").symbology)
        assertEquals(BarcodeSymbology.UPC_A, barcodeOf("123456789012").symbology)
        assertEquals(BarcodeSymbology.EAN_13, barcodeOf("6901234567892").symbology)
        assertEquals(BarcodeSymbology.ITF_14, barcodeOf("12345678901234").symbology)
        assertEquals(BarcodeSymbology.UNKNOWN, barcodeOf("123456789").symbology)
    }

    @Test
    fun parse_digitsOutsideGtinRange_isPlainText() {
        assertEquals(Kind.PLAIN_TEXT, QrPayloadParser.parse("1234567").kind)
        assertEquals(Kind.PLAIN_TEXT, QrPayloadParser.parse("123456789012345").kind)
    }

    @Test
    fun parse_gtin_keepsOriginalAsSummaryAndBarcodeField() {
        val barcode = barcodeOf("6901234567892")
        assertEquals("6901234567892", barcode.summary)
        assertEquals(FieldId.BARCODE, barcode.fields.first().id)
        assertEquals("6901234567892", barcode.fields.first().value)
    }

    // ---------- 兜底 ----------

    @Test
    fun parse_emptyInput_returnsPlainText() {
        assertEquals(Kind.PLAIN_TEXT, QrPayloadParser.parse("").kind)
        assertEquals(Kind.PLAIN_TEXT, QrPayloadParser.parse("   ").kind)
    }

    @Test
    fun parse_plainText_summaryIsFirstLine() {
        val text = QrPayloadParser.parse("第一行\n第二行\n第三行") as QrPayloadParser.PlainText
        assertEquals("第一行", text.summary)
        assertTrue(text.fields.isEmpty())
    }

    @Test
    fun parse_plainText_longLineIsTruncatedToEightyChars() {
        val text = QrPayloadParser.parse("x".repeat(200)) as QrPayloadParser.PlainText
        assertEquals(80, text.summary.length)
    }

    @Test
    fun parse_keepsRawTextUntrimmedForPlainText() {
        assertEquals("  spaced  ", QrPayloadParser.parse("  spaced  ").raw)
    }

    private fun barcodeOf(digits: String): QrPayloadParser.ProductBarcode =
        QrPayloadParser.parse(digits) as QrPayloadParser.ProductBarcode
}
