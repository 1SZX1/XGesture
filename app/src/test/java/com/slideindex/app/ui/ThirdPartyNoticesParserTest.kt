package com.slideindex.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdPartyNoticesParserTest {

    private val markdown = """
        # Third-Party Notices

        Intro paragraph.

        ## SideGesture

        - **Copyright:** aaronzzx and contributors
        - **License:** [Apache License 2.0](app/src/main/assets/licenses/Apache-2.0.txt)
        - **Source:** https://github.com/aaronzzx/SideGesture
        - **Used in:** 边缘手势触发动画、快捷方式解析
        - **Modifications:** 以 Kotlin 重写并集成

        ---

        ## 许可证全文

        - `app/src/main/assets/licenses/MIT.txt`
    """.trimIndent()

    private val document = parseThirdPartyNotices(markdown)

    @Test
    fun `parse splits intro entries and appendix`() {
        // 文档自带的 H1 会被剥掉：否则它在 App 里渲染成一个巨大标题，与页面顶部标题重复
        assertFalse(document.introMarkdown.contains("Third-Party Notices"))
        assertTrue(document.introMarkdown.contains("Intro paragraph."))
        assertEquals(listOf("SideGesture"), document.entries.map { it.title })
        // 「许可证全文」没有 `- **字段:**` 结构 → 归入附录而不是条目
        assertTrue(document.appendixMarkdown.contains("许可证全文"))
        assertTrue(document.appendixMarkdown.contains("MIT.txt"))
    }

    @Test
    fun `source field with trailing prose keeps only the first url`() {
        val doc = parseThirdPartyNotices(
            """
            # Title

            ## SideGesture

            - **License:** [Apache License 2.0](app/src/main/assets/licenses/Apache-2.0.txt)
            - **Source:** https://github.com/aaronzzx/SideGesture (formerly https://github.com/aaronzzx/gulugulu)
            """.trimIndent(),
        )
        val entry = doc.entries.single()
        assertEquals("https://github.com/aaronzzx/SideGesture", entry.sourceUrl)
        // 展示文本必须短到一行放得下：早先把整串塞进芯片，被撑成六行
        assertEquals("github.com/aaronzzx/SideGesture", entry.sourceLabel)
    }

    /**
     * 回归测试：旧实现用 `.trimStart('-', '\n', '\r')` 剥分隔线，会把区块**第一个列表项的
     * `-` 一起吃掉**，于是 Copyright 那行不再匹配字段模式、整个字段丢失（界面上表现为
     * 每个条目第一行缩进错位）。现在必须五个字段一个不少。
     */
    @Test
    fun `separator handling keeps every field including the first one`() {
        val entry = document.entries.single()
        assertEquals(
            listOf("Copyright", "License", "Source", "Used in", "Modifications"),
            entry.fields.map { it.label },
        )
    }

    @Test
    fun `license link is split into label and url`() {
        val license = document.entries.single().license
        assertEquals("Apache License 2.0", license?.label)
        assertEquals("app/src/main/assets/licenses/Apache-2.0.txt", license?.url)
    }

    @Test
    fun `source url and copyright are extracted`() {
        val entry = document.entries.single()
        assertEquals("https://github.com/aaronzzx/SideGesture", entry.sourceUrl)
        assertEquals("aaronzzx and contributors", entry.copyright)
    }

    @Test
    fun `body fields exclude copyright license and source`() {
        assertEquals(
            listOf("Used in", "Modifications"),
            document.entries.single().bodyFields.map { it.label },
        )
    }

    @Test
    fun `entry without any field becomes appendix`() {
        val onlyAppendix = parseThirdPartyNotices(
            """
            # Title

            ## 没有字段的小节

            这里都是普通文字。
            """.trimIndent(),
        )
        assertTrue(onlyAppendix.entries.isEmpty())
        assertTrue(onlyAppendix.appendixMarkdown.contains("普通文字"))
    }

    @Test
    fun `document without headings keeps everything as intro`() {
        val noHeading = parseThirdPartyNotices("只有一段没有标题的文字。")
        assertEquals("只有一段没有标题的文字。", noHeading.introMarkdown)
        assertTrue(noHeading.entries.isEmpty())
        assertTrue(noHeading.appendixMarkdown.isEmpty())
    }

    @Test
    fun `matches searches title label and body`() {
        val entry = document.entries.single()
        assertTrue(entry.matches(""))
        assertTrue(entry.matches("sidegesture"))
        assertTrue(entry.matches("Modified".replace("Modified", "modifications")))
        assertTrue(entry.matches("快捷方式"))
        assertFalse(entry.matches("quickdash"))
    }

    @Test
    fun `parseSingleMarkdownLink rejects plain text and keeps nested brackets`() {
        assertNull(parseSingleMarkdownLink("GPL-3.0"))
        assertNull(parseSingleMarkdownLink("[只有文案]"))
        val link = parseSingleMarkdownLink("[Hail（雹）](https://github.com/aistra0528/Hail)")
        assertEquals("Hail（雹）", link?.label)
        assertEquals("https://github.com/aistra0528/Hail", link?.url)
    }
}
