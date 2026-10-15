package com.slideindex.app.ui.miuix.theme

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import com.slideindex.app.settings.AppSettings
import com.slideindex.app.settings.AppThemeMode
import com.slideindex.app.settings.DarkBackgroundStyle
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDarkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColorScheme

/** 与 [ModuleTheme] 一致的 Material3 [ColorScheme]，供 Compose 与 View 主题共用。 */
fun resolveAppMaterialColorScheme(
    context: Context,
    settings: AppSettings,
    darkTheme: Boolean,
): ColorScheme =
    if (settings.customColorEnabled) {
        MiuixSeedResolver.materialScheme(
            MiuixSeedResolver.customSeed(context, settings, darkTheme),
            settings,
            darkTheme,
        )
    } else {
        defaultMiuixMaterialScheme(darkTheme, DarkBackgroundStyle.fromId(settings.darkBackgroundStyleId))
    }

/**
 * 浮层 chrome（悬浮球 / 边缘线条 / 拖影 / 手势提示）要的**主题强调色**（ARGB）。
 *
 * 种子选择与 [resolveAppMaterialColorScheme] **完全一致**：
 * - 开了自定义色 → 用户选的颜色（开了动态取色时是壁纸强调色）；
 * - 没开 → Miuix 默认主色。
 *
 * 为什么要它：这些 chrome 原先直接吃 `settings.themeColorArgb`（默认 `#6750A4` = Material 紫），
 * 于是出现"用户从没选过紫色，球和线条却是紫的"。任何"跟主题色"的绘制都应走这里，
 * 而不是读 `themeColorArgb` 原始值。
 */
fun resolveAppAccentArgb(context: Context, settings: AppSettings): Int {
    val systemDark =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    return resolveAppAccentArgb(context, settings, settings.darkFor(systemDark))
}

/** Compose 侧入口：系统深色由 [isSystemInDarkTheme] 提供，并跟着设置变化重算。 */
@Composable
fun rememberAppAccentColor(settings: AppSettings): Color {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    return remember(
        context,
        settings.customColorEnabled,
        settings.themeColorArgb,
        settings.dynamicColorEnabled,
        settings.themeModeId,
        settings.darkBackgroundStyleId,
        settings.themePaletteStyleId,
        settings.themeColorSpecId,
        systemDark,
    ) {
        Color(resolveAppAccentArgb(context, settings, settings.darkFor(systemDark)))
    }
}

/** 明显更热（拖影 / 提示窗会反复问），配色生成不便宜，所以按输入做一层单值缓存。 */
@Volatile
private var accentCacheKey: String? = null

@Volatile
private var accentCacheArgb: Int = 0

private fun resolveAppAccentArgb(context: Context, settings: AppSettings, dark: Boolean): Int {
    val key = buildString {
        append(settings.customColorEnabled).append('|')
        append(settings.themeColorArgb).append('|')
        append(settings.dynamicColorEnabled).append('|')
        append(settings.themePaletteStyleId).append('|')
        append(settings.themeColorSpecId).append('|')
        append(settings.darkBackgroundStyleId).append('|')
        append(dark)
    }
    if (key == accentCacheKey) return accentCacheArgb
    val argb = if (settings.customColorEnabled) {
        MiuixSeedResolver.customSeed(context, settings, dark)
    } else {
        resolveAppMaterialColorScheme(context, settings, dark).primary.toArgb()
    }
    accentCacheKey = key
    accentCacheArgb = argb
    return argb
}

private fun AppSettings.darkFor(systemDark: Boolean): Boolean =
    AppThemeMode.fromId(themeModeId).resolveIsDark(systemDark)

internal fun defaultMiuixMaterialScheme(
    darkTheme: Boolean,
    darkBackgroundStyle: DarkBackgroundStyle,
): ColorScheme {
    val miuixColors = if (darkTheme) darkColorsFor(darkBackgroundStyle) else miuixLightColorScheme()
    val dialogSurface = miuixColors.surfaceContainer

    return dynamicColorScheme(
        seedColor = miuixColors.primary,
        isDark = darkTheme,
        style = PaletteStyle.TonalSpot,
        specVersion = ColorSpec.SpecVersion.SPEC_2021,
    ).copy(
        primary = miuixColors.primary,
        onPrimary = miuixColors.onPrimary,
        primaryContainer = miuixColors.primaryContainer,
        onPrimaryContainer = miuixColors.onPrimaryContainer,
        error = miuixColors.error,
        onError = miuixColors.onError,
        errorContainer = miuixColors.errorContainer,
        onErrorContainer = miuixColors.onErrorContainer,
        background = miuixColors.background,
        onBackground = miuixColors.onBackground,
        surface = dialogSurface,
        onSurface = miuixColors.onSurfaceContainer,
        surfaceVariant = miuixColors.surfaceVariant,
        onSurfaceVariant = miuixColors.onSurfaceVariantSummary,
        surfaceTint = dialogSurface,
        outline = miuixColors.outline,
        outlineVariant = miuixColors.dividerLine,
        surfaceBright = dialogSurface,
        surfaceContainerLowest = dialogSurface,
        surfaceContainerLow = dialogSurface,
        surfaceContainer = dialogSurface,
        surfaceContainerHigh = miuixColors.surfaceContainerHigh,
        surfaceContainerHighest = miuixColors.surfaceContainerHighest,
        surfaceDim = miuixColors.surface,
    )
}

internal fun darkColorsFor(style: DarkBackgroundStyle) = miuixDarkColorScheme().copy(
    background = when (style) {
        DarkBackgroundStyle.QUIET_BLUE -> Color(0xFF101820)
        DarkBackgroundStyle.DEEP_BLACK -> Color(0xFF090D12)
        DarkBackgroundStyle.AMOLED_BLACK -> Color.Black
    },
    surface = when (style) {
        DarkBackgroundStyle.QUIET_BLUE -> Color(0xFF17232D)
        DarkBackgroundStyle.DEEP_BLACK -> Color(0xFF11161C)
        DarkBackgroundStyle.AMOLED_BLACK -> Color(0xFF080808)
    },
    surfaceContainer = when (style) {
        DarkBackgroundStyle.QUIET_BLUE -> Color(0xFF1C2B36)
        DarkBackgroundStyle.DEEP_BLACK -> Color(0xFF171D24)
        DarkBackgroundStyle.AMOLED_BLACK -> Color(0xFF101010)
    },
    surfaceContainerHigh = when (style) {
        DarkBackgroundStyle.QUIET_BLUE -> Color(0xFF243744)
        DarkBackgroundStyle.DEEP_BLACK -> Color(0xFF202832)
        DarkBackgroundStyle.AMOLED_BLACK -> Color(0xFF181818)
    },
    surfaceContainerHighest = when (style) {
        DarkBackgroundStyle.QUIET_BLUE -> Color(0xFF2C4352)
        DarkBackgroundStyle.DEEP_BLACK -> Color(0xFF29333E)
        DarkBackgroundStyle.AMOLED_BLACK -> Color(0xFF202020)
    },
)
