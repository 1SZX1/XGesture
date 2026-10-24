package com.slideindex.app.gesture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「相册图片编辑」的 id 约束与 `from()` 映射。
 *
 * id 是**持久化 ABI**（`GestureRuleCodec` 存的就是 `type.id`）：既不允许重复，也不允许给已发布的动作改号
 * —— `GestureActionNormalizedTest` 里记的那次「93 撞号把快捷轮盘解析成强行停止」就是教训。
 */
class GalleryImageEditActionTest {

    @Test
    fun id_isStable() {
        assertEquals(105, GestureActionType.GALLERY_IMAGE_EDIT.id)
        val allIds = GestureActionType.entries.map { it.id }
        assertEquals(allIds.size, allIds.toSet().size)
    }

    @Test
    fun fromId_roundTrips() {
        assertEquals(
            GestureActionType.GALLERY_IMAGE_EDIT,
            GestureActionType.fromId(GestureActionType.GALLERY_IMAGE_EDIT.id),
        )
    }

    @Test
    fun from_mapsTypeToAction() {
        assertEquals(
            GestureAction.GalleryImageEdit,
            GestureAction.from(GestureActionType.GALLERY_IMAGE_EDIT, ""),
        )
        assertEquals(GestureActionType.GALLERY_IMAGE_EDIT, GestureAction.GalleryImageEdit.type)
        assertEquals("", GestureAction.GalleryImageEdit.payload)
    }
}
