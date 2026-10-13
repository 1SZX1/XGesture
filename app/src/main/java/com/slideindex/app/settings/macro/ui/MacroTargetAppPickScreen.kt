package com.slideindex.app.settings.macro.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.slideindex.app.R
import com.slideindex.app.settings.macro.MacroTargetApp
import com.slideindex.app.settings.macro.MacroTargetApps
import com.slideindex.app.ui.miuix.groupedCardItems
import com.slideindex.app.ui.settings.components.SettingLinkRow
import com.slideindex.app.ui.settings.components.SettingsLazyScreenScaffold
import com.slideindex.app.ui.settings.components.settingsCardScopeItem
import com.slideindex.app.ui.settings.components.settingsLazyTipCard
import com.slideindex.app.util.queryIntentActivitiesCompat

/**
 * 目标 App 选择页：列出**可启动**的应用（应用名 + 包名），选一个作为宏的目标。
 *
 * 枚举走 `queryIntentActivities`（MAIN + LAUNCHER），去重与排序交给
 * [MacroTargetApps] 这个纯函数，方便单测。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MacroTargetAppPickScreen(
    context: Context,
    selectedPackage: String,
    onBack: () -> Unit,
    onSelect: (MacroTargetApp) -> Unit,
) {
    val apps = remember(context) { queryLaunchableApps(context) }
    // 注意：scaffold 的内容是 LazyListScope（不是 @Composable），文案必须在这里先取好。
    val emptyText = stringResource(R.string.macro_target_empty)

    SettingsLazyScreenScaffold(
        title = stringResource(R.string.macro_target_pick_title),
        subtitle = stringResource(R.string.macro_target_pick_subtitle),
        onBack = onBack,
    ) {
        if (apps.isEmpty()) {
            settingsLazyTipCard(
                key = "macro-target-empty",
                text = emptyText,
            )
            return@SettingsLazyScreenScaffold
        }
        groupedCardItems(
            keyPrefix = "macro-target-apps",
            items = apps.map { app ->
                settingsCardScopeItem("macro-target-${app.packageName}") {
                    SettingLinkRow(
                        title = app.label,
                        subtitle = if (app.packageName == selectedPackage) {
                            stringResource(R.string.macro_target_selected, app.packageName)
                        } else {
                            app.packageName
                        },
                        onClick = { onSelect(app) },
                    )
                }
            },
        )
    }
}

/** MAIN + LAUNCHER 枚举 → [MacroTargetApp] 列表（包名去重、按应用名排序）。 */
internal fun queryLaunchableApps(context: Context): List<MacroTargetApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved = runCatching {
        pm.queryIntentActivitiesCompat(intent, PackageManager.MATCH_DISABLED_COMPONENTS)
    }.getOrDefault(emptyList())

    val entries = resolved.map { info ->
        val packageName = info.activityInfo?.packageName.orEmpty()
        val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault("")
        packageName to label
    }
    return MacroTargetApps.fromEntries(entries)
}

/** 编辑页显示"已选目标 App"：优先应用名，取不到时退回包名。 */
internal fun resolveAppLabel(context: Context, packageName: String): String {
    if (packageName.isBlank()) return ""
    return runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}
