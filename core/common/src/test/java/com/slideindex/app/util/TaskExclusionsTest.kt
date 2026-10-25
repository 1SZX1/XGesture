package com.slideindex.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskExclusionsTest {

    @Test
    fun shouldSkipFreeWindow_skipsSelfAndLaunchers() {
        assertTrue(TaskExclusions.shouldSkipFreeWindow("com.slideindex.app", "com.slideindex.app"))
        assertTrue(TaskExclusions.shouldSkipFreeWindow("com.android.launcher3", "com.slideindex.app"))
    }

    @Test
    fun shouldSkipFreeWindow_allowsRegularApps() {
        assertFalse(TaskExclusions.shouldSkipFreeWindow("com.tencent.mm", "com.slideindex.app"))
    }

    /**
     * 任务切换器需要显示本应用；这里只保留 launcher / systemui，
     * 不能再把 com.slideindex.app 硬编码进排除集合（否则任务切换器永远看不到自己）。
     */
    @Test
    fun launcherAndSystemSet_keepsLaunchersButNotSelf() {
        assertTrue("com.android.launcher3" in TaskExclusions.LAUNCHER_AND_SYSTEM)
        assertTrue("com.meizu.flyme.launcher" in TaskExclusions.LAUNCHER_AND_SYSTEM)
        assertFalse("com.slideindex.app" in TaskExclusions.LAUNCHER_AND_SYSTEM)
    }
}
