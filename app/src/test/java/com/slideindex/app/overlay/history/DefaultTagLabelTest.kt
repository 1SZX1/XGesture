package com.slideindex.app.overlay.history

import com.slideindex.app.stash.StashMetaRepository
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 默认标签必须都有**当前语言的显示名**资源。
 *
 * 守的是很容易踩的一个坑：往 `StashMetaRepository.DEFAULT_TAGS` 里加/改标签名时，忘了在
 * [defaultTagLabelRes] 里挂资源 —— 那枚标签就会在英文 / 日文 / 阿文界面里显示成中文
 * （用户报过的问题："闪念 tab 下的「待办」是固定的？其他语言没做"）。
 *
 * 注意这里**只**校验"资源挂上了"，不校验具体文案：文案由翻译决定，不该由单测锁死。
 */
class DefaultTagLabelTest {

    @Test
    fun everyDefaultTagHasLocalizedLabel() {
        StashMetaRepository.DEFAULT_TAGS.forEach { tag ->
            assertNotNull(
                "默认标签「${tag.name}」缺少显示名资源：去 HistoryPanelUi.defaultTagLabelRes 里挂一条",
                defaultTagLabelRes(tag.name),
            )
        }
    }

    @Test
    fun todoKeywordHasLabel() {
        assertNotNull(
            "「待办」是关键字标签，必须有显示名资源（各语言的 stash_tag_default_todo）",
            defaultTagLabelRes(StashMetaRepository.TODO_TAG_NAME),
        )
    }

    @Test
    fun ordinaryTagFallsBackToItsOwnName() {
        // 非默认标签（用户自己建的）不挂资源 → 显示原名，不能被误映射。
        assertNull(defaultTagLabelRes("项目"))
        assertNull(defaultTagLabelRes("待办事项"))
        assertNull(defaultTagLabelRes(""))
    }
}
