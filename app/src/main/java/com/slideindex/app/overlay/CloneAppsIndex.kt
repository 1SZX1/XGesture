package com.slideindex.app.overlay

import com.slideindex.app.data.AppInfo
import com.slideindex.app.util.PinyinHelper
import com.slideindex.app.util.TaskManagerUtil

/**
 * 「应用索引里也要能看到分身」的枚举器。
 *
 * 分身（Flyme 平行空间 user 999 / 工作资料）与主实例**同包名同图标**，但不在当前用户的
 * launcher 查询结果里，所以索引默认永远看不到它们。这里通过特权 shell 的
 * `pm list packages --user N` 找出"确实装在其他 user 下的包"，再给每个包补一条
 * `cloneUserId = N` 的条目：图标沿用主实例，名字加「 · 分身」，启动走 `am start --user N`。
 *
 * 结果缓存 30s：枚举要走 shell，不能每次刷列表都跑。
 */
internal object CloneAppsIndex {
    private const val CACHE_MS = 30_000L

    @Volatile
    private var cachedPackages: Map<String, Int> = emptyMap()

    @Volatile
    private var cachedAt = 0L

    /** 包名 → 该包所在的其他 user id（同一包在多个 user 下时取第一个）。 */
    fun clonePackages(): Map<String, Int> {
        val now = System.currentTimeMillis()
        if (now - cachedAt < CACHE_MS) return cachedPackages
        val result = runCatching { queryClonePackages() }.getOrNull()
        if (result != null) {
            cachedPackages = result
            cachedAt = now
        }
        // 查询失败（Binder 抖动 / 服务未就绪）时保留上一次结果，避免分身条目忽隐忽现。
        return cachedPackages
    }

    /**
     * 给索引列表补上分身条目。只补"主实例也在启动器里"的包，保证图标/名字拿得到。
     *
     * [cloneSuffix] 是本地化的"分身"后缀（调用方传 `getString(task_switcher_clone_label)` 之类）。
     */
    fun enrich(apps: List<AppInfo>, cloneSuffix: (String) -> String): List<AppInfo> {
        if (apps.isEmpty()) return apps
        val clones = clonePackages()
        if (clones.isEmpty()) return apps
        val extras = apps.mapNotNull { app ->
            val userId = clones[app.packageName] ?: return@mapNotNull null
            val label = cloneSuffix(app.label)
            app.copy(
                label = label,
                letter = PinyinHelper.firstLetter(label),
                pinyinKey = PinyinHelper.sortKey(label),
                cloneUserId = userId,
                // 分身是另一个 user 下的独立实例：主实例的冻结/暂停状态不能带过来，
                // 否则主应用被暂停时，桌面明明没被暂停的分身也会显示暂停角标。
                isFrozen = false,
                isPaused = false,
            )
        }
        if (extras.isEmpty()) return apps
        return (apps + extras).sortedBy { it.pinyinKey }
    }

    /** 查询失败时抛异常，让调用方保留上一次的缓存（避免特权服务抖动导致分身时有时无）。 */
    private fun queryClonePackages(): Map<String, Int> {
        val usersResult = TaskManagerUtil.runShellCommandOutput("pm", "list", "users")
        check(usersResult.exitCode == 0 && usersResult.output.contains("UserInfo{")) {
            "pm list users failed: ${usersResult.exitCode}"
        }
        val users = parseOtherUserIds(usersResult.output)
        if (users.isEmpty()) return emptyMap()
        val packages = mutableMapOf<String, Int>()
        for (userId in users) {
            val result = TaskManagerUtil.runShellCommandOutput(
                "pm", "list", "packages", "--user", userId.toString(),
            )
            check(result.exitCode == 0) { "pm list packages --user $userId failed: ${result.exitCode}" }
            result.output.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("package:") }
                .forEach { line ->
                    packages.putIfAbsent(line.removePrefix("package:"), userId)
                }
        }
        return packages
    }

    /** 当前用户之外的 user（工作资料 / 平行空间）。 */
    private fun parseOtherUserIds(output: String): List<Int> {
        val current = runCatching { android.os.Process.myUserHandle().hashCode() }.getOrDefault(0)
        return Regex("UserInfo\\{(\\d+):")
            .findAll(output)
            .map { it.groupValues[1].toInt() }
            .filter { it != current }
            .distinct()
            .toList()
    }
}
