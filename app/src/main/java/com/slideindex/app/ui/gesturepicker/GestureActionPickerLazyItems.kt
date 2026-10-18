package com.slideindex.app.ui.gesturepicker

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
import com.slideindex.app.gesture.ActionPickerCatalogPolicy
import com.slideindex.app.gesture.GestureTriggerType
import com.slideindex.app.gesture.LaunchWindowMode
import com.slideindex.app.gesture.SlotPickerKind
import com.slideindex.app.ui.picker.FilteredShortcutCatalog
import com.slideindex.app.ui.picker.GestureActionCatalog
import com.slideindex.app.ui.picker.GestureActionCatalogScope
import com.slideindex.app.ui.picker.activityShortcutPickerRadioSection
import com.slideindex.app.ui.picker.shortcutFolderCardsSection
import com.slideindex.app.ui.picker.systemShortcutCatalogItems
import com.slideindex.app.ui.requestPermissionForAdjustAction
import com.slideindex.app.util.AppShortcutLoader
import com.slideindex.app.util.ShortcutScanProgress

@Composable
fun rememberActionPickerFilteredActions(
    trigger: GestureTriggerType,
    searchQuery: String,
    includePointerGestureActions: Boolean,
    includeCornerInnerZoneActions: Boolean,
    pinNoneAtTop: Boolean,
    catalogPolicy: ActionPickerCatalogPolicy,
): List<GestureAction> {
    val context = LocalContext.current
    val actionOptions = remember(
        trigger,
        includePointerGestureActions,
        includeCornerInnerZoneActions,
        catalogPolicy,
    ) {
        GestureActionCatalog.build(
            scope = GestureActionCatalogScope.GesturePicker,
            trigger = trigger,
            includePointerGestureActions = includePointerGestureActions,
            includeCornerInnerZoneActions = includeCornerInnerZoneActions,
            catalogPolicy = catalogPolicy,
        )
    }
    return remember(
        actionOptions,
        searchQuery,
        context,
        includeCornerInnerZoneActions,
        pinNoneAtTop,
    ) {
        GestureActionCatalog.filter(
            context = context,
            actions = actionOptions,
            query = searchQuery,
            pinNoneAtTop = pinNoneAtTop,
            includeCornerInnerZoneActions = includeCornerInnerZoneActions,
        )
    }
}

@Composable
fun rememberActionPickerFilteredApps(
    apps: List<AppInfo>,
    searchQuery: String,
): List<AppInfo> {
    val query = searchQuery.trim().lowercase()
    return remember(apps, query) {
        apps.filter { app ->
            query.isEmpty() ||
                app.label.lowercase().contains(query) ||
                app.packageName.lowercase().contains(query) ||
                app.pinyinKey.contains(query)
        }.sortedBy { it.pinyinKey }
    }
}

fun LazyListScope.actionPickerActionItems(
    filtered: List<GestureAction>,
    current: GestureAction,
    onSelect: (GestureAction) -> Unit,
    onOpenExecuteShellCommand: () -> Unit,
    onOpenOpenLink: () -> Unit = {},
    onOpenSimulateKeyEvent: (() -> Unit)? = null,
    onOpenQuickWheel: (() -> Unit)? = null,
    onOpenQuickLauncher: (() -> Unit)? = null,
    /** 定位到"当前已绑定动作"之后闪一下的强度（0..1），只作用在当前那一行上。 */
    highlightPulse: Float = 0f,
) {
    if (filtered.isEmpty()) {
        item(key = "actions-empty") {
            Text(
                text = stringResource(R.string.search_no_actions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
            )
        }
        return
    }

    val sections = GestureActionCatalog.groupIntoSections(filtered)
    sections.forEach { section ->
        item(key = "action-cat-${section.category.name}") {
            com.slideindex.app.ui.Md3PickerSectionHeader(stringResource(section.category.titleRes))
        }
        items(
            count = section.actions.size,
            key = { index -> "action-${section.category.name}-${section.actions[index].type.id}" },
        ) { index ->
            val action = section.actions[index]
            val context = LocalContext.current
            if (action.type == GestureActionType.OPEN_LINK) {
                val linkSubtitle = if (current is GestureAction.OpenLink) {
                    gestureActionSettingSubtitle(current)
                } else {
                    gestureActionDescription(action)
                }
                ActionPickerOpenLinkRow(
                    action = action,
                    segmentIndex = index,
                    segmentCount = section.actions.size,
                    subtitle = linkSubtitle,
                    onOpenConfig = onOpenOpenLink,
                    highlightPulse = if (current is GestureAction.OpenLink) highlightPulse else 0f,
                )
            } else if (action.type == GestureActionType.EXECUTE_SHELL_COMMAND) {
                val shellSubtitle = if (
                    current is GestureAction.ExecuteShellCommand &&
                    current.command.isNotBlank()
                ) {
                    gestureActionSettingSubtitle(current)
                } else {
                    gestureActionDescription(action)
                }
                ActionPickerExecuteShellCommandRow(
                    action = action,
                    segmentIndex = index,
                    segmentCount = section.actions.size,
                    subtitle = shellSubtitle,
                    onOpenConfig = {
                        requestPermissionForAdjustAction(context, action)
                        onOpenExecuteShellCommand()
                    },
                    highlightPulse = if (current is GestureAction.ExecuteShellCommand) highlightPulse else 0f,
                )
            } else if (action.type == GestureActionType.SIMULATE_KEY_EVENT && onOpenSimulateKeyEvent != null) {
                val keySubtitle = if (current is GestureAction.SimulateKeyEvent) {
                    gestureActionSettingSubtitle(current)
                } else {
                    gestureActionDescription(action)
                }
                ActionPickerSimulateKeyEventRow(
                    action = action,
                    segmentIndex = index,
                    segmentCount = section.actions.size,
                    subtitle = keySubtitle,
                    onOpenConfig = {
                        requestPermissionForAdjustAction(context, action)
                        onOpenSimulateKeyEvent()
                    },
                    highlightPulse = if (current is GestureAction.SimulateKeyEvent) highlightPulse else 0f,
                )
            } else if (action.type == GestureActionType.QUICK_WHEEL && onOpenQuickWheel != null) {
                // 快捷轮盘要先选「哪个轮盘 + 以什么形态呼出」，所以点它是打开配置而不是直接选中；
                // 但"当前就是这个动作"时必须按已选中显示（主色标题 + 打勾），与其它动作一致。
                ActionPickerQuickWheelRow(
                    action = action,
                    segmentIndex = index,
                    segmentCount = section.actions.size,
                    subtitle = gestureActionDescription(action),
                    selected = current is GestureAction.QuickWheel,
                    onOpenConfig = onOpenQuickWheel,
                    highlightPulse = highlightPulse,
                )
            } else if (action.type == GestureActionType.QUICK_LAUNCHER && onOpenQuickLauncher != null) {
                // 快速启动器要先选「哪个面板 + 是否钉住」，所以点它同样是打开配置而不是直接选中。
                ActionPickerActionRow(
                    action = action,
                    segmentIndex = index,
                    segmentCount = section.actions.size,
                    selected = current is GestureAction.QuickLauncher,
                    onClick = {
                        requestPermissionForAdjustAction(context, action)
                        onOpenQuickLauncher()
                    },
                    highlightPulse = if (current is GestureAction.QuickLauncher) highlightPulse else 0f,
                )
            } else {
                ActionPickerActionRow(
                    action = action,
                    segmentIndex = index,
                    segmentCount = section.actions.size,
                    selected = when {
                        action.type == current.type &&
                            action.type != GestureActionType.LAUNCH_APP &&
                            action.type != GestureActionType.LAUNCH_SHORTCUT -> true
                        else -> false
                    },
                    onClick = {
                        requestPermissionForAdjustAction(context, action)
                        onSelect(action)
                    },
                    highlightPulse = highlightPulse,
                )
            }
        }
    }
}

fun LazyListScope.actionPickerAppItems(
    filtered: List<AppInfo>,
    current: GestureAction,
    onSelect: (AppInfo) -> Unit,
    /** 定位到"当前已绑定应用"之后闪一下的强度（0..1），只作用在当前那一行上。 */
    highlightPulse: Float = 0f,
) {
    items(filtered.size, key = { filtered[it].packageName }) { index ->
        val app = filtered[index]
        val currentApp = current as? GestureAction.LaunchApp
        val selected = currentApp?.packageName == app.packageName
        ActionPickerAppRow(
            app = app,
            segmentIndex = index,
            segmentCount = filtered.size,
            selected = selected,
            windowMode = if (selected) currentApp.windowMode else LaunchWindowMode.FOLLOW_GLOBAL,
            onSelect = onSelect,
            highlightPulse = highlightPulse,
        )
    }
}

fun LazyListScope.actionPickerShortcutItems(
    appsByPackage: Map<String, AppInfo>,
    searchQuery: String,
    current: GestureAction,
    onSelect: (GestureAction) -> Unit,
    activityShortcuts: List<ActivityShortcut>,
    onOpenMyShortcuts: () -> Unit,
    onOpenPresetShortcuts: () -> Unit,
    onBrowseActivityShortcut: () -> Unit,
    filtered: FilteredShortcutCatalog,
    loading: Boolean,
    scanProgress: ShortcutScanProgress?,
    onCreateHostClick: (AppShortcutLoader.CreateShortcutHost) -> Unit,
    shortcutRowContent: @Composable (
        group: AppShortcutLoader.AppShortcutGroup,
        shortcut: com.slideindex.app.overlay.TaskSwitcherMenuItem,
        segmentIndex: Int,
        segmentCount: Int,
    ) -> Unit,
) {
    if (searchQuery.isBlank()) {
        shortcutFolderCardsSection(
            activityShortcutsCount = activityShortcuts.size,
            onOpenMyShortcuts = onOpenMyShortcuts,
            onOpenPresetShortcuts = onOpenPresetShortcuts,
        )
    } else if (activityShortcuts.isNotEmpty()) {
        activityShortcutPickerRadioSection(
            activityShortcuts = activityShortcuts,
            current = current,
            onSelect = onSelect,
            onBrowse = onBrowseActivityShortcut,
            searchQuery = searchQuery,
        )
    }
    systemShortcutCatalogItems(
        filtered = filtered,
        appsByPackage = appsByPackage,
        loading = loading,
        scanProgress = scanProgress,
        onCreateHostClick = onCreateHostClick,
        shortcutRowContent = shortcutRowContent,
    )
}

/**
 * 当前已绑定的动作属于哪一页 —— 打开选择器时据此预选标签页，
 * 不要让人每次都从第 0 页开始翻。
 */
internal fun GestureAction.pickerTab(): ActionPickerTab = when (this) {
    is GestureAction.LaunchApp -> ActionPickerTab.APPS
    is GestureAction.LaunchShortcut -> ActionPickerTab.SHORTCUTS
    else -> ActionPickerTab.ACTIONS
}

/**
 * 当前已绑定的动作在「动作」页里的**扁平下标**（把每个分组的标题行也算进去）。
 *
 * ⚠️ 必须与 [actionPickerActionItems] 的排布逐行对齐：每个分组 = 1 行标题 + N 行动作。
 * 找不到就返回 null（比如「打开应用 / 启动快捷方式」根本不在这一页、或该项已下架），
 * 调用方回落到列表顶部。
 */
internal fun actionPickerIndexOfCurrent(
    filtered: List<GestureAction>,
    current: GestureAction,
): Int? {
    if (current.type == GestureActionType.LAUNCH_APP ||
        current.type == GestureActionType.LAUNCH_SHORTCUT ||
        current.type == GestureActionType.NONE
    ) {
        return null
    }
    var index = 0
    for (section in GestureActionCatalog.groupIntoSections(filtered)) {
        val offset = section.actions.indexOfFirst { it.type == current.type }
        if (offset >= 0) return index + 1 + offset // +1 = 本分组那行标题
        index += 1 + section.actions.size
    }
    return null
}

/**
 * 当前已绑定的快捷方式在「快捷方式」页里的**扁平下标**（把标题行 / 应用分组行 / 分组间占位都算进去）。
 *
 * ⚠️ 必须与这三段的实际排布逐行对齐（改了任何一段都要同步改这里）：
 * 1. [shortcutFolderCardsSection]（搜索为空时）：固定两张卡 = 2 行；
 * 2. [activityShortcutPickerRadioSection]（搜索非空且有自定义快捷方式时）：1 行标题 + n 行 + 1 行「浏览」；
 * 3. [systemShortcutCatalogItems]：可选「创建快捷方式」标题 + n 行 + 占位，然后「启动快捷方式」标题 +
 *    每个应用（1 行分组 + m 行 + 1 行占位）。
 *
 * 加载中 / 该项已不在目录里时返回 null，调用方回落到顶部。
 */
internal fun actionPickerShortcutIndexOfCurrent(
    filtered: FilteredShortcutCatalog,
    activityShortcuts: List<ActivityShortcut>,
    searchQuery: String,
    loading: Boolean,
    current: GestureAction,
): Int? {
    if (loading) return null
    val launch = current as? GestureAction.LaunchShortcut ?: return null
    var index = 0
    if (searchQuery.isBlank()) {
        index += 2
    } else if (activityShortcuts.isNotEmpty()) {
        index += activityShortcuts.size + 2
    }
    if (filtered.createHosts.isNotEmpty()) {
        index += 1 + filtered.createHosts.size + 1
    }
    if (filtered.groups.isEmpty()) return null
    index += 1 // 「启动快捷方式」标题
    for (group in filtered.groups) {
        index += 1 // 该应用的分组行
        for (shortcut in group.shortcuts) {
            if (shortcutToLaunchShortcut(shortcut, group.app.packageName).payloadKey == launch.payloadKey) {
                return index
            }
            index += 1
        }
        index += 1 // 分组间占位
    }
    return null
}
