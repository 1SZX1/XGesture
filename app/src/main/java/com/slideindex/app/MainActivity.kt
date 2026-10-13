@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, kotlinx.coroutines.FlowPreview::class)

package com.slideindex.app

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.slideindex.app.settings.FreeWindowLayoutFractions
import com.slideindex.app.settings.OverlaySettings
import com.slideindex.app.ui.FreeWindowLayoutEditorOverlay
import com.slideindex.app.ui.FreeWindowLayoutEditorSession
import kotlinx.coroutines.launch
import com.slideindex.app.clipboard.monitor.ClipboardMonitorStartup
import com.slideindex.app.di.AppDependencies
import com.slideindex.app.external.AppLinks
import com.slideindex.app.freezer.FreezerLaunchState
import com.slideindex.app.freezer.FreezerTab
import com.slideindex.app.launcher.LauncherShortcutsApplier
import com.slideindex.app.notification.NotificationHistoryLaunchState
import com.slideindex.app.overlay.FloatBallPickResultPanel
import com.slideindex.app.overlay.WidgetPickerOverlayWindow
import com.slideindex.app.overlay.WidgetPopupOverlayWindow
import com.slideindex.app.service.OverlayService
import com.slideindex.app.service.OverlayServiceController
import com.slideindex.app.service.QuickLauncherAddTrampoline
import com.slideindex.app.service.ShellCommandEditorTrampoline
import com.slideindex.app.service.ShellCommandPanelTrampoline
import com.slideindex.app.service.ShellCommandResultTrampoline
import com.slideindex.app.service.WidgetBindTrampolineActivity
import com.slideindex.app.service.WidgetPickerTrampoline
import com.slideindex.app.service.StashClipboardTrampolineActivity
import com.slideindex.app.ui.navigation.MainNavHost
import com.slideindex.app.ui.navigation.NavPermissionStates
import com.slideindex.app.update.UpdateAppForeground
import com.slideindex.app.update.UpdateIntents
import com.slideindex.app.util.AppLocaleApplier
import com.slideindex.app.util.PermissionHelper
import com.slideindex.app.util.PredictiveBackHelper
import com.slideindex.app.util.TaskManagerUtil
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import androidx.core.content.pm.ShortcutManagerCompat

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var deps: AppDependencies

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleApplier.wrapContextIfNeeded(newBase))
    }

    internal val permissionStates = NavPermissionStates(
        overlayGranted = mutableStateOf(false),
        notificationGranted = mutableStateOf(true),
        usageAccessGranted = mutableStateOf(false),
        shizukuGranted = mutableStateOf(false),
        accessibilityGranted = mutableStateOf(false),
        batteryOptimizationExempt = mutableStateOf(false),
        writeSecureSettingsGranted = mutableStateOf(false),
        notificationListenerEnabled = mutableStateOf(false)
    )

    private val currentIntentAction = mutableStateOf<String?>(null)
    private val pendingNavRoute = mutableStateOf<String?>(null)
    private val pendingShowUpdate = mutableStateOf(false)
    /** 设置页浮层预览与常驻服务控制的入口（同进程直连）。 */
    internal lateinit var overlayServiceController: OverlayServiceController
    private val permissionRefreshHandler = Handler(Looper.getMainLooper())
    private var accessibilitySettingsObserver: ContentObserver? = null

    private val permissionRefreshRetryRunnable = object : Runnable {
        private var retryIndex = 0

        fun reset() {
            retryIndex = 0
        }

        override fun run() {
            refreshPermissionState()
            refreshServiceState()
            if (retryIndex < PERMISSION_REFRESH_RETRY_DELAYS_MS.lastIndex) {
                retryIndex++
                permissionRefreshHandler.postDelayed(
                    this,
                    PERMISSION_REFRESH_RETRY_DELAYS_MS[retryIndex]
                )
            }
        }
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        permissionStates.shizukuGranted.value = grantResult == PackageManager.PERMISSION_GRANTED
        if (permissionStates.shizukuGranted.value) {
            TaskManagerUtil.warmUpPrivilegedBackend()
            deps.clipboardHistoryRepository.syncClipboardMonitoringFromSettings()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionStates.notificationGranted.value =
            granted || PermissionHelper.hasNotificationPermission(this)
        refreshServiceState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyLaunchIntent(intent)
        overlayServiceController = OverlayServiceController(
            context = this,
            permissionStates = permissionStates,
            scope = lifecycleScope,
            settingsRepository = deps.settingsRepository
        )
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> UpdateAppForeground.isInForeground = true
                    Lifecycle.Event.ON_STOP -> UpdateAppForeground.isInForeground = false
                    else -> Unit
                }
            }
        )
        enableEdgeToEdge()
        refreshPermissionState()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            applyPredictiveBackEnabled(deps.settingsRepository.readSnapshot().predictiveBackEnabled)
        }

        setContent {
            val initialIntentAction by currentIntentAction
            val initialNavRoute by pendingNavRoute
            val showUpdate by pendingShowUpdate
            // 小窗尺寸编辑层挂在根部（导航宿主之上），这样它能盖住整屏、且不受导航栈影响。
            val freeWindowSettings by deps.settingsRepository.overlaySettings.collectAsStateWithLifecycle(
                initialValue = OverlaySettings.from(deps.settingsRepository.readSnapshot()),
            )
            val editorScope = rememberCoroutineScope()
            Box(modifier = Modifier.fillMaxSize()) {
                MainNavHost(
                    activity = this@MainActivity,
                    deps = deps,
                    permissionStates = permissionStates,
                    initialIntentAction = initialIntentAction,
                    initialNavRoute = initialNavRoute,
                    showUpdateFromIntent = showUpdate,
                    onNavRouteConsumed = { pendingNavRoute.value = null },
                    onShowUpdateConsumed = { pendingShowUpdate.value = false }
                )
                if (FreeWindowLayoutEditorSession.isOpen) {
                    FreeWindowLayoutEditorOverlay(
                        portrait = FreeWindowLayoutFractions(
                            widthFraction = freeWindowSettings.freeWindowWidthFraction,
                            heightFraction = freeWindowSettings.freeWindowHeightFraction,
                            leftFraction = freeWindowSettings.freeWindowLeftFraction,
                            topFraction = freeWindowSettings.freeWindowTopFraction,
                        ),
                        landscape = FreeWindowLayoutFractions(
                            widthFraction = freeWindowSettings.freeWindowLandWidthFraction,
                            heightFraction = freeWindowSettings.freeWindowLandHeightFraction,
                            leftFraction = freeWindowSettings.freeWindowLandLeftFraction,
                            topFraction = freeWindowSettings.freeWindowLandTopFraction,
                        ),
                        onDismiss = { FreeWindowLayoutEditorSession.close() },
                        onSave = { portrait, landscape ->
                            editorScope.launch {
                                deps.settingsRepository.setFreeWindowLayout(portrait, landscape)
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyLaunchIntent(intent)
    }

    private fun applyLaunchIntent(intent: Intent?) {
        val resolvedAction = resolveLaunchAction(intent)
        currentIntentAction.value = resolvedAction
        pendingNavRoute.value = intent?.getStringExtra(EXTRA_NAV_ROUTE)
        pendingShowUpdate.value = intent?.getBooleanExtra(UpdateIntents.EXTRA_SHOW_UPDATE, false) == true
        when (intent?.getStringExtra(EXTRA_FREEZER_TAB)) {
            FREEZER_TAB_FROZEN -> FreezerLaunchState.setPendingInitialTab(FreezerTab.FROZEN)
        }
        reportShortcutUsageIfNeeded(resolvedAction)
    }

    private fun resolveLaunchAction(intent: Intent?): String? {
        intent?.data?.let { uri ->
            if (AppLinks.isAppLink(uri)) {
                when (uri.pathSegments.firstOrNull()?.lowercase()) {
                    AppLinks.PATH_NOTIFICATION_HISTORY -> {
                        NotificationHistoryLaunchState.setPendingSearchQuery(
                            uri.getQueryParameter(AppLinks.QUERY_PARAM)
                        )
                        return ACTION_OPEN_NOTIFICATION_HISTORY
                    }
                }
            }
        }
        return intent?.action
    }

    private fun reportShortcutUsageIfNeeded(action: String?) {
        val shortcutId = when (action) {
            "com.slideindex.app.action.TOGGLE_GESTURE" -> "toggle_gesture"
            ACTION_OPEN_NOTIFICATION_HISTORY -> "notification_hub"
            "com.slideindex.app.action.OPEN_SHELL_PANEL" -> "shell_panel"
            StashClipboardTrampolineActivity.ACTION_OPEN_STASH -> StashClipboardTrampolineActivity.SHORTCUT_ID_STASH
            StashClipboardTrampolineActivity.ACTION_OPEN_CLIPBOARD -> StashClipboardTrampolineActivity.SHORTCUT_ID_CLIPBOARD
            else -> null
        }
        shortcutId?.let { ShortcutManagerCompat.reportShortcutUsed(this, it) }
    }

    private fun setupDynamicShortcuts() {
        // 条目集合与顺序由「外部调用 → 桌面图标长按菜单」设置决定，见 LauncherShortcutsApplier。
        val settings = deps.settingsRepository.readSnapshot()
        LauncherShortcutsApplier.sync(
            this,
            order = settings.launcherShortcutMenuOrder,
            disabled = settings.launcherShortcutMenuDisabled,
        )
    }

    override fun onStart() {
        super.onStart()
        registerAccessibilitySettingsObserver()
    }

    override fun onResume() {
        super.onResume()
        setupDynamicShortcuts()
        refreshPermissionState()
        schedulePermissionRefreshRetries()
        refreshServiceState()
        ClipboardMonitorStartup.runOnMainWhenReady {
            // 兜底：监听没在跑时（Shizuku 未启动 / 监听服务被停 / binder 掉线）先把最新一条补进历史，
            // 再尝试拉起监听。监听正常时这个方法内部会直接返回，不会多抢一次焦点。
            deps.clipboardHistoryRepository.catchUpLatestClipboard(this)
            deps.clipboardHistoryRepository.syncClipboardMonitoringFromSettings()
        }
        com.slideindex.app.widget.WidgetPopupHost.startListening(this)
        lifecycleScope.launch {
            applyHideFromRecents(deps.settingsRepository.settings.first().hideFromRecents)
        }
    }

    override fun onDestroy() {
        cancelPermissionRefreshRetries()
        unregisterAccessibilitySettingsObserver()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    override fun onPause() {
        cancelPermissionRefreshRetries()
        if (!WidgetBindTrampolineActivity.isActive() &&
            !WidgetPickerOverlayWindow.isShowing
        ) {
            com.slideindex.app.widget.WidgetPopupHost.stopListening(this)
        }
        if (!QuickLauncherAddTrampoline.isActive() &&
            !ShellCommandPanelTrampoline.isActive() &&
            !ShellCommandEditorTrampoline.isActive() &&
            !ShellCommandResultTrampoline.isActive()
        ) {
            overlayServiceController.stopPreviewOnPause()
        }
        super.onPause()
    }

    override fun onStop() {
        unregisterAccessibilitySettingsObserver()
        super.onStop()
    }

    private fun schedulePermissionRefreshRetries() {
        cancelPermissionRefreshRetries()
        permissionRefreshRetryRunnable.reset()
        permissionRefreshHandler.postDelayed(
            permissionRefreshRetryRunnable,
            PERMISSION_REFRESH_RETRY_DELAYS_MS[0]
        )
    }

    private fun cancelPermissionRefreshRetries() {
        permissionRefreshHandler.removeCallbacks(permissionRefreshRetryRunnable)
        permissionRefreshRetryRunnable.reset()
    }

    private fun registerAccessibilitySettingsObserver() {
        if (accessibilitySettingsObserver != null) return
        val observer = object : ContentObserver(permissionRefreshHandler) {
            override fun onChange(selfChange: Boolean) {
                refreshPermissionState()
                refreshServiceState()
            }
        }
        val resolver = contentResolver
        resolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
            false,
            observer
        )
        resolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            observer
        )
        accessibilitySettingsObserver = observer
    }

    private fun unregisterAccessibilitySettingsObserver() {
        accessibilitySettingsObserver?.let { observer ->
            runCatching { contentResolver.unregisterContentObserver(observer) }
        }
        accessibilitySettingsObserver = null
    }

    internal fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    internal fun refreshPermissionState() {
        overlayServiceController.refreshPermissionState()
    }

    internal fun applyHideFromRecents(hide: Boolean) {
        getSystemService(ActivityManager::class.java)
            ?.appTasks
            ?.firstOrNull()
            ?.setExcludeFromRecents(hide)
    }

    internal fun applyPredictiveBackEnabled(enabled: Boolean) {
        PredictiveBackHelper.applyEnabled(applicationInfo, enabled)
        FloatBallPickResultPanel.refreshBackHandlingIfShowing()
        WidgetPopupOverlayWindow.refreshBackHandlingIfVisible()
    }

    @Suppress("DEPRECATION")
    internal fun recreateWithoutTransition() {
        overridePendingTransition(0, 0)
        recreate()
        overridePendingTransition(0, 0)
    }

    internal fun refreshServiceState() {
        overlayServiceController.refreshServiceState()
    }

    companion object {
        const val ACTION_OPEN_NOTIFICATION_HISTORY = "com.slideindex.app.action.OPEN_NOTIFICATION_HISTORY"
        const val EXTRA_NAV_ROUTE = "extra_nav_route"
        const val EXTRA_FREEZER_TAB = "extra_freezer_tab"
        const val NAV_ROUTE_EXTENSION_FREEZER = "extension_freezer"
        const val NAV_ROUTE_EXTENSION_FREEZER_APPS = "extension_freezer_apps"
        const val FREEZER_TAB_FROZEN = "frozen"

        /** Gaps after resume; first tick is relative to scheduling (see [schedulePermissionRefreshRetries]). */
        private val PERMISSION_REFRESH_RETRY_DELAYS_MS = longArrayOf(300L, 500L)
    }
}
