package com.slideindex.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slideindex.app.settings.AppSettings
import com.slideindex.app.settings.SwipeHoverDurationLimits
import com.slideindex.app.settings.forLandscapeEditing
import com.slideindex.app.settings.gestureProfileById
import com.slideindex.app.settings.gestureSnapshot
import com.slideindex.app.settings.materializeGestureProfile
import com.slideindex.app.settings.toMinimalAppSettings
import com.slideindex.app.ui.GestureProfileScopeUi
import com.slideindex.app.ui.trigger.TriggerSettingsLandscapeSession
import com.slideindex.app.ui.viewmodel.HomeDetailSettingsViewModel

/**
 * 触钮编辑流的**读写作用域**：全局（[profileId] == null）或某个「按应用分档」档位。
 *
 * 读：全局直接用设置，档内先 `materializeGestureProfile` 把「全局 + 该档那一片」拼出来，
 * 于是档内页面看到的 `AppSettings` 与全局页面完全同构。
 * 写：全局照旧走仓库 mutator；档内把同一批纯函数作用在副本上，再整片写回该档
 * （见 `HomeDetailSettingsViewModel.editGestureProfile`）。
 */
@Stable
internal class TriggerEditScope(
    private val viewModel: HomeDetailSettingsViewModel,
    val profileId: String?,
    val landscapeEditing: Boolean,
    /** 原样的「全局」或「全局 + 该档那一片」设置（触钮集合页要它，横屏映射由那个页面自己做）。 */
    val rawSettings: AppSettings,
    /** 子页面用：[landscapeEditing] 时已经把横屏字段映射到主字段。 */
    val settings: AppSettings,
    val profileScope: GestureProfileScopeUi?,
) {
    /** 触钮集合页的路由（全局 = 触钮集合；档内 = 该档的整页）。子页返回时回到它。 */
    val collectionKey: AppNavKey = profileId
        ?.let { AppNavKey.HomeGestureAppProfileDetail(it) }
        ?: AppNavKey.HomeTriggerCollection

    /**
     * 写一处设置。
     *
     * [global] 与 [scoped] 必须是同一件事的两种实现（分别对应"写全局"与"在档里改"），
     * 这样档内行为才能和全局逐字对齐。档不存在（已被删）时什么都不做。
     */
    fun mutate(
        optimistic: Boolean = false,
        global: () -> Unit,
        scoped: (AppSettings) -> AppSettings,
    ) {
        val id = profileId
        if (id == null) {
            global()
        } else {
            viewModel.editGestureProfile(id, optimistic = optimistic, transform = scoped)
        }
    }
}

@Composable
internal fun rememberTriggerEditScope(
    viewModel: HomeDetailSettingsViewModel,
    profileId: String?,
): TriggerEditScope {
    val gestureSettings by viewModel.gestureSettings.collectAsStateWithLifecycle()
    val landscapeEditing = TriggerSettingsLandscapeSession.active
    val globalSettings = gestureSettings.toMinimalAppSettings()
    val working = if (profileId == null) {
        globalSettings
    } else {
        globalSettings.materializeGestureProfile(profileId)
    }
    val settings = if (landscapeEditing) working.forLandscapeEditing() else working
    val profile = profileId?.let(globalSettings::gestureProfileById)
    val profileScope = profile?.let {
        GestureProfileScopeUi(
            profileId = it.id,
            profileName = it.name,
            globalSnapshot = globalSettings.gestureSnapshot(),
            profileSnapshot = it.snapshot,
        )
    }
    return TriggerEditScope(
        viewModel = viewModel,
        profileId = profileId,
        landscapeEditing = landscapeEditing,
        rawSettings = working,
        settings = settings,
        profileScope = profileScope,
    )
}

/**
 * 冻结的数据层只给了「整片写回档」的入口，没有这两个字段的纯函数版本，
 * 这里按 `EdgeSettingsMutator.setSwipeHoverDurationMs` / `setTriggerDoubleTapIntervalMs`
 * 的同一套夹取规则补上——档内写它们才不至于和全局行为不一致。
 */
internal fun AppSettings.withSwipeHoverDurationMs(value: Int): AppSettings = copy(
    edgeTrigger = edgeTrigger.copy(
        swipeHoverDurationMs = value.coerceIn(
            SwipeHoverDurationLimits.MIN_MS,
            SwipeHoverDurationLimits.MAX_MS,
        ),
    ),
)

internal fun AppSettings.withTriggerDoubleTapIntervalMs(value: Int): AppSettings = copy(
    edgeTrigger = edgeTrigger.copy(triggerDoubleTapIntervalMs = value.coerceIn(0, 800)),
)
