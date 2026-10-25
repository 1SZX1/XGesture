package com.slideindex.app.notification

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.util.Log
import com.slideindex.app.diagnostic.EdgeDiag
import com.slideindex.app.message.NotificationData
import com.slideindex.app.settings.AppSettings
import com.slideindex.app.settings.FreeWindowMode
import com.slideindex.app.settings.resolvedFreeWindowMode
import com.slideindex.app.util.FreeWindowLauncher
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppNotificationIntentLaunchPort @Inject constructor(
    private val listenerPort: NotificationListenerPort
) : NotificationIntentLaunchPort {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun open(context: Context, data: NotificationData): Boolean {
        val appContext = context.applicationContext
        findSbn(data)?.let { sbn ->
            if (replayFromSbn(appContext, sbn)) return true
        }
        if (sendPendingIntent(appContext, data.contentIntent)) return true
        return NotificationAppLauncher.open(appContext, data.packageName)
    }

    override fun openInSmallWindow(context: Context, data: NotificationData, settings: AppSettings): Boolean {
        val appContext = context.applicationContext
        if (!settings.freeWindowEnabled) {
            EdgeDiag.log("小窗阅读", "小窗总开关关闭，按全屏打开 pkg=${data.packageName}")
            return open(context, data)
        }

        val mode = settings.resolvedFreeWindowMode()
        if (mode.usesLauncherFirstForSmallWindow()) {
            if (openInSmallWindowByLauncherThenNavigate(appContext, data, settings, mode)) {
                return true
            }
            EdgeDiag.log("小窗阅读", "先 launch 失败，回退旧链路 mode=$mode pkg=${data.packageName}")
        }
        return openInSmallWindowByPendingIntent(appContext, data, settings, mode)
    }

    /**
     * 米窗 / 努比亚：通知链路只会带 ActivityOptions，漏掉这两档真正开窗的动作
     * （米窗广播、努比亚 `_WindowReply` 标识），结果就是系统自由窗口。
     *
     * 这里改成和「应用索引长按」同一条启动路径：先把应用开进我们的小窗，
     * 等任务真正建好之后，再补发一次不带任何窗口参数的通知点击，只让它跳到消息页。
     * 第二步发不出去时应用已经停在小窗里，所以最差也只是停在应用页。
     */
    private fun openInSmallWindowByLauncherThenNavigate(
        context: Context,
        data: NotificationData,
        settings: AppSettings,
        mode: FreeWindowMode,
    ): Boolean {
        // 先取 contentIntent：部分应用被打开后会立刻取消通知，事后再取可能就没了。
        val contentIntent = findSbn(data)?.notification?.contentIntent ?: data.contentIntent
        val launched = FreeWindowLauncher.launchPackageInFreeWindow(context, data.packageName, settings)
        if (!launched) {
            return false
        }
        if (contentIntent == null) {
            EdgeDiag.log("小窗阅读", "小窗已开但无 contentIntent，停在应用页 mode=$mode pkg=${data.packageName}")
            return true
        }

        EdgeDiag.log("小窗阅读", "先开小窗（厂商分支可能回落），${NAVIGATE_DELAY_MS}ms 后补发通知点击 mode=$mode pkg=${data.packageName}")
        mainHandler.postDelayed({
            val sent = sendPendingIntent(context, contentIntent)
            EdgeDiag.log("小窗阅读", "补发通知点击 sent=$sent pkg=${data.packageName}")
        }, NAVIGATE_DELAY_MS)
        return true
    }

    private fun openInSmallWindowByPendingIntent(
        context: Context,
        data: NotificationData,
        settings: AppSettings,
        mode: FreeWindowMode,
    ): Boolean {
        EdgeDiag.log("小窗阅读", "旧链路：通知点击 + 窗口参数 mode=$mode pkg=${data.packageName}")
        val launchOptions = FreeWindowLauncher.launchOptionsBundle(context, settings)
        findSbn(data)?.let { sbn ->
            if (replayFromSbn(context, sbn, launchOptions)) return true
        }
        if (sendPendingIntent(context, data.contentIntent, launchOptions)) return true
        return openAppInSmallWindow(context, data.packageName, settings)
    }

    private fun findSbn(data: NotificationData): StatusBarNotification? {
        NotificationSbnCache.find(data.key, data.postTime)?.let { return it }
        val listener = listenerPort.listenerOrNull() ?: return null
        return runCatching {
            listener.activeNotifications?.firstOrNull { it.key == data.key }
        }.getOrNull()
    }

    private fun replayFromSbn(
        context: Context,
        sbn: StatusBarNotification,
        launchOptions: Bundle? = null
    ): Boolean {
        val notification = sbn.notification ?: return false
        if (sendPendingIntent(context, notification.contentIntent, launchOptions)) return true
        if (sendPendingIntent(context, notification.fullScreenIntent, launchOptions)) return true
        if (sendPendingIntent(context, notification.publicVersion?.contentIntent, launchOptions)) return true
        notification.actions?.forEach { action ->
            if (sendPendingIntent(context, action.actionIntent, launchOptions)) return true
        }
        return false
    }

    private fun sendPendingIntent(
        context: Context,
        pendingIntent: PendingIntent?,
        launchOptions: Bundle? = null
    ): Boolean {
        if (pendingIntent == null) return false
        val options = launchOptions ?: createPendingIntentSendOptions()
        val sentWithContext = runCatching {
            pendingIntent.send(context, 0, null, null, null, null, options)
        }.onFailure { error ->
            Log.w(TAG, "PendingIntent.send(context) failed", error)
        }.isSuccess
        if (sentWithContext) return true
        return runCatching {
            pendingIntent.send()
        }.onFailure { error ->
            Log.w(TAG, "PendingIntent.send() failed", error)
        }.isSuccess
    }

    private fun openAppInSmallWindow(
        context: Context,
        packageName: String,
        settings: AppSettings
    ): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        FreeWindowLauncher.launch(context, launchIntent, settings, fullscreen = false)
        return true
    }

    private fun createPendingIntentSendOptions(): Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val options = ActivityOptions.makeBasic()
        val mode = when {
            Build.VERSION.SDK_INT >= 36 -> ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
            else -> @Suppress("DEPRECATION") ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }
        options.pendingIntentBackgroundActivityStartMode = mode
        return options.toBundle()
    }

    private companion object {
        const val TAG = "NotificationIntentLauncher"

        /**
         * 第二步「补发通知点击」的延迟：等系统把任务真正建在小窗里。
         * 太早会另起一个任务（常见于冷启动），太晚用户已经看到应用首页在闪。
         * 400ms 是首版经验值，真机上按 `launcher-first: navigate` 日志与窗口时序再调。
         */
        const val NAVIGATE_DELAY_MS = 400L
    }
}

/**
 * 「小窗阅读」是否要先走 [FreeWindowLauncher.launchPackageInFreeWindow] 再补通知点击。
 *
 * 只有米窗与努比亚：它们开窗的关键动作不在 ActivityOptions 里（分别是米窗广播和
 * `_WindowReply` 标识），通知链路重放 PendingIntent 时带不上，必然退化成系统自由窗口。
 * 其余模式继续走原来的「通知点击 + 窗口参数」，行为不变。
 */
private fun FreeWindowMode.usesLauncherFirstForSmallWindow(): Boolean =
    this == FreeWindowMode.SUNSHINE || this == FreeWindowMode.NUBIA
