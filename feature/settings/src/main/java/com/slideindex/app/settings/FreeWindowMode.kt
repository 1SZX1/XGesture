package com.slideindex.app.settings

import android.os.Build

enum class FreeWindowMode(
    val id: Int,
    val windowingMode: Int,
) {
    STANDARD(0, 5),
    COLOROS(1, 100),
    MAGICOS(2, 102),
    ORIGINOS(3, 106),
    FLYME(4, 11),
    NUBIA(5, 5),

    /**
     * 米窗（Sunshine，需自装 `com.sunshine.freeform`）。
     *
     * 它不走 `windowingMode`：改为向米窗发广播请它开小窗，所以这里的 [windowingMode] 只是
     * 「米窗未安装时回落到标准自由窗口」用的兜底值。因为不消费 `launchBounds`，
     * 该模式下尺寸与跟随开关在设置页一并禁用（见 [ignoresLaunchBounds]）。
     */
    SUNSHINE(6, 5),
    ;

    companion object {
        fun fromId(id: Int): FreeWindowMode =
            entries.firstOrNull { it.id == id } ?: detectDefault()

        fun detectDefault(): FreeWindowMode {
            val manufacturer = Build.MANUFACTURER.lowercase()
            val brand = Build.BRAND.lowercase()
            return when {
                manufacturer.contains("nubia") || brand.contains("nubia") ||
                    manufacturer.contains("redmagic") || brand.contains("redmagic") -> NUBIA
                manufacturer.contains("meizu") || brand.contains("meizu") -> FLYME
                manufacturer.contains("xiaomi") || brand.contains("redmi") -> STANDARD
                manufacturer.contains("oppo") || manufacturer.contains("realme") ||
                    brand.contains("oppo") || brand.contains("realme") -> COLOROS
                manufacturer.contains("vivo") || brand.contains("vivo") -> ORIGINOS
                manufacturer.contains("honor") || manufacturer.contains("huawei") ||
                    brand.contains("honor") || brand.contains("huawei") -> MAGICOS
                else -> STANDARD
            }
        }
    }
}

fun AppSettings.resolvedFreeWindowMode(): FreeWindowMode = FreeWindowMode.fromId(freeWindowModeId)

fun FreeWindowMode.usesNubiaFreeformIdentifier(): Boolean = this == FreeWindowMode.NUBIA

/** 走米窗广播而不是 `ActivityOptions.setLaunchWindowingMode`。 */
fun FreeWindowMode.usesSunshineBroadcast(): Boolean = this == FreeWindowMode.SUNSHINE

/**
 * 该模式不消费 `launchBounds`，所以尺寸 / 位置 / 跟随触发对它都没有意义：
 * 米窗由米窗自己决定窗口几何。
 */
fun FreeWindowMode.ignoresLaunchBounds(): Boolean = this == FreeWindowMode.SUNSHINE
