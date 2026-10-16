package com.slideindex.app.settings

import com.slideindex.app.gesture.GestureAction
import com.slideindex.app.gesture.GestureRule
import com.slideindex.app.gesture.GestureTriggerType
import com.slideindex.app.gesture.SideGestureDefaults
import com.slideindex.app.gesture.TriggerHandle
import com.slideindex.app.overlay.PanelSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 档位编解码的往返与**抗分隔符碰撞**回归（GitHub issue #26）。
 *
 * 这条测试守的是踩过两次的坑：动作正文是**不透明串**，里面可能带 `\u001C` / `\u001D` / `\u001F`
 * （`GestureShortcutPayload` 自己就拿这三种当分隔符）。把正文拼进"用某个字符分隔的列表"里，
 * 迟早会从正文中间切一刀，表现为某个槽位的动作莫名变半截或整条丢失。
 *
 * ⚠️ 必须跑在 Robolectric 下：`AppSettings()` 会在 `FreeWindowSettings` 里读 `Build.MANUFACTURER`
 * （`FreeWindowMode.detectDefault`），纯 JVM 单测里它是 null → NPE。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class GestureProfileCodecTest {

    private val leftSwipeIn = GestureRule.slotId(
        PanelSide.LEFT,
        GestureTriggerType.SHORT_SWIPE_IN,
        TriggerHandle.DEFAULT_ID,
    )

    /** 正文里同时带 `\u001C`（hostPackage 分隔）、`\u001D`（label 分隔）、`\u001F`（多 intent 分隔）。 */
    private val nastyShortcut = GestureAction.LaunchShortcut.intents(
        intentUris = listOf("intent://a", "intent://b"),
        label = "多步",
        hostPackage = "com.example.app",
    )

    private fun profileWith(actions: Map<String, GestureAction>): GestureProfile = GestureProfile(
        id = "gp-test",
        name = "游戏",
        packageNames = setOf("com.example.game", "com.example.other"),
        snapshot = GestureProfileSnapshot.from(AppSettings()).copy(
            gestureRules = SideGestureDefaults.defaultRules().map { rule ->
                actions[rule.id]?.let { rule.copy(action = it) } ?: rule
            },
        ),
    )

    @Test
    fun encodeDecode_roundTripsProfileAndKeepsNastyActionBodyIntact() {
        val profile = profileWith(mapOf(leftSwipeIn to nastyShortcut))

        val raw = GestureProfileCodec.encodeAll(listOf(profile))
        val decoded = GestureProfileCodec.decodeAll(raw)

        assertEquals(1, decoded.size)
        val restored = decoded.single()
        assertEquals(profile.id, restored.id)
        assertEquals(profile.name, restored.name)
        assertEquals(profile.packageNames, restored.packageNames)
        // 关键：带三种控制字符的 action 必须原样回来（不是半截 payloadKey）。
        assertEquals(
            nastyShortcut,
            restored.snapshot.gestureRules.first { it.id == leftSwipeIn }.action,
        )
        // 其余槽位的动作也要在（没被"列表切碎"连带丢掉）。
        assertEquals(
            profile.snapshot.gestureRules.size,
            restored.snapshot.gestureRules.size,
        )
    }

    @Test
    fun encodeAll_isIdempotentThroughDecode() {
        val profile = profileWith(mapOf(leftSwipeIn to nastyShortcut))
        val raw = GestureProfileCodec.encodeAll(listOf(profile))

        // 解码再编码必须逐字节一致（这条同时兜住触钮组/外观/横屏那几类记录的稳定性）。
        val again = GestureProfileCodec.encodeAll(GestureProfileCodec.decodeAll(raw))

        assertEquals(raw, again)
    }

    @Test
    fun decodeAll_dropsMalformedRecordsButKeepsValidOnes() {
        // 从一次真实编码里取出一条**合法**的规则记录，避免手写分段与实现漂移。
        val validRuleRecord = GestureProfileCodec
            .encodeAll(listOf(profileWith(emptyMap())))
            .first { it.contains("\u001Fr\u001F") }

        val raw = setOf(
            "gp1\u001Fgp-x\u001Fname\u001F坏档",
            "gp1\u001Fgp-x\u001Fnum\u001Fnot-a-kv-record",
            "gp1\u001Fgp-y\u001Fname\u001F好档",
            validRuleRecord.replace("\u001Fgp-test\u001F", "\u001Fgp-y\u001F"),
            "garbage-without-header",
        )

        val decoded = GestureProfileCodec.decodeAll(raw).associateBy { it.id }

        assertEquals(setOf("gp-x", "gp-y"), decoded.keys)
        // 坏档只有 name/num 两条记录 → 规则回落到出厂默认（与全局空的处理一致），但不是空表。
        assertTrue(decoded.getValue("gp-x").snapshot.gestureRules.isNotEmpty())
        assertTrue(decoded.getValue("gp-y").snapshot.gestureRules.isNotEmpty())
    }

    @Test
    fun profileNameSanitizesControlChars() {
        val profile = profileWith(emptyMap()).copy(name = "游\u001F戏\u001D\u0000")

        val restored = GestureProfileCodec
            .decodeAll(GestureProfileCodec.encodeAll(listOf(profile)))
            .single()

        assertEquals("游 戏", restored.name)
    }
}
