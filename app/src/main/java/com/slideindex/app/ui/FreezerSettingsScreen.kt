package com.slideindex.app.ui

import android.widget.Toast
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slideindex.app.R
import com.slideindex.app.freezer.FreezerLauncherHelper
import com.slideindex.app.freezer.FreezerListOperations
import com.slideindex.app.settings.FreezerWorkMode
import com.slideindex.app.settings.SettingsRepository
import com.slideindex.app.ui.miuix.groupedCardItems
import com.slideindex.app.ui.settings.components.SettingLinkRow
import com.slideindex.app.ui.settings.components.SettingRadioRow
import com.slideindex.app.ui.settings.components.SettingSwitchRow
import com.slideindex.app.ui.settings.components.SettingsLazyScreenScaffold
import com.slideindex.app.ui.settings.components.settingsCardScopeItem
import com.slideindex.app.ui.settings.components.settingsLazyHint
import com.slideindex.app.ui.settings.components.settingsLazySmallTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 冰箱的**全局设置**（工作模式、系统应用、启动器图标、导入）。
 *
 * 为什么从「管理应用」里拆出来：那页的职责是「挑成员」，而这些是配置项。原先两类挤在一屏，
 * 设置卡把列表顶到了第二屏，用户进页面看到的全是开关而不是应用（见 `FreezerAppsPickerScreen`
 * 的同类说明）。拆开后设置各自待在它真正生效的那一行旁边，管理页第一屏就是列表。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FreezerSettingsScreen(
    settingsRepository: SettingsRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val settings by settingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = settingsRepository.readSnapshot()
    )
    val scope = rememberCoroutineScope()
    val pauseMode = settings.freezerWorkMode.isPause
    // LazyListScope 的段标题 / 段说明是普通函数，参数必须在 composable 作用域里先取好。
    val workModeTitle = stringResource(R.string.freezer_work_mode_title)
    val workModeHint = stringResource(R.string.freezer_work_mode_hint)

    SettingsLazyScreenScaffold(
        title = stringResource(R.string.freezer_settings_title),
        onBack = onBack,
    ) {
        settingsLazySmallTitle(
            key = "freezer-settings-work-mode-title",
            title = workModeTitle,
        )
        settingsLazyHint(
            key = "freezer-settings-work-mode-hint",
            text = workModeHint,
        )
        // 两行单选，而不是一个「标题随状态变」的开关：开关的关=冻结、开=暂停，标题还跟着翻，
        // 用户永远读不出「现在是什么、关掉会怎样」。两行摊开后每个选项自带一句后果说明。
        groupedCardItems(
            keyPrefix = "freezer-settings-work-mode",
            selectableGroup = true,
            items = listOf(
                settingsCardScopeItem("freeze") {
                    SettingRadioRow(
                        title = stringResource(R.string.freezer_action_freeze),
                        subtitle = stringResource(R.string.freezer_work_mode_freeze_desc),
                        selected = !pauseMode,
                        onClick = {
                            scope.launch {
                                settingsRepository.setFreezerWorkMode(FreezerWorkMode.FREEZE.id)
                            }
                        },
                    )
                },
                settingsCardScopeItem("pause") {
                    SettingRadioRow(
                        title = stringResource(R.string.freezer_action_pause),
                        subtitle = stringResource(R.string.freezer_work_mode_pause_desc),
                        selected = pauseMode,
                        onClick = {
                            scope.launch {
                                settingsRepository.setFreezerWorkMode(FreezerWorkMode.PAUSE.id)
                            }
                        },
                    )
                },
            ),
        )
        groupedCardItems(
            keyPrefix = "freezer-settings-options",
            items = listOf(
                settingsCardScopeItem("show-system") {
                    SettingSwitchRow(
                        title = stringResource(R.string.freezer_show_system_apps),
                        subtitle = stringResource(R.string.freezer_show_system_apps_desc),
                        checked = settings.freezerShowSystemApps,
                        enabled = true,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                settingsRepository.setFreezerShowSystemApps(enabled)
                            }
                        },
                    )
                },
                settingsCardScopeItem("show-launcher") {
                    SettingSwitchRow(
                        title = stringResource(R.string.freezer_show_in_launcher_title),
                        subtitle = stringResource(R.string.freezer_show_in_launcher_desc),
                        checked = settings.freezerShowInLauncher,
                        enabled = true,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                if (enabled) {
                                    if (!FreezerLauncherHelper.requestPinShortcut(context)) {
                                        FreezerLauncherHelper.showPinShortcutFailedToast(context)
                                        return@launch
                                    }
                                    settingsRepository.setFreezerShowInLauncher(true)
                                } else {
                                    settingsRepository.setFreezerShowInLauncher(false)
                                    FreezerLauncherHelper.cleanupLegacyAlias(context)
                                    FreezerLauncherHelper.showUnpinHintToast(context)
                                }
                            }
                        },
                    )
                },
                settingsCardScopeItem("import-frozen") {
                    // 用不带图标的动作行：同卡片里另外两行是纯文字开关，这里多一个灰色图标块
                    // 会把行高撑出一截，整张卡就不齐了。
                    SettingLinkRow(
                        title = stringResource(R.string.freezer_import_frozen_apps),
                        subtitle = stringResource(R.string.freezer_import_frozen_apps_desc),
                        onClick = {
                            scope.launch {
                                val count = withContext(Dispatchers.IO) {
                                    FreezerListOperations.importFrozenApps(context, settingsRepository)
                                }
                                val message = if (count > 0) {
                                    resources.getQuantityString(
                                        R.plurals.freezer_import_frozen_done,
                                        count,
                                        count,
                                    )
                                } else {
                                    resources.getString(R.string.freezer_import_frozen_none)
                                }
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                },
            ),
        )
    }
}
