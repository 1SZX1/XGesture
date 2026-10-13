package com.slideindex.app.settings.macro

/**
 * 宏编辑页里"可录制"的目标 App（列表项只用得上这两项）。
 *
 * @param packageName 包名，录制与执行都按它定位目标 App
 * @param label 应用名（展示用；取不到时回退成包名）
 */
data class MacroTargetApp(
    val packageName: String,
    val label: String,
)

/**
 * 目标 App 选择器的**纯逻辑**：把枚举到的 (包名, 应用名) 去重、丢空、排序。
 *
 * 枚举本身（`queryIntentActivities`）在界面层做，这里只负责把原始结果整理成可展示的列表，
 * 因此可以在 JVM 单测里直接跑。
 */
object MacroTargetApps {

    /**
     * @param entries 原始 (包名, 应用名) 序列；应用名可能为空或是包名本身
     */
    fun fromEntries(entries: List<Pair<String, String>>): List<MacroTargetApp> =
        entries
            .filter { it.first.isNotBlank() }
            .map { (packageName, label) ->
                MacroTargetApp(
                    packageName = packageName,
                    label = label.trim().ifBlank { packageName },
                )
            }
            // 同一个包可能被多个可启动 Activity 命中：只保留第一条。
            .distinctBy { it.packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { app -> app.label })
}
