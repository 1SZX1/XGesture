package com.slideindex.app.ui.quicklauncher

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.slideindex.app.R
import com.slideindex.app.activity.ActivityShortcut
import com.slideindex.app.data.AppInfo
import com.slideindex.app.gesture.GestureAction
import com.slideindex.app.gesture.GestureActionType
import com.slideindex.app.launcher.QuickLauncherItem
import com.slideindex.app.launcher.QuickLauncherItemCodec
import com.slideindex.app.overlay.TaskSwitcherMenuItem
import com.slideindex.app.ui.AppPackageEntry
import com.slideindex.app.ui.gestureActionIcon
import com.slideindex.app.ui.gesturepicker.gestureActionDescription
import com.slideindex.app.ui.gesturepicker.gestureActionLabel
import com.slideindex.app.ui.gesturepicker.gestureActionLabelText
import com.slideindex.app.ui.picker.FilteredShortcutCatalog
import com.slideindex.app.ui.picker.GestureActionCatalog
import com.slideindex.app.gesture.ActionPickerCatalogPolicy
import com.slideindex.app.gesture.SlotPickerKind
import com.slideindex.app.ui.picker.GestureActionCatalogScope
import com.slideindex.app.ui.picker.activityShortcutPickerToggleSection
import com.slideindex.app.ui.picker.shortcutFolderCardsSection
import com.slideindex.app.ui.picker.systemShortcutCatalogItems
import com.slideindex.app.ui.requestPermissionForAdjustAction
import com.slideindex.app.util.AppShortcutLoader
import com.slideindex.app.util.AppShortcutLoader.toQuickLauncherItem
import com.slideindex.app.util.PinyinHelper
import com.slideindex.app.util.ShortcutScanProgress

fun LazyListScope.quickLauncherAddPickerActionItems(
    filtered: List<GestureAction>,
    configuredActionKeys: Set<String>,
    onToggleItem: (QuickLauncherItem, Boolean) -> Unit,
    onOpenExecuteShellCommand: () -> Unit,
    onOpenOpenLink: () -> Unit = {},
    onOpenSimulateKeyEvent: () -> Unit = {},
    /**
     * 「手势宏」必须带 macroId 才有意义：这里把"挑一条宏"的动作交回给调用方（由它挂选择框），
     * 拿到结果后再落库。缺省空实现时保持旧行为（不推荐，等于存了个空动作）。
     */
    onRequestRunMacro: ((GestureAction) -> Unit) -> Unit = {},
    singleSelect: Boolean = false,
    pinNoneAtTop: Boolean = false,
    noneSelected: Boolean = false,
    onSelectNone: () -> Unit = {},
    searchQuery: String = "",
) {
    if (pinNoneAtTop) {
        item(key = "ql-action-none") {
            val noneLabel = stringResource(R.string.gesture_action_none)
            val showNone = searchQuery.isBlank() || noneLabel.contains(searchQuery, ignoreCase = true)
            if (showNone) {
                com.slideindex.app.ui.Md3PickerListRow(
                    segmentIndex = 0,
                    segmentCount = 1,
                    title = noneLabel,
                    subtitle = null,
                    selected = noneSelected,
                    onClick = onSelectNone,
                    leadingContent = {
                        com.slideindex.app.ui.Md3PickerIconLeading(
                            icon = gestureActionIcon(GestureAction.None, outlined = true),
                            selected = noneSelected,
                        )
                    },
                    trailingMode = com.slideindex.app.ui.PickerTrailingMode.Radio,
                )
            }
        }
    }

    if (filtered.isEmpty()) {
        if (!pinNoneAtTop || !searchQuery.isBlank()) {
            item(key = "actions_empty") {
                Text(
                    text = stringResource(R.string.search_no_actions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                )
            }
        }
        return
    }

    val sections = GestureActionCatalog.groupIntoSections(filtered)
    sections.forEach { section ->
        item(key = "ql-action-cat-${section.category.name}") {
            com.slideindex.app.ui.Md3PickerSectionHeader(stringResource(section.category.titleRes))
        }
        items(
            count = section.actions.size,
            key = { index -> "ql-action-${section.category.name}-${section.actions[index].type.id}" },
        ) { index ->
            val action = section.actions[index]
            val context = LocalContext.current
            val label = gestureActionLabel(action)
            if (action.type == GestureActionType.OPEN_LINK) {
                QuickLauncherShellCommandActionRow(
                    action = action,
                    segmentIndex = index,
                    segmentCount = section.actions.size,
                    label = label,
                    subtitle = gestureActionDescription(action),
                    onOpenConfig = onOpenOpenLink,
                )
            } else if (action.type == GestureActionType.EXECUTE_SHELL_COMMAND) {
                QuickLauncherShellCommandActionRow(
                    action = action,
                    segmentIndex = index,
                    segmentCount = section.actions.size,
                    label = label,
                    subtitle = gestureActionDescription(action),
                    onOpenConfig = {
                        requestPermissionForAdjustAction(context, action)
                        onOpenExecuteShellCommand()
                    },
                )
            } else if (action.type == GestureActionType.SIMULATE_KEY_EVENT) {
                QuickLauncherShellCommandActionRow(
                    action = action,
                    segmentIndex = index,
                    segmentCount = section.actions.size,
                    label = stringResource(R.string.gesture_action_simulate_key_event),
                    subtitle = gestureActionDescription(action),
                    onOpenConfig = {
                        requestPermissionForAdjustAction(context, action)
                        onOpenSimulateKeyEvent()
                    },
                )
            } else if (action.type == GestureActionType.RUN_MACRO) {
                // 「手势宏」：先弹选择框挑一条宏，拿到带 macroId 的动作再落库
                //（否则会存进一个 macroId 为空的"空宏动作"，执行时静默失效）。
                val added = QuickLauncherItemCodec.actionKey(action) in configuredActionKeys
                // 列表里这一项是"模板动作"（macroId 为空）：显示通用名，别显示成"宏已删除"。
                val macroLabel = if ((action as? GestureAction.RunMacro)?.macroId.isNullOrBlank()) {
                    stringResource(R.string.macro_action_run_macro)
                } else {
                    gestureActionLabelText(context, action)
                }
                QuickLauncherActionRow(
                    action = action,
                    segmentIndex = index,
                    segmentCount = section.actions.size,
                    label = macroLabel,
                    subtitle = gestureActionDescription(action),
                    added = added,
                    singleSelect = singleSelect,
                    onToggle = {
                        onRequestRunMacro { picked ->
                            if (!added) {
                                requestPermissionForAdjustAction(context, picked)
                            }
                            // 配置型动作一律按"新增"落库（与 Shell 命令/打开链接的约定一致）：
                            // 否则在"已添加"状态下选完宏反而会把它取消掉，表现为"选了没反应"。
                            onToggleItem(
                                QuickLauncherItem.action(picked, gestureActionLabelText(context, picked)),
                                false,
                            )
                        }
                    },
                )
            } else {
                val added = QuickLauncherItemCodec.actionKey(action) in configuredActionKeys
                QuickLauncherActionRow(
                    action = action,
                    segmentIndex = index,
                    segmentCount = section.actions.size,
                    label = label,
                    subtitle = gestureActionDescription(action),
                    added = added,
                    singleSelect = singleSelect,
                    onToggle = {
                        if (!added) {
                            requestPermissionForAdjustAction(context, action)
                        }
                        onToggleItem(QuickLauncherItem.action(action, label), if (singleSelect) false else added)
                    },
                )
            }
        }
    }
}

fun LazyListScope.quickLauncherAddPickerAppItems(
    filtered: List<AppInfo>,
    configuredAppPackages: Set<String>,
    onToggle: (AppInfo, Boolean) -> Unit,
    singleSelect: Boolean = false,
) {
    items(filtered.size, key = { filtered[it].packageName }) { index ->
        val app = filtered[index]
        val added = app.packageName in configuredAppPackages
        QuickLauncherToggleRow(
            entry = AppPackageEntry.Installed(app),
            segmentIndex = index,
            segmentCount = filtered.size,
            added = added,
            singleSelect = singleSelect,
            onToggle = { onToggle(app, if (singleSelect) false else added) },
        )
    }
}

fun LazyListScope.quickLauncherAddPickerShortcutItems(
    searchQuery: String,
    activityShortcuts: List<ActivityShortcut>,
    configuredShortcutKeys: Set<String>,
    filtered: FilteredShortcutCatalog,
    appsByPackage: Map<String, AppInfo>,
    loading: Boolean,
    scanProgress: ShortcutScanProgress?,
    onCreateHostClick: (AppShortcutLoader.CreateShortcutHost) -> Unit,
    onToggle: (AppInfo, TaskSwitcherMenuItem, Boolean) -> Unit,
    onToggleActivityShortcut: (QuickLauncherItem, Boolean) -> Unit,
    onBrowseActivityShortcut: () -> Unit,
    onOpenMyShortcuts: () -> Unit = {},
    onOpenPresetShortcuts: () -> Unit = {},
    singleSelect: Boolean = false,
) {
    if (searchQuery.isBlank()) {
        shortcutFolderCardsSection(
            activityShortcutsCount = activityShortcuts.size,
            onOpenMyShortcuts = onOpenMyShortcuts,
            onOpenPresetShortcuts = onOpenPresetShortcuts,
        )
    } else if (activityShortcuts.isNotEmpty()) {
        activityShortcutPickerToggleSection(
            activityShortcuts = activityShortcuts,
            configuredShortcutKeys = configuredShortcutKeys,
            onToggle = onToggleActivityShortcut,
            onBrowse = onBrowseActivityShortcut,
            searchQuery = searchQuery,
            singleSelect = singleSelect,
        )
    }
    systemShortcutCatalogItems(
        filtered = filtered,
        appsByPackage = appsByPackage,
        loading = loading,
        scanProgress = scanProgress,
        onCreateHostClick = onCreateHostClick,
        shortcutRowContent = { group, shortcut, segmentIndex, segmentCount ->
            val item = shortcut.toQuickLauncherItem(group.app.packageName)
            val added = QuickLauncherItemCodec.shortcutItemKey(item) in configuredShortcutKeys
            ShortcutCatalogRow(
                shortcut = shortcut,
                segmentIndex = segmentIndex,
                segmentCount = segmentCount,
                added = added,
                onToggle = { onToggle(group.app, shortcut, if (singleSelect) false else added) },
            )
        },
    )
}

@Composable
fun rememberQuickLauncherFilteredActions(searchQuery: String): List<GestureAction> {
    val context = LocalContext.current
    val actionOptions = remember {
        GestureActionCatalog.build(
            scope = GestureActionCatalogScope.QuickLauncher,
            catalogPolicy = ActionPickerCatalogPolicy.Slot(SlotPickerKind.OverlayTap),
        )
    }
    return remember(actionOptions, searchQuery, context) {
        GestureActionCatalog.filter(context, actionOptions, searchQuery)
    }
}

@Composable
fun rememberQuickLauncherFilteredApps(
    apps: List<AppInfo>,
    searchQuery: String,
    enabled: Boolean = true,
): List<AppInfo> {
    val query = searchQuery.trim().lowercase()
    return remember(apps, query, enabled) {
        if (!enabled || apps.isEmpty()) return@remember emptyList()
        val filtered = apps.filter { app ->
            query.isEmpty() ||
                app.label.lowercase().contains(query) ||
                app.packageName.lowercase().contains(query) ||
                app.pinyinKey.contains(query)
        }
        filtered.sortedBy { it.pinyinKey }
    }
}
