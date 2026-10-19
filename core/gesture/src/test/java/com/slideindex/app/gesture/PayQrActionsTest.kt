package com.slideindex.app.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 「支付码 / 扫码」四个动作的编解码与 id 约束。
 *
 * id 必须与 MyGesture 的取值体系无关（那是它自己的内部 id），这里只保证本项目内唯一、稳定，
 * 并且 `fromId(type.id)` 能回到同一个类型（prefs 里存的就是这个 id）。
 */
class PayQrActionsTest {

    private val actions = mapOf(
        GestureActionType.WECHAT_SCAN to GestureAction.WechatScan,
        GestureActionType.WECHAT_PAY_CODE to GestureAction.WechatPayCode,
        GestureActionType.ALIPAY_PAY_CODE to GestureAction.AlipayPayCode,
        GestureActionType.ALIPAY_SCAN to GestureAction.AlipayScan,
    )

    @Test
    fun ids_areStableAndUnique() {
        assertEquals(101, GestureActionType.WECHAT_SCAN.id)
        assertEquals(102, GestureActionType.WECHAT_PAY_CODE.id)
        assertEquals(103, GestureActionType.ALIPAY_PAY_CODE.id)
        assertEquals(104, GestureActionType.ALIPAY_SCAN.id)

        val allIds = GestureActionType.entries.map { it.id }
        assertEquals(allIds.size, allIds.toSet().size)
    }

    @Test
    fun fromId_roundTrips() {
        for (type in actions.keys) {
            assertEquals(type, GestureActionType.fromId(type.id))
        }
    }

    @Test
    fun from_mapsTypeToAction() {
        for ((type, action) in actions) {
            assertEquals(action, GestureAction.from(type, ""))
            assertEquals(type, action.type)
        }
        // 四个动作互不相等，别在 from() 里写错分支。
        assertEquals(actions.size, actions.values.toSet().size)
        assertNotEquals(GestureAction.WechatScan, GestureAction.WechatPayCode)
    }
}
