package com.slideindex.app.macro

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 宏列表 JSON 编解码：往返、容错、默认值、越界不裁剪。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class MacroJsonTest {

    private fun macro(
        id: String = "m-1",
        name: String = "签到",
        targetPackage: String = "com.example.app",
        steps: List<MacroStep> = emptyList(),
    ) = Macro(
        id = id,
        name = name,
        targetPackage = targetPackage,
        createdAtMs = 1_700_000_000_000L,
        updatedAtMs = 1_700_000_001_000L,
        steps = steps,
    )

    @Test
    fun roundTrip_keepsAllStepKinds() {
        val source = listOf(
            macro(
                steps = listOf(
                    MacroStep.Wait(500L),
                    MacroStep.Tap(0.25f, 0.75f, 90L),
                    MacroStep.Swipe(
                        startX = 0.5f,
                        startY = 0.8f,
                        endX = 0.5f,
                        endY = 0.2f,
                        durationMs = 300L,
                        points = listOf(
                            MacroStep.Swipe.PathPoint(0.5f, 0.8f),
                            MacroStep.Swipe.PathPoint(0.51f, 0.5f),
                            MacroStep.Swipe.PathPoint(0.5f, 0.2f),
                        ),
                    ),
                ),
            ),
        )

        val decoded = MacroJson.decode(MacroJson.encode(source))

        assertEquals(1, decoded.size)
        assertEquals(source[0].id, decoded[0].id)
        assertEquals(source[0].name, decoded[0].name)
        assertEquals(source[0].targetPackage, decoded[0].targetPackage)
        assertEquals(source[0].createdAtMs, decoded[0].createdAtMs)
        assertEquals(source[0].updatedAtMs, decoded[0].updatedAtMs)
        assertEquals(source[0].steps.size, decoded[0].steps.size)
        assertEquals(MacroStep.Wait(500L), decoded[0].steps[0])
        assertEquals(MacroStep.Tap(0.25f, 0.75f, 90L), decoded[0].steps[1])

        val swipe = decoded[0].steps[2] as MacroStep.Swipe
        assertEquals(0.5f, swipe.startX, 0f)
        assertEquals(0.8f, swipe.startY, 0f)
        assertEquals(0.5f, swipe.endX, 0f)
        assertEquals(0.2f, swipe.endY, 0f)
        assertEquals(300L, swipe.durationMs)
        assertEquals(3, swipe.points.size)
        assertEquals(0.51f, swipe.points[1].x, 0f)
    }

    @Test
    fun macroWithoutId_isDropped() {
        val raw = JSONArray()
            .put(JSONObject().put("name", "没有 id").put("targetPackage", "com.a"))
            .put(JSONObject().put("id", "   ").put("targetPackage", "com.b"))
            .toString()

        assertTrue(MacroJson.decode(raw).isEmpty())
    }

    @Test
    fun macroWithoutTargetPackage_isKept() {
        // 新建宏的流程是"先建占位宏（目标 App 还没选）→ 再进编辑页选"，所以空目标包必须能读回来。
        val raw = JSONArray()
            .put(JSONObject().put("id", "m-blank").put("name", "新建的宏"))
            .toString()

        val macro = MacroJson.decode(raw).single()
        assertEquals("m-blank", macro.id)
        assertEquals("", macro.targetPackage)
    }

    @Test
    fun missingOptionalFields_useDefaults() {
        val raw = JSONArray()
            .put(JSONObject().put("id", "m-3").put("targetPackage", "com.c"))
            .toString()

        val macro = MacroJson.decode(raw).single()
        assertEquals(Macro.FALLBACK_NAME, macro.name)
        assertEquals(Macro.CURRENT_SCHEMA_VERSION, macro.schemaVersion)
        assertTrue(macro.createdAtMs > 0L)
        assertTrue(macro.updatedAtMs > 0L)
        assertTrue(macro.steps.isEmpty())
    }

    @Test
    fun stepDefaults_matchSpec() {
        val raw = JSONArray()
            .put(
                JSONObject()
                    .put("id", "m-4")
                    .put("targetPackage", "com.d")
                    .put(
                        "steps",
                        JSONArray()
                            .put(JSONObject().put("type", "delay"))
                            .put(JSONObject().put("type", "tap"))
                            .put(JSONObject().put("type", "swipe")),
                    ),
            )
            .toString()

        val steps = MacroJson.decode(raw).single().steps
        assertEquals(MacroStep.Wait(MacroStep.DEFAULT_WAIT_MS), steps[0])
        assertEquals(MacroStep.Tap(0.5f, 0.5f, MacroStep.DEFAULT_TAP_MS), steps[1])
        val swipe = steps[2] as MacroStep.Swipe
        assertEquals(0.5f, swipe.startX, 0f)
        assertEquals(0.8f, swipe.startY, 0f)
        assertEquals(0.5f, swipe.endX, 0f)
        assertEquals(0.2f, swipe.endY, 0f)
        assertEquals(MacroStep.DEFAULT_SWIPE_MS, swipe.durationMs)
        assertTrue(swipe.points.isEmpty())
    }

    @Test
    fun legacyHoldMs_isAcceptedForTapDuration() {
        val raw = JSONArray()
            .put(
                JSONObject()
                    .put("id", "m-5")
                    .put("targetPackage", "com.e")
                    .put(
                        "steps",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "tap")
                                .put("x", 0.1)
                                .put("y", 0.2)
                                .put("holdMs", 123L),
                        ),
                    ),
            )
            .toString()

        val tap = MacroJson.decode(raw).single().steps.single() as MacroStep.Tap
        assertEquals(123L, tap.durationMs)
    }

    @Test
    fun unknownStepType_isSkippedOthersKept() {
        val raw = JSONArray()
            .put(
                JSONObject()
                    .put("id", "m-6")
                    .put("targetPackage", "com.f")
                    .put(
                        "steps",
                        JSONArray()
                            .put(JSONObject().put("type", "tap").put("durationMs", 50L))
                            .put(JSONObject().put("type", "长按"))
                            .put(JSONObject().put("type", "delay").put("durationMs", 10L)),
                    ),
            )
            .toString()

        val steps = MacroJson.decode(raw).single().steps
        assertEquals(2, steps.size)
        assertTrue(steps[0] is MacroStep.Tap)
        assertTrue(steps[1] is MacroStep.Wait)
    }

    @Test
    fun brokenOrEmptyInput_yieldsEmptyList() {
        assertTrue(MacroJson.decode(null).isEmpty())
        assertTrue(MacroJson.decode("").isEmpty())
        assertTrue(MacroJson.decode("   ").isEmpty())
        assertTrue(MacroJson.decode("{不是数组").isEmpty())
        assertTrue(MacroJson.decode("{\"a\":1}").isEmpty())
        assertTrue(MacroJson.decode("[]").isEmpty())
    }

    @Test
    fun outOfRangeValues_areNotClampedWhileParsing() {
        val raw = JSONArray()
            .put(
                JSONObject()
                    .put("id", "m-7")
                    .put("targetPackage", "com.g")
                    .put(
                        "steps",
                        JSONArray()
                            .put(JSONObject().put("type", "tap").put("x", 5.0).put("durationMs", -3L))
                            .put(JSONObject().put("type", "delay").put("durationMs", 999_999L)),
                    ),
            )
            .toString()

        val steps = MacroJson.decode(raw).single().steps
        val tap = steps[0] as MacroStep.Tap
        assertEquals(5.0f, tap.x, 0f)
        assertEquals(-3L, tap.durationMs)
        assertEquals(999_999L, (steps[1] as MacroStep.Wait).durationMs)
    }

    @Test
    fun encode_isCompressedAndKeepsKeyOrder() {
        val text = MacroJson.encode(listOf(macro(steps = listOf(MacroStep.Wait(200L)))))
        assertFalse("输出不应含空白格式化", text.contains("\n"))
        assertFalse(text.contains(": "))
        assertTrue(text.startsWith("[{\"schemaVersion\":2,\"id\":\"m-1\",\"name\":\"签到\",\"targetPackage\":\"com.example.app\",\"createdAtMs\":1700000000000,\"updatedAtMs\":1700000001000,\"steps\":["))
        assertEquals(text, MacroJson.decode(text).let { MacroJson.encode(it) })
    }

    @Test
    fun actionAndWaitCounts_ignoreWaits() {
        val macro = macro(
            steps = listOf(
                MacroStep.Wait(100L),
                MacroStep.Tap(),
                MacroStep.Wait(200L),
                MacroStep.Swipe(),
            ),
        )
        assertEquals(2, macro.actionCount)
        assertEquals(2, macro.waitCount)
    }

    @Test
    fun limits_clampIntoSpecRanges() {
        assertEquals(0L, MacroLimits.clampWait(-5L))
        assertEquals(10_000L, MacroLimits.clampWait(99_999L))
        assertEquals(1L, MacroLimits.clampTap(0L))
        assertEquals(2_000L, MacroLimits.clampTap(9_999L))
        assertEquals(50L, MacroLimits.clampSwipe(1L))
        assertEquals(5_000L, MacroLimits.clampSwipe(99_999L))
        assertEquals(0f, MacroLimits.clampFraction(-1f), 0f)
        assertEquals(1f, MacroLimits.clampFraction(2f), 0f)
    }
}
