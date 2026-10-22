package com.slideindex.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.slideindex.app.R
import com.slideindex.app.settings.AppSettings
import com.slideindex.app.settings.DragDropFlowPosition
import com.slideindex.app.ui.miuix.groupedCardItems
import com.slideindex.app.ui.settings.components.SettingDropdownRow
import com.slideindex.app.ui.settings.components.SettingNavigationRow
import com.slideindex.app.ui.settings.components.SettingSwitchRow
import com.slideindex.app.ui.settings.components.SettingsScreenScaffold
import com.slideindex.app.ui.settings.components.settingsCardScopeItem
import com.slideindex.app.ui.settings.components.settingsLazySmallTitle
import com.slideindex.app.ui.settings.components.settingsLazyTipCard

@Composable
fun DragDropFlowSettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onPositionChange: (DragDropFlowPosition) -> Unit,
    onHapticChange: (Boolean) -> Unit,
    onOpenTextSearchEngines: (() -> Unit)? = null,
    onOpenImageSearchEngines: (() -> Unit)? = null,
    onOpenFileShareEngines: (() -> Unit)? = null,
) {
    val currentPosition = settings.dragDropFlowPosition
    val positionLabels = listOf(
        "底边横向（默认）",
        "左侧纵向",
        "右侧纵向",
        "环形罗盘（居中）",
    )
    val positionIndex = currentPosition.id.coerceIn(0, 3)

    SettingsScreenScaffold(
        title = "全局拖拽流转",
        onBack = onBack,
    ) {
        settingsLazySmallTitle(
            key = "drag_drop_flow_basic_section",
            title = "基础功能",
        )
        groupedCardItems(
            keyPrefix = "drag_drop_flow_basic",
            items = buildList {
                add(
                    settingsCardScopeItem("switch") {
                        SettingSwitchRow(
                            title = "启用全局拖拽流转",
                            subtitle = "在任意应用长按图片或文字开始拖拽时，屏幕边缘自动滑出流转搜索栏",
                            icon = { label: String -> Icon(Icons.Outlined.PowerSettingsNew, contentDescription = label) },
                            checked = settings.dragDropFlowEnabled,
                            enabled = true,
                            onCheckedChange = onEnabledChange,
                        )
                    }
                )
            }
        )

        settingsLazySmallTitle(
            key = "drag_drop_flow_layout_section",
            title = "外观与位置",
        )
        groupedCardItems(
            keyPrefix = "drag_drop_flow_layout",
            items = buildList {
                add(
                    settingsCardScopeItem("position") {
                        SettingDropdownRow(
                            title = "流转栏显示位置",
                            subtitle = "条状栏贴哪条边；选「环形罗盘」则改为屏幕中央的圆盘，一圈排开全部目标",
                            items = positionLabels,
                            selectedIndex = positionIndex,
                            enabled = settings.dragDropFlowEnabled,
                            icon = { label: String -> Icon(Icons.Outlined.OpenWith, contentDescription = label) },
                            onSelectedIndexChange = { index ->
                                val target = DragDropFlowPosition.fromId(index)
                                onPositionChange(target)
                            },
                        )
                    }
                )
                add(
                    settingsCardScopeItem("haptic") {
                        SettingSwitchRow(
                            title = "触觉震动反馈",
                            subtitle = "拖动至应用图标、取消或恢复按钮时提供细腻震动",
                            icon = { label: String -> Icon(Icons.Outlined.Vibration, contentDescription = label) },
                            checked = settings.dragDropFlowHapticEnabled,
                            enabled = settings.dragDropFlowEnabled,
                            onCheckedChange = onHapticChange,
                        )
                    }
                )
            }
        )

        settingsLazySmallTitle(
            key = "drag_drop_flow_targets_section",
            title = "流转目标管理",
        )
        groupedCardItems(
            keyPrefix = "drag_drop_flow_targets",
            items = buildList {
                if (onOpenTextSearchEngines != null) {
                    add(
                        settingsCardScopeItem("text_engines") {
                            SettingNavigationRow(
                                title = "文字搜索引擎",
                                subtitle = "拖拽文字时呼出的默认搜索、翻译与快捷动作",
                                icon = { label: String -> Icon(Icons.Outlined.Search, contentDescription = label) },
                                enabled = settings.dragDropFlowEnabled,
                                onClick = onOpenTextSearchEngines,
                            )
                        }
                    )
                }
                if (onOpenImageSearchEngines != null) {
                    add(
                        settingsCardScopeItem("image_engines") {
                            SettingNavigationRow(
                                title = "图片搜索引擎",
                                subtitle = "拖拽图片时呼出的以图搜图及购物、社交分享目标",
                                icon = { label: String -> Icon(Icons.Outlined.ImageSearch, contentDescription = label) },
                                enabled = settings.dragDropFlowEnabled,
                                onClick = onOpenImageSearchEngines,
                            )
                        }
                    )
                }
                if (onOpenFileShareEngines != null) {
                    add(
                        settingsCardScopeItem("file_targets") {
                            SettingNavigationRow(
                                title = "文件分享引擎",
                                subtitle = "管理拖拽音频或文件时的流转分享应用与排序",
                                icon = { label: String -> Icon(Icons.Outlined.FolderShared, contentDescription = label) },
                                enabled = settings.dragDropFlowEnabled,
                                onClick = onOpenFileShareEngines,
                            )
                        }
                    )
                }
            }
        )

        settingsLazyTipCard(
            key = "drag_drop_flow_guide_tip",
            text = "• 触发方式：在微信、相册、浏览器或任意支持系统拖拽的应用中，长按文字或图片拖动即可激活流转栏。\n• 边缘滚动：当引擎项目较多时，拖拽至流转栏两侧边缘即可自动平滑滚动。\n• 放弃与取消：拖动到「取消」按钮松手，可直接取消本次流转且不打扰原应用；拖至「恢复」可保持原状态。",
        )
    }
}
