@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.slideindex.app.ui.picker

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.slideindex.app.R
import com.slideindex.app.search.ShareFileTargetResolver
import com.slideindex.app.search.ShareImageTarget
import com.slideindex.app.search.ShareImageTargetResolver
import com.slideindex.app.search.ShareTargetAppGroup
import com.slideindex.app.search.ShareTextTargetResolver
import com.slideindex.app.ui.Md3PickerActivityLeading
import com.slideindex.app.ui.Md3PickerListRow
import com.slideindex.app.ui.Md3PickerPackageLeading
import com.slideindex.app.ui.PickerChipSpec
import com.slideindex.app.ui.PickerListGroupSpacing
import com.slideindex.app.ui.PickerTrailingMode
import com.slideindex.app.ui.settings.components.SettingsLazyScreenScaffoldWithExpandableSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok

private const val ShareTargetExpandMs = 260
private const val ShareTargetCollapseMs = 200

@Composable
fun ShareImageTargetPickScreen(
    selectedPackageName: String = "",
    selectedActivityClassName: String = "",
    onBack: () -> Unit,
    onSelectTarget: (ShareImageTarget) -> Unit,
) {
    ShareTargetPickScreen(
        forImage = true,
        selectedPackageName = selectedPackageName,
        selectedActivityClassName = selectedActivityClassName,
        onBack = onBack,
        onSelectTarget = onSelectTarget,
    )
}

/**
 * 文件分享目标选择器：和图片分享一样支持勾选多个接口，确认后一次性生成多条文件分享引擎。
 */
@Composable
fun ShareFileTargetPickScreen(
    onBack: () -> Unit,
    onAddTargets: (List<ShareImageTarget>) -> Unit,
) {
    val context = LocalContext.current
    var targets by remember { mutableStateOf<List<ShareImageTarget>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        loading = true
        targets = withContext(Dispatchers.IO) {
            ShareFileTargetResolver.listTargets(context)
        }
        loading = false
    }

    val filtered = remember(targets, query) {
        ShareFileTargetResolver.searchTargets(targets, query)
    }

    ShareTargetPickContent(
        title = stringResource(R.string.search_engine_pick_share_file_target_title),
        pageHint = stringResource(R.string.share_target_batch_hint),
        searchHintResId = R.string.search_engine_share_file_target_search_hint,
        emptyTextResId = R.string.search_engine_share_file_target_empty,
        targets = filtered,
        loading = loading,
        query = query,
        onSearchQueryChange = { query = it },
        selectedPackageName = "",
        selectedActivityClassName = "",
        multiSelect = true,
        onBack = onBack,
        onSelectTarget = {},
        onConfirmSelection = onAddTargets,
    )
}

@Composable
fun ShareTargetPickScreen(
    forImage: Boolean,
    selectedPackageName: String = "",
    selectedActivityClassName: String = "",
    onBack: () -> Unit,
    onSelectTarget: (ShareImageTarget) -> Unit,
) {
    val context = LocalContext.current
    var targets by remember { mutableStateOf<List<ShareImageTarget>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(forImage) {
        loading = true
        targets = withContext(Dispatchers.IO) {
            if (forImage) {
                ShareImageTargetResolver.listTargets(context)
            } else {
                ShareTextTargetResolver.listTargets(context)
            }
        }
        loading = false
    }

    val filtered = remember(targets, query, forImage) {
        if (forImage) {
            ShareImageTargetResolver.searchTargets(targets, query)
        } else {
            ShareTextTargetResolver.searchTargets(targets, query)
        }
    }

    ShareTargetPickContent(
        title = stringResource(
            if (forImage) {
                R.string.search_engine_pick_share_image_target_title
            } else {
                R.string.search_engine_pick_share_text_target_title
            },
        ),
        pageHint = stringResource(
            if (forImage) {
                R.string.search_engine_pick_share_image_target
            } else {
                R.string.search_engine_pick_share_text_target
            },
        ),
        searchHintResId = if (forImage) {
            R.string.search_engine_share_image_target_search_hint
        } else {
            R.string.search_engine_share_text_target_search_hint
        },
        emptyTextResId = if (forImage) {
            R.string.search_engine_share_image_target_empty
        } else {
            R.string.search_engine_share_text_target_empty
        },
        targets = filtered,
        loading = loading,
        query = query,
        onSearchQueryChange = { query = it },
        selectedPackageName = selectedPackageName,
        selectedActivityClassName = selectedActivityClassName,
        multiSelect = false,
        onBack = onBack,
        onSelectTarget = onSelectTarget,
        onConfirmSelection = {},
    )
}

/**
 * 图片分享引擎的「批量添加」选择器：勾选多个分享入口，确认后一次生成多条引擎。
 *
 * 与 [ShareTargetPickScreen] 共用同一套分组 UI，只是行尾从单选点换成勾选框，
 * 并在右上角给出一个确认动作。
 */
@Composable
fun ShareTargetBatchPickScreen(
    onBack: () -> Unit,
    onAddTargets: (List<ShareImageTarget>) -> Unit,
) {
    val context = LocalContext.current
    var targets by remember { mutableStateOf<List<ShareImageTarget>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        loading = true
        targets = withContext(Dispatchers.IO) {
            ShareImageTargetResolver.listTargets(context)
        }
        loading = false
    }

    val filtered = remember(targets, query) {
        ShareImageTargetResolver.searchTargets(targets, query)
    }

    ShareTargetPickContent(
        title = stringResource(R.string.search_engine_pick_share_image_target_title),
        pageHint = stringResource(R.string.share_target_batch_hint),
        searchHintResId = R.string.search_engine_share_image_target_search_hint,
        emptyTextResId = R.string.search_engine_share_image_target_empty,
        targets = filtered,
        loading = loading,
        query = query,
        onSearchQueryChange = { query = it },
        selectedPackageName = "",
        selectedActivityClassName = "",
        multiSelect = true,
        onBack = onBack,
        onSelectTarget = {},
        onConfirmSelection = onAddTargets,
    )
}

/**
 * 分享目标选择器（图片 / 文本 / 文件共用）。
 *
 * 列表按「应用」分组：组头一行放应用图标、应用名和包名，组内才是具体的分享入口
 * （入口名 + Activity 类名）。多入口的应用默认收起，点组头展开；只有一个入口的应用
 * 直接把选择控件放在组头，保持一次点击就能选中。
 */
@Composable
private fun ShareTargetPickContent(
    title: String,
    pageHint: String,
    @StringRes searchHintResId: Int,
    @StringRes emptyTextResId: Int,
    targets: List<ShareImageTarget>,
    loading: Boolean,
    query: String,
    onSearchQueryChange: (String) -> Unit,
    selectedPackageName: String,
    selectedActivityClassName: String,
    multiSelect: Boolean,
    onBack: () -> Unit,
    onSelectTarget: (ShareImageTarget) -> Unit,
    onConfirmSelection: (List<ShareImageTarget>) -> Unit,
) {
    val groups = remember(targets) { ShareImageTargetResolver.groupByApp(targets) }
    var expandedPackages by remember(groups) {
        mutableStateOf(
            initialExpandedPackages(multiSelect, selectedPackageName),
        )
    }
    // 勾选状态不能跟着 filtered 目标走：搜索时列表会变，勾过的项不能丢，
    // 同时把目标本身一起存下来，确认时就不用再从当前过滤结果里反查。
    var pickedTargetsByKey by remember { mutableStateOf<Map<String, ShareImageTarget>>(emptyMap()) }
    val activeKeys = remember(multiSelect, pickedTargetsByKey, selectedPackageName, selectedActivityClassName) {
        if (multiSelect) {
            pickedTargetsByKey.keys
        } else if (selectedPackageName.isBlank()) {
            emptySet()
        } else {
            setOf(selectionKey(selectedPackageName, selectedActivityClassName))
        }
    }
    val pickedTargets = remember(pickedTargetsByKey) { pickedTargetsByKey.values.toList() }
    // 搜索时把命中的分组全部展开（否则匹配到的入口还藏在收起的分组里）；清空搜索后
    // 回到「只展开当前已选分组」，避免整屏分组都留在展开状态。
    LaunchedEffect(groups, query) {
        expandedPackages = if (query.isBlank()) {
            initialExpandedPackages(multiSelect, selectedPackageName)
        } else {
            groups.map { it.packageName }.toSet()
        }
    }

    SettingsLazyScreenScaffoldWithExpandableSearch(
        title = title,
        pageHint = if (multiSelect && pickedTargets.isNotEmpty()) {
            stringResource(R.string.share_target_batch_selected, pickedTargets.size)
        } else {
            pageHint
        },
        searchQuery = query,
        onSearchQueryChange = onSearchQueryChange,
        onBack = onBack,
        hintResId = searchHintResId,
        extraActions = {
            if (multiSelect) {
                MiuixIconButton(
                    onClick = {
                        if (pickedTargets.isNotEmpty()) {
                            onConfirmSelection(pickedTargets)
                        }
                    },
                    enabled = pickedTargets.isNotEmpty(),
                ) {
                    MiuixIcon(
                        imageVector = MiuixIcons.Medium.Ok,
                        contentDescription = stringResource(R.string.share_target_batch_confirm),
                    )
                }
            }
        },
    ) {
        shareTargetGroupedItems(
            groups = groups,
            loading = loading,
            emptyTextResId = emptyTextResId,
            expandedPackages = expandedPackages,
            activeKeys = activeKeys,
            multiSelect = multiSelect,
            onToggleGroup = { packageName ->
                expandedPackages = if (packageName in expandedPackages) {
                    expandedPackages - packageName
                } else {
                    expandedPackages + packageName
                }
            },
            onActivate = { target ->
                if (multiSelect) {
                    val key = selectionKey(target.packageName, target.activityClassName)
                    pickedTargetsByKey = if (key in pickedTargetsByKey) {
                        pickedTargetsByKey - key
                    } else {
                        pickedTargetsByKey + (key to target)
                    }
                } else {
                    onSelectTarget(target)
                }
            },
        )
    }
}

private fun initialExpandedPackages(multiSelect: Boolean, selectedPackageName: String): Set<String> =
    if (multiSelect || selectedPackageName.isBlank()) emptySet() else setOf(selectedPackageName)

private fun selectionKey(packageName: String, activityClassName: String): String =
    "$packageName/$activityClassName"

private fun LazyListScope.shareTargetGroupedItems(
    groups: List<ShareTargetAppGroup>,
    loading: Boolean,
    @StringRes emptyTextResId: Int,
    expandedPackages: Set<String>,
    activeKeys: Set<String>,
    multiSelect: Boolean,
    onToggleGroup: (String) -> Unit,
    onActivate: (ShareImageTarget) -> Unit,
) {
    when {
        loading -> {
            item(key = "share-target-loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
        groups.isEmpty() -> {
            item(key = "share-target-empty") {
                Text(
                    text = stringResource(emptyTextResId),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        else -> groups.forEachIndexed { groupIndex, group ->
            item(key = "share-target-app-${group.packageName}") {
                ShareTargetAppGroupCard(
                    group = group,
                    expanded = group.packageName in expandedPackages,
                    activeKeys = activeKeys,
                    multiSelect = multiSelect,
                    isLastGroup = groupIndex == groups.lastIndex,
                    onToggleGroup = { onToggleGroup(group.packageName) },
                    onActivate = onActivate,
                )
            }
        }
    }
}

@Composable
private fun ShareTargetAppGroupCard(
    group: ShareTargetAppGroup,
    expanded: Boolean,
    activeKeys: Set<String>,
    multiSelect: Boolean,
    isLastGroup: Boolean,
    onToggleGroup: () -> Unit,
    onActivate: (ShareImageTarget) -> Unit,
) {
    val targets = group.targets
    val expandable = targets.size > 1
    // 收起动画期间仍要保持「组头 + 子项」同一张卡片的圆角，所以延迟到动画结束再降回单行样式。
    var collapsing by remember(group.packageName) { mutableStateOf(false) }
    var wasExpanded by remember(group.packageName) { mutableStateOf(expanded) }
    LaunchedEffect(expanded) {
        if (expanded) {
            collapsing = false
        } else if (wasExpanded) {
            collapsing = true
            delay(ShareTargetCollapseMs.toLong())
            collapsing = false
        }
        wasExpanded = expanded
    }
    val cardOpen = expandable && (expanded || collapsing)
    val segmentCount = if (cardOpen) targets.size + 1 else 1

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = if (isLastGroup) 0.dp else PickerListGroupSpacing)
            .animateContentSize(animationSpec = tween(ShareTargetExpandMs)),
    ) {
        ShareTargetAppHeaderRow(
            group = group,
            expandable = expandable,
            expanded = expanded,
            segmentCount = segmentCount,
            activeKeys = activeKeys,
            multiSelect = multiSelect,
            onToggleGroup = onToggleGroup,
            onActivate = onActivate,
        )
        if (expandable) {
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = tween(ShareTargetExpandMs),
                    expandFrom = Alignment.Top,
                ),
                exit = shrinkVertically(
                    animationSpec = tween(ShareTargetCollapseMs),
                    shrinkTowards = Alignment.Top,
                ),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    targets.forEachIndexed { index, target ->
                        ShareTargetEntryRow(
                            target = target,
                            segmentIndex = index + 1,
                            segmentCount = targets.size + 1,
                            active = selectionKey(target.packageName, target.activityClassName) in activeKeys,
                            multiSelect = multiSelect,
                            onClick = { onActivate(target) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareTargetAppHeaderRow(
    group: ShareTargetAppGroup,
    expandable: Boolean,
    expanded: Boolean,
    segmentCount: Int,
    activeKeys: Set<String>,
    multiSelect: Boolean,
    onToggleGroup: () -> Unit,
    onActivate: (ShareImageTarget) -> Unit,
) {
    val leading: @Composable () -> Unit = { ShareTargetAppLeading(group) }
    if (!expandable) {
        // 只有一个入口的应用：组头本身就是可选中行，点一下直接选中 / 勾选。
        val target = group.targets.first()
        val active = selectionKey(target.packageName, target.activityClassName) in activeKeys
        Md3PickerListRow(
            segmentIndex = 0,
            segmentCount = 1,
            title = ShareImageTargetResolver.displayGroupTitle(target),
            subtitle = group.packageName,
            selected = active,
            onClick = { onActivate(target) },
            leadingContent = leading,
            trailingMode = if (multiSelect) PickerTrailingMode.Toggle else PickerTrailingMode.Radio,
            onTrailingClick = { onActivate(target) },
        )
        return
    }
    val anyActive = group.targets.any {
        selectionKey(it.packageName, it.activityClassName) in activeKeys
    }
    Md3PickerListRow(
        segmentIndex = 0,
        segmentCount = segmentCount,
        title = group.appLabel,
        subtitle = group.packageName,
        selected = false,
        onClick = onToggleGroup,
        leadingContent = leading,
        trailingMode = PickerTrailingMode.Icon,
        trailingIcon = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
        trailingIconDescription = stringResource(
            if (expanded) R.string.cd_collapse_section else R.string.cd_expand_section,
        ),
        onTrailingClick = onToggleGroup,
        trailingIconAlignedToChoice = true,
        trailingChip = if (!expanded && anyActive) {
            PickerChipSpec(
                text = stringResource(R.string.freezer_selection_selected),
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            null
        },
    )
}

@Composable
private fun ShareTargetEntryRow(
    target: ShareImageTarget,
    segmentIndex: Int,
    segmentCount: Int,
    active: Boolean,
    multiSelect: Boolean,
    onClick: () -> Unit,
) {
    Md3PickerListRow(
        segmentIndex = segmentIndex,
        segmentCount = segmentCount,
        title = target.label,
        subtitle = target.activityClassName,
        selected = active,
        onClick = onClick,
        leadingContent = {
            Md3PickerActivityLeading(
                packageName = target.packageName,
                className = target.activityClassName,
                contentDescription = target.label,
            )
        },
        trailingMode = if (multiSelect) PickerTrailingMode.Toggle else PickerTrailingMode.Radio,
        onTrailingClick = onClick,
    )
}

@Composable
private fun ShareTargetAppLeading(group: ShareTargetAppGroup) {
    Md3PickerPackageLeading(
        packageName = group.packageName,
        contentDescription = group.appLabel,
    )
}
