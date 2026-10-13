package com.slideindex.app.util

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import com.slideindex.app.settings.AppSettings
import com.slideindex.app.settings.resolvedFreeWindowLayout
import com.slideindex.app.settings.resolvedFreeWindowMode
import com.slideindex.app.settings.usesNubiaFreeformIdentifier
import com.slideindex.app.settings.usesSunshineBroadcast

import android.content.ComponentName
import android.content.pm.PackageManager
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.WindowManager
import com.slideindex.app.overlay.OverlayScreenMetrics
import com.slideindex.app.overlay.PanelSide
import com.slideindex.app.service.FreeWindowShareProxyActivity
import com.slideindex.app.settings.FreeWindowMode
import kotlin.math.roundToInt

object FreeWindowLauncher {
    private const val TAG = "FreeWindowLauncher"
    private const val KEY_WINDOWING_MODE = "android.activity.windowingMode"
    private const val NUBIA_FREEFORM_INTENT_IDENTIFIER = "_WindowReply"
    private const val HUAWEI_FREEFORM_STACK_ID = 2
    private const val MIUI_PORTRAIT_SCALE = 0.7f
    private const val MIUI_LANDSCAPE_SCALE = 0.555f

    /** 米窗（Sunshine）包名与它约定的两条广播。 */
    private const val SUNSHINE_PACKAGE = "com.sunshine.freeform"
    private const val ACTION_SUNSHINE_START_FREEFORM = "com.sunshine.freeform.start_freeform"
    private const val ACTION_SUNSHINE_START_BY_MI_FREEFORM = "com.sunshine.freeform.start_by_mi_freeform"
    private const val EXTRA_SUNSHINE_PACKAGE_NAME = "packageName"
    private const val EXTRA_SUNSHINE_ACTIVITY_NAME = "activityName"

    /** 小窗底部安全边距：状态栏高度与 20mm（≈126dp）取大者，避免压住手势条 / 输入法区。 */
    private const val BOTTOM_SAFE_MARGIN_MM = 20f

    fun launch(context: Context, intent: Intent, settings: AppSettings, fullscreen: Boolean) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!fullscreen && settings.freeWindowEnabled) {
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        if (fullscreen || !settings.freeWindowEnabled) {
            context.startActivity(intent)
            return
        }

        val mode = settings.resolvedFreeWindowMode()
        if (mode.usesSunshineBroadcast() && launchSunshineFreeform(context, intent)) {
            return
        }
        if (mode.usesNubiaFreeformIdentifier()) {
            launchNubiaFreeform(context, intent)
            return
        }

        if (mode == FreeWindowMode.ORIGINOS) {
            if (launchOriginOsShareProxy(context, intent)) {
                return
            }
        }

        val bundle = launchOptionsBundle(context, settings) ?: Bundle()
        runCatching {
            context.startActivity(intent, bundle)
        }.onFailure { error ->
            android.util.Log.e("FreeWindowLauncher", "startActivity failed", error)
        }
    }

    /**
     * 以小窗启动某个应用（照搬 SideGesture「应用小窗(7.0+)」的做法）：
     * 解析该包的 launcher Activity → `Intent(ACTION_MAIN + CATEGORY_LAUNCHER)` → 一次
     * `startActivity(intent, ActivityOptions)`。
     *
     * 与 [launch] 的差别只有 flags：这里只带 NEW_TASK，不叠加
     * REORDER_TO_FRONT / SINGLE_TOP，也不叠加 MULTIPLE_TASK —— 复用还是新建任务交给系统；
     * 不校验、不重试、不搬移已有任务。
     */
    fun launchPackageInFreeWindow(
        context: Context,
        packageName: String,
        settings: AppSettings,
    ): Boolean {
        if (packageName.isBlank()) return false
        val component = resolveLauncherComponent(context, packageName) ?: return false
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            this.component = component
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val mode = settings.resolvedFreeWindowMode()
        if (mode.usesSunshineBroadcast() && launchSunshineFreeform(context, intent)) {
            return true
        }
        if (mode.usesNubiaFreeformIdentifier()) {
            launchNubiaFreeform(context, intent)
            return true
        }
        if (mode == FreeWindowMode.ORIGINOS && launchOriginOsShareProxy(context, intent)) {
            return true
        }

        val bundle = launchOptionsBundle(context, settings) ?: return false
        return runCatching {
            context.startActivity(intent, bundle)
        }.onFailure { error ->
            android.util.Log.e("FreeWindowLauncher", "launchPackageInFreeWindow($packageName) failed", error)
        }.isSuccess
    }

    /** ACTION_MAIN + CATEGORY_LAUNCHER 查询该包的 launcher Activity（对齐 SideGesture）。 */
    private fun resolveLauncherComponent(context: Context, packageName: String): ComponentName? {
        val query = Intent(Intent.ACTION_MAIN).apply {
            setPackage(packageName)
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val className = runCatching {
            context.packageManager
                .queryIntentActivitiesCompat(query, PackageManager.MATCH_ALL)
                .firstOrNull()
                ?.activityInfo
                ?.name
        }.getOrNull()
        return className
            ?.takeIf { it.isNotBlank() }
            ?.let { ComponentName.createRelative(packageName, it) }
    }

    private fun launchOriginOsShareProxy(context: Context, targetIntent: Intent): Boolean {
        return runCatching {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                component = ComponentName(context, FreeWindowShareProxyActivity::class.java)
                type = "text/plain"
                putExtra(Intent.EXTRA_INTENT, Intent(targetIntent))
                val pkgName = targetIntent.`package` ?: targetIntent.component?.packageName.orEmpty()
                putExtra(Intent.EXTRA_TEXT, pkgName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(shareIntent, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        }.getOrElse { e ->
            android.util.Log.e("FreeWindowLauncher", "OriginOS share proxy launch failed", e)
            false
        }
    }

    private fun launchNubiaFreeform(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            intent.identifier = NUBIA_FREEFORM_INTENT_IDENTIFIER
        }
        runCatching {
            context.startActivity(intent, Bundle())
        }.onFailure { error ->
            android.util.Log.e("FreeWindowLauncher", "nubia freeform startActivity failed", error)
        }
    }

    /**
     * 米窗（Sunshine）开小窗：向米窗发两条广播，请它代为把目标应用拉进小窗。
     *
     * 与「一次 ActivityOptions 启动」的差别：
     * - 米窗未安装（或广播发不出去）时返回 false，调用方**回落**到自由窗口的兜底模式值，
     *   而不是"看起来成功、实际什么都没发生"；
     * - 广播即发即忘，米窗装上之后这一次到底有没有开出小窗，这里无法校验。
     */
    private fun launchSunshineFreeform(context: Context, targetIntent: Intent): Boolean {
        val packageName = targetPackageOf(targetIntent)
        if (packageName.isNullOrBlank()) return false
        if (!isPackageInstalled(context, SUNSHINE_PACKAGE)) return false
        val activityName = targetIntent.component?.className
        return runCatching {
            context.sendBroadcast(
                Intent(ACTION_SUNSHINE_START_FREEFORM).apply {
                    setPackage(SUNSHINE_PACKAGE)
                    putExtra(EXTRA_SUNSHINE_PACKAGE_NAME, packageName)
                    activityName?.takeIf { it.isNotBlank() }
                        ?.let { putExtra(EXTRA_SUNSHINE_ACTIVITY_NAME, it) }
                },
            )
            context.sendBroadcast(
                Intent(ACTION_SUNSHINE_START_BY_MI_FREEFORM).apply {
                    setPackage(SUNSHINE_PACKAGE)
                    putExtra(EXTRA_SUNSHINE_PACKAGE_NAME, packageName)
                    activityName?.takeIf { it.isNotBlank() }
                        ?.let { putExtra(EXTRA_SUNSHINE_ACTIVITY_NAME, it) }
                },
            )
            true
        }.getOrElse { error ->
            android.util.Log.e(TAG, "sunshine freeform broadcast failed pkg=$packageName", error)
            false
        }
    }

    /**
     * 目标包名的两个来源：显式 `setPackage`，或组件所属包名。
     * （`Intent.setComponent` 不会回填 `package`，只读 `intent.package` 会拿到 null。）
     */
    private fun targetPackageOf(intent: Intent): String? =
        intent.`package` ?: intent.component?.packageName

    private fun isPackageInstalled(context: Context, packageName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }
    }.isSuccess

    fun launchOptionsBundle(context: Context, settings: AppSettings): Bundle? {
        if (!settings.freeWindowEnabled) return null
        val options = ActivityOptions.makeBasic()
        val mode = settings.resolvedFreeWindowMode()
        applyWindowingMode(options, mode.windowingMode)
        if (mode == FreeWindowMode.MAGICOS) {
            applyStackId(options, HUAWEI_FREEFORM_STACK_ID)
        }
        options.setLaunchBounds(launchBounds(context, settings))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val backgroundStartMode = when {
                Build.VERSION.SDK_INT >= 36 -> ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                else -> @Suppress("DEPRECATION") ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
            options.pendingIntentBackgroundActivityStartMode = backgroundStartMode
        }
        val bundle = options.toBundle() ?: Bundle()
        if (bundle.getInt(KEY_WINDOWING_MODE, -1) == -1) {
            bundle.putInt(KEY_WINDOWING_MODE, mode.windowingMode)
        }
        return bundle
    }

    /**
     * 启动用的 launch bounds，坐标语义对齐 LinkGo-X 的 `WindowConfig.calculateBounds`：
     * 用户配置的百分比 = **真机上看得见的尺寸**，补偿系数只作用在发给系统的逻辑矩形上
     * （小米的 freeform 会按 `original_scale` 把逻辑尺寸缩到约 0.7 再显示，见
     * `MiuiMultiWindowUtils` 的 freeform args 档位表），所以逻辑尺寸 = 可见尺寸 / 补偿。
     *
     * 与 LinkGo-X 的唯一差别在这里：LinkGo-X 允许逻辑矩形溢出屏幕、也不夹 left/top，
     * 结果小米的 fit-in-display（AOSP `LaunchParamsUtil.adjustBoundsToFitInDisplayArea`）
     * 会把窗口整体推回屏内 —— 一旦溢出就贴左，位置配置等于失效。本实现先把**可见**
     * 尺寸夹进屏幕，再反推逻辑尺寸，位置百分比因此始终生效。
     *
     * 另外三条就地生效的规则：
     * - **屏幕尺寸与朝向**取自 WindowManager（见 [resolveDisplaySize]），不读会被旋转落下的
     *   `resources.configuration`；横屏算错宽高会让逻辑矩形整段出屏、被系统无视。
     * - **底部安全边距**：可用高度先扣掉 `max(状态栏高度, 20mm)`，再把可见高与上边距夹进去，
     *   避免小窗压住手势条与输入法起始区；配置本来就放得下时结果不变。
     * - **可达上限**：逻辑矩形不能溢出屏幕，所以可见尺寸上限 = 屏幕 × 补偿系数（小米竖屏 70%、
     *   横屏 55.5%）。设置页与编辑器用 [deliverableLimitFraction] 把这个上限提前告诉用户，
     *   而不是等到启动时静默夹掉。
     * - **跟随触发位置**：开启后水平贴到触发那一侧、竖直以触发高度居中；拿不到触发信息
     *   （或已过期）时原样使用配置值。见 [FreeWindowTriggerAnchor]。
     */
    fun launchBounds(context: Context, settings: AppSettings): Rect {
        val display = resolveDisplaySize(context)
        val displayWidth = display.widthPx
        val displayHeight = display.heightPx
        val isLandscape = display.isLandscape
        val layout = settings.resolvedFreeWindowLayout(isLandscape)

        // 底部安全区：状态栏高度与 20mm 取大者；可用高度 = 屏幕高度 - 安全区。
        // mm→px 只用到密度，所以这里的 DisplayMetrics 与朝向无关。
        val safeBottomPx = maxOf(
            statusBarHeightPx(context),
            millimetersToPixels(context.resources.displayMetrics, BOTTOM_SAFE_MARGIN_MM),
        ).coerceIn(0, (displayHeight - 1).coerceAtLeast(0))
        val availableHeight = (displayHeight - safeBottomPx).coerceAtLeast(1)

        // 可见尺寸上限：逻辑尺寸 = 可见 / 补偿，而逻辑矩形不能溢屏 → 可见 ≤ 屏幕 × 补偿。
        val scale = resolveScaleCompensation(settings, isLandscape)
        val deliverableWidth = (displayWidth * scale).roundToInt().coerceIn(1, displayWidth)
        val deliverableHeight = (displayHeight * scale).roundToInt().coerceIn(1, displayHeight)

        // 用户想要看到的可见尺寸与位置（位置是左上角，左/上留白直接用配置值）。
        val requestedWidth = (displayWidth * layout.widthFraction).toInt()
        val requestedHeight = (displayHeight * layout.heightFraction).toInt()
        val heightLimit = minOf(deliverableHeight, availableHeight)
        val visualWidth = requestedWidth.coerceIn(1, deliverableWidth)
        val visualHeight = requestedHeight.coerceIn(1, heightLimit)
        var visualLeft = (displayWidth * layout.leftFraction).toInt()
            .coerceIn(0, (displayWidth - visualWidth).coerceAtLeast(0))
        var visualTop = (displayHeight * layout.topFraction).toInt()
            .coerceIn(0, (availableHeight - visualHeight).coerceAtLeast(0))

        if (settings.freeWindowFollowTriggerSide) {
            FreeWindowTriggerAnchor.peek()?.let { anchor ->
                when (anchor.side) {
                    // 水平：贴到触发那一侧。贴边距取「配置的左边距」与「其对称右边距」中较小者，
                    // 这样默认的居中配置跟随之后仍然左右对称。
                    PanelSide.LEFT, PanelSide.RIGHT -> {
                        val configuredLeft = (displayWidth * layout.leftFraction).toInt()
                            .coerceIn(0, displayWidth)
                        val configuredRight =
                            (displayWidth - visualWidth - configuredLeft).coerceAtLeast(0)
                        val edgeMargin = minOf(configuredLeft, configuredRight)
                        visualLeft = if (anchor.side == PanelSide.LEFT) {
                            edgeMargin
                        } else {
                            displayWidth - visualWidth - edgeMargin
                        }.coerceIn(0, (displayWidth - visualWidth).coerceAtLeast(0))
                    }
                    // TOP / BOTTOM 触发没有「左右侧」可言，保持配置里的水平位置。
                    else -> Unit
                }
                // 竖直：以触发高度为中心。
                if (anchor.rawY > 0f) {
                    visualTop = (anchor.rawY - visualHeight / 2f).toInt()
                        .coerceIn(0, (availableHeight - visualHeight).coerceAtLeast(0))
                }
            }
        }

        // 逻辑尺寸 = 可见尺寸 / 补偿；再夹一次屏幕，保证逻辑矩形也不会溢屏。
        val logicalWidth = (visualWidth / scale).roundToInt().coerceIn(1, displayWidth)
        val logicalHeight = (visualHeight / scale).roundToInt().coerceIn(1, displayHeight)
        val bounds = Rect(visualLeft, visualTop, visualLeft + logicalWidth, visualTop + logicalHeight)

        // 一条可诊断日志：用户报"尺寸不生效"时，靠它就能分辨是"被我们夹了"还是"系统没照做"。
        android.util.Log.d(
            TAG,
            "launchBounds 屏幕=${displayWidth}x${displayHeight} ${if (isLandscape) "横屏" else "竖屏"} " +
                "请求=${requestedWidth}x${requestedHeight} scale=$scale " +
                "上限=${deliverableWidth}x$heightLimit 被夹=${requestedWidth > deliverableWidth}," +
                "${requestedHeight > heightLimit} 安全底边距=${safeBottomPx}px " +
                "跟随触发=${settings.freeWindowFollowTriggerSide}",
        )
        android.util.Log.d(TAG, "launchBounds → $bounds")
        return bounds
    }

    /** 状态栏高度；拿不到时按 0 处理，安全边距退回 20mm。 */
    private fun statusBarHeightPx(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id <= 0) return 0
        return runCatching { context.resources.getDimensionPixelSize(id) }.getOrDefault(0)
    }

    private fun millimetersToPixels(metrics: DisplayMetrics, millimeters: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, millimeters, metrics).toInt()

    /**
     * 真实屏幕尺寸与当前朝向。
     *
     * **刻意不读** `context.resources.displayMetrics` / `resources.configuration`：小窗的调用方既有
     * overlay 的 WindowContext、也有 applicationContext（通知链路），后者的 configuration 在
     * 「没有可见 Activity」时可能不随旋转更新 —— 横屏若仍按竖屏宽高算，逻辑矩形必然出屏、
     * 被系统整段无视（真机反馈：横屏尺寸完全不生效）。口径与 LinkGo-X / GestureEVO 一致：
     * 物理边长取 `maximumWindowMetrics`，朝向取显示 rotation（见 [OverlayScreenMetrics]）。
     */
    private data class DisplaySize(val widthPx: Int, val heightPx: Int, val isLandscape: Boolean)

    private fun resolveDisplaySize(context: Context): DisplaySize {
        val fallback = context.resources.displayMetrics
        val physical = context.getSystemService(WindowManager::class.java)
            ?.let { runCatching { it.maximumWindowMetrics.bounds }.getOrNull() }
        val rawWidth = physical?.width()?.takeIf { it > 0 } ?: fallback.widthPixels.coerceAtLeast(1)
        val rawHeight = physical?.height()?.takeIf { it > 0 } ?: fallback.heightPixels.coerceAtLeast(1)
        val isLandscape = OverlayScreenMetrics.snapshot(context).isLandscape
        val minD = minOf(rawWidth, rawHeight)
        val maxD = maxOf(rawWidth, rawHeight)
        return DisplaySize(
            widthPx = if (isLandscape) maxD else minD,
            heightPx = if (isLandscape) minD else maxD,
            isLandscape = isLandscape,
        )
    }

    /**
     * 当前模式 / 朝向 / 机型下，这套算法能交付的最大**可见**比例（≤1）。
     *
     * 与 [launchBounds] 里的"上限"同源：逻辑矩形不能溢出屏幕，所以可见上限 = 屏幕 × 补偿系数。
     * 设置页与尺寸编辑器用它把"我们做不到的比例"提前告诉用户，而不是等启动时静默夹掉。
     */
    fun deliverableLimitFraction(
        mode: FreeWindowMode,
        isLandscape: Boolean,
        isXiaomi: Boolean = isXiaomiDevice(),
    ): Float = resolveScaleCompensation(mode, isLandscape, isXiaomi)

    private fun isXiaomiDevice(): Boolean =
        Build.MANUFACTURER.contains("xiaomi", ignoreCase = true) ||
            Build.BRAND.contains("xiaomi", ignoreCase = true) ||
            Build.BRAND.contains("redmi", ignoreCase = true)

    private fun resolveScaleCompensation(settings: AppSettings, isLandscape: Boolean): Float =
        resolveScaleCompensation(settings.resolvedFreeWindowMode(), isLandscape, isXiaomiDevice())

    private fun resolveScaleCompensation(
        mode: FreeWindowMode,
        isLandscape: Boolean,
        isXiaomi: Boolean,
    ): Float = if (mode == FreeWindowMode.STANDARD && isXiaomi) {
        if (isLandscape) MIUI_LANDSCAPE_SCALE else MIUI_PORTRAIT_SCALE
    } else {
        1f
    }

    private fun applyWindowingMode(options: ActivityOptions, mode: Int) {
        try {
            val method = ActivityOptions::class.java.getMethod(
                "setLaunchWindowingMode",
                Int::class.javaPrimitiveType,
            )
            method.invoke(options, mode)
        } catch (_: Exception) {
            // Hidden API unavailable; bundle fallback applied in launch().
        }
    }

    private fun applyStackId(options: ActivityOptions, stackId: Int) {
        try {
            val method = ActivityOptions::class.java.getMethod(
                "setLaunchStackId",
                Int::class.javaPrimitiveType,
            )
            method.invoke(options, stackId)
        } catch (_: Exception) {
            // Ignored on platforms without setLaunchStackId
        }
    }
}
