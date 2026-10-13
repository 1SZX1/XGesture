package com.slideindex.app.settings.macro

import android.content.Context

/**
 * 宏录制器的**窄接口**，由 P2 的录制器（`app/.../macro/record/MacroRecordOverlay.kt`）实现。
 *
 * 界面层只调 `MacroRecordingLauncher.impl.start(...)` 这一个入口，不直接依赖录制实现：
 * 没接线时 `impl` 为 null，界面提示"录制功能尚未接入"，
 * 因此本模块不会因为 P2 还没落地而编译不过。
 */
interface MacroRecordingStart {

    /**
     * 拉起目标 App 并开始录制。
     *
     * @param context 用于启动 Activity / 创建浮层
     * @param macroId 正在编辑的宏 id（新建时为 `MacroStore.newId()` 预生成的 id）
     * @param targetPackage 目标 App 包名
     * @param macroName 当前宏名（录制条上展示用，允许为空）
     * @param listener 录制开始 / 结束的回调，界面用它切换帮助文案
     * @return 是否成功开始录制（false = 界面提示失败）
     */
    fun start(
        context: Context,
        macroId: String,
        targetPackage: String,
        macroName: String,
        listener: Listener,
    ): Boolean

    /** 录制器 → 界面的回调。 */
    interface Listener {

        /** 录制已开始（目标 App 到前台、悬浮条已出现）。 */
        fun onRecordingStarted()

        /**
         * 录制结束。
         *
         * @param savedSteps 用户点了"保存"时保存下来的步数；放弃录制时为 0
         */
        fun onRecordingFinished(savedSteps: Int)
    }
}

/** 录制器的全局接线点：P2 落地后在 Application 初始化里塞进实现即可。 */
object MacroRecordingLauncher {

    @Volatile
    var impl: MacroRecordingStart? = null
}
