@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.slideindex.app.ui


import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.automirrored.filled.Shortcut
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.slideindex.app.R
import com.slideindex.app.activity.ActivityShortcut
import com.slideindex.app.activity.ActivityShortcutKind
import com.slideindex.app.activity.ManagedShortcutIconResolver
import com.slideindex.app.activity.findForLaunchShortcut
import com.slideindex.app.data.AppInfo
import com.slideindex.app.gesture.GestureAction
import com.slideindex.app.launcher.isShellActivityShortcut
import com.slideindex.app.launcher.showsShellActivityShortcutBadge
import com.slideindex.app.overlay.ShellCommandBadgeOverlay
import com.slideindex.app.overlay.ShortcutBadgeOverlay
import com.slideindex.app.ui.miuix.miuixGroupedCardItem
import com.slideindex.app.util.PickerAppIconBitmap
import com.slideindex.app.util.toSafeImageBitmap
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference

internal val PickerListScaffoldEmbeddedHorizontalPadding = 0.dp
internal val PickerListOverlayHorizontalPadding = 12.dp
internal val PickerListHorizontalPadding = PickerListScaffoldEmbeddedHorizontalPadding
internal val PickerListGroupSpacing = 12.dp
internal val PickerListContentPadding = PaddingValues(
    horizontal = PickerListScaffoldEmbeddedHorizontalPadding,
    vertical = 8.dp,
)

enum class PickerTrailingMode {
    None,
    Radio,
    Toggle,
    Icon,
}

@Composable
internal fun settingsSegmentedColors() = ListItemDefaults.segmentedColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    selectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
)

@Composable
internal fun pickerSegmentedColors() = ListItemDefaults.segmentedColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
)

@Composable
internal fun pickerSegmentedShapes(index: Int, count: Int) =
    ListItemDefaults.segmentedShapes(index = index, count = count)

@Composable
internal fun pickerListSegmentedGap() = ListItemDefaults.SegmentedGap

private const val PickerSegmentGroupMax = 12

internal fun pickerSegmentIndex(index: Int, total: Int): Int =
    if (total <= PickerSegmentGroupMax) index else 0

internal fun pickerSegmentCount(total: Int): Int =
    if (total <= PickerSegmentGroupMax) total else 1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PickerSearchListHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    @androidx.annotation.StringRes hintResId: Int = R.string.search_hint,
    horizontalPadding: Dp = PickerListScaffoldEmbeddedHorizontalPadding,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchBar(
            query = query,
            onQueryChange = onQueryChange,
            hintResId = hintResId,
            modifier = if (trailing != null) Modifier.weight(1f) else Modifier,
        )
        trailing?.invoke()
    }
}

@Composable
fun Md3PickerSectionHeader(title: String, modifier: Modifier = Modifier) {
    SmallTitle(
        text = title,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * 行尾的状态胶囊：同色文字 + 14% 同色底（深色主题下也看得清，不依赖 container 配色）。
 *
 * 只用来标「例外」——未导出、入口这类少数行。像「已导出」这种恒真的状态不该上胶囊：
 * 满屏同色胶囊会互相稀释，真要扫视时反而找不到重点。
 */
@Composable
fun PickerStatusChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** 行尾状态胶囊的内容与配色。 */
data class PickerChipSpec(val text: String, val color: Color)

/** 行尾槽位：`endActions` 是必填闭包，没有胶囊时这里什么都不画，别让调用点各写一份空判断。 */
@Composable
private fun RowScope.PickerRowEndSlot(chip: PickerChipSpec?) {
    chip?.let { PickerStatusChip(text = it.text, color = it.color) }
}

/** 闪烁时卡片底色最多往 primary 方向拉多少（再大就开始压文字的对比度了）。 */
private const val HighlightPulseMaxTint = 0.22f

@Composable
fun Md3PickerListRow(
    segmentIndex: Int,
    segmentCount: Int,
    title: String,
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingContent: @Composable () -> Unit,
    supportingContent: (@Composable () -> Unit)? = null,
    trailingMode: PickerTrailingMode = PickerTrailingMode.None,
    trailingIcon: ImageVector? = null,
    trailingIconDescription: String? = null,
    onTrailingClick: (() -> Unit)? = null,
    /**
     * Icon 模式下把行尾图标按 26dp 盒子里居中渲染，与单选 / 勾选控件（同为 26dp、贴内边距右缘）
     * 在竖向上对齐；此时图标不再单独响应点击，整行点击即触发 [onTrailingClick] / [onClick]。
     *
     * 默认 false 保持原有 IconButton（48dp 触控区）行为，其它调用点零影响。
     */
    trailingIconAlignedToChoice: Boolean = false,
    /**
     * 行尾（右侧、选中标记左边）的状态胶囊，用于标「未导出」「入口」这类例外。
     *
     * 空值不占位：四档 trailingMode 里都走同一个空判断，调用点不用各写一遍。
     */
    trailingChip: PickerChipSpec? = null,
    enabled: Boolean = true,
    /**
     * 「定位到已绑定的那一项」时的闪烁强度（0..1，由调用方用 `Animatable` 驱动）。
     *
     * 只是把卡片底色往 primary 方向拉一点 —— 比加阴影便宜得多（LazyColumn 里每帧重算 elevation
     * 开销明显），而且深浅色主题下都看得见。默认 0 = 完全不参与，其它调用点零影响。
     */
    highlightPulse: Float = 0f,
) {
    val cardModifier = modifier.miuixGroupedCardItem(
        index = segmentIndex,
        count = segmentCount,
        color = if (highlightPulse > 0f) {
            lerp(
                MiuixTheme.colorScheme.surfaceContainer,
                MiuixTheme.colorScheme.primary,
                (highlightPulse * HighlightPulseMaxTint).coerceIn(0f, HighlightPulseMaxTint),
            )
        } else {
            MiuixTheme.colorScheme.surfaceContainer
        },
    )
    val summaryText = subtitle?.takeIf { supportingContent == null }
    val bottomAction: (@Composable () -> Unit)? = supportingContent?.let { extra ->
        { extra() }
    }

    when (trailingMode) {
        PickerTrailingMode.Radio -> {
            val click = onClick ?: return
            RadioButtonPreference(
                modifier = cardModifier,
                title = title,
                summary = summaryText,
                selected = selected,
                onClick = click,
                enabled = enabled,
                startAction = { leadingContent() },
                endActions = { PickerRowEndSlot(trailingChip) },
                radioButtonLocation = RadioButtonLocation.End,
                bottomAction = bottomAction,
            )
        }
        PickerTrailingMode.Toggle -> {
            val click = onTrailingClick ?: onClick ?: return
            CheckboxPreference(
                modifier = cardModifier,
                title = title,
                summary = summaryText,
                checked = selected,
                onCheckedChange = { click() },
                enabled = enabled,
                startAction = { leadingContent() },
                endActions = { PickerRowEndSlot(trailingChip) },
                checkboxLocation = CheckboxLocation.End,
                bottomAction = bottomAction,
            )
        }
        PickerTrailingMode.Icon -> {
            val icon = trailingIcon ?: return
            val trailingClick = onTrailingClick ?: onClick ?: return
            val rowClick = onClick ?: trailingClick
            BasicComponent(
                modifier = cardModifier,
                title = title,
                summary = summaryText,
                enabled = enabled,
                onClick = rowClick,
                startAction = { leadingContent() },
                endActions = {
                    PickerRowEndSlot(trailingChip)
                    if (trailingIconAlignedToChoice) {
                        Box(
                            modifier = Modifier.size(26.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            MiuixIcon(
                                imageVector = icon,
                                contentDescription = trailingIconDescription,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    } else {
                        IconButton(onClick = trailingClick) {
                            MiuixIcon(
                                imageVector = icon,
                                contentDescription = trailingIconDescription,
                            )
                        }
                    }
                },
                bottomAction = bottomAction,
            )
        }
        PickerTrailingMode.None -> {
            BasicComponent(
                modifier = cardModifier,
                title = title,
                summary = summaryText,
                enabled = enabled,
                onClick = onClick,
                startAction = { leadingContent() },
                endActions = { PickerRowEndSlot(trailingChip) },
                bottomAction = bottomAction,
            )
        }
    }
}

@Composable
fun Md3PickerIconLeading(
    icon: ImageVector,
    selected: Boolean,
    contentDescription: String? = null,
) {
    val containerShape = if (selected) {
        MaterialShapes.Cookie9Sided.toShape()
    } else {
        MaterialTheme.shapes.small
    }
    Surface(
        modifier = Modifier.size(40.dp),
        shape = containerShape,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(24.dp),
                tint = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
fun Md3PickerManagedShortcutLeading(
    shortcut: ActivityShortcut,
    selected: Boolean,
) {
    val context = LocalContext.current
    val fallbackIcon = when (shortcut.kind) {
        ActivityShortcutKind.COMPONENT -> Icons.AutoMirrored.Filled.Launch
        else -> Icons.AutoMirrored.Filled.Shortcut
    }
    val iconBitmap = remember(shortcut.id, shortcut.iconPath, shortcut.identityKey()) {
        ManagedShortcutIconResolver.drawableForManaged(context, shortcut)
            ?.toBitmap(96, 96)
    }
    if (iconBitmap != null) {
        Image(
            bitmap = iconBitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Fit,
        )
    } else {
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun Md3PickerAppShortcutLeading(
    packageName: String,
    selected: Boolean = false,
    contentDescription: String? = null,
) {
    Box(
        modifier = Modifier.size(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Md3PickerPackageLeading(
            packageName = packageName,
            contentDescription = contentDescription,
            selected = selected,
        )
        ShortcutBadgeOverlay(
            iconSize = 40.dp,
            modifier = Modifier.size(40.dp),
        )
    }
}

@Composable
fun Md3PickerLaunchShortcutLeading(
    action: GestureAction.LaunchShortcut,
    activityShortcuts: List<ActivityShortcut>,
    selected: Boolean = false,
) {
    val managed = remember(action.payloadKey, activityShortcuts) {
        activityShortcuts.findForLaunchShortcut(action.payloadKey)
    }
    Box(
        modifier = Modifier.size(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (managed != null) {
            Md3PickerManagedShortcutLeading(shortcut = managed, selected = selected)
        } else {
            val hostPackage = ManagedShortcutIconResolver.hostPackageForLaunchShortcut(action.payloadKey).orEmpty()
            Md3PickerPackageLeading(
                packageName = hostPackage,
                contentDescription = action.label,
                selected = selected,
            )
        }
        when {
            action.showsShellActivityShortcutBadge(activityShortcuts) -> {
                ShellCommandBadgeOverlay(
                    iconSize = 40.dp,
                    modifier = Modifier.size(40.dp),
                )
            }
            !action.isShellActivityShortcut(activityShortcuts) -> {
                ShortcutBadgeOverlay(
                    iconSize = 40.dp,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
    }
}

@Composable
fun Md3PickerPackageLeading(
    packageName: String,
    contentDescription: String? = null,
    selected: Boolean = false,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var bitmap by remember(packageName) {
        mutableStateOf(if (packageName.isBlank()) null else PickerAppIconBitmap.peek(packageName))
    }
    var failed by remember(packageName) { mutableStateOf(packageName.isBlank()) }
    LaunchedEffect(packageName) {
        if (packageName.isNotBlank() && bitmap == null && !failed) {
            val loaded = PickerAppIconBitmap.load(context, packageName)
            if (loaded == null) {
                failed = true
            } else {
                bitmap = loaded
            }
        }
    }
    val currentBitmap = bitmap
    if (currentBitmap != null) {
        // 真实图标不套底色方块：选中态交给行尾单选点 / 开关，图标本身保持裸展示，
        // 一屏几十个应用时不会有满屏浅色格子把内容压扁。
        Image(
            bitmap = currentBitmap,
            contentDescription = contentDescription,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Fit,
        )
    } else {
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Shortcut,
                contentDescription = contentDescription,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun Md3PickerAppLeading(app: AppInfo) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 全局图标覆盖优先（扩展 → 图标库 → 应用图标）
    val iconOverride = remember(app.packageName) {
        GlobalIconStore.appIcon(context, app.packageName)
    }
    if (iconOverride != null) {
        ActionIconView(
            action = com.slideindex.app.gesture.GestureAction.LaunchApp(app.packageName),
            iconKey = iconOverride,
            sizeDp = 40,
            // 与其它真实图标保持一致：不画底色方块。
            showBackground = false,
        )
        return
    }
    var bitmap by remember(app.packageName) {
        mutableStateOf(PickerAppIconBitmap.peek(app.packageName))
    }
    var failed by remember(app.packageName) { mutableStateOf(false) }
    LaunchedEffect(app.packageName) {
        if (bitmap == null && !failed) {
            val loaded = PickerAppIconBitmap.load(context, app.packageName)
            if (loaded == null) {
                failed = true
            } else {
                bitmap = loaded
            }
        }
    }
    Md3PickerBitmapLeading(
        bitmap = bitmap,
        failed = failed,
        contentDescription = app.label,
    )
}

@Composable
fun Md3PickerActivityLeading(
    packageName: String,
    className: String,
    contentDescription: String,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val cacheKey = "$packageName/$className"
    // 只认 Activity 缓存；不要用 App 图标 peek，否则会短路掉真正的 Activity 图标加载。
    var bitmap by remember(cacheKey) {
        mutableStateOf(PickerAppIconBitmap.peekActivity(packageName, className))
    }
    var failed by remember(cacheKey) { mutableStateOf(false) }
    LaunchedEffect(cacheKey) {
        val loaded = PickerAppIconBitmap.loadActivity(context, packageName, className)
        if (loaded == null) {
            failed = true
            bitmap = null
        } else {
            failed = false
            bitmap = loaded
        }
    }
    Md3PickerBitmapLeading(
        bitmap = bitmap,
        failed = failed,
        contentDescription = contentDescription,
    )
}

@Composable
private fun Md3PickerBitmapLeading(
    bitmap: ImageBitmap?,
    failed: Boolean,
    contentDescription: String,
) {
    when {
        bitmap != null -> {
            Image(
                bitmap = bitmap,
                contentDescription = contentDescription,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Fit,
            )
        }
        failed -> {
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        else -> {
            Box(modifier = Modifier.size(40.dp))
        }
    }
}

@Composable
fun Md3PickerDrawableLeading(
    drawable: android.graphics.drawable.Drawable,
    contentDescription: String,
    cacheKey: String,
) {
    val bitmap = remember(cacheKey) { drawable.toSafeImageBitmap(96) }
    Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp)),
        contentScale = ContentScale.Fit,
    )
}

@Composable
fun Md3PickerAppEntryLeading(
    entry: AppPackageEntry,
    missingIcon: ImageVector,
) {
    when (entry) {
        is AppPackageEntry.Installed -> Md3PickerAppLeading(entry.app)
        is AppPackageEntry.Missing -> Md3PickerIconLeading(
            icon = missingIcon,
            selected = false,
        )
    }
}

@Composable
fun Md3PickerSupportingHints(
    description: String?,
    permissionHint: String?,
) {
    if (description.isNullOrBlank() && permissionHint.isNullOrBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (!description.isNullOrBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!permissionHint.isNullOrBlank()) {
            Text(
                text = permissionHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}
