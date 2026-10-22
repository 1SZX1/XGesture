package com.slideindex.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PictureInPicture
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.Security
import androidx.compose.ui.graphics.vector.ImageVector

internal fun homeLeadingIcon(outlined: Boolean, filled: ImageVector, outlinedIcon: ImageVector): ImageVector =
    if (outlined) outlinedIcon else filled

internal object HomeLeadingIcons {
    fun gesture(outlined: Boolean) = homeLeadingIcon(outlined, Icons.Default.Gesture, Icons.Outlined.Gesture)
    fun batteryKeepAlive(outlined: Boolean) =
        if (outlined) HomeCustomIcons.BatteryKeepAliveOutlined else Icons.Default.BatteryChargingFull
    fun privilegeMode(outlined: Boolean) =
        homeLeadingIcon(outlined, Icons.Default.Security, Icons.Outlined.Security)
    fun cornerWheel(outlined: Boolean) = HomeCustomIcons.CornerWheel
    fun triggerCollection(outlined: Boolean) =
        homeLeadingIcon(outlined, Icons.Default.TouchApp, Icons.Outlined.TouchApp)

    /**
     * 「按应用分档」。
     *
     * ⚠️ 不能和 [triggerCollection] 共用一个图标：两行在同一张卡里相邻，图标一样就分不出谁是谁
     * （用户提过）。也不能用 androidx 的 `Icons.*.DashboardCustomize`——旧 Material Icons 里那个
     * 画的是"九宫格 + 加号"，而用户要的是 Material Symbols 新版"九宫格 + 铅笔"，所以自绘见
     * [HomeCustomIcons.DashboardCustomizeOutlined]。
     */
    fun gestureAppProfiles(outlined: Boolean) = HomeCustomIcons.DashboardCustomizeOutlined
    fun floatBall(outlined: Boolean) =
        homeLeadingIcon(outlined, Icons.Default.RadioButtonChecked, Icons.Outlined.Circle)
    fun gestureAngle(outlined: Boolean) = homeLeadingIcon(outlined, Icons.Default.Tune, Icons.Outlined.Tune)
    fun gestureAnimation(outlined: Boolean) =
        homeLeadingIcon(outlined, Icons.Default.Animation, Icons.Outlined.Animation)
    fun systemBackGestureWidth(outlined: Boolean) =
        homeLeadingIcon(outlined, Icons.AutoMirrored.Filled.ArrowBack, Icons.AutoMirrored.Outlined.ArrowBack)
    fun excludedApps(outlined: Boolean) = homeLeadingIcon(outlined, Icons.Default.Block, Icons.Outlined.Block)
    fun previousAppBlacklist(outlined: Boolean) =
        homeLeadingIcon(outlined, Icons.Default.SwapHoriz, Icons.Outlined.SwapHoriz)
    fun hideTriggerLandscape(outlined: Boolean) =
        homeLeadingIcon(outlined, Icons.Default.ScreenRotation, Icons.Outlined.ScreenRotation)
    fun hideTriggerLock(outlined: Boolean) = homeLeadingIcon(outlined, Icons.Default.Lock, Icons.Outlined.Lock)
    fun hideTriggerLauncher(outlined: Boolean) = homeLeadingIcon(outlined, Icons.Default.Home, Icons.Outlined.Home)
    fun freeWindow(outlined: Boolean) =
        homeLeadingIcon(outlined, Icons.Default.PictureInPictureAlt, Icons.Outlined.PictureInPicture)
    fun keyboardTrigger(outlined: Boolean) =
        homeLeadingIcon(outlined, Icons.Default.Keyboard, Icons.Outlined.Keyboard)
    fun haptic(outlined: Boolean) = homeLeadingIcon(outlined, Icons.Default.Vibration, Icons.Outlined.Vibration)
    fun themeMode(outlined: Boolean) =
        homeLeadingIcon(outlined, Icons.Default.BrightnessMedium, Icons.Outlined.Brightness6)
    fun themeSeedColor(outlined: Boolean) = homeLeadingIcon(outlined, Icons.Default.Colorize, Icons.Outlined.Colorize)
    fun themePalette(outlined: Boolean) = homeLeadingIcon(outlined, Icons.Default.Style, Icons.Outlined.Style)
    fun themeColorSpec(outlined: Boolean) = homeLeadingIcon(outlined, Icons.Default.Contrast, Icons.Outlined.Contrast)
}
