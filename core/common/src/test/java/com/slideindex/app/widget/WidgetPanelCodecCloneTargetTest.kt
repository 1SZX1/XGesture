package com.slideindex.app.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetPanelCodecCloneTargetTest {

    @Test
    fun roundTrip_preservesCloneUserId() {
        val page = WidgetPanelPage(
            id = 7L,
            name = "测试",
            items = listOf(
                WidgetPanelItem(
                    appWidgetId = -123,
                    x = 0,
                    y = 0,
                    spanX = 1,
                    spanY = 1,
                    label = "抖音 · 分身",
                    itemType = ITEM_TYPE_APP,
                    packageName = "com.ss.android.ugc.aweme",
                    cloneUserId = 999,
                ),
            ),
        )

        val decoded = WidgetPanelCodec.decodePage(WidgetPanelCodec.encodePage(page))

        assertEquals(999, decoded?.items?.single()?.cloneUserId)
    }

    @Test
    fun legacyItem_withoutCloneField_decodesAsMainUser() {
        // 第 12 个字段（cloneUserId）是后加的：老数据没有它，解析出来必须是 user 0。
        val header = listOf("1", "page", "4", "26", "0.25", "true", "6", "62", "18", "100")
            .joinToString("\u001E")
        val legacyItem = listOf(
            "1", "0", "0", "1", "1", "抖音", ITEM_TYPE_APP,
            "com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.SplashActivity", "", "",
        ).joinToString("\u001E")

        val decoded = WidgetPanelCodec.decodePage("$header\u001E$legacyItem")

        val item = decoded?.items?.single()
        assertEquals("com.ss.android.ugc.aweme", item?.packageName)
        assertEquals(0, item?.cloneUserId)
    }
}
