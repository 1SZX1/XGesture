/*
 * 移植自 GestureEVO（https://github.com/LumiaGG/GestureEVO，GPL-3.0-only）
 *   源文件：utils/TempDndRestoreWorker.kt
 * 已按本项目的数据模型、主题与集成层改写（部分模块拆分为多文件、部分能力为本项目新增）；
 * 版权与许可声明见 THIRD_PARTY_NOTICES.md，文件级映射见 docs/ATTRIBUTIONS.md。
 */

package com.slideindex.app.timeddnd

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class TempDndRestoreWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val success = TempDndManager.restoreDnd(applicationContext)
        return if (success) Result.success() else Result.failure()
    }
}
