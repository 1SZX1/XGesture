package com.slideindex.app.freezer

import android.content.Context
import android.widget.Toast
import com.slideindex.app.R
import com.slideindex.app.data.AppRepository
import com.slideindex.app.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object FreezerListOperations {

    /**
     * 批量移除的结果。
     *
     * - [removed]：真的移出了列表的数量。
     * - [skipped]：没能移出的数量 —— 「移出」路径下是还在冻结/暂停、被拦下的；
     *   「恢复并移出」路径下是恢复失败的（权限被撤、受保护包之类）。
     */
    data class BatchRemovalResult(val removed: Int, val skipped: Int)

    suspend fun removeFromList(
        context: Context,
        settingsRepository: SettingsRepository,
        packageName: String,
        appRepository: AppRepository? = null
    ): Boolean {
        when (FreezerOperations.stateOf(context, packageName)) {
            FreezerAppState.FROZEN -> {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.freezer_remove_while_frozen, Toast.LENGTH_SHORT).show()
                }
                return false
            }
            FreezerAppState.PAUSED -> {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.freezer_remove_while_paused, Toast.LENGTH_SHORT).show()
                }
                return false
            }
            FreezerAppState.ACTIVE -> Unit
        }
        settingsRepository.removeFreezerApp(packageName)
        appRepository?.invalidate()
        return true
    }

    /** 解冻或取消暂停之后再移出列表（对应菜单里的「解冻并移除」/「取消暂停并移除」）。 */
    suspend fun restoreAndRemoveFromList(
        context: Context,
        settingsRepository: SettingsRepository,
        packageName: String,
        appRepository: AppRepository? = null
    ): Boolean {
        when (FreezerOperations.stateOf(context, packageName)) {
            FreezerAppState.FROZEN ->
                if (!FreezerOperations.setFrozen(context, packageName, frozen = false)) return false
            FreezerAppState.PAUSED ->
                if (!FreezerOperations.setPaused(context, packageName, paused = false)) return false
            FreezerAppState.ACTIVE -> Unit
        }
        settingsRepository.removeFreezerApp(packageName)
        appRepository?.invalidate()
        return true
    }

    /**
     * 批量「移出列表」。冻结 / 暂停中的成员**逐个跳过**，语义与单包 [removeFromList] 一致
     * （那种状态要从列表里拿掉得先恢复，见 [restoreAndRemoveFromList]）。
     *
     * 跳过的数量交给调用方提示，别静默吞掉 —— 否则用户点完「批量移出」只会看到列表少了一半，
     * 剩下那几项还冻着，他还以为没生效。
     */
    suspend fun removeManyFromList(
        context: Context,
        settingsRepository: SettingsRepository,
        packageNames: Collection<String>,
        appRepository: AppRepository? = null
    ): BatchRemovalResult = withContext(Dispatchers.IO) {
        var removed = 0
        var skipped = 0
        for (packageName in packageNames) {
            when (FreezerOperations.stateOf(context, packageName)) {
                FreezerAppState.ACTIVE -> {
                    settingsRepository.removeFreezerApp(packageName)
                    removed++
                }
                FreezerAppState.FROZEN, FreezerAppState.PAUSED -> skipped++
            }
        }
        if (removed > 0) appRepository?.invalidate()
        BatchRemovalResult(removed = removed, skipped = skipped)
    }

    /**
     * 批量「恢复并移出」：冻结的先解冻、暂停的先取消暂停，再移出列表。
     *
     * 这是批量移除的主力路径 —— 用户想清空列表时，成员多半正冻着/暂停着（那正是列表的意义），
     * 只给「移出」会让他每项都要先单独恢复一遍。
     *
     * 恢复失败的（没权限、受保护包）**不移出**：留在列表里至少状态还看得见，
     * 直接从列表抹掉就再也没人管它了。
     */
    suspend fun restoreAndRemoveManyFromList(
        context: Context,
        settingsRepository: SettingsRepository,
        packageNames: Collection<String>,
        appRepository: AppRepository? = null
    ): BatchRemovalResult = withContext(Dispatchers.IO) {
        var removed = 0
        var skipped = 0
        for (packageName in packageNames) {
            val restored = when (FreezerOperations.stateOf(context, packageName)) {
                FreezerAppState.FROZEN ->
                    FreezerOperations.setFrozen(context, packageName, frozen = false)
                FreezerAppState.PAUSED ->
                    FreezerOperations.setPaused(context, packageName, paused = false)
                FreezerAppState.ACTIVE -> true
            }
            if (!restored) {
                skipped++
                continue
            }
            settingsRepository.removeFreezerApp(packageName)
            removed++
        }
        if (removed > 0) appRepository?.invalidate()
        BatchRemovalResult(removed = removed, skipped = skipped)
    }

    suspend fun importFrozenApps(
        context: Context,
        settingsRepository: SettingsRepository
    ): Int {
        val scanned = FreezerBootstrap.scanImportableLauncherPackages(context)
        if (scanned.isEmpty()) return 0
        val current = settingsRepository.readFreshSnapshot().freezerAppPackages
        val toAdd = scanned - current
        toAdd.forEach { packageName ->
            settingsRepository.addFreezerApp(packageName)
            // 导入的包本来就带着冻结 / 暂停状态：补一条意图，否则手势会按全局模式猜一个档位。
            val intent = FreezerBootstrap.intentForState(FreezerOperations.stateOf(context, packageName))
                ?: return@forEach
            settingsRepository.setFreezerAppIntent(packageName, intent)
        }
        return toAdd.size
    }
}
