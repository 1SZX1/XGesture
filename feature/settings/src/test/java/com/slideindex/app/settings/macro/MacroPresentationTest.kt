package com.slideindex.app.settings.macro

import com.slideindex.app.macro.Macro
import com.slideindex.app.macro.MacroStep
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 行摘要与目标 App 列表的纯逻辑：
 * - 摘要里的动作数 = 会真正产生手势的步（等待步不算，与 `Macro.actionCount` 同一口径）
 * - 名字为空时用兜底名
 * - 目标 App 枚举结果去重 / 丢空包名 / 应用名回退到包名 / 按应用名排序
 */
class MacroPresentationTest {

    private fun macro(name: String, steps: List<MacroStep>) = Macro(
        id = "id",
        name = name,
        targetPackage = "com.example.target",
        steps = steps,
    )

    @Test
    fun `摘要数字只数会动的步`() {
        val subject = macro(
            name = "签到",
            steps = listOf(
                MacroStep.Wait(500),
                MacroStep.Tap(0.5f, 0.5f),
                MacroStep.Wait(200),
                MacroStep.Swipe(),
            ),
        )
        assertEquals(2, MacroPresentation.actionCount(subject))
    }

    @Test
    fun `只有等待步时动作数为零`() {
        val subject = macro(name = "纯等待", steps = listOf(MacroStep.Wait(300)))
        assertEquals(0, MacroPresentation.actionCount(subject))
    }

    @Test
    fun `空名字用兜底名`() {
        assertEquals(Macro.FALLBACK_NAME, MacroPresentation.displayName(""))
        assertEquals(Macro.FALLBACK_NAME, MacroPresentation.displayName("   "))
    }

    @Test
    fun `正常名字原样显示`() {
        assertEquals("Daily", MacroPresentation.displayName("Daily"))
    }

    @Test
    fun `目标应用列表去重丢空并排序`() {
        val apps = MacroTargetApps.fromEntries(
            listOf(
                "com.b" to "Bravo",
                "" to "没有包名",
                "com.a" to "alpha",
                "com.b" to "Bravo 的另一个入口",
            ),
        )
        assertEquals(listOf("com.a", "com.b"), apps.map { it.packageName })
        assertEquals(listOf("alpha", "Bravo"), apps.map { it.label })
    }

    @Test
    fun `应用名取不到时回退成包名`() {
        val apps = MacroTargetApps.fromEntries(listOf("com.example.app" to "   "))
        assertEquals("com.example.app", apps.single().label)
    }

    @Test
    fun `没有任何应用时是空列表`() {
        assertEquals(emptyList<MacroTargetApp>(), MacroTargetApps.fromEntries(emptyList()))
    }
}
