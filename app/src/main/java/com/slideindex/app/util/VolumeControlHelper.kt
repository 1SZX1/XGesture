package com.slideindex.app.util

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.slideindex.app.privilege.PrivilegeGateway
import kotlin.math.abs
import kotlin.math.roundToInt

object VolumeControlHelper {
    enum class Stream {
        MEDIA,
        RING,
        NOTIFICATION,
        ALARM,
    }

    fun hasAccess(context: Context): Boolean =
        PermissionHelper.hasNotificationPolicyAccess(context.applicationContext)

    fun readRingerMode(context: Context): Int {
        val manager = audioManager(context) ?: return AudioManager.RINGER_MODE_NORMAL
        return manager.ringerMode
    }

    fun cycleRingerMode(context: Context): Int? {
        if (!hasAccess(context)) return null
        val manager = audioManager(context) ?: return null
        val nextMode = when (manager.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        return runCatching {
            manager.ringerMode = nextMode
            nextMode
        }.getOrElse { error ->
            Log.w(TAG, "cycle ringer mode failed", error)
            null
        }
    }

    fun readInterruptionFilter(context: Context): Int {
        if (!hasAccess(context)) return NotificationManager.INTERRUPTION_FILTER_ALL
        val manager = notificationManager(context) ?: return NotificationManager.INTERRUPTION_FILTER_ALL
        return manager.currentInterruptionFilter
    }

    fun isDndEnabled(context: Context): Boolean = isDndFilter(readInterruptionFilter(context))

    fun isDndFilter(filter: Int): Boolean = when (filter) {
        NotificationManager.INTERRUPTION_FILTER_ALL,
        NotificationManager.INTERRUPTION_FILTER_UNKNOWN,
        -> false
        else -> true
    }

    /**
     * Toggles system Do Not Disturb.
     *
     * Uses `cmd notification set_dnd` when privileged shell access is available (Flyme/OEM
     * often ignores [NotificationManager.setInterruptionFilter]), otherwise falls back to the API.
     *
     * @return the current interruption filter after the attempt when the enabled state changed,
     *         or null when access is missing, the call failed, or the system state did not change.
     */
    fun toggleDnd(context: Context): Int? {
        if (!hasAccess(context)) return null
        val appContext = context.applicationContext
        val beforeEnabled = isDndEnabled(appContext)
        val targetFilter = if (beforeEnabled) {
            NotificationManager.INTERRUPTION_FILTER_ALL
        } else {
            NotificationManager.INTERRUPTION_FILTER_PRIORITY
        }
        val after = setInterruptionFilter(appContext, targetFilter) ?: return null
        val afterEnabled = isDndFilter(after)
        if (beforeEnabled == afterEnabled) {
            Log.w(TAG, "toggle dnd had no effect after=$after")
            return null
        }
        Log.i(TAG, "toggle dnd ok after=$after")
        return after
    }

    /**
     * Applies the requested interruption filter via shell when available, otherwise via API.
     *
     * @return the filter read back after the attempt when it matches the request or DND state,
     *         or null when access is missing or the call had no effect.
     */
    fun setInterruptionFilter(context: Context, filter: Int): Int? {
        if (!hasAccess(context)) return null
        val appContext = context.applicationContext
        if (TaskManagerUtil.hasPermission()) {
            setInterruptionFilterViaShell(appContext, filter)?.let { return it }
        }
        return setInterruptionFilterViaNotificationManager(appContext, filter)
    }

    fun ensureDndEnabled(context: Context): Boolean {
        if (isDndEnabled(context)) return true
        return setInterruptionFilter(
            context,
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
        ) != null
    }

    private fun setInterruptionFilterViaShell(context: Context, filter: Int): Int? {
        val beforeEnabled = isDndEnabled(context)
        val targetEnabled = isDndFilter(filter)
        val mode = shellModeForInterruptionFilter(filter)
        val result = TaskManagerUtil.runShellCommandLine(
            command = "cmd notification set_dnd $mode",
            useRoot = PrivilegeGateway.isRootMode(),
        )
        if (!result.success) {
            Log.w(
                TAG,
                "set dnd shell failed: mode=$mode filter=$filter exit=${result.exitCode} output=${result.output}",
            )
            return null
        }
        return readInterruptionFilterIfChanged(context, beforeEnabled, "shell:$mode")
            ?: readInterruptionFilter(context).takeIf { isDndFilter(it) == targetEnabled }
    }

    private fun setInterruptionFilterViaNotificationManager(context: Context, filter: Int): Int? {
        val manager = notificationManager(context) ?: return null
        return runCatching {
            val beforeEnabled = isDndEnabled(context)
            manager.setInterruptionFilter(filter)
            readInterruptionFilterIfChanged(context, beforeEnabled, "api:$filter")
                ?: readInterruptionFilter(context).takeIf { it == filter }
        }.getOrElse { error ->
            Log.w(TAG, "set dnd api failed filter=$filter", error)
            null
        }
    }

    private fun shellModeForInterruptionFilter(filter: Int): String = when (filter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "silent"
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
        else -> if (isDndFilter(filter)) "priority" else "off"
    }

    private fun readInterruptionFilterIfChanged(
        context: Context,
        beforeEnabled: Boolean,
        via: String,
    ): Int? {
        val after = readInterruptionFilter(context)
        val afterEnabled = isDndFilter(after)
        if (beforeEnabled == afterEnabled) {
            Log.w(TAG, "toggle dnd had no effect via $via after=$after")
            return null
        }
        Log.i(TAG, "toggle dnd ok via $via after=$after")
        return after
    }

    fun readFraction(context: Context, stream: Stream): Float {
        val manager = audioManager(context) ?: return 0f
        val audioStream = toAudioStream(stream)
        val min = streamMin(manager, audioStream)
        val max = streamMax(manager, audioStream, min)
        return (manager.getStreamVolume(audioStream).coerceIn(min, max) - min).toFloat() / (max - min)
    }

    fun setFraction(context: Context, stream: Stream, fraction: Float) {
        if (stream.requiresPolicyAccess() && !hasAccess(context)) return
        val manager = audioManager(context) ?: return
        val audioStream = toAudioStream(stream)
        val min = streamMin(manager, audioStream)
        val max = streamMax(manager, audioStream, min)
        val level = (fraction.coerceIn(0f, 1f) * (max - min)).roundToInt() + min
        applyStreamLevel(manager, audioStream, level, min, max)
    }

    /**
     * 分层写入音量：
     *
     * 1. 先用 [AudioManager.setStreamVolume] 做绝对设置 —— 正常 ROM 一次到位；
     * 2. 立即回读校验：ColorOS 等 ROM 对非前台应用会**静默忽略**绝对设置（不抛异常、值不变），
     *    因此是否生效只能靠回读判断，不能靠 try/catch；
     * 3. 未生效则改用相对步进 [AudioManager.adjustStreamVolume]（ADJUST_RAISE / ADJUST_LOWER）
     *    一格一格补齐，单次最多 [MAX_ADJUST_STEPS] 步，且每一步都回读，避免走过头。
     *
     * 正常 ROM 上第 2 步就相等并直接返回，行为与"只做绝对设置"完全一致（只多一次读取）。
     *
     * @return 实际生效的音量级数
     */
    fun applyStreamLevel(manager: AudioManager, stream: Int, target: Int, min: Int, max: Int): Int {
        val want = target.coerceIn(min, max)
        if (manager.getStreamVolume(stream).coerceIn(min, max) == want) return want

        try {
            manager.setStreamVolume(stream, want, 0)
        } catch (error: Exception) {
            Log.w(TAG, "setStreamVolume(stream=$stream, want=$want) threw", error)
        }

        var actual = manager.getStreamVolume(stream).coerceIn(min, max)
        if (actual == want) return actual
        Log.i(TAG, "setStreamVolume no effect stream=$stream want=$want actual=$actual; stepping")

        val steps = minOf(abs(want - actual), MAX_ADJUST_STEPS)
        var done = 0
        while (done < steps && actual != want) {
            try {
                manager.adjustStreamVolume(
                    stream,
                    if (want > actual) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                    0,
                )
                actual = manager.getStreamVolume(stream).coerceIn(min, max)
                done++
            } catch (error: Exception) {
                Log.w(TAG, "adjustStreamVolume step=$done failed stream=$stream", error)
                return actual
            }
        }
        return actual
    }

    private fun streamMin(manager: AudioManager, audioStream: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.getStreamMinVolume(audioStream)
        } else {
            0
        }

    private fun streamMax(manager: AudioManager, audioStream: Int, min: Int): Int =
        manager.getStreamMaxVolume(audioStream).coerceAtLeast(min + 1)

    fun toAudioStream(stream: Stream): Int = when (stream) {
        Stream.MEDIA -> AudioManager.STREAM_MUSIC
        Stream.RING -> AudioManager.STREAM_RING
        Stream.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
        Stream.ALARM -> AudioManager.STREAM_ALARM
    }

    private fun Stream.requiresPolicyAccess(): Boolean =
        this == Stream.RING || this == Stream.NOTIFICATION

    private fun audioManager(context: Context): AudioManager? =
        context.applicationContext.getSystemService(AudioManager::class.java)

    private fun notificationManager(context: Context): NotificationManager? =
        context.applicationContext.getSystemService(NotificationManager::class.java)

    private const val TAG = "VolumeControlHelper"

    /** 单次应用最多补多少级（经验值：24 级足够覆盖常见 ROM 的 0..160 档位跨度）。 */
    private const val MAX_ADJUST_STEPS = 24
}
