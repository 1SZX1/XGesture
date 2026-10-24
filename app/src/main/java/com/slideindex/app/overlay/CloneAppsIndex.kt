package com.slideindex.app.overlay

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import com.slideindex.app.R
import com.slideindex.app.data.AppInfo
import com.slideindex.app.util.PinyinHelper
import com.slideindex.app.util.TaskManagerUtil

/** 分身空间是哪一类：真·应用分身 / 工作资料 / 认不出来的其他空间。 */
internal enum class CloneSpaceKind {
    CLONE_PROFILE,
    MANAGED_PROFILE,
    UNKNOWN,
}

/** 一条「这个包在这个 user 下还有一份」的记录。 */
internal data class CloneAppRef(
    val packageName: String,
    val userId: Int,
    val kind: CloneSpaceKind,
)

/**
 * 分身空间枚举器：找出当前用户之外、确实装了应用的 user，并给索引补 `cloneUserId` 条目。
 *
 * 数据来源分两层，按稳定性排序：
 * 1. **公开 API**（无特权）：`UserManager.getUserProfiles()` 拿 profile（工作资料 / clone profile），
 *    `LauncherApps.getActivityList(null, user)` 拿该 user 下的启动器可见包。
 * 2. **特权 shell**（可选）：`pm list users` 补上不是 profile 的空间（例如 MIUI 的 999），
 *    `pm list packages --user N` 补上公开 API 看不到的包（冻结/隐藏）。
 *
 * 结果缓存，事件（LauncherApps 回调）到来时 [invalidate]；失败保留上一次结果并做短退避，
 * 避免分身条目忽隐忽现或每次刷列表都重打一遍 shell。
 */
internal object CloneAppsIndex {
    private const val TAG = "CloneAppsIndex"
    private const val CACHE_MS = 30_000L
    private const val FAILURE_BACKOFF_MS = 5_000L
    private const val PER_USER_RANGE = 100_000

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedRefs: List<CloneAppRef> = emptyList()

    @Volatile
    private var cachedAtMs = 0L

    @Volatile
    private var lastQueryFailed = false

    @Volatile
    private var dirty = true

    private val queryLock = Any()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        dirty = true
    }

    /** 包增删改 / 用户变化后调用，下一次枚举重新查询。 */
    fun invalidate() {
        dirty = true
    }

    /** 包名 → 该包所在的其他 user id（同一包在多个 user 下时取枚举到的第一个）。 */
    fun clonePackages(): Map<String, Int> =
        cloneRefs().associate { it.packageName to it.userId }

    /** 当前用户之外的所有「分身空间」。查不到时返回空（不抛异常）。 */
    fun cloneRefs(): List<CloneAppRef> {
        val now = SystemClock.elapsedRealtime()
        if (!dirty && now - cachedAtMs < currentTtlMs()) return cachedRefs
        synchronized(queryLock) {
            val lockedNow = SystemClock.elapsedRealtime()
            if (!dirty && lockedNow - cachedAtMs < currentTtlMs()) return cachedRefs
            val result = runCatching { queryRefs() }
            result
                .onSuccess { refs ->
                    lastQueryFailed = false
                    if (refs.size != cachedRefs.size || refs != cachedRefs) {
                        Log.i(TAG, "clone spaces refreshed: refs=${refs.size}")
                    }
                    cachedRefs = refs
                }
                .onFailure { error ->
                    // 保留旧结果：Binder 抖动不应该让分身条目从索引里消失。
                    lastQueryFailed = true
                    Log.w(TAG, "clone space query failed; keep ${cachedRefs.size} cached refs", error)
                }
            cachedAtMs = lockedNow
            dirty = false
            return cachedRefs
        }
    }

    private fun currentTtlMs(): Long = if (lastQueryFailed) FAILURE_BACKOFF_MS else CACHE_MS

    /**
     * 给索引列表补上分身条目。只补"主实例也在启动器里"的包，保证图标/名字拿得到。
     *
     * [labelFor] 决定条目显示名（调用方按空间类型选资源字符串）。
     */
    fun enrich(
        apps: List<AppInfo>,
        labelFor: ((String, CloneSpaceKind) -> String)? = null,
    ): List<AppInfo> {
        if (apps.isEmpty()) return apps
        val refs = cloneRefs()
        if (refs.isEmpty()) return apps
        val refsByPackage = refs.groupBy { it.packageName }
        val renderLabel = labelFor ?: { label, kind -> label(label, kind) }
        val extras = ArrayList<AppInfo>()
        apps.forEach { app ->
            if (app.isClone) return@forEach
            val packageRefs = refsByPackage[app.packageName] ?: return@forEach
            packageRefs.forEach { ref ->
                val label = renderLabel(app.label, ref.kind)
                val (frozen, paused) = cloneState(ref.packageName, ref.userId)
                Log.d(
                    TAG,
                    "clone state: ${ref.packageName}@${ref.userId} frozen=$frozen paused=$paused",
                )
                extras += app.copy(
                    label = label,
                    letter = PinyinHelper.firstLetter(label),
                    pinyinKey = PinyinHelper.sortKey(label),
                    cloneUserId = ref.userId,
                    // 分身是另一个 user 下的独立实例：主实例的冻结/暂停状态不能带过来。
                    isFrozen = frozen,
                    isPaused = paused,
                )
            }
        }
        if (extras.isEmpty()) return apps
        return (apps + extras).sortedBy { it.pinyinKey }
    }

    /** 搜索面板用：把分身条目并进可搜索列表。 */
    fun searchEntries(base: List<AppInfo>): List<AppInfo> = enrich(base)

    /**
     * 任务切换器用：按 user 的已知类型取显示名。
     *
     * 只读缓存、不触发查询（任务行是热路径）；缓存里没有这个 user 时按「分身」处理。
     */
    fun labelForUser(context: Context, userId: Int, label: String): String {
        val kind = cachedRefs.firstOrNull { it.userId == userId }?.kind ?: CloneSpaceKind.UNKNOWN
        return context.getString(labelRes(kind), label)
    }

    /** 按空间类型取显示名模板；工作资料有自己的文案，其余沿用「· 分身」。 */
    fun labelRes(kind: CloneSpaceKind): Int = when (kind) {
        CloneSpaceKind.MANAGED_PROFILE -> R.string.clone_label_managed_profile
        CloneSpaceKind.CLONE_PROFILE, CloneSpaceKind.UNKNOWN -> R.string.task_switcher_clone_label
    }

    private fun label(label: String, kind: CloneSpaceKind): String {
        val context = appContext ?: return label
        return context.getString(labelRes(kind), label)
    }

    /** 分身实例自己的启用/挂起状态（不是主实例的）。查不到时按正常处理。 */
    private fun cloneState(packageName: String, userId: Int): Pair<Boolean, Boolean> {
        val launcherApps = appContext?.getSystemService(LauncherApps::class.java) ?: return false to false
        val user = userHandle(userId) ?: return false to false
        val info = runCatching {
            launcherApps.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS, user)
        }.getOrNull() ?: return false to false
        return (!info.enabled) to ((info.flags and ApplicationInfo.FLAG_SUSPENDED) != 0)
    }

    private fun userHandle(userId: Int): UserHandle? = runCatching {
        if (userId <= 0) Process.myUserHandle() else UserHandle.getUserHandleForUid(userId * PER_USER_RANGE)
    }.getOrNull()

    /** 查询失败时抛异常，让调用方保留上一次的缓存。 */
    private fun queryRefs(): List<CloneAppRef> {
        val spaces = collectSpaces()
        if (spaces.isEmpty()) {
            Log.i(TAG, "no clone spaces (profiles/shell both unavailable)")
            return emptyList()
        }
        Log.i(TAG, "spaces=$spaces")
        val refs = ArrayList<CloneAppRef>()
        spaces.forEach { (userId, kind) ->
            val launcherPackages = packagesViaLauncherApps(userId)
            val shellPackages = packagesViaShell(userId)
            val packages = LinkedHashSet<String>().apply {
                addAll(launcherPackages)
                addAll(shellPackages)
            }
            Log.i(
                TAG,
                "user=$userId kind=$kind launcher=${launcherPackages.size} shell=${shellPackages.size} total=${packages.size}",
            )
            packages.forEach { packageName ->
                refs += CloneAppRef(packageName, userId, kind)
            }
        }
        return refs
    }

    /**
     * 当前用户之外的空间：公开 profile 列表 + shell 全量打底，再用 `dumpsys user` 的类型分类。
     *
     * 之所以两层都要：clone profile（Flyme 999 / Android 12+）能从公开 API 拿到；
     * 而 MIUI 那种把分身做成普通 secondary user 的，只能靠 `pm list users`。
     * 分类依赖 `dumpsys user`（没有公开 API 能查"某个 user 的 type"），拿不到就按未知处理。
     */
    private fun collectSpaces(): Map<Int, CloneSpaceKind> {
        val currentUserId = runCatching { Process.myUserHandle().hashCode() }.getOrDefault(0)
        val spaces = LinkedHashMap<Int, CloneSpaceKind>()

        runCatching {
            val result = TaskManagerUtil.runShellCommandOutput("pm", "list", "users")
            Log.i(TAG, "pm list users exit=${result.exitCode}")
            if (result.exitCode == 0) {
                CloneSpaceParsing.parseUserIds(result.output, currentUserId)
                    .forEach { spaces[it] = CloneSpaceKind.UNKNOWN }
            }
        }.onFailure { Log.w(TAG, "pm list users failed", it) }

        val userManager = appContext?.getSystemService(UserManager::class.java)
        runCatching {
            userManager?.userProfiles.orEmpty().forEach { handle ->
                val id = handle.hashCode()
                if (id != currentUserId) spaces.putIfAbsent(id, CloneSpaceKind.UNKNOWN)
            }
        }.onFailure { Log.w(TAG, "UserManager.getUserProfiles failed", it) }

        // 单用户设备：没有分身空间就不必再打一次 dumpsys。
        if (spaces.isEmpty()) return spaces

        val userTypes = queryUserTypes()
        if (userTypes.isNotEmpty()) {
            spaces.keys.toList().forEach { id -> spaces[id] = classify(id, userTypes) }
        }
        return spaces
    }

    /** `dumpsys user` 里的 `UserInfo{...}` + `Type: ...`，没特权时为空。 */
    private fun queryUserTypes(): Map<Int, String> {
        val result = runCatching {
            TaskManagerUtil.runShellCommandOutput("dumpsys", "user")
        }.getOrNull() ?: return emptyMap()
        if (result.exitCode != 0) return emptyMap()
        return CloneSpaceParsing.parseUserTypes(result.output)
    }

    private fun classify(userId: Int, userTypes: Map<Int, String>): CloneSpaceKind {
        val type = userTypes[userId] ?: return CloneSpaceKind.UNKNOWN
        return when {
            type.contains("profile.CLONE", ignoreCase = true) -> CloneSpaceKind.CLONE_PROFILE
            type.contains("MANAGED", ignoreCase = true) -> CloneSpaceKind.MANAGED_PROFILE
            else -> CloneSpaceKind.UNKNOWN
        }
    }

    /** 公开 API 路径：该 user 下启动器可见的包。拿不到（quiet mode / 无权限）时为空。 */
    private fun packagesViaLauncherApps(userId: Int): Set<String> {
        val launcherApps = appContext?.getSystemService(LauncherApps::class.java) ?: return emptySet()
        val user = userHandle(userId) ?: return emptySet()
        return runCatching {
            launcherApps.getActivityList(null, user)
                .mapNotNull { it.applicationInfo?.packageName }
                .toCollection(LinkedHashSet())
        }.getOrElse { error ->
            Log.w(TAG, "LauncherApps enumeration failed for user $userId", error)
            emptySet()
        }
    }

    /** 特权路径：补公开 API 看不到的包（冻结/隐藏/非 profile 空间）。没特权时立即返回空。 */
    private fun packagesViaShell(userId: Int): Set<String> {
        val result = runCatching {
            TaskManagerUtil.runShellCommandOutput("pm", "list", "packages", "--user", userId.toString())
        }.getOrNull() ?: return emptySet()
        if (result.exitCode != 0) return emptySet()
        return CloneSpaceParsing.parsePackageNames(result.output)
    }
}
