package com.slideindex.app.timeddnd

/**
 * 临时免打扰的时长范围（分钟）。
 *
 * 原先借用 `RemindAlarmScheduler` 的钳制（1..120 分钟）——那是**跨功能耦合**：想调免打扰的
 * 上限，得去改提醒模块的常量，还会连带影响提醒本身。这里独立成自己的范围，取值与 GestureEVO
 * 对齐（最长 24 小时），并顺手去掉 `TempDndNotificationReceiver` 里的重复钳制。
 *
 * 单独成文件还有个实际好处：`TempDndManager` 的静态初始化里有 `Uri.parse(...)` 等 Android
 * 依赖，普通 JVM 单测加载不了它的 companion；这里的常量与函数是**纯**的，可以直接测。
 */
internal object TempDndLimits {

    const val MIN_MINUTES = 1

    /** 24 小时。 */
    const val MAX_MINUTES = 24 * 60

    fun clampMinutes(minutes: Int): Int = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)
}
