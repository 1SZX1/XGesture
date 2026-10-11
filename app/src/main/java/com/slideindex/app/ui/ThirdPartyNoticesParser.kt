package com.slideindex.app.ui

/**
 * 第三方声明文档的结构化模型。
 *
 * 为什么不再直接把 markdown 丢给渲染器：
 * - 一个条目有 4~6 个字段（Copyright / License / Source / Used in / Modifications / Note），
 *   其中 `Used in`、`Modifications` 经常上百字 → 整页就是一面文字墙，"拿了谁什么、什么许可"
 *   这两个最该一眼看到的信息埋在散文里；
 * - 旧解析用 `.trimStart('-', '\n', '\r')` 剥分隔线，它按**字符集**裁剪，会把每个区块
 *   **第一个列表项的 `-` 一起吃掉**（Copyright 那行于是被当成普通段落，缩进与其余 bullet
 *   不一致），区块尾部的 `---` 还会留在正文里渲染成卡片内多余横线。见 [cleanSectionBody]。
 *
 * 于是先把文档拆成"引言 + 条目 + 附录"，条目再拆成字段；页面据此排版，notices.md
 * 仍然是**唯一数据源**（仓库文档 → assets 副本的流程不变）。
 */
internal data class ThirdPartyNoticeEntry(
    val title: String,
    val fields: List<Field>,
) {
    internal data class Field(val label: String, val markdown: String)

    fun field(label: String): Field? =
        fields.firstOrNull { it.label.equals(label, ignoreCase = true) }

    /** `License` 字段形如 `[文案](链接)`；取出后渲染成可点的许可行。 */
    val license: MarkdownLink? get() = field("License")?.markdown?.let(::parseSingleMarkdownLink)

    /**
     * `Source` 字段里的**第一个 URL**。
     *
     * 该字段可能带补充说明，例如
     * `https://github.com/aaronzzx/SideGesture (formerly https://github.com/aaronzzx/gulugulu)`。
     * 早先把**整串**当标签塞进芯片 → 芯片被撑成 6 行（真机截图里最难看的一处）。
     * 这里只取第一个 URL 作链接与显示文本；括号里的补充说明留给仓库文档（不上界面）。
     */
    val sourceUrl: String?
        get() = SOURCE_URL.find(field("Source")?.markdown.orEmpty())?.value

    /** 来源的展示文本：去掉 scheme，短到能一行放下。 */
    val sourceLabel: String?
        get() = sourceUrl
            ?.removePrefix("https://")
            ?.removePrefix("http://")
            ?.removeSuffix("/")

    val copyright: String?
        get() = field("Copyright")?.markdown?.trim()?.takeIf { it.isNotEmpty() }

    /** 正文：`Used in` / `Modifications` / `Note` 等（版权、许可、来源已单独成行）。 */
    val bodyFields: List<Field>
        get() = fields.filterNot { field ->
            field.label.equals("Copyright", true) ||
                field.label.equals("License", true) ||
                field.label.equals("Source", true)
        }
}

internal data class MarkdownLink(val label: String, val url: String)

internal data class ThirdPartyNoticesDocument(
    /** 第一个 `## ` 之前的引言（保留 markdown，含维护提示与仓库链接）。 */
    val introMarkdown: String,
    val entries: List<ThirdPartyNoticeEntry>,
    /** 不以条目结构出现的尾部区块（例如「许可证全文」），保留 markdown。 */
    val appendixMarkdown: String,
)

private val SECTION_HEADING = Regex("""^## (.+)$""", RegexOption.MULTILINE)
private val H1_HEADING = Regex("""^#\s+.+$""", RegexOption.MULTILINE)
private val SOURCE_URL = Regex("""https?://\S+""")
private val FIELD_LINE = Regex("""^-\s+\*\*(.+?):\*\*\s*(.*)$""")
private val SINGLE_LINK = Regex("""^\[(.+?)]\((.+?)\)$""")

internal fun parseThirdPartyNotices(markdown: String): ThirdPartyNoticesDocument {
    val headings = SECTION_HEADING.findAll(markdown).toList()
    if (headings.isEmpty()) {
        return ThirdPartyNoticesDocument(
            introMarkdown = cleanIntro(markdown),
            entries = emptyList(),
            appendixMarkdown = "",
        )
    }

    val intro = cleanIntro(markdown.substring(0, headings.first().range.first))
    val entries = mutableListOf<ThirdPartyNoticeEntry>()
    val appendix = StringBuilder()

    headings.forEachIndexed { index, heading ->
        val title = heading.groupValues[1].trim()
        val bodyStart = heading.range.last + 1
        val bodyEnd = headings.getOrNull(index + 1)?.range?.first ?: markdown.length
        val body = cleanSectionBody(markdown.substring(bodyStart, bodyEnd))
        val fields = body.lineSequence()
            .mapNotNull { line ->
                val match = FIELD_LINE.matchEntire(line.trim()) ?: return@mapNotNull null
                ThirdPartyNoticeEntry.Field(
                    label = match.groupValues[1].trim(),
                    markdown = match.groupValues[2].trim(),
                )
            }
            .toList()

        if (fields.isEmpty()) {
            // 不是条目结构（如「许可证全文」）：整段留到附录，按 markdown 渲染
            if (appendix.isNotEmpty()) appendix.append("\n\n")
            appendix.append("## ").append(title).append("\n\n").append(body)
        } else {
            entries += ThirdPartyNoticeEntry(title = title, fields = fields)
        }
    }

    return ThirdPartyNoticesDocument(
        introMarkdown = intro,
        entries = entries,
        appendixMarkdown = appendix.toString().trim(),
    )
}

/**
 * 引言清理：剥掉文档自带的 H1 与分隔线。
 *
 * `# Third-Party Notices` 在 App 里会渲染成一个巨大的标题，而页面顶部**已经有**标题
 * 「第三方开源组件」→ 真机上就是两个大标题叠在一起。
 */
private fun cleanIntro(raw: String): String =
    raw.lineSequence()
        .filterNot { line -> H1_HEADING.matches(line.trim()) || line.trim() == "---" }
        .joinToString("\n")
        .trim()

/**
 * 去掉区块之间那行 `---` 分隔线。
 *
 * ⚠️ 只删**整行**等于 `---` 的行。旧实现用 `.trimStart('-', '\n', '\r')`，那是按字符集裁剪，
 * 会把紧接着的第一个列表项的 `-` 一起吃掉（真机上表现为每个条目第一行缩进错位），
 * 而且尾部分隔线仍留在正文里。
 */
private fun cleanSectionBody(raw: String): String =
    raw.lineSequence()
        .filterNot { it.trim() == "---" }
        .joinToString("\n")
        .trim()

internal fun parseSingleMarkdownLink(markdown: String): MarkdownLink? {
    val match = SINGLE_LINK.matchEntire(markdown.trim()) ?: return null
    return MarkdownLink(
        label = match.groupValues[1].trim(),
        url = match.groupValues[2].trim(),
    )
}
