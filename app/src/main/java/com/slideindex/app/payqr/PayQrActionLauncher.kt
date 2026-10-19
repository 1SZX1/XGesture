package com.slideindex.app.payqr

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import com.slideindex.app.R

/**
 * 「微信扫一扫 / 微信付款码 / 支付宝付款码 / 支付宝扫一扫」四个动作。
 *
 * Intent 的构造方式逆向自 MyGesture 7.2194P 的 `f4/f2`（`a`/`b`/`c`/`d` 四个方法），四个动作逐字节对齐；
 * 分析过程与真机验证记录见 `docs/reverse/mygesture-pay-actions/README.md`。
 *
 * 与原版唯一的差别是启动方式：原版一律包一层 `PendingIntent.send()`（绕后台启动 Activity 限制），
 * 这里先直接 `startActivity`（本项目其它「打开应用」动作也这么做、实测可用），
 * 被拦/抛异常时再退回 `PendingIntent.send()`，两个都失败才提示。
 */
object PayQrActionLauncher {
    private const val TAG = "PayQrActionLauncher"

    private const val WECHAT_PACKAGE = "com.tencent.mm"
    private const val WECHAT_LAUNCHER_ACTIVITY = "com.tencent.mm.ui.LauncherUI"

    /** 微信扫一扫的入口开关。`Scaner` 这个拼写错误是微信自己的约定，必须逐字一致。 */
    private const val WECHAT_SCAN_EXTRA = "LauncherUI.From.Scaner.Shortcut"

    /** `launchFlags=0x4000000` = FLAG_ACTIVITY_CLEAR_TOP；`launch_type_offline_wallet` = 微信付款码。 */
    private const val WECHAT_PAY_CODE_URI =
        "android-app://com.tencent.mm/#Intent;action=com.tencent.mm.action.BIZSHORTCUT;" +
            "launchFlags=0x4000000;S.LauncherUI.Shortcut.LaunchType=launch_type_offline_wallet;end"

    private const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"

    /** 支付宝的「快速启动」入口，会自己路由到当面付 / 付款码页（`onsitepay.merge.OnsitepayActivity`）。 */
    private const val ALIPAY_FAST_START_ACTIVITY = "com.eg.android.AlipayGphone.FastStartActivity"

    /** `saId=10000007` = 支付宝扫一扫。 */
    private const val ALIPAY_SCAN_URI = "alipays://platformapi/startapp?saId=10000007&sourceId=shortcut"

    /** 四个动作；[packageName] 用来判断目标 App 是否安装，[appLabelRes] 只用于提示文案。 */
    enum class PayQrAction(
        val packageName: String,
        @StringRes val appLabelRes: Int,
    ) {
        WECHAT_SCAN(WECHAT_PACKAGE, R.string.pay_qr_app_wechat),
        WECHAT_PAY_CODE(WECHAT_PACKAGE, R.string.pay_qr_app_wechat),
        ALIPAY_PAY_CODE(ALIPAY_PACKAGE, R.string.pay_qr_app_alipay),
        ALIPAY_SCAN(ALIPAY_PACKAGE, R.string.pay_qr_app_alipay),
        ;

        fun buildIntent(): Intent = when (this) {
            WECHAT_SCAN -> Intent(Intent.ACTION_VIEW).apply {
                component = ComponentName(WECHAT_PACKAGE, WECHAT_LAUNCHER_ACTIVITY)
                putExtra(WECHAT_SCAN_EXTRA, true)
            }

            WECHAT_PAY_CODE -> Intent.parseUri(WECHAT_PAY_CODE_URI, Intent.URI_INTENT_SCHEME)

            ALIPAY_PAY_CODE -> Intent(Intent.ACTION_VIEW).apply {
                component = ComponentName(ALIPAY_PACKAGE, ALIPAY_FAST_START_ACTIVITY)
            }

            ALIPAY_SCAN -> Intent(Intent.ACTION_VIEW, Uri.parse(ALIPAY_SCAN_URI))
        }
    }

    /**
     * 执行动作。
     *
     * @return true = 已把 Intent 交给系统（不保证目标页一定显示出来，付款码页本身是 FLAG_SECURE）。
     */
    fun open(context: Context, action: PayQrAction): Boolean {
        val appContext = context.applicationContext
        val appLabel = appContext.getString(action.appLabelRes)
        if (!isInstalled(appContext, action.packageName)) {
            toast(context, appContext.getString(R.string.pay_qr_app_missing, appLabel))
            return false
        }
        val intent = runCatching { action.buildIntent() }
            .onFailure { Log.w(TAG, "build intent failed: $action", it) }
            .getOrNull()
        if (intent == null) {
            toast(context, appContext.getString(R.string.pay_qr_launch_failed, appLabel))
            return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (startDirectly(appContext, intent)) {
            Log.i(TAG, "opened $action")
            return true
        }
        // 后台启动 Activity（BAL）被拦时 startActivity 可能不抛异常但也没起来；这里用 PendingIntent 再试一次。
        if (startViaPendingIntent(appContext, intent)) {
            Log.i(TAG, "opened $action (PendingIntent fallback)")
            return true
        }
        Log.w(TAG, "open failed: $action")
        toast(context, appContext.getString(R.string.pay_qr_launch_failed, appLabel))
        return false
    }

    private fun isInstalled(context: Context, packageName: String): Boolean =
        runCatching { context.packageManager.getLaunchIntentForPackage(packageName) != null }
            .getOrDefault(false)

    private fun startDirectly(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (missing: ActivityNotFoundException) {
        Log.w(TAG, "target activity not found", missing)
        false
    } catch (error: Exception) {
        // 部分 ROM 的后台启动限制会以 SecurityException 等形式抛出来。
        Log.w(TAG, "startActivity failed", error)
        false
    }

    private fun startViaPendingIntent(context: Context, intent: Intent): Boolean {
        val pendingIntent = runCatching {
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
            )
        }.getOrNull() ?: return false
        return runCatching {
            pendingIntent.send()
            true
        }.getOrDefault(false)
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
