package com.slideindex.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slideindex.app.R
import com.slideindex.app.ui.miuix.CardSegment
import com.slideindex.app.ui.settings.components.LazySettingsItem

/** 收起时正文只露前几行。 */
private const val COLLAPSED_BODY_LINES = 3

/** 卡内边距：引言、条目、附录三处统一，避免同一页出现两套缩进。 */
private val CARD_INSIDE_PADDING = PaddingValues(horizontal = 14.dp, vertical = 12.dp)

/**
 * 卡片之间的垂直间距。
 *
 * ⚠️ `CardSegment` 的 `outerTopPadding` / `outerBottomPadding` **默认都是 0dp**：每张卡都用
 * `isFirst = isLast = true` 却不给间距，卡片就会**贴死在一起**（真机反馈）。12dp 与仓库既有
 * 约定一致（`GroupedCardItems` / `MiuixSettingsCards` 的 `outerBottomPadding` 默认值）。
 */
private val CARD_SPACING = 12.dp

/**
 * 「第三方开源组件」页。
 *
 * 排版：**引言、每个项目、附录都各自是一张卡**（同样的左右内缩与卡内边距）。
 *
 * 走过的弯路（真机截图反馈）：
 * 1. 引言原先直接铺在列表项里、只给了底部 padding → 文字**顶到屏幕边缘**，与卡片的
 *    12dp 内缩不齐，看起来像"把文档原样倒在页面上"。现在也放进卡片；
 * 2. 曾加过搜索框，用户判定页面更丑、且 17 个条目用不上 → 已删除；
 * 3. 来源字段带 "(formerly …)" 时被整串塞进芯片撑成六行 → 改成单行文本链接（见下）。
 *
 * 数据源仍是 `assets/licenses/third_party_notices.md`（与仓库根那份必须逐行一致，
 * 由 `scripts/check-notices-sync.py` 校验）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ThirdPartyNoticesScreen(
    onBack: () -> Unit,
    onOpenLicenseText: (assetFileName: String) -> Unit,
) {
    val context = LocalContext.current
    val projectBaseUrl = stringResource(R.string.about_project_url_desc)
    val uriHandler = LocalUriHandler.current
    val body = remember {
        runCatching {
            context.assets.open("licenses/third_party_notices.md")
                .bufferedReader()
                .use { it.readText() }
        }.getOrElse { "" }
    }
    val document = remember(body) { parseThirdPartyNotices(body) }

    SettingsScreenScaffold(
        title = stringResource(R.string.about_third_party_notices_title),
        subtitle = stringResource(R.string.about_third_party_notices_subtitle),
        onBack = onBack,
    ) {
        if (document.introMarkdown.isNotBlank()) {
            LazySettingsItem(key = "third-party-intro") {
                CardSegment(
                    isFirst = true,
                    isLast = true,
                    outerBottomPadding = CARD_SPACING,
                    insidePadding = CARD_INSIDE_PADDING,
                ) {
                    MarkdownDocumentContent(
                        markdown = document.introMarkdown,
                        projectBaseUrl = projectBaseUrl,
                        onOpenAssetLicense = onOpenLicenseText,
                        compact = true,
                    )
                }
            }
        }

        document.entries.forEach { entry ->
            LazySettingsItem(key = "third-party-entry-${entry.title}") {
                ThirdPartyEntryCard(
                    entry = entry,
                    onOpenLicenseText = onOpenLicenseText,
                )
            }
        }

        // 附录（如「许可证全文」）**同步渲染**：它原先走异步 markdown 组件，滑完才"长高"，
        // 于是整页 fling 到不了底、要再滑一次（真机反馈）。这里高度一开始就定死。
        document.appendices.forEach { section ->
            LazySettingsItem(key = "third-party-appendix-${section.title}") {
                CardSegment(
                    isFirst = true,
                    isLast = true,
                    outerBottomPadding = CARD_SPACING,
                    insidePadding = CARD_INSIDE_PADDING,
                ) {
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    section.items.forEach { item ->
                        val url = item.url
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (url != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .then(
                                    if (url == null) {
                                        Modifier
                                    } else {
                                        Modifier.clickable {
                                            runCatching {
                                                openDocumentLink(
                                                    uri = url,
                                                    projectBaseUrl = projectBaseUrl,
                                                    uriHandler = uriHandler,
                                                    onOpenAssetLicense = onOpenLicenseText,
                                                )
                                            }
                                        }
                                    },
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThirdPartyEntryCard(
    entry: ThirdPartyNoticeEntry,
    onOpenLicenseText: (assetFileName: String) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    // 每个条目自己的展开状态；换条目（键不同）时重置。
    var expanded by remember(entry.title) { mutableStateOf(false) }
    val bodyFields = entry.bodyFields
    val visibleFields = if (expanded) bodyFields else bodyFields.take(1)

    CardSegment(
        isFirst = true,
        isLast = true,
        outerBottomPadding = CARD_SPACING,
        insidePadding = CARD_INSIDE_PADDING,
    ) {
        Text(
            text = entry.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        entry.license?.let { license ->
            AssistChip(
                onClick = { onOpenLicenseText(license.url.substringAfterLast('/')) },
                label = { Text(license.label) },
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        entry.sourceUrl?.let { url ->
            // 来源用**单行文本链接**而不是芯片：`Source` 字段可能带 "(formerly …)" 这类补充
            // 说明，整串塞进芯片会被撑成六行（真机截图里最难看的一处）。
            Text(
                text = entry.sourceLabel.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clickable { runCatching { uriHandler.openUri(url) } },
            )
        }

        entry.copyright?.let { copyright ->
            Text(
                text = copyright,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        visibleFields.forEach { field ->
            Column(modifier = Modifier.padding(top = 10.dp)) {
                Text(
                    text = field.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = markdownInlineAnnotatedString(field.markdown),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_BODY_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        if (bodyFields.size > 1) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Text(
                    text = stringResource(
                        if (expanded) {
                            R.string.about_third_party_collapse
                        } else {
                            R.string.about_third_party_expand
                        },
                    ),
                )
            }
        }
    }
}
