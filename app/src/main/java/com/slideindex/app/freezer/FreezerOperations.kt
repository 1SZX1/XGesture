package com.slideindex.app.freezer

/**
 * Portions derived from EdgeX (https://github.com/oxohang/EdgeX)
 * Licensed under GPL-3.0. Modified for com.slideindex.app.
 */

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.slideindex.app.R
import com.slideindex.app.data.AppInfo
import com.slideindex.app.data.AppRepository
import com.slideindex.app.di.AppGraphEntryPoint
import com.slideindex.app.launcher.AppTarget
import com.slideindex.app.overlay.OverlayToastWindow
import com.slideindex.app.settings.AppSettings
import com.slideindex.app.settings.FreezerAppIntent
import com.slideindex.app.settings.SettingsRepository
import com.slideindex.app.util.CloneAppLauncher
import com.slideindex.app.util.TaskManagerUtil
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object FreezerOperations {
    fun hasShellAccess(): Boolean = TaskManagerUtil.hasPrivilegedAccess()

    /**
     * 冰箱提示统一出口：**优先用覆盖层窗口显示**，拿不到悬浮窗权限时由它自己回退系统 Toast。
     *
     * 为什么不用系统 Toast：冰箱面板是全屏浮窗（`TYPE_APPLICATION_OVERLAY` + `MATCH_PARENT`），
     * 面板打开时弹出的 Toast 会被面板自己盖在底下（用户反馈「提示看不见」）。覆盖层窗口是同类
     * 窗口、后 `addView`，稳定叠在面板之上。
     */
    private suspend fun showFreezerMessage(context: Context, message: String) {
        Log.i(TAG, "message: $message")
        withContext(Dispatchers.Main) {
            OverlayToastWindow.show(context, message)
        }
    }

    private suspend fun showFreezerMessage(context: Context, messageRes: Int) {
        showFreezerMessage(context, context.getString(messageRes))
    }

    /**
     * 记下「用户要这个成员处于哪种状态」。
     *
     * 系统只有 enabled / suspended 两个当前状态，没有「上次用的是冻结还是暂停」；
     * 而我们又必须知道它，才能在用户点开应用（`launchAndRestore` 会把状态清成启用）之后
     * 还能把它按原样收回去。列表外的包不记录，避免留下永远不会被用到的档位。
     */
    private suspend fun setFreezerIntent(context: Context, key: String, intent: FreezerAppIntent) {
        val repository = freezerSettingsRepository(context)
        if (repository == null) {
            Log.w(TAG, "intent not recorded (no settings repository): $key -> ${intent.storageValue}")
            return
        }
        if (key !in repository.readSnapshot().freezerAppPackages) {
            Log.i(TAG, "intent not recorded (not a freezer member): $key")
            return
        }
        Log.i(TAG, "intent recorded: $key -> ${intent.storageValue}")
        runCatching { repository.setFreezerAppIntent(key, intent) }
            .onFailure { Log.w(TAG, "intent write failed: $key", it) }
    }

    private fun freezerSettingsRepository(context: Context): SettingsRepository? = runCatching {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            AppGraphEntryPoint::class.java
        ).dependencies().settingsRepository
    }.getOrNull()

    /**
     * 冰箱成员的统一键：主实例 = 裸包名，分身 = `包名\u0002userId`。
     *
     * 存储（`freezerAppPackages` / `freezerAppIntents`）与 UI 的选中集都用它，主实例与分身
     * 同包名也不会互相覆盖。旧数据只有裸包名，解析出来就是 user 0。
     */
    fun targetOf(app: AppInfo): AppTarget = AppTarget(app.packageName, app.cloneUserId)

    fun keyOf(app: AppInfo): String = keyOf(targetOf(app))

    fun keyOf(target: AppTarget): String = AppTarget.encode(target.packageName, target.cloneUserId)

    /** 一次查询出三态（按目标 user）；Compose 组合期逐项调用，不要拆成多次包管理查询。 */
    fun stateOf(context: Context, app: AppInfo): FreezerAppState = stateOf(context, targetOf(app))

    fun stateOf(context: Context, target: AppTarget): FreezerAppState =
        FreezerPrivilegedOps.appState(context, target.packageName, target.cloneUserId)

    fun isFrozen(context: Context, app: AppInfo): Boolean = isFrozen(context, targetOf(app))

    fun isFrozen(context: Context, target: AppTarget): Boolean =
        FreezerPrivilegedOps.isAppDisabled(context, target.packageName, target.cloneUserId)

    fun isPaused(context: Context, app: AppInfo): Boolean = isPaused(context, targetOf(app))

    fun isPaused(context: Context, target: AppTarget): Boolean =
        FreezerPrivilegedOps.isAppSuspended(context, target.packageName, target.cloneUserId)

    /** 本应用自身、system、SystemUI、当前桌面等「动了就回不来」的包。 */
    fun isProtectedPackage(context: Context, packageName: String): Boolean =
        FreezerPrivilegedOps.isProtectedPackage(context, packageName)

    suspend fun setFrozen(context: Context, app: AppInfo, frozen: Boolean): Boolean =
        setFrozen(context, targetOf(app), frozen)

    suspend fun setFrozen(context: Context, target: AppTarget, frozen: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val packageName = target.packageName
            val userId = target.cloneUserId
            if (!TaskManagerUtil.hasPrivilegedAccess()) {
                showFreezerMessage(context, R.string.freezer_permission_required)
                return@withContext false
            }
            if (frozen && FreezerPrivilegedOps.isProtectedPackage(context, packageName)) {
                showFreezerMessage(context, R.string.freezer_protected_package)
                return@withContext false
            }
            if (frozen && isPaused(context, target)) {
                // 停用会让桌面图标消失，挂起态在它面前「看不见」：先取消暂停再停用，
                // 否则之后解冻出来仍是挂起态，用户会以为解冻失败。
                FreezerPrivilegedOps.setAppSuspended(
                    context = context,
                    packageName = packageName,
                    suspended = false,
                    dialogMessage = null,
                    userId = userId,
                )
            }
            val (success, detail) =
                FreezerPrivilegedOps.setAppDisabled(context, packageName, frozen, userId)
            if (success) {
                Log.i(TAG, "setFrozen(${keyOf(target)}, $frozen) -> ok")
                if (frozen) setFreezerIntent(context, keyOf(target), FreezerAppIntent.FROZEN)
                // 索引里的冻结项靠冰箱清单拼出来，冻结状态变了要主动让它重算。
                com.slideindex.app.overlay.OverlayRefreshBus.notifyFreezerChanged()
                return@withContext true
            }
            Log.w(TAG, "setFrozen(${keyOf(target)}, $frozen) -> failed: ${detail.take(160)}")
            val message = when (detail) {
                FreezerPrivilegedOps.NEED_ROOT_FOR_SYSTEM_DISABLE ->
                    context.getString(R.string.freezer_unfreeze_need_root)
                else -> {
                    val messageRes = if (frozen) {
                        R.string.freezer_freeze_failed
                    } else {
                        R.string.freezer_unfreeze_failed
                    }
                    detail.take(160).ifBlank { null }?.let {
                        context.getString(messageRes, it)
                    } ?: context.getString(R.string.freezer_permission_required)
                }
            }
            showFreezerMessage(context, message)
            false
        }

    /**
     * 暂停（应用挂起）：应用仍安装，桌面图标保留但灰化、点击弹系统对话框。
     * 与冻结的差别是「图标不消失」，代价是挂起只拦启动、不拦后台。
     *
     * [AppTarget.cloneUserId] 会一路传到 `setPackagesSuspendedAsUser` / `pm suspend --user`，
     * 分身因此与主实例一样能暂停，互不影响。
     */
    suspend fun setPaused(context: Context, app: AppInfo, paused: Boolean): Boolean =
        setPaused(context, targetOf(app), paused)

    suspend fun setPaused(context: Context, target: AppTarget, paused: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val packageName = target.packageName
            val userId = target.cloneUserId
            if (!TaskManagerUtil.hasPrivilegedAccess()) {
                showFreezerMessage(context, R.string.freezer_permission_required)
                return@withContext false
            }
            if (paused) {
                if (FreezerPrivilegedOps.isProtectedPackage(context, packageName)) {
                    showFreezerMessage(context, R.string.freezer_protected_package)
                    return@withContext false
                }
                if (isFrozen(context, target)) {
                    // 挂起本身不要求 enabled，但停用的应用图标已经消失，暂停没有意义：先解冻。
                    if (!setFrozen(context, target, frozen = false)) return@withContext false
                }
            }
            val dialogMessage = if (paused) {
                context.getString(
                    R.string.freezer_pause_dialog_message,
                    context.getString(R.string.app_name),
                )
            } else {
                null
            }
            val (success, detail) = FreezerPrivilegedOps.setAppSuspended(
                context = context,
                packageName = packageName,
                suspended = paused,
                dialogMessage = dialogMessage,
                userId = userId,
            )
            if (success) {
                Log.i(TAG, "setPaused(${keyOf(target)}, $paused) -> ok")
                // 取消暂停不改意图：用户要的还是「暂停」，只是这次为了使用而临时放出来。
                if (paused) setFreezerIntent(context, keyOf(target), FreezerAppIntent.PAUSE)
                // 暂停态在索引里也有角标，状态变了同样要重算。
                com.slideindex.app.overlay.OverlayRefreshBus.notifyFreezerChanged()
                if (paused && target.cloneUserId > 0) warnClonePauseNeedsRootOnce(context, keyOf(target))
                return@withContext true
            }
            Log.w(TAG, "setPaused(${keyOf(target)}, $paused) -> failed: ${detail.take(160)}")
            // PMS 的跨 user 挂起校验对 suspend / unsuspend 是同一套：发起包必须存在于目标 user，
            // 只有 root 豁免。所以 Shizuku 非 root 下「暂停」和「取消暂停」都过不去，各给一句人话。
            if (target.cloneUserId > 0 && detail.contains(CLONE_PAUSE_NEEDS_ROOT_HINT)) {
                showFreezerMessage(
                    context,
                    if (paused) {
                        R.string.freezer_pause_clone_need_root
                    } else {
                        R.string.freezer_unpause_clone_need_root
                    },
                )
                return@withContext false
            }
            val messageRes = if (paused) {
                R.string.freezer_pause_failed
            } else {
                R.string.freezer_unpause_failed
            }
            val message = detail.take(160).ifBlank { null }?.let {
                context.getString(messageRes, it)
            } ?: context.getString(R.string.freezer_permission_required)
            showFreezerMessage(context, message)
            false
        }

    /**
     * 点击冰箱里的应用：按当前状态恢复（解冻 / 取消暂停）后再启动。
     *
     * 恢复前先把「它原本是冻结还是暂停」补进意图表：状态一被清成启用，系统那边就再也看不出
     * 它原来是哪一种了。这样用户点开应用、用完再用「重冻应用」手势收回时，它会回到原来的档位。
     */
    suspend fun launchAndRestore(
        context: Context,
        appRepository: AppRepository,
        settings: AppSettings,
        app: AppInfo,
        fullscreen: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        val target = targetOf(app)
        when (stateOf(context, target)) {
            FreezerAppState.FROZEN -> {
                setFreezerIntent(context, keyOf(target), FreezerAppIntent.FROZEN)
                if (!setFrozen(context, target, frozen = false)) {
                    return@withContext false
                }
            }
            FreezerAppState.PAUSED -> {
                setFreezerIntent(context, keyOf(target), FreezerAppIntent.PAUSE)
                if (!setPaused(context, target, paused = false)) {
                    return@withContext false
                }
            }
            FreezerAppState.ACTIVE ->
                // 已经是启用的：没有原状态可记（首次加入列表的成员就落在这里，由批量动作
                // 按全局工作模式兜底），也不需要在启动前做任何恢复。
                Unit
        }
        // 分身是另一个 user 下的独立实例：`launchApp` 的 `getLaunchIntentForPackage` 只能启动
        // 当前 user，跨 user 统一交给 CloneAppLauncher（有特权走 shell，没有特权走透明跳板）。
        if (app.isClone) {
            return@withContext CloneAppLauncher.launch(context, app.packageName, app.cloneUserId)
        }
        withContext(Dispatchers.Main) {
            launchApp(context, app, settings, appRepository, fullscreen)
        }
    }

    private fun launchApp(
        context: Context,
        app: AppInfo,
        settings: AppSettings,
        appRepository: AppRepository,
        fullscreen: Boolean
    ): Boolean {
        val effectiveSettings = if (!fullscreen) {
            settings.copy(freeWindow = settings.freeWindow.copy(freeWindowEnabled = true))
        } else {
            settings
        }
        if (appRepository.launchApp(app, effectiveSettings, fullscreen = fullscreen)) return true
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(app.packageName)
        val flags = PackageManager.MATCH_DEFAULT_ONLY
        val resolveInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcherIntent, flags)
        }.firstOrNull() ?: return false
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).apply {
            setClassName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /**
     * 批量动作的统一入口：**按每个成员自己记录的档位收回**。冰箱面板底部按钮、「重冻应用」
     * 手势与后续的自动触发都走这里。
     *
     * 有记录的按记录来（上次是暂停的就还它暂停，不会变成冻结）；没有记录的（刚加入列表、
     * 或从别的工具导入的）按 [fallbackPause] 兜底 —— 取全局工作模式。
     *
     * 之所以不是「一律冻结」：用户点开一个应用时状态会被清成启用，只有记录还记得它原来
     * 该是冻结还是暂停。一律冻结会把用户手动设成暂停的成员一起变成图标消失。
     */
    suspend fun restoreIntents(
        context: Context,
        targets: Collection<AppTarget>,
        fallbackPause: Boolean,
        report: Boolean = true
    ): Int = withContext(Dispatchers.IO) {
        if (!hasShellAccess()) {
            Log.w(TAG, "restoreIntents: no privileged access, members=${targets.size}")
            if (report) showFreezerMessage(context, R.string.freezer_permission_required)
            return@withContext 0
        }
        val intents = freezerSettingsRepository(context)?.readSnapshot()?.freezerAppIntents.orEmpty()
        var frozen = 0
        var paused = 0
        for (target in targets) {
            val key = keyOf(target)
            val state = stateOf(context, target)
            val decision = FreezerIntentResolution.decide(
                state = state,
                intent = intents[key],
                fallbackPause = fallbackPause,
            )
            Log.d(
                TAG,
                "restoreIntents: $key state=$state intent=${intents[key]?.storageValue ?: "none"} " +
                    "fallbackPause=$fallbackPause -> $decision"
            )
            when (decision) {
                FreezerIntentResolution.Decision.Skip -> Unit
                FreezerIntentResolution.Decision.Freeze ->
                    if (setFrozen(context, target, frozen = true)) frozen++
                FreezerIntentResolution.Decision.Pause ->
                    if (setPaused(context, target, paused = true)) paused++
            }
        }
        val count = frozen + paused
        Log.i(
            TAG,
            "restoreIntents done: members=${targets.size} changed=$count frozen=$frozen paused=$paused"
        )
        if (report) {
            when {
                count > 0 -> showFreezerMessage(
                    context,
                    restoreSummary(context, frozen = frozen, paused = paused),
                )
                // 全都在目标态：给一句反馈，否则用户会以为手势没生效（曾经真的被这么报过）。
                targets.isNotEmpty() -> showFreezerMessage(context, R.string.freezer_restore_intents_noop)
            }
        }
        count
    }

    /**
     * 「按档位收回」的结果文案。
     *
     * 面板内用它渲染提示条（`report = false` 时不弹提示）——面板是全屏浮窗，系统 Toast 会被
     * 它自己盖住，所以在面板里必须由面板自己显示。
     */
    fun restoreSummary(context: Context, frozen: Int, paused: Int): String = when {
        paused == 0 -> context.resources.getQuantityString(
            R.plurals.freezer_refreeze_done, frozen, frozen
        )
        frozen == 0 -> context.resources.getQuantityString(
            R.plurals.freezer_pause_all_done, paused, paused
        )
        else -> context.getString(R.string.freezer_restore_intents_done, frozen, paused)
    }

    suspend fun pauseAll(
        context: Context,
        targets: Collection<AppTarget>,
        report: Boolean = true
    ): Int = withContext(Dispatchers.IO) {
        if (!hasShellAccess()) {
            if (report) {
                showFreezerMessage(context, R.string.freezer_permission_required)
            }
            return@withContext 0
        }
        var count = 0
        for (target in targets) {
            if (stateOf(context, target).isActive && setPaused(context, target, paused = true)) count++
        }
        if (report) {
            showFreezerMessage(
                context,
                context.resources.getQuantityString(R.plurals.freezer_pause_all_done, count, count),
            )
        }
        count
    }

    suspend fun unpauseAll(
        context: Context,
        targets: Collection<AppTarget>,
        report: Boolean = true
    ): Int = withContext(Dispatchers.IO) {
        if (!hasShellAccess()) {
            if (report) {
                showFreezerMessage(context, R.string.freezer_permission_required)
            }
            return@withContext 0
        }
        var count = 0
        for (target in targets) {
            // 只取消挂起，**不动意图表**：「取消暂停」是让它们现在能跑，用户给它们记的档位
            // 仍然是「暂停」，下次按档位收回时还要把暂停还回去。删记录会让它们退回兜底档位
            // （工作模式为冻结时就是图标消失），那正是用户报过的「说好暂停的又被冻结了」。
            if (stateOf(context, target).isPaused && setPaused(context, target, paused = false)) count++
        }
        if (report) {
            showFreezerMessage(
                context,
                context.resources.getQuantityString(R.plurals.freezer_unpause_all_done, count, count),
            )
        }
        count
    }

    suspend fun unfreezeAll(
        context: Context,
        targets: Collection<AppTarget>,
        report: Boolean = true
    ): Int = withContext(Dispatchers.IO) {
        if (!hasShellAccess()) {
            if (report) {
                showFreezerMessage(context, R.string.freezer_permission_required)
            }
            return@withContext 0
        }
        var count = 0
        for (target in targets) {
            if (stateOf(context, target).isFrozen && setFrozen(context, target, frozen = false)) count++
        }
        if (report) {
            showFreezerMessage(
                context,
                context.resources.getQuantityString(R.plurals.freezer_unfreeze_all_done, count, count),
            )
        }
        count
    }

    private const val TAG = "FreezerOperations"

    /** PMS 拒绝跨 user 挂起时的固定报错片段（见 `enforceCanSetPackagesSuspendedAsUser`）。 */
    private const val CLONE_PAUSE_NEEDS_ROOT_HINT = "does not belong to calling uid"

    /**
     * 已经提醒过「分身暂停只有 Root 能恢复」的目标。
     *
     * 分身一旦用 Root 暂停，Shizuku 非 root 既不能再次暂停也不能取消暂停；切模式之前
     * 用户必须知道这件事，否则会以为卡死。每个目标只提醒一次，避免每次暂停都弹。
     */
    private val clonePauseRootWarned: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    private suspend fun warnClonePauseNeedsRootOnce(context: Context, key: String) {
        if (!clonePauseRootWarned.add(key)) return
        showFreezerMessage(context, R.string.freezer_clone_pause_needs_root_to_resume)
    }
}
