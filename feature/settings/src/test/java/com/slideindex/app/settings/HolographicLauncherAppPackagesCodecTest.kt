package com.slideindex.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HolographicLauncherAppPackagesCodecTest {

    @Test
    fun encodeDecode_keepsInsertionOrder() {
        val packages = listOf("com.c", "com.a", "com.b")
        assertEquals(packages, HolographicLauncherAppPackagesCodec.decode(
            HolographicLauncherAppPackagesCodec.encode(packages)
        ))
    }

    @Test
    fun decode_returnsEmptyListWhenNeverConfigured() {
        assertTrue(HolographicLauncherAppPackagesCodec.decode(null).isEmpty())
        assertTrue(HolographicLauncherAppPackagesCodec.decode(emptySet()).isEmpty())
    }

    @Test
    fun encode_emptyListProducesNoPreferenceValue() {
        // 空列表必须写成"没有值"，否则界面上「未配置」与「配置成空」会无法区分。
        assertTrue(HolographicLauncherAppPackagesCodec.encode(emptyList()).isEmpty())
    }

    @Test
    fun normalize_dropsBlanksAndDuplicates() {
        assertEquals(
            listOf("com.a", "com.b"),
            HolographicLauncherAppPackagesCodec.normalize(listOf("com.a", "", "com.b", "com.a", "  ")),
        )
    }

    @Test
    fun normalize_trimsAndClampsToUpperBound() {
        val many = (1..(HolographicLauncherSettings.MAX_APP_PACKAGES + 20)).map { "com.app$it" }
        val normalized = HolographicLauncherAppPackagesCodec.normalize(many + listOf(" com.spaced "))
        assertEquals(HolographicLauncherSettings.MAX_APP_PACKAGES, normalized.size)
        assertEquals("com.app1", normalized.first())
    }
}
