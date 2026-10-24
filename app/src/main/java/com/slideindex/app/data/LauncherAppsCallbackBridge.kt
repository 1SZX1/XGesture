package com.slideindex.app.data

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import com.slideindex.app.overlay.CloneAppsIndex
import com.slideindex.app.overlay.OverlayRefreshBus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LauncherAppsCallbackBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appRepository: AppRepository,
) {
    private val launcherApps: LauncherApps? =
        context.getSystemService(LauncherApps::class.java)

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) {
            onPackageEvent("launcher_added:$packageName", user)
        }

        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            onPackageEvent("launcher_removed:$packageName", user)
        }

        override fun onPackageChanged(packageName: String, user: UserHandle) {
            onPackageEvent("launcher_changed:$packageName", user)
        }

        override fun onPackagesAvailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) {
            onPackageEvent("launcher_available:${packageNames.joinToString()}", user)
        }

        override fun onPackagesUnavailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) {
            onPackageEvent("launcher_unavailable:${packageNames.joinToString()}", user)
        }

        private fun onPackageEvent(reason: String, user: UserHandle) {
            appRepository.invalidate()
            appRepository.requestRefresh(reason)
            // 只有当前用户的变化不影响分身；别的 user 装/卸包时，分身条目也要重算，
            // 否则索引会停留在"新克隆的应用看不到 / 已删的克隆还在"。
            if (user.hashCode() != Process.myUserHandle().hashCode()) {
                CloneAppsIndex.invalidate()
                OverlayRefreshBus.notifyCloneSpaceChanged()
            }
        }
    }

    fun register() {
        launcherApps?.registerCallback(callback)
    }

    fun unregister() {
        launcherApps?.unregisterCallback(callback)
    }
}
