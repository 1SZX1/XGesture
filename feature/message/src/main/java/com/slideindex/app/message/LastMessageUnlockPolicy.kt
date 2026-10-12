package com.slideindex.app.message

/** “解锁后进入最后一条消息”的决策逻辑（纯函数，便于测试）。 */
internal fun shouldAutoOpenLastMessageOnUnlock(
    settings: MessageSettings,
    pendingUnlockMessage: NotificationData?,
): Boolean = settings.enabled && settings.openLastMessageOnUnlock && pendingUnlockMessage != null

/**
 * 解锁后等待多久再打开（毫秒）：用户可调，越界数据一律收敛到可执行区间。
 *
 * 默认 [MessageSettings.DEFAULT_UNLOCK_OPEN_DELAY_MS]（600ms）是"避开解锁/桌面动画"的经验值；
 * 0 表示立即打开，代价是会与解锁动画竞争（部分 ROM 的 ACTION_USER_PRESENT 早于动画结束）。
 */
internal fun resolveUnlockOpenDelayMs(settings: MessageSettings): Long =
    settings.unlockOpenDelayMs
        .coerceIn(0, MessageSettings.MAX_UNLOCK_OPEN_DELAY_MS)
        .toLong()
