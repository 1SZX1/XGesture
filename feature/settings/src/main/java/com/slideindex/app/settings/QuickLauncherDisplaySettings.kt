package com.slideindex.app.settings

import androidx.datastore.preferences.core.Preferences
import kotlin.math.roundToInt

data class QuickLauncherDisplaySettings(
    val backgroundOpacityPercent: Int = DEFAULT_BACKGROUND_OPACITY_PERCENT,
    val iconSizeDp: Int = DEFAULT_ICON_SIZE_DP,
    val iconShape: Int = ICON_SHAPE_DEFAULT,
    val blurRadiusDp: Int = DEFAULT_BLUR_RADIUS_DP,
    /**
     * 是否显示面板底部那条操作栏（+ 添加 / − 编辑 / ⇄ 切面板）。
     *
     * 关掉后：普通使用态面板下方干净，进入编辑态只能走「设置 → 扩展中心 → 快速启动器」里的编辑器。
     * 但**编辑态本身仍然强制显示**——否则用户在面板内进了编辑态就再也没有「✓」可以退出来。
     */
    val showToolbar: Boolean = true,
) {
    companion object {
        const val MIN_BACKGROUND_OPACITY_PERCENT = 0
        const val MAX_BACKGROUND_OPACITY_PERCENT = 100
        /** 默认背景不透明度 63%。 */
        const val DEFAULT_BACKGROUND_OPACITY_PERCENT = 63

        const val MIN_ICON_SIZE_DP = 28
        const val MAX_ICON_SIZE_DP = 48
        /** 对齐 overlay 硬编码 `host.dp(38f)` */
        const val DEFAULT_ICON_SIZE_DP = 38

        /** 0 = 不模糊，仅显示纯色背景。 */
        const val MIN_BLUR_RADIUS_DP = 0
        const val MAX_BLUR_RADIUS_DP = 150
        /** 默认模糊强度 16 dp。 */
        const val DEFAULT_BLUR_RADIUS_DP = 16

        /** 不二次裁剪，保留系统绘制结果。 */
        const val ICON_SHAPE_DEFAULT = 0
        /** @deprecated 已移除；持久化值 1 会迁移为 [ICON_SHAPE_DEFAULT]。 */
        const val ICON_SHAPE_ROUNDED_LEGACY = 1
        const val ICON_SHAPE_CIRCLE = 2
        const val ICON_SHAPE_ADAPTIVE = 3

        fun coerceIconShape(shape: Int): Int = when (shape) {
            ICON_SHAPE_CIRCLE, ICON_SHAPE_ADAPTIVE -> shape
            else -> ICON_SHAPE_DEFAULT
        }

        fun legacyBackgroundOpacityPercent(panelOpacity: Float): Int {
            val alpha = (225f * panelOpacity).toInt().coerceIn(150, 225)
            return (alpha * 100f / 255f).roundToInt()
                .coerceIn(MIN_BACKGROUND_OPACITY_PERCENT, MAX_BACKGROUND_OPACITY_PERCENT)
        }

        fun backgroundAlphaArgb(backgroundOpacityPercent: Int): Int =
            (255f * backgroundOpacityPercent.coerceIn(
                MIN_BACKGROUND_OPACITY_PERCENT,
                MAX_BACKGROUND_OPACITY_PERCENT,
            ) / 100f).roundToInt().coerceIn(0, 255)

        fun fromPreferences(prefs: Preferences): QuickLauncherDisplaySettings {
            val storedOpacity = prefs[SettingsPreferenceKeys.QUICK_LAUNCHER_BACKGROUND_OPACITY_PERCENT]
            return QuickLauncherDisplaySettings(
                backgroundOpacityPercent = storedOpacity?.coerceIn(
                    MIN_BACKGROUND_OPACITY_PERCENT,
                    MAX_BACKGROUND_OPACITY_PERCENT,
                ) ?: DEFAULT_BACKGROUND_OPACITY_PERCENT,
                iconSizeDp = (prefs[SettingsPreferenceKeys.QUICK_LAUNCHER_ICON_SIZE_DP]
                    ?: DEFAULT_ICON_SIZE_DP)
                    .coerceIn(MIN_ICON_SIZE_DP, MAX_ICON_SIZE_DP),
                iconShape = coerceIconShape(
                    prefs[SettingsPreferenceKeys.QUICK_LAUNCHER_ICON_SHAPE] ?: ICON_SHAPE_DEFAULT,
                ),
                blurRadiusDp = (prefs[SettingsPreferenceKeys.QUICK_LAUNCHER_BLUR_RADIUS_DP]
                    ?: DEFAULT_BLUR_RADIUS_DP)
                    .coerceIn(MIN_BLUR_RADIUS_DP, MAX_BLUR_RADIUS_DP),
                showToolbar = prefs[SettingsPreferenceKeys.QUICK_LAUNCHER_SHOW_TOOLBAR] ?: true,
            )
        }
    }
}
