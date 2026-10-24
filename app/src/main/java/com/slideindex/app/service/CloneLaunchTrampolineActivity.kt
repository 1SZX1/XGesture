package com.slideindex.app.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.UserHandle
import android.util.Log
import android.view.WindowManager

/**
 * 无特权时跨 user 启动的“前台跳板”。
 *
 * 直接从浮层（后台）调 `LauncherApps.startMainActivity` 会被系统的
 * `AppSwitchWarningActivity` 拦截，用户确认后仍可能返回 `START_CANCELED`（Flyme 实测 -93）。
 * 先把本 Activity 拉到前台，再由它发起启动：调用方从“后台窗口”变成“前台 Activity”，
 * 既不触发后台启动限制，也不会再被那条确认链路取消。
 *
 * 自身必须保持透明、无动画、不进最近任务，启动后立刻 finish()。
 */
class CloneLaunchTrampolineActivity : Activity() {
    private var launched = false
    private var packageName: String = ""
    private var userId: Int = 0

    private val finishRunnable = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        // 透明 + 不接收触摸：即使因为等用户确认而短暂停留，也绝不能挡住下面浮层的点击。
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        )
        readTarget(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 上一次的跳板还没收掉时，新的点击会复用这个实例：必须重新读目标再发一次。
        launched = false
        window.decorView.removeCallbacks(finishRunnable)
        readTarget(intent)
        maybeLaunch()
    }

    private fun readTarget(intent: Intent?) {
        packageName = intent?.getStringExtra(EXTRA_PACKAGE).orEmpty()
        userId = intent?.getIntExtra(EXTRA_USER_ID, 0) ?: 0
        if (packageName.isBlank() || userId <= 0) finish()
    }

    override fun onResume() {
        super.onResume()
        maybeLaunch()
    }

    private fun maybeLaunch() {
        if (launched || packageName.isBlank() || userId <= 0) return
        launched = true
        // 必须等 Activity 真正回到前台再发起跨 user 启动：
        // 这样系统判定调用方是前台 Activity，而不是后台浮层。
        val ok = runCatching { launchInUser(packageName, userId) }.getOrDefault(false)
        Log.i(TAG, "trampoline launch $packageName@$userId -> $ok")
        // 不能立刻 finish：系统的「正在尝试打开」确认框/多开选择器会把结果回给发起 Activity，
        // 发起者提前销毁会让用户确认之后只拿到 START_CANCELED（Flyme 实测 -93）。
        // 窗口不可触摸，所以即使保活也不会挡住后续操作。
        window.decorView.postDelayed(finishRunnable, FINISH_TIMEOUT_MS)
    }

    override fun onDestroy() {
        window.decorView.removeCallbacks(finishRunnable)
        super.onDestroy()
    }

    private fun launchInUser(packageName: String, userId: Int): Boolean {
        val launcherApps = getSystemService(LauncherApps::class.java) ?: return false
        val user = UserHandle.getUserHandleForUid(userId * PER_USER_RANGE)
        val activity = launcherApps.getActivityList(packageName, user).firstOrNull() ?: return false
        launcherApps.startMainActivity(activity.componentName, user, null, null)
        return true
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val TAG = "CloneLaunchTrampoline"
        private const val PER_USER_RANGE = 100_000
        private const val FINISH_TIMEOUT_MS = 60_000L
        private const val EXTRA_PACKAGE = "clone_package"
        private const val EXTRA_USER_ID = "clone_user_id"

        fun intent(context: Context, packageName: String, userId: Int): Intent =
            Intent(context, CloneLaunchTrampolineActivity::class.java)
                .putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_USER_ID, userId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }
}
