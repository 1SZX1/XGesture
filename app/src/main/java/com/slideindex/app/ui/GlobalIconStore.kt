package com.slideindex.app.ui

import android.content.Context

/**
 * **全局图标覆盖**：按「动作类型」或「应用包名」指定图标。
 *
 * 与旧版（按单个动作配置存）不同，这里是全局的：
 * - 给某个动作类型（如"屏幕绘制"）统一指定图标 → 所有绑定该类型的位置都显示它
 * - 给某个应用指定图标 → 该应用在任何列表里都显示它
 *
 * 存储仍是 SharedPreferences（纯展示层覆盖，不影响动作载荷）。
 */
object GlobalIconStore {

    private const val PREFS = "global_icon_overrides"
    private const val ACTION_PREFIX = "action:"
    private const val APP_PREFIX = "app:"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun actionIcon(context: Context, typeId: String): String? =
        prefs(context).getString(ACTION_PREFIX + typeId, null)?.takeIf { it.isNotBlank() }

    fun setActionIcon(context: Context, typeId: String, iconKey: String?) {
        val editor = prefs(context).edit()
        if (iconKey.isNullOrBlank()) editor.remove(ACTION_PREFIX + typeId)
        else editor.putString(ACTION_PREFIX + typeId, iconKey)
        editor.apply()
    }

    fun appIcon(context: Context, packageName: String): String? =
        prefs(context).getString(APP_PREFIX + packageName, null)?.takeIf { it.isNotBlank() }

    fun setAppIcon(context: Context, packageName: String, iconKey: String?) {
        val editor = prefs(context).edit()
        if (iconKey.isNullOrBlank()) editor.remove(APP_PREFIX + packageName)
        else editor.putString(APP_PREFIX + packageName, iconKey)
        editor.apply()
    }

    fun allActionIcons(context: Context): Map<String, String> =
        prefs(context).all.entries
            .filter { it.key.startsWith(ACTION_PREFIX) && it.value is String }
            .associate { it.key.removePrefix(ACTION_PREFIX) to (it.value as String) }

    fun allAppIcons(context: Context): Map<String, String> =
        prefs(context).all.entries
            .filter { it.key.startsWith(APP_PREFIX) && it.value is String }
            .associate { it.key.removePrefix(APP_PREFIX) to (it.value as String) }
}
