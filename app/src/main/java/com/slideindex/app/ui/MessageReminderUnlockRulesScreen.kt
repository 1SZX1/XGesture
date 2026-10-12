package com.slideindex.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.slideindex.app.R
import com.slideindex.app.data.AppInfo
import com.slideindex.app.message.MessageSettings
import com.slideindex.app.ui.compose.collectLaunchableAppsAsState
import com.slideindex.app.ui.miuix.groupedCardItems
import com.slideindex.app.ui.settings.components.SettingsLazyScreenScaffoldWithExpandableSearch
import com.slideindex.app.ui.settings.components.SettingsSliderRow
import com.slideindex.app.ui.settings.components.settingsCardScopeItem
import com.slideindex.app.ui.settings.components.settingsLazyHint
import com.slideindex.app.util.PinyinHelper
import top.yukonga.miuix.kmp.basic.SmallTitle

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MessageReminderUnlockRulesScreen(
    settings: MessageSettings,
    onBack: () -> Unit,
    onAlwaysAllowChange: (String, Boolean) -> Unit,
    onUnlockConfirmationAutoDismissSecondsChange: (Int) -> Unit,
    onUnlockOpenDelayMsChange: (Int) -> Unit,
) {
    val allApps by collectLaunchableAppsAsState()
    var searchQuery by remember { mutableStateOf("") }
    val controlsEnabled = settings.enabled && settings.openLastMessageOnUnlock
    val neverDismissLabel = stringResource(R.string.message_reminder_unlock_auto_dismiss_never)
    val unlockRulesDesc = stringResource(R.string.message_reminder_open_last_rules_desc_page)
    val unlockAutoDismissDesc = stringResource(R.string.message_reminder_unlock_auto_dismiss_desc)
    val unlockOpenDelayDesc = stringResource(R.string.message_reminder_unlock_open_delay_desc)
    val autoDismissTitle = stringResource(R.string.message_reminder_unlock_auto_dismiss)
    val openDelayTitle = stringResource(R.string.message_reminder_unlock_open_delay)
    val alwaysAllowSectionTitle = stringResource(R.string.message_reminder_open_last_rules_always)
    val emptyAppsText = stringResource(R.string.message_reminder_open_last_rules_empty)
    val autoDismissSeconds = settings.unlockConfirmationAutoDismissSeconds
    val openDelayMs = settings.unlockOpenDelayMs

    val apps = remember(allApps, settings.enabledPackages, searchQuery) {
        val query = searchQuery.trim().lowercase()
        allApps
            .filter { it.packageName in settings.enabledPackages }
            .filter {
                query.isEmpty() ||
                    it.label.lowercase().contains(query) ||
                    it.packageName.lowercase().contains(query) ||
                    it.pinyinKey.contains(query)
            }
            .sortedBy { it.pinyinKey }
    }

    SettingsLazyScreenScaffoldWithExpandableSearch(
        title = stringResource(R.string.message_reminder_open_last_on_unlock),
        searchQuery = searchQuery,
        onSearchQueryChange = { searchQuery = it },
        onBack = onBack,
    ) {
        settingsLazyHint(
            key = "unlock-rules-desc",
            text = unlockRulesDesc,
        )
        settingsLazyHint(
            key = "unlock-open-delay-desc",
            text = unlockOpenDelayDesc,
        )
        settingsLazyHint(
            key = "unlock-auto-dismiss-desc",
            text = unlockAutoDismissDesc,
        )
        groupedCardItems(
            keyPrefix = "unlock-confirm-dismiss",
            items = listOf(
                settingsCardScopeItem("open-delay") {
                    SettingsSliderRow(
                        title = openDelayTitle,
                        value = openDelayMs.toFloat(),
                        valueRange = 0f..MessageSettings.MAX_UNLOCK_OPEN_DELAY_MS.toFloat(),
                        steps = UNLOCK_OPEN_DELAY_STEPS,
                        enabled = controlsEnabled,
                        label = "${openDelayMs}ms",
                        formatLabel = { millis -> "${millis.toInt()}ms" },
                        onValueChange = { onUnlockOpenDelayMsChange(it.toInt()) },
                    )
                },
                settingsCardScopeItem("auto-dismiss") {
                    SettingsSliderRow(
                        title = autoDismissTitle,
                        value = autoDismissSeconds.toFloat(),
                        valueRange = 0f..30f,
                        steps = 29,
                        enabled = controlsEnabled,
                        label = if (autoDismissSeconds == 0) {
                            neverDismissLabel
                        } else {
                            "${autoDismissSeconds}s"
                        },
                        formatLabel = { seconds ->
                            if (seconds == 0f) {
                                neverDismissLabel
                            } else {
                                "${seconds.toInt()}s"
                            }
                        },
                        onValueChange = { onUnlockConfirmationAutoDismissSecondsChange(it.toInt()) },
                    )
                },
            ),
        )
        item(key = "section-apps") {
            SmallTitle(
                text = alwaysAllowSectionTitle,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (apps.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = emptyAppsText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 24.dp),
                )
            }
        } else {
            items(apps, key = { it.packageName }) { app ->
                val entry = AppPackageEntry.Installed(app)
                Md3PickerListRow(
                    segmentIndex = apps.indexOf(app),
                    segmentCount = apps.size,
                    title = app.label,
                    subtitle = app.packageName,
                    selected = app.packageName in settings.openLastMessageAlwaysPackages,
                    onClick = {
                        onAlwaysAllowChange(
                            app.packageName,
                            app.packageName !in settings.openLastMessageAlwaysPackages,
                        )
                    },
                    leadingContent = {
                        Md3PickerAppEntryLeading(entry = entry, missingIcon = Icons.Default.Block)
                    },
                    trailingMode = PickerTrailingMode.Toggle,
                    onTrailingClick = {
                        onAlwaysAllowChange(
                            app.packageName,
                            app.packageName !in settings.openLastMessageAlwaysPackages,
                        )
                    },
                )
            }
        }
    }
}

/** 解锁后延迟滑杆的档位数：0–1500ms 按 100ms 一档（steps = 档数 - 1）。 */
private const val UNLOCK_OPEN_DELAY_STEPS = 14
