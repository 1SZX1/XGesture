package com.slideindex.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slideindex.app.R
import com.slideindex.app.gesture.GestureAction
import com.slideindex.app.gesture.GestureActionType
import com.slideindex.app.ui.miuix.MiuixTabRowWithContourInCard
import com.slideindex.app.ui.settings.components.SettingsScreenScaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 扩展页里的「图标库」：**全局**给动作类型 / 应用指定图标。
 *
 * 每一行都能设置完整来源（图标库 / 文字 / 应用图标 / 相册 / 恢复默认），
 * 未设置时显示**默认图标**（动作类型图标 / 应用自带图标）。
 */
@Composable
fun GlobalIconLibraryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    val installedApps = remember { loadInstalledAppIcons(context) }
    // 用 catalog 的实例（而不是 enum）：这样有中文名和默认图标，同一类型只保留一个代表
    val actions = remember {
        val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
        com.slideindex.app.ui.picker.GestureActionCatalog.build(
            scope = com.slideindex.app.ui.picker.GestureActionCatalogScope.GesturePicker,
            catalogPolicy = com.slideindex.app.gesture.ActionPickerCatalogPolicy.EdgeGesture,
        )
            .distinctBy { it.type }
            // 按中文名的拼音排序（而不是英文枚举名）
            .sortedWith(
                compareBy(collator) {
                    com.slideindex.app.ui.gesturepicker.gestureActionLabelText(context, it)
                },
            )
    }

    val actionsTabLabel = stringResource(R.string.global_icon_tab_actions)
    val appsTabLabel = stringResource(R.string.global_icon_tab_apps)

    SettingsScreenScaffold(
        title = stringResource(R.string.action_icon_library_title),
        onBack = onBack,
    ) {
        item(key = "global-icon-tabs") {
            MiuixTabRowWithContourInCard(
                tabs = listOf(actionsTabLabel, appsTabLabel),
                selectedTabIndex = tab,
                onTabSelected = { tab = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (tab == 0) {
            items(
                items = actions,
                key = { "action-${it.type.id}" },
            ) { action ->
                GlobalIconRow(
                    label = gestureActionLabel(action),
                    currentKey = GlobalIconStore.actionIcon(context, action.type.name),
                    defaultIcon = gestureActionIcon(action, outlined = true),
                    defaultDrawable = null,
                    onPick = { key -> GlobalIconStore.setActionIcon(context, action.type.name, key) },
                    resetKey = "global:action:${action.type.name}",
                )
            }
        } else {
            items(items = installedApps, key = { "app-${it.packageName}" }) { app ->
                GlobalIconRow(
                    label = app.label,
                    currentKey = GlobalIconStore.appIcon(context, app.packageName),
                    defaultIcon = null,
                    defaultDrawable = app.icon,
                    onPick = { key -> GlobalIconStore.setAppIcon(context, app.packageName, key) },
                    resetKey = "global:app:${app.packageName}",
                )
            }
        }
    }
}

/**
 * 一行：图标（可点，弹完整来源菜单）+ 名称 + 「恢复默认」。
 *
 * 用本地 state 保证"选完立刻变"（写 store 不会触发重组）。
 */
/**
 * 一行：Miuix 下拉行（整行可点）+ 左侧图标 + 「恢复默认」。
 *
 * 用 `WindowDropdownMenu`：整行点击展开 Miuix 菜单，菜单项就是完整来源列表。
 */
@Composable
private fun GlobalIconRow(
    label: String,
    currentKey: String?,
    defaultIcon: ImageVector?,
    defaultDrawable: android.graphics.drawable.Drawable?,
    onPick: (String?) -> Unit,
    resetKey: String,
) {
    val context = LocalContext.current
    var key by remember(label) { mutableStateOf(currentKey) }
    var textDialogOpen by remember { mutableStateOf(false) }
    var appDialogOpen by remember { mutableStateOf(false) }

    val libraryLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val picked = result.data?.getStringExtra(
                com.slideindex.app.service.IconLibraryPickerTrampolineActivity.EXTRA_PICKED_KEY,
            )
            if (!picked.isNullOrBlank()) {
                key = picked
                onPick(picked)
            }
        }
    }

    val galleryLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            val saved = ActionIconStorage.saveFromUriForGlobalKey(context, resetKey, uri)
            if (saved != null) {
                val newKey = ActionIconKeyCodec.file(saved)
                key = newKey
                onPick(newKey)
            }
        }
    }

    val fontIcon = remember(key) { IconFontCatalog.resolve(context, key) }
    val keySnapshot = key
    val bitmap: Bitmap? = remember(keySnapshot, defaultDrawable) {
        if (keySnapshot != null && ActionIconKeyCodec.isFile(keySnapshot)) {
            val path = keySnapshot.removePrefix("file:")
            runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull()
        } else {
            defaultDrawable?.let { drawable ->
                runCatching {
                    val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
                    val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bmp)
                    drawable.setBounds(0, 0, w, h)
                    drawable.draw(canvas)
                    bmp
                }.getOrNull()
            }
        }
    }

    val menuEntry = top.yukonga.miuix.kmp.basic.DropdownEntry(
        items = buildList {
            add(
                top.yukonga.miuix.kmp.basic.DropdownItem(
                    text = stringResource(R.string.action_icon_from_library),
                    onClick = {
                        libraryLauncher.launch(
                            com.slideindex.app.service.IconLibraryPickerTrampolineActivity
                                .createIntent(context, key),
                        )
                    },
                ),
            )
            add(
                top.yukonga.miuix.kmp.basic.DropdownItem(
                    text = stringResource(R.string.action_icon_text),
                    onClick = { textDialogOpen = true },
                ),
            )
            add(
                top.yukonga.miuix.kmp.basic.DropdownItem(
                    text = stringResource(R.string.action_icon_from_app),
                    onClick = { appDialogOpen = true },
                ),
            )
            add(
                top.yukonga.miuix.kmp.basic.DropdownItem(
                    text = stringResource(R.string.action_icon_from_gallery),
                    onClick = {
                        galleryLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                ),
            )
            if (key != null) {
                add(
                    top.yukonga.miuix.kmp.basic.DropdownItem(
                        text = stringResource(R.string.action_detail_icon_reset),
                        onClick = {
                            ActionIconStorage.deleteForGlobalKey(context, resetKey)
                            key = null
                            onPick(null)
                        },
                    ),
                )
            }
        },
    )

    top.yukonga.miuix.kmp.menu.WindowIconDropdownMenu(
        entry = menuEntry,
        modifier = Modifier.fillMaxWidth(),
        minHeight = 64.dp,
        backgroundColor = androidx.compose.ui.graphics.Color.Transparent,
        cornerRadius = 12.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        fontIcon != null -> Text(
                            text = fontIcon.second,
                            fontFamily = fontIcon.first,
                            fontSize = 27.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        bitmap != null -> Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                        defaultIcon != null -> Icon(
                            imageVector = defaultIcon,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(27.dp),
                        )
                        else -> Text(
                            text = label.take(1),
                            fontSize = 16.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                // 已自定义：图标右下角蓝点
                if (key != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(12.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MiuixTheme.colorScheme.primary)
                            .border(
                                width = 1.5.dp,
                                color = MiuixTheme.colorScheme.surface,
                                shape = androidx.compose.foundation.shape.CircleShape,
                            ),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = label,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (key.isNullOrBlank()) {
                        stringResource(R.string.global_icon_default)
                    } else {
                        stringResource(R.string.global_icon_custom)
                    },
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            // 已自定义：右侧小标签（比副标题醒目）
            if (key != null) {
                Text(
                    text = stringResource(R.string.global_icon_custom),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }

    if (textDialogOpen) {
        ActionTextIconDialog(
            initial = key,
            onPick = { picked ->
                textDialogOpen = false
                key = picked
                onPick(picked)
            },
            onDismiss = { textDialogOpen = false },
        )
    }

    if (appDialogOpen) {
        ActionAppIconPickerDialog(
            action = GestureAction.None,
            onPick = { picked ->
                appDialogOpen = false
                key = picked
                onPick(picked)
            },
            onDismiss = { appDialogOpen = false },
        )
    }
}
