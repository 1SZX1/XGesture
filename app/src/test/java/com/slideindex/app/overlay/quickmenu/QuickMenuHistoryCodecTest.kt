package com.slideindex.app.overlay.quickmenu

import android.app.Application
import com.slideindex.app.overlay.quickmenu.QuickMenuHistoryEntry.Kind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 计时历史的持久化规则测试。
 *
 * 用 Robolectric：同文件里还有基于 DataStore 的 [QuickMenuHistoryStore]，其文件级委托会碰到
 * Android 侧类；这些用例本身是纯逻辑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class QuickMenuHistoryCodecTest {

    private fun stopwatch(totalMs: Long, atEpochMs: Long, laps: List<Long> = emptyList()) =
        QuickMenuHistoryEntry(kind = Kind.STOPWATCH, totalMs = totalMs, lapsMs = laps, atEpochMs = atEpochMs)

    private fun timer(totalMs: Long, atEpochMs: Long) =
        QuickMenuHistoryEntry(kind = Kind.TIMER, totalMs = totalMs, atEpochMs = atEpochMs)

    // ---------- add ----------

    @Test
    fun add_putsNewestFirst() {
        val older = stopwatch(1_000L, 100L)
        val newer = timer(2_000L, 200L)
        assertEquals(listOf(newer, older), QuickMenuHistoryCodec.add(listOf(older), newer))
    }

    @Test
    fun add_fromEmpty_yieldsSingleEntry() {
        val entry = timer(60_000L, 1L)
        assertEquals(listOf(entry), QuickMenuHistoryCodec.add(emptyList(), entry))
    }

    @Test
    fun add_beyondMax_evictsOldest() {
        val existing = (1..QuickMenuHistoryCodec.MAX_ENTRIES).map { stopwatch(1_000L, it.toLong()) }
        val result = QuickMenuHistoryCodec.add(existing, timer(5_000L, 999L))
        assertEquals(QuickMenuHistoryCodec.MAX_ENTRIES, result.size)
        // 最旧的（时间戳 = 1）应被挤掉
        assertTrue(result.none { it.atEpochMs == 1L })
    }

    // ---------- sanitize ----------

    @Test
    fun sanitize_dropsNonPositiveDuration() {
        val entries = listOf(
            stopwatch(0L, 10L),
            stopwatch(-5L, 20L),
            stopwatch(1_000L, 30L),
        )
        assertEquals(listOf(30L), QuickMenuHistoryCodec.sanitize(entries).map { it.atEpochMs })
    }

    @Test
    fun sanitize_dropsZeroTimestamp() {
        assertEquals(emptyList<QuickMenuHistoryEntry>(), QuickMenuHistoryCodec.sanitize(listOf(timer(1_000L, 0L))))
    }

    @Test
    fun sanitize_sortsByTimeDescending() {
        val entries = listOf(timer(1L, 100L), timer(2L, 300L), timer(3L, 200L))
        assertEquals(listOf(300L, 200L, 100L), QuickMenuHistoryCodec.sanitize(entries).map { it.atEpochMs })
    }

    @Test
    fun sanitize_truncatesToMax() {
        val entries = (1..50).map { timer(1_000L, it.toLong()) }
        assertEquals(QuickMenuHistoryCodec.MAX_ENTRIES, QuickMenuHistoryCodec.sanitize(entries).size)
    }

    @Test
    fun sanitize_empty_returnsEmpty() {
        assertTrue(QuickMenuHistoryCodec.sanitize(emptyList()).isEmpty())
    }

    // ---------- 序列化往返（存储格式正确性的核心保证） ----------

    @Test
    fun entry_survivesJsonRoundTrip() {
        val entry = stopwatch(totalMs = 12_345L, atEpochMs = 1_700_000_000_000L, laps = listOf(500L, 734L, 11_111L))
        val restored = Json.decodeFromString<QuickMenuHistoryEntry>(Json.encodeToString(entry))
        assertEquals(entry, restored)
    }

    @Test
    fun entryList_survivesJsonRoundTrip() {
        val entries = listOf(timer(60_000L, 1L), stopwatch(2_000L, 2L, listOf(1_000L, 1_000L)))
        val restored = Json.decodeFromString<List<QuickMenuHistoryEntry>>(Json.encodeToString(entries))
        assertEquals(entries, restored)
    }

    @Test
    fun decode_unknownKind_failsInsteadOfSilentlyCorrupting() {
        // 枚举名无法识别时应当抛错（由 Store 的 runCatching 兜底成空列表），
        // 而不是解析出一个 kind=null 的半成品条目。
        val malformed = """[{"kind":"SOMETHING_ELSE","totalMs":1,"atEpochMs":1}]"""
        val result = runCatching { Json.decodeFromString<List<QuickMenuHistoryEntry>>(malformed) }
        assertTrue(result.isFailure)
    }
}
