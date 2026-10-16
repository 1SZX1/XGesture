package com.slideindex.app.service

import android.content.Context
import android.content.Intent
import com.slideindex.app.settings.HistoryFloatHandleSide
import com.slideindex.app.settings.SettingsRepository
import com.slideindex.app.util.PermissionHelper
import kotlinx.coroutines.flow.first

/** Starts or stops [HistoryFloatService] based on clipboard history float settings. */
object HistoryFloatLifecycle {
    suspend fun syncFromSettings(context: Context, settingsRepository: SettingsRepository) {
        val appContext = context.applicationContext
        val settings = settingsRepository.settings.first()
        if (settings.clipboardHistoryFloatEnabled && PermissionHelper.canDrawOverlays(appContext)) {
            start(
                context = appContext,
                side = settings.clipboardHistoryFloatHandleSide,
                lockPosition = settings.clipboardHistoryFloatLockPosition,
                landscapeEnabled = settings.clipboardHistoryFloatEnabledLandscape
            )
        } else {
            stop(appContext)
        }
    }

    fun start(
        context: Context,
        side: HistoryFloatHandleSide = HistoryFloatHandleSide.DEFAULT,
        lockPosition: Boolean = true,
        landscapeEnabled: Boolean = false
    ) {
        if (!PermissionHelper.canDrawOverlays(context)) return
        val appContext = context.applicationContext
        appContext.startService(
            Intent(appContext, HistoryFloatService::class.java).apply {
                putExtra(HistoryFloatService.EXTRA_HANDLE_SIDE, side.storageKey)
                putExtra(HistoryFloatService.EXTRA_LOCK_POSITION, lockPosition)
                putExtra(HistoryFloatService.EXTRA_LANDSCAPE_ENABLED, landscapeEnabled)
            }
        )
    }

    fun stop(context: Context) {
        context.applicationContext.stopService(Intent(context.applicationContext, HistoryFloatService::class.java))
    }

    /** 应用内语言变更后重建浮窗，使 Compose 使用新 Configuration。 */
    fun recreateForLocaleChange(
        context: Context,
        side: HistoryFloatHandleSide = HistoryFloatHandleSide.DEFAULT,
        lockPosition: Boolean = true,
        landscapeEnabled: Boolean = false,
    ) {
        val appContext = context.applicationContext
        stop(appContext)
        start(
            context = appContext,
            side = side,
            lockPosition = lockPosition,
            landscapeEnabled = landscapeEnabled,
        )
    }

    fun applyRuntimeConfig(
        context: Context,
        side: HistoryFloatHandleSide,
        lockPosition: Boolean,
        landscapeEnabled: Boolean
    ) {
        val appContext = context.applicationContext
        appContext.startService(
            Intent(appContext, HistoryFloatService::class.java).apply {
                action = HistoryFloatService.ACTION_SET_HANDLE_SIDE
                putExtra(HistoryFloatService.EXTRA_HANDLE_SIDE, side.storageKey)
            }
        )
        appContext.startService(
            Intent(appContext, HistoryFloatService::class.java).apply {
                action = HistoryFloatService.ACTION_LOCK_POSITION
                putExtra(HistoryFloatService.EXTRA_LOCK_POSITION, lockPosition)
            }
        )
        appContext.startService(
            Intent(appContext, HistoryFloatService::class.java).apply {
                action = HistoryFloatService.ACTION_SET_LANDSCAPE_ENABLED
                putExtra(HistoryFloatService.EXTRA_LANDSCAPE_ENABLED, landscapeEnabled)
            }
        )
    }
}
