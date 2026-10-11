package com.slideindex.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownInlineTextTest {

    private fun render(markdown: String): String = markdownInlineAnnotatedString(markdown).text

    @Test
    fun `plain text passes through unchanged`() {
        assertEquals("普通文字 with ascii", render("普通文字 with ascii"))
    }

    @Test
    fun `bold markers are removed`() {
        assertEquals("Hail（雹）", render("**Hail（雹）**"))
        // 标记必须真的消失，否则界面上会看到一堆星号
        assertTrue(render("**粗体**").none { it == '*' })
    }

    @Test
    fun `inline code markers are removed`() {
        assertEquals("FreezerPrivilegedOps", render("`FreezerPrivilegedOps`"))
        assertTrue(render("`code`").none { it == '`' })
    }

    @Test
    fun `mixed content keeps order and strips all markers`() {
        val rendered = render("对齐 **Hail** 的 `HShell.setAppDisabled`：优先 PM API")
        assertEquals("对齐 Hail 的 HShell.setAppDisabled：优先 PM API", rendered)
    }

    @Test
    fun `multiple tokens in one line`() {
        assertEquals("a-b-c", render("**a**-`b`-**c**"))
    }

    @Test
    fun `unclosed markers are left as plain text`() {
        assertEquals("**未闭合", render("**未闭合"))
        assertEquals("`未闭合", render("`未闭合"))
    }

    @Test
    fun `empty string renders empty`() {
        assertEquals("", render(""))
    }

    @Test
    fun `cjk adjacent to markers is preserved`() {
        assertEquals("（Hail）", render("（**Hail**）"))
    }
}
