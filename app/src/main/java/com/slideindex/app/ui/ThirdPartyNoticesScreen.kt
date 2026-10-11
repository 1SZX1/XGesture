package com.slideindex.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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

/** 收起时只显示第一个正文字段（`Used in`）的前几行。 */
private const val COLLAPSED_BODY_LINES = 3

/**
 * 「第三方开源组件」页。
 *
 * 排版思路（改版原因：原来把 markdown 原样铺开，整页是一面文字墙，而"拿了谁什么、什么许可"
 * 这两个最该一眼看到的信息埋在长句里）：
 *
 * - 每个项目一张卡：**项目名** → **许可（可点，进应用内许可全文）** + **来源（可点，去 GitHub）**
 *   → 版权小字 → 正文（`Used in` / `Modifications` / `Note`），正文默认只露前几行、可展开；
 * - 顶部**搜索框**：按项目名、字段名、正文关键字过滤（这比目录更好用，所以没做目录）；
 * - 引言与附录（如「许可证全文」）仍走 markdown 渲染，其中的链接照旧分流
 *   （http → 浏览器、`licenses/` 下的 txt → 应用内许可页、其它相对路径 → GitHub）。
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
    val body = remember {
        runCatching {
            context.assets.open("licenses/third_party_notices.md")
                .bufferedReader()
                .use { it.readText() }
        }.getOrElse { "" }
    }
    val document = remember(body) { parseThirdPartyNotices(body) }
    var query by remember { mutableStateOf("") }
    val entries = remember(document, query) {
        document.entries.filter { it.matches(query) }
    }
    val searching = query.isNotBlank()

    SettingsScreenScaffold(
        title = stringResource(R.string.about_third_party_notices_title),
        subtitle = stringResource(R.string.about_third_party_notices_subtitle),
        onBack = onBack,
    ) {
        LazySettingsItem(key = "third-party-search") {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.about_third_party_search_hint)) },
                leadingIcon = { Icon(imageVector = Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (searching) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(imageVector = Icons.Filled.Close, contentDescription = null)
                        }
                    }
                } else {
                    null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(16.dp),
            )
        }

        // 搜索时收起引言与附录，让结果尽量靠上。
        if (!searching && document.introMarkdown.isNotBlank()) {
            LazySettingsItem(key = "third-party-intro") {
                MarkdownDocumentContent(
                    markdown = document.introMarkdown,
                    projectBaseUrl = projectBaseUrl,
                    onOpenAssetLicense = onOpenLicenseText,
                    compact = true,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }

        if (entries.isEmpty()) {
            LazySettingsItem(key = "third-party-empty") {
                Text(
                    text = stringResource(R.string.about_third_party_no_match),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }

        entries.forEach { entry ->
            LazySettingsItem(key = "third-party-entry-${entry.title}") {
                ThirdPartyEntryCard(
                    entry = entry,
                    onOpenLicenseText = onOpenLicenseText,
                )
            }
        }

        if (!searching && document.appendixMarkdown.isNotBlank()) {
            LazySettingsItem(key = "third-party-appendix") {
                CardSegment(
                    isFirst = true,
                    isLast = true,
                    insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    MarkdownDocumentContent(
                        markdown = document.appendixMarkdown,
                        projectBaseUrl = projectBaseUrl,
                        onOpenAssetLicense = onOpenLicenseText,
                        compact = true,
                    )
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
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = entry.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        if (entry.license != null || entry.sourceUrl != null) {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                entry.license?.let { license ->
                    AssistChip(
                        onClick = { onOpenLicenseText(license.url.substringAfterLast('/')) },
                        label = { Text(license.label) },
                    )
                }
                entry.sourceUrl?.let { url ->
                    AssistChip(
                        onClick = { runCatching { uriHandler.openUri(url) } },
                        label = { Text(url.removePrefix("https://").removePrefix("http://")) },
                    )
                }
            }
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
