package com.slideindex.app.barcode

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「单码 / 多码」判定的测试。
 *
 * 这层判定的意义在于**保证最常见的单码场景不受影响**：只有一个码时必须走 `Single`（直接回填），
 * 只有真的扫到多个才进 `Multiple`（列表让用户选）。所以这些用例是"改成支持多码"的风险边界测试。
 */
class ScanOutcomeTest {

    @Test
    fun empty_isNoCode() {
        assertEquals(ScanOutcome.NoCode, scanOutcome(emptyList()))
    }

    @Test
    fun blanksOnly_isNoCode() {
        assertEquals(ScanOutcome.NoCode, scanOutcome(listOf("", "   ", "\n")))
    }

    @Test
    fun single_isSingle() {
        assertEquals(ScanOutcome.Single("https://a.example"), scanOutcome(listOf("https://a.example")))
    }

    @Test
    fun singleWithBlanks_isStillSingle() {
        assertEquals(ScanOutcome.Single("abc"), scanOutcome(listOf("", "abc", "  ")))
    }

    @Test
    fun duplicateTexts_collapseToSingle() {
        // 同一内容被两种码制各报一次，或阈值化后重复报告 —— 都不该让用户看到"找到 2 个"。
        assertEquals(ScanOutcome.Single("same"), scanOutcome(listOf("same", "same", "same")))
    }

    @Test
    fun multiple_keepsOrderAndDropsDuplicatesAndBlanks() {
        val outcome = scanOutcome(listOf("a", "b", "a", "", "c"))
        assertEquals(ScanOutcome.Multiple(listOf("a", "b", "c")), outcome)
    }

    @Test
    fun multiple_isDetectedRegardlessOfLength() {
        assertEquals(ScanOutcome.Multiple(listOf("x", "y")), scanOutcome(listOf("x", "y")))
        assertEquals(
            ScanOutcome.Multiple(listOf("1", "2", "3", "4")),
            scanOutcome(listOf("1", "2", "3", "4")),
        )
    }

    @Test
    fun whitespaceOnlyDifference_isTreatedAsDistinctCodes() {
        // 刻意不做 trim 后比较：内容是"原文"，多一个空格就是不同的码（解析结果也可能不同）。
        assertEquals(ScanOutcome.Multiple(listOf("a", "a ")), scanOutcome(listOf("a", "a ")))
    }
}
