package com.slideindex.app.screencontrol

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.slideindex.app.di.OverlayDependencyAccess
import com.slideindex.app.overlay.DefaultGestureRecorderColorArgb
import com.slideindex.app.overlay.FloatingPointerTrailPoint
import com.slideindex.app.overlay.QcPointerClickEasing
import com.slideindex.app.overlay.drawFloatingPointer
import com.slideindex.app.overlay.drawFloatingPointerRipple
import com.slideindex.app.overlay.drawFloatingPointerTrail
import com.slideindex.app.overlay.drawQcGestureRecorderPointer
import com.slideindex.app.overlay.floatingPointerClickAnimDurationMs
import com.slideindex.app.settings.AppSettings
import kotlinx.coroutines.delay

/**
 * 「屏幕控制」指针：直接复用悬浮指针的绘制/动画链路。
 *
 * - 光标本体：`drawFloatingPointer`，`clickProgress` 由和悬浮指针相同的
 *   `floatingPointerClickAnimDurationMs + QcPointerClickEasing` 驱动（点击/长按缩成点）
 * - 轨迹：`drawFloatingPointerTrail`
 * - 点击涟漪：`drawFloatingPointerRipple`，时间和缓动和悬浮指针一致
 */
internal class ScreenControlPointerState {

    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)

    var clickGeneration by mutableIntStateOf(0)
    var rippleGeneration by mutableIntStateOf(0)
    var hold by mutableStateOf(false)

    val trail = mutableStateListOf<FloatingPointerTrailPoint>()

    fun playClick() {
        clickGeneration++
        rippleGeneration++
    }

    fun addTrail(now: Long = SystemClock.uptimeMillis()) {
        trail.add(FloatingPointerTrailPoint(x, y, now))
        while (trail.size > 24) trail.removeAt(0)
        val cutoff = now - 500L
        while (trail.isNotEmpty() && trail.first().timeMs < cutoff) trail.removeAt(0)
    }

    fun clearTrail() {
        trail.clear()
    }
}

@Composable
internal fun ScreenControlPointerCanvas(state: ScreenControlPointerState) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    var settings by remember { mutableStateOf(AppSettings()) }
    LaunchedEffect(Unit) {
        OverlayDependencyAccess.overlayDependencies(context)
            ?.settingsRepository
            ?.settings
            ?.collect { settings = it }
    }

    val clickAnim = remember { Animatable(0f) }
    val rippleAnim = remember { Animatable(0f) }
    val holdAnim = remember { Animatable(0f) }
    val holding = state.hold

    LaunchedEffect(holding) {
        if (holding) {
            holdAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
            )
        } else {
            holdAnim.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
            )
        }
    }

    LaunchedEffect(state.clickGeneration, settings.floatingPointerRippleDurationMs) {
        if (state.clickGeneration <= 0) return@LaunchedEffect
        val generation = state.clickGeneration
        clickAnim.snapTo(0f)
        clickAnim.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = floatingPointerClickAnimDurationMs(settings, density),
                easing = QcPointerClickEasing,
            ),
        )
        if (state.clickGeneration == generation) {
            clickAnim.snapTo(0f)
        }
    }

    LaunchedEffect(state.rippleGeneration, settings.floatingPointerRippleDurationMs) {
        if (state.rippleGeneration <= 0) return@LaunchedEffect
        val generation = state.rippleGeneration
        rippleAnim.snapTo(0f)
        rippleAnim.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = settings.floatingPointerRippleDurationMs,
                easing = FastOutSlowInEasing,
            ),
        )
        if (state.rippleGeneration == generation) {
            rippleAnim.snapTo(0f)
        }
    }

    LaunchedEffect(state.trail.size) {
        if (state.trail.isNotEmpty()) {
            delay(settings.floatingPointerTrailDurationMs.coerceAtLeast(80).toLong() + 80L)
            state.clearTrail()
        }
    }

    val now = SystemClock.uptimeMillis()
    val pointerX = state.x
    val pointerY = state.y
    val trailPoints = state.trail.toList()
    val clickValue = clickAnim.value
    val rippleValue = rippleAnim.value
    val holdValue = holdAnim.value
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawFloatingPointerTrail(
            trailPoints = trailPoints,
            settings = settings,
            lifespanMs = settings.floatingPointerTrailDurationMs.coerceAtLeast(50).toLong(),
            nowMs = now,
            trailColorArgb = settings.floatingPointerTrailColorArgb,
        )
        drawFloatingPointerRipple(
            center = Offset(pointerX, pointerY),
            progress = rippleValue,
            rippleColor = Color(settings.floatingPointerRippleColorArgb),
            rippleSizePx = settings.floatingPointerRippleSizeDp * density,
        )
        if (holdValue > 0.001f) {
            // 和悬浮指针长按同一套：recorderProgress=1 时只剩一个点。
            drawQcGestureRecorderPointer(
                center = Offset(pointerX, pointerY),
                settings = settings,
                recorderColor = Color(DefaultGestureRecorderColorArgb),
                recorderProgress = holdValue,
                ringColor = Color(settings.floatingPointerRingColorArgb),
                fillColor = Color(settings.floatingPointerFillColorArgb),
                dotColor = Color(settings.floatingPointerDotColorArgb),
            )
        } else {
            drawFloatingPointer(
                center = Offset(pointerX, pointerY),
                settings = settings,
                clickProgress = clickValue,
            )
        }
    }
}
