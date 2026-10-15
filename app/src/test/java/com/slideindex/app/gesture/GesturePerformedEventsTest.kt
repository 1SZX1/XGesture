package com.slideindex.app.gesture

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 引导靠这个广播判断"用户自己做成了一次手势"，所以：
 * 真动作必须能收到；而「无」和「点击穿透」这类"什么都没发生"的占位动作不能算数，
 * 否则用户随便碰一下边缘就会被判成"完成"。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GesturePerformedEventsTest {

    @Test
    fun realActionIsBroadcast() = runTest {
        val received = async { GesturePerformedEvents.performed.first() }
        advanceUntilIdle()

        GesturePerformedEvents.notify(GestureAction.OpenIndex)

        assertEquals(GestureAction.OpenIndex, received.await())
    }

    @Test
    fun noOpActionsAreNotBroadcast() = runTest {
        val received = async { GesturePerformedEvents.performed.first() }
        advanceUntilIdle()

        GesturePerformedEvents.notify(GestureAction.None)
        GesturePerformedEvents.notify(GestureAction.ClickPassthrough)
        GesturePerformedEvents.notify(GestureAction.Back)

        assertEquals(GestureAction.Back, received.await())
    }
}
