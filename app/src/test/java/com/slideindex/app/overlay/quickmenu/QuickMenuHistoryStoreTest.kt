package com.slideindex.app.overlay.quickmenu

import android.content.Context
import com.slideindex.app.overlay.quickmenu.QuickMenuHistoryEntry.Kind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 计时历史**真实落盘**的往返测试（Robolectric 提供真实的 filesDir 与 DataStore 文件）。
 *
 * 存在意义：本轮 review 的现象是"历史里没有记录"，而存储写入是**全程静默**的
 * （Store 里所有异常都被兜底成空列表）。所以必须真的写一次、读一次，才能证明这条链路是通的，
 * 而不是"编译过了就以为没事"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class QuickMenuHistoryStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun entry(kind: Kind, totalMs: Long, atEpochMs: Long, laps: List<Long> = emptyList()) =
        QuickMenuHistoryEntry(kind = kind, totalMs = totalMs, lapsMs = laps, atEpochMs = atEpochMs)

    @Test
    fun add_thenRead_returnsSameEntry() = runTest {
        QuickMenuHistoryStore.clear(context)
        val timer = entry(Kind.TIMER, 60_000L, 1_700_000_000_000L)

        assertEquals(listOf(timer), QuickMenuHistoryStore.add(context, timer))
        assertEquals(listOf(timer), QuickMenuHistoryStore.read(context))
    }

    @Test
    fun read_whenNothingWritten_returnsEmpty() = runTest {
        QuickMenuHistoryStore.clear(context)
        assertTrue(QuickMenuHistoryStore.read(context).isEmpty())
    }

    @Test
    fun add_multiple_keepsNewestFirst_andPreservesLaps() = runTest {
        QuickMenuHistoryStore.clear(context)
        val older = entry(Kind.STOPWATCH, 12_345L, 1_000L, listOf(6_000L, 6_345L))
        val newer = entry(Kind.TIMER, 300_000L, 2_000L)

        QuickMenuHistoryStore.add(context, older)
        QuickMenuHistoryStore.add(context, newer)

        val read = QuickMenuHistoryStore.read(context)
        assertEquals(listOf(newer, older), read)
        assertEquals(listOf(6_000L, 6_345L), read.last().lapsMs)
    }

    @Test
    fun clear_removesEverything() = runTest {
        QuickMenuHistoryStore.add(context, entry(Kind.TIMER, 1_000L, 1_000L))
        QuickMenuHistoryStore.clear(context)
        assertTrue(QuickMenuHistoryStore.read(context).isEmpty())
    }

    @Test
    fun add_ignoresInvalidEntry_butKeepsExisting() = runTest {
        QuickMenuHistoryStore.clear(context)
        val valid = entry(Kind.TIMER, 1_000L, 1_000L)
        QuickMenuHistoryStore.add(context, valid)

        // 无效条目（时长为 0）不应污染已有历史
        val updated = QuickMenuHistoryStore.add(context, entry(Kind.TIMER, 0L, 2_000L))
        assertEquals(listOf(valid), updated)
    }

    @Test
    fun add_beyondMax_keepsOnlyNewest() = runTest {
        QuickMenuHistoryStore.clear(context)
        repeat(QuickMenuHistoryCodec.MAX_ENTRIES + 3) { index ->
            QuickMenuHistoryStore.add(context, entry(Kind.TIMER, 1_000L, index + 1L))
        }
        val read = QuickMenuHistoryStore.read(context)
        assertEquals(QuickMenuHistoryCodec.MAX_ENTRIES, read.size)
        // 最新的（时间戳最大）在最前
        assertEquals((QuickMenuHistoryCodec.MAX_ENTRIES + 3).toLong(), read.first().atEpochMs)
    }
}
