package com.slideindex.app.util

object TaskExclusions {
    /**
     * 最近任务里对用户没有意义的 launcher / systemui；任务切换器、圆环等都不显示它们。
     *
     * 注意**不要**把本应用包名放进来：任务切换器需要显示自己（用户反馈），
     * 不需要自己的消费方（如圆环启动器的最近应用）在各自那一层过滤。
     */
    val LAUNCHER_AND_SYSTEM = setOf(
        "com.android.systemui",
        "com.android.launcher",
        "com.android.launcher3",
        "com.miui.home",
        "com.meizu.flyme.launcher",
        "com.google.android.apps.nexuslauncher",
    )

    fun shouldSkipFreeWindow(packageName: String, selfPackage: String): Boolean {
        if (packageName.isBlank()) return true
        if (packageName == selfPackage) return true
        return packageName in LAUNCHER_AND_SYSTEM
    }
}
