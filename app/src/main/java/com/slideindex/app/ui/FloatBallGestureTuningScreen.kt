package com.slideindex.app.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.slideindex.app.R
import com.slideindex.app.settings.AppSettings
import com.slideindex.app.ui.miuix.groupedCardItems
import com.slideindex.app.ui.settings.components.SettingsScreenScaffold
import com.slideindex.app.ui.settings.components.settingsCardScopeItem
import com.slideindex.app.ui.settings.components.settingsLazySmallTitle
import kotlin.math.roundToInt

/**
 * 悬浮球手势页的「识别与定位」子页（滑动距离 / 长按触发时长 / 启动器位置）。
 *
 * 这三段原来压在手势页最上面，把 8 个方向手势挤到了第二屏。手势是高频内容、参数是低频内容，
 * 所以参数整体收进子页，入口留在手势页最底下；入口副标题带当前值摘要（[floatBallGestureTuningSummary]），
 * 不进入子页也能确认参数现状。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FloatBallGestureTuningScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onDownSwipeShortPercentChange: (Float) -> Unit,
    onSideSwipeShortPercentChange: (Float) -> Unit,
    onUpSwipeShortPercentChange: (Float) -> Unit,
    onLongPressMsChange: (Int) -> Unit,
    onOverlayAnchorAtTouchDownChange: (Boolean) -> Unit,
    onGestureHapticChange: (Boolean) -> Unit,
    onRingAutoSelectChange: (Boolean) -> Unit,
) {
    // 分组小标题必须在 Lazy 作用域外解析（settingsLazySmallTitle 的 title 是普通 String）。
    val distanceSectionTitle = stringResource(R.string.float_ball_gesture_distance_section)
    val longPressSectionTitle = stringResource(R.string.float_ball_gesture_long_press_section)
    val overlayAnchorSectionTitle = stringResource(R.string.float_ball_gesture_overlay_anchor_section)
    val feedbackSectionTitle = stringResource(R.string.float_ball_gesture_feedback_section)

    SettingsScreenScaffold(
        title = stringResource(R.string.float_ball_gesture_tuning_title),
        pageHint = stringResource(R.string.float_ball_gesture_tuning_summary),
        onBack = onBack
    ) {
        settingsLazySmallTitle(
            key = "section-distance",
            title = distanceSectionTitle
        )
        groupedCardItems(
            keyPrefix = "float-ball-gesture-distance",
            items = buildList {
                add(
                    settingsCardScopeItem("down-swipe-distance") {
                        SettingsSliderRow(
                            title = stringResource(R.string.float_ball_gesture_down_swipe_distance),
                            value = settings.floatBallDownSwipeShortPercent,
                            valueRange = 50f..500f,
                            steps = 18,
                            enabled = true,
                            label = stringResource(
                                R.string.floating_pointer_percent_value,
                                settings.floatBallDownSwipeShortPercent.roundToInt()
                            ),
                            onValueChange = onDownSwipeShortPercentChange
                        )
                    }
                )
                add(
                    settingsCardScopeItem("side-swipe-distance") {
                        SettingsSliderRow(
                            title = stringResource(R.string.float_ball_gesture_side_swipe_distance),
                            value = settings.floatBallSideSwipeShortPercent,
                            valueRange = 50f..500f,
                            steps = 18,
                            enabled = true,
                            label = stringResource(
                                R.string.floating_pointer_percent_value,
                                settings.floatBallSideSwipeShortPercent.roundToInt()
                            ),
                            onValueChange = onSideSwipeShortPercentChange
                        )
                    }
                )
                add(
                    settingsCardScopeItem("up-swipe-distance") {
                        SettingsSliderRow(
                            title = stringResource(R.string.float_ball_gesture_up_swipe_distance),
                            value = settings.floatBallUpSwipeShortPercent,
                            valueRange = 50f..500f,
                            steps = 18,
                            enabled = true,
                            label = stringResource(
                                R.string.floating_pointer_percent_value,
                                settings.floatBallUpSwipeShortPercent.roundToInt()
                            ),
                            onValueChange = onUpSwipeShortPercentChange
                        )
                    }
                )
            }
        )

        settingsLazySmallTitle(
            key = "section-long-press",
            title = longPressSectionTitle
        )
        groupedCardItems(
            keyPrefix = "float-ball-gesture-long-press",
            items = listOf(
                settingsCardScopeItem("long-press-ms") {
                    SettingsSliderRow(
                        title = stringResource(R.string.float_ball_gesture_long_press_ms),
                        value = settings.floatBallLongPressMs.toFloat(),
                        valueRange = 200f..2000f,
                        steps = 17,
                        enabled = true,
                        label = stringResource(
                            R.string.float_ball_gesture_long_press_ms_value,
                            settings.floatBallLongPressMs
                        ),
                        onValueChange = { onLongPressMsChange(it.roundToInt()) }
                    )
                },
                settingsCardScopeItem("long-press-ring-auto-select") {
                    SettingSwitchRow(
                        title = stringResource(R.string.float_ball_gesture_ring_auto_select),
                        subtitle = stringResource(R.string.float_ball_gesture_ring_auto_select_summary),
                        checked = settings.floatBallLongPressAutoSelectRingSlot,
                        enabled = true,
                        onCheckedChange = onRingAutoSelectChange
                    )
                }
            )
        )

        settingsLazySmallTitle(
            key = "section-overlay-anchor",
            title = overlayAnchorSectionTitle
        )
        groupedCardItems(
            keyPrefix = "float-ball-gesture-overlay-anchor",
            items = listOf(
                settingsCardScopeItem("overlay-anchor-touch-down") {
                    SettingSwitchRow(
                        title = stringResource(R.string.float_ball_gesture_overlay_anchor_touch_down),
                        subtitle = stringResource(R.string.float_ball_gesture_overlay_anchor_touch_down_summary),
                        checked = settings.floatBallOverlayAnchorAtTouchDown,
                        enabled = true,
                        onCheckedChange = onOverlayAnchorAtTouchDownChange
                    )
                }
            )
        )

        settingsLazySmallTitle(
            key = "section-feedback",
            title = feedbackSectionTitle
        )
        groupedCardItems(
            keyPrefix = "float-ball-gesture-feedback",
            items = listOf(
                settingsCardScopeItem("gesture-haptic") {
                    SettingSwitchRow(
                        title = stringResource(R.string.float_ball_gesture_haptic),
                        subtitle = stringResource(R.string.float_ball_gesture_haptic_summary),
                        checked = settings.floatBallGestureHapticEnabled,
                        enabled = true,
                        onCheckedChange = onGestureHapticChange
                    )
                }
            )
        )
    }
}

/**
 * 手势页底部入口的副标题：把三项参数压成一行，让不进入子页也能看到当前值。
 *
 * 三个滑动距离相同时只显示一个值（默认配置就是这个情况），避免「100% / 100% / 100%」这种噪音。
 */
@Composable
fun floatBallGestureTuningSummary(settings: AppSettings): String {
    val down = settings.floatBallDownSwipeShortPercent.roundToInt()
    val side = settings.floatBallSideSwipeShortPercent.roundToInt()
    val up = settings.floatBallUpSwipeShortPercent.roundToInt()
    val swipeValue = if (down == side && side == up) "$down%" else "$down% / $side% / $up%"
    val anchor = stringResource(
        if (settings.floatBallOverlayAnchorAtTouchDown) {
            R.string.float_ball_gesture_tuning_anchor_touch_down
        } else {
            R.string.float_ball_gesture_tuning_anchor_gesture_fire
        }
    )
    return listOf(
        stringResource(R.string.float_ball_gesture_tuning_swipe_prefix, swipeValue),
        stringResource(R.string.float_ball_gesture_tuning_hold_prefix, "${settings.floatBallLongPressMs} ms"),
        anchor,
    ).joinToString(" · ")
}
