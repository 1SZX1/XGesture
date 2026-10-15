package com.slideindex.app.gesture

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 「用户刚才真的执行了哪个动作」的广播口。
 *
 * 只通知、不参与任何决策：新手引导靠它判断"用户自己完成了一次手势"。
 * 否则每个动作都得各写一套探测——悬浮指针有 `isVisible`、取词有结果面板，
 * 而「返回键」「应用索引」这类动作在外部**没有任何可查的状态**。
 *
 * 发出点只有一处：`GestureSessionActionDispatch.handleClassifiedGesture()` 的动作分发入口，
 * 也就是所有手势动作真正落地的那道门。
 */
object GesturePerformedEvents {
    private val mutablePerformed = MutableSharedFlow<GestureAction>(extraBufferCapacity = 8)

    val performed: SharedFlow<GestureAction> = mutablePerformed.asSharedFlow()

    internal fun notify(action: GestureAction) {
        if (action == GestureAction.None || action is GestureAction.ClickPassthrough) return
        mutablePerformed.tryEmit(action)
    }
}
