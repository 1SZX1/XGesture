package com.slideindex.app.barcode

data class BarcodeScanResult(
    val text: String,
    val format: String
)

fun List<BarcodeScanResult>.joinDisplayText(): String =
    joinToString(separator = "\n") { it.text }

/**
 * 一次扫描的**结果集合**该怎么用。
 *
 * 抽成纯函数是为了可单测：界面的分支完全由它决定——**只有一个码时走"直接回填"这条老路**
 * （最常见的场景不该因为支持多码而变复杂），多个码时才列出来让用户点选。
 */
sealed interface ScanOutcome {
    /** 什么也没扫到（或全是空串）。 */
    data object NoCode : ScanOutcome

    /** 只有一个码：与支持多码之前的行为完全一致。 */
    data class Single(val text: String) : ScanOutcome

    /** 多个码：交给界面列出候选。 */
    data class Multiple(val texts: List<String>) : ScanOutcome
}

/** @param texts 原始结果，允许含空串与重复（例如同一内容被两种码制各报一次）。 */
fun scanOutcome(texts: List<String>): ScanOutcome {
    val distinct = texts.filter { it.isNotBlank() }.distinct()
    return when (distinct.size) {
        0 -> ScanOutcome.NoCode
        1 -> ScanOutcome.Single(distinct.first())
        else -> ScanOutcome.Multiple(distinct)
    }
}
