package com.slideindex.app.search.ral

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Based on [Root Activity Launcher](https://github.com/zacharee/RootActivityLauncher) (GPL-3.0).
 *
 * RootActivityLauncher-compatible privileged activity launcher.
 *
 * Strategy order (descending priority): Normal, Iterative, ShizukuJava,
 * SamsungExploit, Root, AssistantJava.
 */
object PrivilegedActivityLauncher {
    const val TAG = "PrivilegedActivityLauncher"

    fun createLaunchArgs(
        context: Context,
        packageName: String,
        activityName: String,
    ): LaunchArgs {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(packageName, activityName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return createLaunchArgs(context, intent)
    }

    private fun createLaunchArgs(context: Context, intent: Intent): LaunchArgs {
        val prepared = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val filters = prepared.component?.packageName
            ?.let { packageName -> context.packageManager.getAllIntentFiltersCompat(packageName) }
            .orEmpty()
        return LaunchArgs(prepared, filters)
    }

    suspend fun launch(
        context: Context,
        packageName: String,
        activityName: String,
        privilegedOnly: Boolean = false,
    ): List<Throwable> {
        val appContext = context.applicationContext
        return launch(appContext, createLaunchArgs(appContext, packageName, activityName), privilegedOnly)
    }

    /**
     * 直接启动一个完整 Intent，保留 action / data / extras。
     * 默认只走特权策略（调用方已经确认普通启动会失败，例如目标 Activity 未导出）。
     */
    suspend fun launch(
        context: Context,
        intent: Intent,
        privilegedOnly: Boolean = true,
    ): List<Throwable> {
        val appContext = context.applicationContext
        return launch(appContext, createLaunchArgs(appContext, intent), privilegedOnly)
    }

    private suspend fun launch(
        appContext: Context,
        args: LaunchArgs,
        privilegedOnly: Boolean,
    ): List<Throwable> {
        val errors = mutableListOf<Throwable>()
        val strategies = activityLaunchStrategies
            .filter { it.allowedForCurrentPrivilegeMode() }
            .let { candidates ->
                if (privilegedOnly) {
                    candidates.filter { strategy ->
                        strategy !is ActivityLaunchStrategy.Normal &&
                            strategy !is ActivityLaunchStrategy.Iterative
                    }
                } else {
                    candidates
                }
            }

        for (strategy in strategies) {
            with(strategy) {
                if (!appContext.canRun(args)) return@with
                val result = appContext.tryLaunch(args)
                Log.d(TAG, "${strategy::class.simpleName} -> errors=${result.size}")
                if (result.isEmpty()) {
                    Log.i(TAG, "launched ${args.intent.component} via ${strategy.label}")
                    return emptyList()
                }
                errors += result
            }
        }

        return errors.ifEmpty {
            listOf(Exception("All launch strategies failed for ${args.intent.component?.flattenToString()}"))
        }
    }
}
