package com.slideindex.app.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class LastMessageUnlockPolicyTest {

    @Test
    fun shouldOpen_whenEnabledAndPendingMessageExists() {
        assertTrue(
            shouldAutoOpenLastMessageOnUnlock(
                settings = MessageSettings(enabled = true, openLastMessageOnUnlock = true),
                pendingUnlockMessage = sampleData("k1"),
            ),
        )
    }

    @Test
    fun shouldNotOpen_whenDisabled() {
        assertFalse(
            shouldAutoOpenLastMessageOnUnlock(
                settings = MessageSettings(enabled = true, openLastMessageOnUnlock = false),
                pendingUnlockMessage = sampleData("k1"),
            ),
        )
    }

    @Test
    fun shouldNotOpen_whenNoPendingMessage() {
        assertFalse(
            shouldAutoOpenLastMessageOnUnlock(
                settings = MessageSettings(enabled = true, openLastMessageOnUnlock = true),
                pendingUnlockMessage = null,
            ),
        )
    }

    @Test
    fun openDelay_defaultsTo900ms() {
        assertEquals(
            MessageSettings.DEFAULT_UNLOCK_OPEN_DELAY_MS.toLong(),
            resolveUnlockOpenDelayMs(MessageSettings()),
        )
    }

    @Test
    fun openDelay_keepsUserValue() {
        assertEquals(150L, resolveUnlockOpenDelayMs(MessageSettings(unlockOpenDelayMs = 150)))
    }

    @Test
    fun openDelay_clampsOutOfRangeValues() {
        assertEquals(0L, resolveUnlockOpenDelayMs(MessageSettings(unlockOpenDelayMs = -50)))
        assertEquals(
            MessageSettings.MAX_UNLOCK_OPEN_DELAY_MS.toLong(),
            resolveUnlockOpenDelayMs(MessageSettings(unlockOpenDelayMs = 9_999)),
        )
    }

    private fun sampleData(key: String): NotificationData = NotificationData(
        packageName = "com.example.app",
        key = key,
        title = "Title",
        content = "Content",
        largeIcon = null,
        appIcon = null,
        contentIntent = null,
        postTime = 1L,
    )
}
