package com.slideindex.app.overlay.quickmenu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PomodoroLogic] 的单测。
 *
 * 只测纯逻辑：时长档位、开始/暂停/复位/跳过、**到点自动接续**、以及"离开页面很久之后回来"的补算。
 * 这些正是照 QuickDash 移植过来的行为，界面上看不出来的边界都在这里断言。
 */
class QuickMenuPomodoroLogicTest {

    private val focus = PomodoroLogic.FOCUS_MINUTES * 60_000L
    private val shortBreak = PomodoroLogic.SHORT_BREAK_MINUTES * 60_000L
    private val longBreak = PomodoroLogic.LONG_BREAK_MINUTES * 60_000L

    @Test
    fun defaults_matchReferenceImplementation() {
        // QuickDash：25 分钟专注 / 5 分钟短休 / 15 分钟长休。
        assertEquals(25, PomodoroLogic.FOCUS_MINUTES)
        assertEquals(5, PomodoroLogic.SHORT_BREAK_MINUTES)
        assertEquals(15, PomodoroLogic.LONG_BREAK_MINUTES)
        assertEquals(4, PomodoroLogic.FOCUSES_PER_LONG_BREAK)

        val state = PomodoroLogic.initial()
        assertEquals(PomodoroLogic.Phase.FOCUS, state.phase)
        assertEquals(focus, state.durationMs)
        assertEquals(focus, state.remainingMs)
        assertFalse(state.running)
    }

    @Test
    fun start_thenRemainingCountsDown_thenPauseFreezes() {
        val started = PomodoroLogic.start(PomodoroLogic.initial(), nowMs = 1_000L)
        assertTrue(started.running)
        assertEquals(focus, started.remainingAt(1_000L))
        assertEquals(focus - 60_000L, started.remainingAt(61_000L))

        val paused = PomodoroLogic.pause(started, nowMs = 61_000L)
        assertFalse(paused.running)
        assertEquals(focus - 60_000L, paused.remainingMs)
        // 暂停之后时间继续走，读数不再变化。
        assertEquals(focus - 60_000L, paused.remainingAt(600_000L))
    }

    @Test
    fun start_whenRunning_isNoOp() {
        val started = PomodoroLogic.start(PomodoroLogic.initial(), nowMs = 0L)
        assertEquals(started, PomodoroLogic.start(started, nowMs = 5_000L))
    }

    @Test
    fun runningPastTheEnd_remainingAtIsNegative_soOverflowIsRecoverable() {
        // 回归测试：早先 remainingAt 里写了 coerceAtLeast(0)，把"超时量"抹成 0，
        // 于是补算永远减不掉时间（离开 3 分钟回来，短休还是完整的 5 分钟）。
        val started = PomodoroLogic.start(PomodoroLogic.initial(), nowMs = 0L)
        val now = focus + 90_000L
        assertEquals(-90_000L, started.remainingAt(now))
        assertEquals(0L, started.remainingAt(focus))
    }

    @Test
    fun reset_keepsFocusCount_butRestoresDuration() {
        val state = PomodoroLogic.initial()
            .copy(phase = PomodoroLogic.Phase.FOCUS, focusCount = 3)
        val started = PomodoroLogic.start(state, nowMs = 0L)
        val mid = started.copy(remainingMs = 10_000L, endsAtMs = 10_000L)

        val reset = PomodoroLogic.reset(mid)
        assertEquals(focus, reset.remainingMs)
        assertEquals(3, reset.focusCount)
        assertFalse(reset.running)
        assertEquals(null, reset.endsAtMs)
    }

    @Test
    fun select_switchesPhaseAndStopsRunning() {
        val running = PomodoroLogic.start(PomodoroLogic.initial(), nowMs = 0L)
        val selected = PomodoroLogic.select(running, PomodoroLogic.Phase.LONG_BREAK)

        assertEquals(PomodoroLogic.Phase.LONG_BREAK, selected.phase)
        assertEquals(longBreak, selected.durationMs)
        assertEquals(longBreak, selected.remainingMs)
        assertFalse(selected.running)
        // 计数与"手动选过的档位"都要留住。
        assertEquals(0, selected.focusCount)
        assertEquals(longBreak, selected.durationOverrideMs[PomodoroLogic.Phase.LONG_BREAK])
    }

    @Test
    fun focusFinish_goesToShortBreak_andCountsSession() {
        // 专注跑完 → 短休（并记 1 个会话）。
        val started = PomodoroLogic.start(PomodoroLogic.initial(), nowMs = 0L)
        val advanced = PomodoroLogic.advanceTo(started, nowMs = focus)

        assertEquals(PomodoroLogic.Phase.SHORT_BREAK, advanced.phase)
        assertEquals(shortBreak, advanced.durationMs)
        assertEquals(shortBreak, advanced.remainingMs)
        assertEquals(1, advanced.focusCount)
        assertTrue(advanced.running)
    }

    @Test
    fun advanceTo_exactlyOnBoundary_startsTheNextPhaseInFull() {
        // 回归测试：恰好停在边界（overdue == 0）时，下一段必须是**完整的**。
        // 早先写成 `nextDuration - overdue`，于是下一段一开始就是 0、被同一轮循环判成"也走完了"，
        // 整段整段被吞掉 —— 表现就是"跑了 25 分钟回来，还停在 25:00 的专注上"。
        val started = PomodoroLogic.start(PomodoroLogic.initial(), nowMs = 0L)
        val atBoundary = PomodoroLogic.advanceTo(started, nowMs = focus)

        assertEquals(PomodoroLogic.Phase.SHORT_BREAK, atBoundary.phase)
        assertEquals(1, atBoundary.focusCount)
        assertEquals(shortBreak, atBoundary.remainingAt(focus))

        // 连续三个边界：专注 → 短休 → 专注 → 短休，计数 2。
        val twoFocuses = PomodoroLogic.advanceTo(started, nowMs = focus + shortBreak + focus)
        assertEquals(PomodoroLogic.Phase.SHORT_BREAK, twoFocuses.phase)
        assertEquals(2, twoFocuses.focusCount)
        assertEquals(shortBreak, twoFocuses.remainingAt(focus + shortBreak + focus))
    }

    @Test
    fun thirdPhase_keepsCountingFocuses() {
        // 回归测试：循环里若用"已经被覆写成下一段"的 current.phase 去判断刚走完的是不是专注，
        // 专注数会一直停在 1（只有第一轮判对）。
        val started = PomodoroLogic.start(PomodoroLogic.initial(), nowMs = 0L)
        // 专注1 → 短休1 → 专注2 → 短休2（三次边界）
        val atThirdBoundary = PomodoroLogic.advanceTo(
            started,
            nowMs = focus + shortBreak + focus,
        )
        assertEquals(PomodoroLogic.Phase.SHORT_BREAK, atThirdBoundary.phase)
        assertEquals(2, atThirdBoundary.focusCount)
    }

    @Test
    fun shortBreakFinish_goesBackToFocus_withoutCounting() {
        val focusDone = PomodoroLogic.advanceTo(
            PomodoroLogic.start(PomodoroLogic.initial(), nowMs = 0L),
            nowMs = focus,
        )
        val breakDone = PomodoroLogic.advanceTo(focusDone, nowMs = focus + shortBreak)

        assertEquals(PomodoroLogic.Phase.FOCUS, breakDone.phase)
        assertEquals(focus, breakDone.durationMs)
        // 休息结束不算完成一次专注。
        assertEquals(1, breakDone.focusCount)
    }

    @Test
    fun fourthFocus_goesToLongBreak() {
        // 连续跑完 4 个专注（中间各接一次短休），第 4 次必须进长休。
        var state = PomodoroLogic.start(PomodoroLogic.initial(), nowMs = 0L)
        var now = 0L
        repeat(PomodoroLogic.FOCUSES_PER_LONG_BREAK) { index ->
            now += focus
            state = PomodoroLogic.advanceTo(state, now)
            val expectedPhase = if (index == PomodoroLogic.FOCUSES_PER_LONG_BREAK - 1) {
                PomodoroLogic.Phase.LONG_BREAK
            } else {
                PomodoroLogic.Phase.SHORT_BREAK
            }
            assertEquals(expectedPhase, state.phase)
            if (index < PomodoroLogic.FOCUSES_PER_LONG_BREAK - 1) {
                now += shortBreak
                state = PomodoroLogic.advanceTo(state, now)
                assertEquals(PomodoroLogic.Phase.FOCUS, state.phase)
            }
        }
        assertEquals(4, state.focusCount)
        assertEquals(longBreak, state.durationMs)
    }

    @Test
    fun overflow_carriesIntoNextPhase() {
        // 离开 3 分钟才回来：短休应该已经跑掉 3 分钟，而不是重新 5 分钟。
        // 注意“快照值”与“实时值”必须一致 —— 否则用户一暂停就会看到剩余量跳回 5 分钟。
        val started = PomodoroLogic.start(PomodoroLogic.initial(), nowMs = 0L)
        val now = focus + 3 * 60_000L
        val advanced = PomodoroLogic.advanceTo(started, nowMs = now)

        assertEquals(PomodoroLogic.Phase.SHORT_BREAK, advanced.phase)
        assertEquals(shortBreak - 3 * 60_000L, advanced.remainingAt(now))
        assertEquals(shortBreak - 3 * 60_000L, advanced.remainingMs)
        assertEquals(shortBreak - 3 * 60_000L, PomodoroLogic.pause(advanced, now).remainingMs)
    }

    @Test
    fun overflow_largerThanSeveralPhases_keepsAdvancing() {
        // 一次推进跨过好几段（模拟"离开很久才回来"）。注意正好落在某一段的**边界**上时，那一段
        // 算走完、已进入下一段（这正是 `<= 0` 判据的含义）：55:00 时第四段刚结束，下一段是短休。
        val started = PomodoroLogic.start(PomodoroLogic.initial(), nowMs = 0L)

        val onBoundary = PomodoroLogic.advanceTo(started, nowMs = focus + shortBreak + focus)
        assertEquals(PomodoroLogic.Phase.SHORT_BREAK, onBoundary.phase)
        assertEquals(2, onBoundary.focusCount)
        assertEquals(shortBreak, onBoundary.remainingAt(focus + shortBreak + focus))

        // 再多 1 分钟：短休已经跑掉 1 分钟（溢出带到下一段），计数仍是 2。
        val now = focus + shortBreak + focus + 60_000L
        val advanced = PomodoroLogic.advanceTo(started, nowMs = now)
        assertEquals(PomodoroLogic.Phase.SHORT_BREAK, advanced.phase)
        assertEquals(2, advanced.focusCount)
        assertEquals(shortBreak - 60_000L, advanced.remainingAt(now))
    }

    @Test
    fun advanceTo_neverReturnsAnExpiredRunningState() {
        // 这是本文件最容易再犯的错：跑满 MAX_CATCH_UP_PHASES 段之后直接返回，会留下一个
        // "running=true 但已经超时"的状态；下一次 advanceTo 又从这个状态继续推，越算越歪。
        val cycle = focus + shortBreak
        listOf(61L, 62L, 120L, 1000L).forEach { cycles ->
            val started = PomodoroLogic.start(PomodoroLogic.initial(), nowMs = 0L)
            val now = cycles * cycle
            val advanced = PomodoroLogic.advanceTo(started, now)
            assertTrue(
                "cycles=$cycles 应当留下一个还没到点的状态，实际 remaining=${advanced.remainingAt(now)}",
                advanced.remainingAt(now) > 0L,
            )
            assertEquals(advanced.durationMs, advanced.remainingMs)
        }
    }

    @Test
    fun overflow_beyondCatchUpLimit_resyncsToAFullPhase() {
        // 面板挂了一整天：宁可"这一段从头开始"，也不能返回负数剩余量、更不能死循环。
        val cycle = focus + shortBreak
        val started = PomodoroLogic.start(PomodoroLogic.initial(), nowMs = 0L)
        val advanced = PomodoroLogic.advanceTo(started, nowMs = 1000L * cycle)

        assertTrue(advanced.running)
        assertTrue(advanced.remainingMs > 0L)
        assertEquals(advanced.durationMs, advanced.remainingMs)
        // 收敛成"当前这一段从头开始"：剩余量就是该段的完整时长。
        assertEquals(advanced.phase.durationMs(), advanced.remainingMs)
    }

    @Test
    fun advanceTo_whenNotRunning_isNoOp() {
        val idle = PomodoroLogic.initial()
        assertEquals(idle, PomodoroLogic.advanceTo(idle, nowMs = 999_999_999L))
    }

    @Test
    fun progress_runsFromZeroToOneAndClamps() {
        val started = PomodoroLogic.start(PomodoroLogic.initial(), nowMs = 0L)
        assertEquals(0f, PomodoroLogic.progress(started, 0L), 1e-6f)
        assertEquals(0.5f, PomodoroLogic.progress(started, focus / 2), 1e-6f)
        assertEquals(1f, PomodoroLogic.progress(started, focus), 1e-6f)
        assertEquals(1f, PomodoroLogic.progress(started, focus * 3), 1e-6f)
    }

    @Test
    fun skip_goesToNextPhase_withoutCountingOrRunning() {
        val running = PomodoroLogic.start(PomodoroLogic.initial(), nowMs = 0L)
        val skipped = PomodoroLogic.skip(running)

        assertEquals(PomodoroLogic.Phase.SHORT_BREAK, skipped.phase)
        assertEquals(shortBreak, skipped.remainingMs)
        // 跳过的专注不算完成：计数不动，也不会因此触发长休。
        assertEquals(0, skipped.focusCount)
        assertFalse(skipped.running)
    }

    @Test
    fun skip_fromFreshFocus_goesToShortBreak_notLongBreak() {
        // 回归测试：计数为 0 时 `0 % 4 == 0` 会把"第一次跳过专注"直接送进长休（曾经真的写错过）。
        val skipped = PomodoroLogic.skip(PomodoroLogic.initial())
        assertEquals(PomodoroLogic.Phase.SHORT_BREAK, skipped.phase)
    }

    @Test
    fun skip_fromBreakAfterFullCycle_goesToLongBreak() {
        // 计数已经是 4（刚满一轮）时，跳过专注就该进长休 —— 与自动接续的规则一致。
        val state = PomodoroLogic.initial().copy(focusCount = 4)
        val skipped = PomodoroLogic.skip(state)
        assertEquals(PomodoroLogic.Phase.LONG_BREAK, skipped.phase)
    }

    @Test
    fun skip_fromBreak_returnsToFocus() {
        val inBreak = PomodoroLogic.select(PomodoroLogic.initial(), PomodoroLogic.Phase.SHORT_BREAK)
        val skipped = PomodoroLogic.skip(inBreak)
        assertEquals(PomodoroLogic.Phase.FOCUS, skipped.phase)
        assertEquals(focus, skipped.remainingMs)
    }

    @Test
    fun start_afterPhaseFinished_restartsTheCurrentPhase() {
        // 一段跑完（剩余 0）后再按播放：按本段时长重来，而不是立刻又"到点"。
        val done = PomodoroLogic.initial().copy(
            phase = PomodoroLogic.Phase.SHORT_BREAK,
            durationMs = shortBreak,
            remainingMs = 0L,
        )
        val restarted = PomodoroLogic.start(done, nowMs = 1_000L)
        assertTrue(restarted.running)
        assertEquals(shortBreak, restarted.remainingMs)
        assertEquals(1_000L + shortBreak, restarted.endsAtMs)
    }

    @Test
    fun state_isImmutableValue_copyPerTransition() {
        // 目标：状态是值对象（data class），每次转换都产出新实例 —— 否则 Compose 的
        // `mutableStateOf(current)` 不会触发重组，"到点自动接续"在界面上就看不见。
        val before = PomodoroLogic.start(PomodoroLogic.initial(), nowMs = 0L)
        val after = PomodoroLogic.advanceTo(before, nowMs = focus)
        assertNotEquals(before, after)
        assertEquals(PomodoroLogic.Phase.FOCUS, before.phase)
    }

    // ------------------------------------------------------------ 走完的段（补算一次跨过好几段）

    @Test
    fun advanceCompleted_reportsEveryFinishedPhaseWithItsRealEndTime() {
        // 专注结束后：走完的段是"专注"，结束时刻是它**真正**结束的那一刻（不是"发现它的那一刻"）。
        val started = PomodoroLogic.start(PomodoroLogic.initial(), nowMs = 1_000L)
        val result = PomodoroLogic.advanceCompleted(started, nowMs = 1_000L + focus + 90_000L)

        assertEquals(1, result.completed.size)
        val finished = result.completed.single()
        assertEquals(PomodoroLogic.Phase.FOCUS, finished.phase)
        assertEquals(focus, finished.durationMs)
        assertEquals(1_000L + focus, finished.endedAtEpochMs)
        assertEquals(PomodoroLogic.Phase.SHORT_BREAK, result.state.phase)
    }

    @Test
    fun advanceCompleted_acrossManyPhases_listsThemInOrder() {
        // 面板很久没打开：一次补算跨过 专注→短休→专注→短休，四段都要按先后被回报。
        val started = PomodoroLogic.start(PomodoroLogic.initial(), nowMs = 0L)
        val now = focus + shortBreak + focus + shortBreak
        val result = PomodoroLogic.advanceCompleted(started, nowMs = now)

        assertEquals(
            listOf(
                PomodoroLogic.Phase.FOCUS,
                PomodoroLogic.Phase.SHORT_BREAK,
                PomodoroLogic.Phase.FOCUS,
                PomodoroLogic.Phase.SHORT_BREAK,
            ),
            result.completed.map { it.phase },
        )
        // 结束时刻要**逐段递增**（不是全都挤在"发现它的那一刻"）。
        assertEquals(
            listOf(focus, focus + shortBreak, focus + shortBreak + focus, now),
            result.completed.map { it.endedAtEpochMs },
        )
        assertEquals(2, result.state.focusCount)
    }

    @Test
    fun advanceCompleted_notRunning_reportsNothing() {
        val idle = PomodoroLogic.initial()
        val result = PomodoroLogic.advanceCompleted(idle, nowMs = 999_999_999L)
        assertTrue(result.completed.isEmpty())
        assertEquals(idle, result.state)
    }

    @Test
    fun advanceCompleted_beyondCatchUpLimit_leavesAHealthyState() {
        // 挂太久时走兜底分支：留下的状态必须是**没到点**的（否则下一次推进又从一个已超时的状态继续算）。
        val cycle = focus + shortBreak
        val started = PomodoroLogic.start(PomodoroLogic.initial(), nowMs = 0L)
        val now = 1000L * cycle
        val result = PomodoroLogic.advanceCompleted(started, nowMs = now)

        assertTrue(result.state.remainingAt(now) > 0L)
        assertEquals(result.state.durationMs, result.state.remainingMs)
    }

    @Test
    fun adoptIfLater_keepsTheMoreAdvancedState() {
        // 补算出来的状态与界面手里的状态可能不同步，只认"时间上更靠后"的那一份，别把用户刚点的操作盖掉。
        val userJustStarted = PomodoroLogic.start(PomodoroLogic.initial(), nowMs = 1_000L)
        val stale = PomodoroLogic.initial()

        assertEquals(userJustStarted, PomodoroLogic.adoptIfLater(userJustStarted, stale, nowMs = 1_000L))
        // 反过来：另一份更靠后（期间被补算推进过），就该采用它。
        val advanced = PomodoroLogic.advanceTo(userJustStarted, nowMs = 1_000L + focus)
        assertEquals(advanced, PomodoroLogic.adoptIfLater(userJustStarted, advanced, nowMs = 1_000L + focus))
    }
}
