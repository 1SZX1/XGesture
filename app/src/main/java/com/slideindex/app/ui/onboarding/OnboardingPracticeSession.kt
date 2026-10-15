package com.slideindex.app.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 「重看新手引导」浮层的开关。
 *
 * 与小窗编辑层同样的理由：它是整屏盖在导航宿主之上的，不在导航栈里，
 * 打开由设置页（关于页）触发，渲染点固定在 Activity 根部。
 */
object OnboardingPracticeSession {
    var isOpen by mutableStateOf(false)
        private set

    fun open() {
        isOpen = true
    }

    fun close() {
        isOpen = false
    }
}
