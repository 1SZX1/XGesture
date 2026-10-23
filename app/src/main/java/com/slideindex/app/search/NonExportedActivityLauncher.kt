package com.slideindex.app.search

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.slideindex.app.search.ral.PrivilegedActivityLauncher
import com.slideindex.app.util.TaskManagerUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Launch non-exported activities using RootActivityLauncher-compatible strategy chain.
 *
 * Based on [Root Activity Launcher](https://github.com/zacharee/RootActivityLauncher) (GPL-3.0).
 *
 * Work runs on IO coroutine dispatcher and [onComplete] is invoked on the main thread when finished (avoids ANR).
 */
object NonExportedActivityLauncher {
    private const val TAG = "NonExportedActivityLauncher"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val launcherScope = CoroutineScope(Dispatchers.IO)

    fun launch(
        context: Context,
        packageName: String,
        activityName: String,
        options: Bundle? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ): Boolean {
        if (packageName.isBlank() || activityName.isBlank()) {
            onComplete?.let { mainHandler.post { it(false) } }
            return false
        }
        if (!TaskManagerUtil.hasPermission()) {
            Log.w(TAG, "privileged access missing for $packageName/$activityName")
            onComplete?.let { mainHandler.post { it(false) } }
            return false
        }
        if (options != null) {
            Log.d(TAG, "launch options ignored; RAL strategies do not accept activity options")
        }

        val appContext = context.applicationContext
        launcherScope.launch {
            val success = PrivilegedActivityLauncher.launch(
                context = appContext,
                packageName = packageName,
                activityName = activityName,
                privilegedOnly = true
            ).isEmpty()
            onComplete?.let { callback ->
                mainHandler.post { callback(success) }
            }
        }
        return true
    }

    /**
     * 用完整 Intent 走特权启动，保留 action / data / extras。
     * 适用于显式组件但未导出的场景（[launch] 的包名+Activity 形式只能重建一个空 Intent）。
     */
    fun launch(
        context: Context,
        intent: Intent,
        onComplete: ((Boolean) -> Unit)? = null
    ): Boolean {
        val component = intent.component
        if (component == null) {
            onComplete?.let { mainHandler.post { it(false) } }
            return false
        }
        if (!TaskManagerUtil.hasPermission()) {
            Log.w(TAG, "privileged access missing for ${component.flattenToShortString()}")
            onComplete?.let { mainHandler.post { it(false) } }
            return false
        }
        val appContext = context.applicationContext
        launcherScope.launch {
            val success = PrivilegedActivityLauncher.launch(
                context = appContext,
                intent = intent,
                privilegedOnly = true
            ).isEmpty()
            onComplete?.let { callback ->
                mainHandler.post { callback(success) }
            }
        }
        return true
    }
}
