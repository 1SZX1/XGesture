package com.slideindex.app.freezer

import android.content.Context
import com.slideindex.app.data.AppInfo
import com.slideindex.app.data.AppRepository
import com.slideindex.app.launcher.AppTarget
import com.slideindex.app.overlay.CloneAppsIndex
import com.slideindex.app.util.PinyinHelper

/**
 * 冰箱成员的解析：**按目标**（`(包名, userId)`）而不是按包名。
 *
 * 同一个包在冰箱里可以有两行：主实例（user 0）与分身（user 999）。两者是独立条目，
 * 各有自己的冻结 / 暂停状态，不合并、不互相覆盖。
 *
 * 旧数据里只有裸包名，[AppTarget.parse] 解析出来就是 user 0，不需要迁移。
 */
object FreezerMembers {

    suspend fun resolve(
        context: Context,
        appRepository: AppRepository,
        targets: Set<String>,
    ): List<AppInfo> {
        if (targets.isEmpty()) return emptyList()
        val parsed = targets
            .map(AppTarget::parse)
            .distinctBy { it.packageName to it.cloneUserId }
        val mainPackages = parsed.filterNot { it.isClone }.mapTo(LinkedHashSet()) { it.packageName }
        val mains = appRepository.resolveFreezerMembers(mainPackages).associateBy { it.packageName }
        return parsed.mapNotNull { target ->
            val base = mains[target.packageName]
                ?: appRepository.lookupApp(target.packageName)
            if (base == null) {
                // 悬空目标（应用已卸载 / 分身已被删除）：也要给一条占位条目，
                // 否则它会永远卡在设置里，用户在界面上既看不见也删不掉。
                return@mapNotNull AppInfo(
                    packageName = target.packageName,
                    label = target.packageName,
                    letter = '#',
                    pinyinKey = target.packageName,
                    cloneUserId = target.cloneUserId,
                )
            }
            if (!target.isClone) {
                base
            } else {
                // 分身与主实例同图标、同包名，只有标签后缀不同；状态由网格按 user 实时读取，
                // 这里把主实例带过来的 isFrozen / isPaused 清掉，免得显示成主实例的状态。
                val label = CloneAppsIndex.labelForUser(context, target.cloneUserId, base.label)
                base.copy(
                    label = label,
                    letter = PinyinHelper.firstLetter(label),
                    pinyinKey = PinyinHelper.sortKey(label),
                    cloneUserId = target.cloneUserId,
                    isFrozen = false,
                    isPaused = false,
                )
            }
        }.sortedBy { it.pinyinKey }
            .also { resolved ->
                android.util.Log.i("FreezerMembers", "targets=${targets.size} resolved=${resolved.size}")
            }
    }
}
