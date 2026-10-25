package com.slideindex.app.ui.quicklauncher

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.slideindex.app.R
import com.slideindex.app.settings.QuickLauncherDisplaySettings
import com.slideindex.app.ui.SettingDropdownRow
import com.slideindex.app.ui.SettingsSliderRow
import com.slideindex.app.ui.miuix.CardItem
import com.slideindex.app.ui.miuix.MiuixSwitchRow
import com.slideindex.app.ui.miuix.groupedCardItems
import com.slideindex.app.ui.settings.components.SETTINGS_SLIDER_PERCENT_KEY_POINTS_100
import com.slideindex.app.ui.settings.components.settingsCardScopeItem
import kotlin.math.roundToInt

@Composable
fun quickLauncherAppearanceCardItems(
    display: QuickLauncherDisplaySettings,
    enabled: Boolean,
    onDisplayChange: (QuickLauncherDisplaySettings) -> Unit,
): List<CardItem> {
    return buildList {
        add(
            settingsCardScopeItem("show-toolbar") {
                MiuixSwitchRow(
                    title = stringResource(R.string.quick_launcher_show_toolbar),
                    summary = stringResource(R.string.quick_launcher_show_toolbar_desc),
                    checked = display.showToolbar,
                    enabled = enabled,
                    onCheckedChange = { checked ->
                        onDisplayChange(display.copy(showToolbar = checked))
                    },
                )
            },
        )
        add(
            settingsCardScopeItem("mirror-right-side") {
                MiuixSwitchRow(
                    title = stringResource(R.string.quick_launcher_mirror_on_right_side),
                    summary = stringResource(R.string.quick_launcher_mirror_on_right_side_desc),
                    checked = display.mirrorOnRightSide,
                    enabled = enabled,
                    onCheckedChange = { checked ->
                        onDisplayChange(display.copy(mirrorOnRightSide = checked))
                    },
                )
            },
        )
        val shapes = listOf(
            QuickLauncherDisplaySettings.ICON_SHAPE_DEFAULT,
            QuickLauncherDisplaySettings.ICON_SHAPE_CIRCLE,
            QuickLauncherDisplaySettings.ICON_SHAPE_ADAPTIVE,
        )
        add(
            settingsCardScopeItem("icon-shape") {
                SettingDropdownRow(
                    title = stringResource(R.string.quick_launcher_icon_shape_section),
                    items = listOf(
                        stringResource(R.string.quick_launcher_icon_shape_default),
                        stringResource(R.string.quick_launcher_icon_shape_circle),
                        stringResource(R.string.quick_launcher_icon_shape_adaptive),
                    ),
                    selectedIndex = shapes.indexOf(
                        QuickLauncherDisplaySettings.coerceIconShape(display.iconShape),
                    ).coerceAtLeast(0),
                    enabled = enabled,
                    onSelectedIndexChange = { index ->
                        onDisplayChange(display.copy(iconShape = shapes[index]))
                    },
                )
            },
        )
        add(
            settingsCardScopeItem("icon-size") {
                SettingsSliderRow(
                    title = stringResource(R.string.quick_launcher_icon_size),
                    value = display.iconSizeDp.toFloat(),
                    valueRange = QuickLauncherDisplaySettings.MIN_ICON_SIZE_DP.toFloat()..
                        QuickLauncherDisplaySettings.MAX_ICON_SIZE_DP.toFloat(),
                    steps = QuickLauncherDisplaySettings.MAX_ICON_SIZE_DP -
                        QuickLauncherDisplaySettings.MIN_ICON_SIZE_DP - 1,
                    enabled = enabled,
                    label = stringResource(
                        R.string.quick_launcher_icon_size_value,
                        display.iconSizeDp,
                    ),
                    formatLabel = { "${it.roundToInt()} dp" },
                    onValueChange = { value ->
                        onDisplayChange(display.copy(iconSizeDp = value.roundToInt()))
                    },
                )
            },
        )
        add(
            settingsCardScopeItem("background-opacity") {
                SettingsSliderRow(
                    title = stringResource(R.string.quick_launcher_background_opacity),
                    value = display.backgroundOpacityPercent.toFloat(),
                    valueRange = QuickLauncherDisplaySettings.MIN_BACKGROUND_OPACITY_PERCENT.toFloat()..
                        QuickLauncherDisplaySettings.MAX_BACKGROUND_OPACITY_PERCENT.toFloat(),
                    enabled = enabled,
                    label = stringResource(
                        R.string.quick_launcher_background_opacity_value,
                        display.backgroundOpacityPercent,
                    ),
                    formatLabel = { "${it.roundToInt()}%" },
                    keyPoints = SETTINGS_SLIDER_PERCENT_KEY_POINTS_100,
                    onValueChange = { value ->
                        onDisplayChange(
                            display.copy(backgroundOpacityPercent = value.roundToInt()),
                        )
                    },
                )
            },
        )
        add(
            settingsCardScopeItem("blur-strength") {
                SettingsSliderRow(
                    title = stringResource(R.string.quick_launcher_blur_strength),
                    value = display.blurRadiusDp.toFloat(),
                    valueRange = QuickLauncherDisplaySettings.MIN_BLUR_RADIUS_DP.toFloat()..
                        QuickLauncherDisplaySettings.MAX_BLUR_RADIUS_DP.toFloat(),
                    steps = QuickLauncherDisplaySettings.MAX_BLUR_RADIUS_DP -
                        QuickLauncherDisplaySettings.MIN_BLUR_RADIUS_DP - 1,
                    enabled = enabled,
                    label = stringResource(
                        R.string.quick_launcher_blur_strength_value,
                        display.blurRadiusDp,
                    ),
                    formatLabel = { "${it.roundToInt()} dp" },
                    onValueChange = { value ->
                        onDisplayChange(display.copy(blurRadiusDp = value.roundToInt()))
                    },
                )
            },
        )
    }
}

fun androidx.compose.foundation.lazy.LazyListScope.quickLauncherAppearanceSettingsSection(
    appearanceItems: List<CardItem>,
) {
    groupedCardItems("quick-launcher-appearance", appearanceItems)
}
