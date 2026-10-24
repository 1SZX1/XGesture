package com.slideindex.app.util

import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.util.Log

/**
 * 在别的 user（应用分身 / 工作资料）里启动应用。
 *
 * 两条路径，按"稳"排序：
 * 1. 有特权（Shizuku / root）时仍走 shell：`resolve-activity --user N` + `am start --user N`，
 *    能绕过 Android 10+ 的后台启动（BAL）限制，行为与之前完全一致。
 * 2. 没有特权时退回公开 API：`LauncherApps.getActivityList(pkg, user)` + `startMainActivity(...)`。
 *    这条路径不需要 Shizuku，但要求调用方有可见窗口（浮层场景满足），否则可能被 BAL 静默拦下。
 */
internal object CloneAppLauncher {
    private const val TAG = "CloneAppLauncher"
    private const val PER_USER_RANGE = 100_000

    fun launch(context: Context, packageName: String, userId: Int): Boolean {
        if (userId <= 0) return false
        if (TaskManagerUtil.hasPrivilegedAccess() && launchViaShell(packageName, userId)) {
            return true
        }
        return launchViaLauncherApps(context, packageName, userId)
    }

    private fun launchViaShell(packageName: String, userId: Int): Boolean {
        val component = runCatching {
            TaskManagerUtil.runShellCommandOutput(
                "cmd", "package", "resolve-activity", "--brief", "--user", userId.toString(), packageName,
            ).output.lineSequence()
                .map { it.trim() }
                .lastOrNull { it.contains('/') && !it.startsWith("priority=") }
        }.getOrNull()
        if (component != null) {
            val result = TaskManagerUtil.runShellCommandOutput(
                "am", "start", "--user", userId.toString(), "-n", component,
            )
            Log.i(TAG, "launch via shell $packageName@$userId -> exit=${result.exitCode}")
            if (result.exitCode == 0) return true
        }
        return runCatching {
            TaskManagerUtil.runShellCommandOutput(
                "monkey", "--user", userId.toString(), "-p", packageName,
                "-c", "android.intent.category.LAUNCHER", "1",
            ).exitCode == 0
        }.getOrDefault(false)
    }

    private fun launchViaLauncherApps(context: Context, packageName: String, userId: Int): Boolean {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return false
        val user = runCatching {
            UserHandle.getUserHandleForUid(userId * PER_USER_RANGE)
        }.getOrNull() ?: return false
        val activity = runCatching {
            launcherApps.getActivityList(packageName, user).firstOrNull()
        }.getOrNull() ?: return false
        return runCatching {
            launcherApps.startMainActivity(activity.componentName, user, null, null)
            Log.i(TAG, "launch via LauncherApps $packageName@$userId")
            true
        }.getOrElse { error ->
            Log.w(TAG, "LauncherApps launch failed for $packageName@$userId", error)
            false
        }
    }
}
