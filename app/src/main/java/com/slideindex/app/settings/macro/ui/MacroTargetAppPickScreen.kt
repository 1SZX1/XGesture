package com.slideindex.app.settings.macro.ui

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.slideindex.app.R
import com.slideindex.app.data.AppInfo
import com.slideindex.app.launcher.AppTarget
import com.slideindex.app.ui.Md3PickerAppLeading
import com.slideindex.app.ui.Md3PickerListRow
import com.slideindex.app.ui.PickerTrailingMode
import com.slideindex.app.ui.compose.collectPickerAppsAsState
import com.slideindex.app.ui.settings.components.SettingsLazyScreenScaffoldWithExpandableSearch
import com.slideindex.app.ui.settings.components.settingsLazyTipCard

/**
 * 目标 App 选择页：列出可启动的应用（图标 + 名称 + 包名），选一个作为宏的目标。
 *
 * 数据与其它挑应用页面同源（`PickerApps`）：分身也会列出来，选中后存 `(包名, userId)`，
 * 录制/执行时由 `MacroEngine` 负责跨 user 启动。旧数据是裸包名，等价于 user 0。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MacroTargetAppPickScreen(
    selectedTarget: String,
    onBack: () -> Unit,
    onSelect: (AppInfo) -> Unit,
) {
    val apps by collectPickerAppsAsState(includeClones = true)
    var query by remember { mutableStateOf("") }
    val emptyText = stringResource(R.string.macro_target_empty)

    val filtered = remember(apps, query) {
        val q = query.trim().lowercase()
        val matched = if (q.isEmpty()) {
            apps
        } else {
            apps.filter {
                it.label.lowercase().contains(q) ||
                    it.packageName.lowercase().contains(q) ||
                    it.pinyinKey.contains(q)
            }
        }
        matched.sortedBy { it.pinyinKey }
    }

    SettingsLazyScreenScaffoldWithExpandableSearch(
        title = stringResource(R.string.macro_target_pick_title),
        pageHint = stringResource(R.string.macro_target_pick_subtitle),
        searchQuery = query,
        onSearchQueryChange = { query = it },
        onBack = onBack,
    ) {
        if (filtered.isEmpty()) {
            settingsLazyTipCard(
                key = "macro-target-empty",
                text = emptyText,
            )
            return@SettingsLazyScreenScaffoldWithExpandableSearch
        }
        val segmentCount = filtered.size
        items(
            count = segmentCount,
            key = { index ->
                AppTarget.encode(filtered[index].packageName, filtered[index].cloneUserId)
            },
        ) { index ->
            val app = filtered[index]
            val target = AppTarget.encode(app.packageName, app.cloneUserId)
            val selected = target == selectedTarget
            Md3PickerListRow(
                segmentIndex = index,
                segmentCount = segmentCount,
                title = app.label,
                subtitle = if (selected) {
                    stringResource(R.string.macro_target_selected, app.packageName)
                } else {
                    app.packageName
                },
                selected = selected,
                onClick = { onSelect(app) },
                leadingContent = { Md3PickerAppLeading(app) },
                trailingMode = PickerTrailingMode.Radio,
            )
        }
    }
}

/** 编辑页显示"已选目标 App"：优先应用名，取不到时退回包名；分身带「· 分身」后缀。 */
internal fun resolveAppLabel(context: Context, target: String): String {
    if (target.isBlank()) return ""
    val parsed = AppTarget.parse(target)
    val label = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(parsed.packageName, 0)).toString()
    }.getOrDefault(parsed.packageName)
    return if (parsed.isClone) {
        com.slideindex.app.overlay.CloneAppsIndex.labelForUser(context, parsed.cloneUserId, label)
    } else {
        label
    }
}
