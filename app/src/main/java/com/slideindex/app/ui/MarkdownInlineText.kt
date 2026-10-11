package com.slideindex.app.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * 极轻量的**行内** markdown 渲染：只处理 `**粗体**` 与 `` `代码` ``。
 *
 * 为什么不用完整的 markdown 组件渲染每个字段：
 * 1. 整页 17 个条目 × 4~6 个字段会产生上百个 markdown 组件；
 * 2. markdown 组件不支持 `maxLines`，没法做"展开 / 收起"。
 *
 * 刻意**不处理链接**：声明文档里唯一的 markdown 链接都出现在 `License` 字段
 * （17 个条目各一处），而那一行由页面渲染成可点的许可行（点击进应用内许可全文页），
 * 比行内链接更好按。因此这里只需处理粗体与行内代码，行为完全可预期。
 */
internal fun markdownInlineAnnotatedString(markdown: String): AnnotatedString =
    buildAnnotatedString {
        var cursor = 0
        INLINE_TOKEN.findAll(markdown).forEach { match ->
            if (match.range.first > cursor) {
                append(markdown.substring(cursor, match.range.first))
            }
            val token = match.value
            when {
                token.length >= 4 && token.startsWith("**") -> withStyle(
                    SpanStyle(fontWeight = FontWeight.SemiBold),
                ) {
                    append(token.substring(2, token.length - 2))
                }

                token.length >= 2 && token.startsWith("`") -> withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace),
                ) {
                    append(token.substring(1, token.length - 1))
                }

                else -> append(token)
            }
            cursor = match.range.last + 1
        }
        if (cursor < markdown.length) {
            append(markdown.substring(cursor))
        }
    }

/** 行内标记：`**粗体**`、`` `代码` ``。数组顺序不影响匹配（正则按最长最左）。 */
private val INLINE_TOKEN = Regex("""\*\*[^*]+\*\*|`[^`]+`""")
