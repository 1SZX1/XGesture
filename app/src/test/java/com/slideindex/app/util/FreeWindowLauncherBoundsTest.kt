package com.slideindex.app.util

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.TypedValue
import com.slideindex.app.overlay.PanelSide
import com.slideindex.app.settings.AppSettings
import com.slideindex.app.settings.FreeWindowMode
import com.slideindex.app.settings.FreeWindowSettings
import com.slideindex.app.settings.ignoresLaunchBounds
import com.slideindex.app.settings.usesSunshineBroadcast
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

/**
 * 小窗 launch bounds 的三条契约：固定百分比、底部安全边距、跟随触发位置。
 *
 * 这里刻意只断言**可独立解释的性质**（贴边距、不越界、居中），不照抄实现里的算式，
 * 免得测试退化成"实现怎么写就怎么对"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class FreeWindowLauncherBoundsTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private val widthPx: Int get() = context.resources.displayMetrics.widthPixels
    private val heightPx: Int get() = context.resources.displayMetrics.heightPixels

    /** 20mm 换算成像素；安全边距取 `max(状态栏, 20mm)`，所以它一定是下界。 */
    private val safeBottomLowerBoundPx: Int
        get() = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_MM,
            20f,
            context.resources.displayMetrics,
        ).toInt()

    @After
    fun clearAnchor() {
        FreeWindowTriggerAnchor.clear()
    }

    private fun settings(
        mode: FreeWindowMode = FreeWindowMode.STANDARD,
        widthFraction: Float = 0.8f,
        heightFraction: Float = 0.55f,
        leftFraction: Float = 0.1f,
        topFraction: Float = 0.15f,
        followTrigger: Boolean = false,
    ): AppSettings = AppSettings(
        freeWindow = FreeWindowSettings(
            freeWindowEnabled = true,
            freeWindowModeId = mode.id,
            freeWindowWidthFraction = widthFraction,
            freeWindowHeightFraction = heightFraction,
            freeWindowLeftFraction = leftFraction,
            freeWindowTopFraction = topFraction,
            freeWindowFollowTriggerSide = followTrigger,
        ),
    )

    /** 默认配置放得下：结果就是配置的百分比（安全边距不该动它）。 */
    @Test
    fun defaultLayoutIsUntouchedBySafeMargin() {
        val bounds = FreeWindowLauncher.launchBounds(context, settings())

        assertEquals((widthPx * 0.1f).toInt(), bounds.left)
        assertEquals((heightPx * 0.15f).toInt(), bounds.top)
        assertEquals((widthPx * 0.8f).toInt(), bounds.width())
        assertEquals((heightPx * 0.55f).toInt(), bounds.height())
    }

    /** 配置高到会压住底部手势条 / 输入法区：高度与位置都被压回安全区之内。 */
    @Test
    fun tallLayoutIsClampedAboveBottomSafeMargin() {
        val bounds = FreeWindowLauncher.launchBounds(
            context,
            settings(widthFraction = 0.8f, heightFraction = 0.9f, topFraction = 0.5f),
        )

        assertTrue(
            "bottom=${bounds.bottom} 应不超过 屏幕高-安全边距(${heightPx - safeBottomLowerBoundPx})",
            bounds.bottom <= heightPx - safeBottomLowerBoundPx,
        )
        assertTrue(bounds.height() <= heightPx - safeBottomLowerBoundPx)
    }

    /** 横屏同样受安全边距约束（横屏高度更矮，安全区占比更大）。 */
    @Test
    @Config(sdk = [33], application = Application::class, qualifiers = "w891dp-h411dp-land-xxhdpi")
    fun landscapeLayoutRespectsSafeMargin() {
        val bounds = FreeWindowLauncher.launchBounds(
            context,
            settings(heightFraction = 0.9f, topFraction = 0.6f),
        )

        assertTrue(
            "bottom=${bounds.bottom} 应不超过 屏幕高-安全边距(${heightPx - safeBottomLowerBoundPx})",
            bounds.bottom <= heightPx - safeBottomLowerBoundPx,
        )
    }

    /** 跟随右触发：窗口贴右，右侧留白 = 配置的贴边距（也就是配置里那个"离边的距离"）。 */
    @Test
    fun followTriggerOnRightEdgeDocksRight() {
        val base = settings(
            widthFraction = 0.5f,
            heightFraction = 0.3f,
            leftFraction = 0.1f,
            topFraction = 0.15f,
            followTrigger = true,
        )
        val withoutFollow = FreeWindowLauncher.launchBounds(context, settings(
            widthFraction = 0.5f,
            heightFraction = 0.3f,
            leftFraction = 0.1f,
            topFraction = 0.15f,
            followTrigger = false,
        ))

        FreeWindowTriggerAnchor.record(PanelSide.RIGHT, heightPx / 2f)
        val bounds = FreeWindowLauncher.launchBounds(context, base)

        assertEquals((widthPx * 0.1f).toInt(), widthPx - bounds.right)
        assertNotEquals(withoutFollow.left, bounds.left)
        assertEquals(withoutFollow.width(), bounds.width())
    }

    /** 跟随左触发：贴左。配置本身已经贴左时，位置与不跟随一致。 */
    @Test
    fun followTriggerOnLeftEdgeDocksLeft() {
        val config = settings(
            widthFraction = 0.5f,
            heightFraction = 0.3f,
            leftFraction = 0.1f,
            topFraction = 0.15f,
            followTrigger = true,
        )
        val withoutFollow = FreeWindowLauncher.launchBounds(context, settings(
            widthFraction = 0.5f,
            heightFraction = 0.3f,
            leftFraction = 0.1f,
            topFraction = 0.15f,
            followTrigger = false,
        ))

        FreeWindowTriggerAnchor.record(PanelSide.LEFT, heightPx / 2f)
        val bounds = FreeWindowLauncher.launchBounds(context, config)

        assertEquals((widthPx * 0.1f).toInt(), bounds.left)
        assertEquals(withoutFollow.left, bounds.left)
    }

    /** 竖直方向以触发高度居中。 */
    @Test
    fun followTriggerCentersOnTriggerHeight() {
        val config = settings(
            widthFraction = 0.5f,
            heightFraction = 0.2f,
            leftFraction = 0.1f,
            topFraction = 0.1f,
            followTrigger = true,
        )
        val triggerY = heightPx / 2f

        FreeWindowTriggerAnchor.record(PanelSide.LEFT, triggerY)
        val bounds = FreeWindowLauncher.launchBounds(context, config)

        assertEquals((triggerY - bounds.height() / 2f).toInt(), bounds.top)
    }

    /** 关闭跟随开关时，锚点完全不参与计算。 */
    @Test
    fun followDisabledIgnoresAnchor() {
        FreeWindowTriggerAnchor.record(PanelSide.RIGHT, heightPx / 2f)

        val bounds = FreeWindowLauncher.launchBounds(context, settings(followTrigger = false))

        assertEquals((widthPx * 0.1f).toInt(), bounds.left)
        assertEquals((heightPx * 0.15f).toInt(), bounds.top)
    }

    /** 过期锚点按"没有锚点"处理：手势开面板、很久之后再启动不会拿旧方位摆窗口。 */
    @Test
    fun staleAnchorIsIgnored() {
        FreeWindowTriggerAnchor.record(PanelSide.RIGHT, heightPx / 2f)

        assertNull(
            FreeWindowTriggerAnchor.peek(
                nowMs = SystemClock.uptimeMillis() + FreeWindowTriggerAnchor.MAX_AGE_MS + 1,
            ),
        )
        assertTrue(FreeWindowTriggerAnchor.peek() != null)
    }

    /** 米窗自己决定窗口几何：尺寸 / 位置 / 跟随对它无效，设置页据此置灰。 */
    @Test
    fun sunshineModeIgnoresLaunchBounds() {
        assertTrue(FreeWindowMode.SUNSHINE.usesSunshineBroadcast())
        assertTrue(FreeWindowMode.SUNSHINE.ignoresLaunchBounds())
        assertFalse(FreeWindowMode.STANDARD.ignoresLaunchBounds())
    }

    /**
     * 交付上限：只有「标准模式 + 小米」才有二次缩放，上限=补偿系数；
     * 其它模式/机型上限是整屏。设置页与编辑器都按这个值限制拖拽。
     */
    @Test
    fun deliverableLimitFractionMatchesCompensation() {
        assertEquals(
            0.7f,
            FreeWindowLauncher.deliverableLimitFraction(
                FreeWindowMode.STANDARD,
                isLandscape = false,
                isXiaomi = true,
            ),
            0.0001f,
        )
        assertEquals(
            0.555f,
            FreeWindowLauncher.deliverableLimitFraction(
                FreeWindowMode.STANDARD,
                isLandscape = true,
                isXiaomi = true,
            ),
            0.0001f,
        )
        assertEquals(
            1f,
            FreeWindowLauncher.deliverableLimitFraction(
                FreeWindowMode.STANDARD,
                isLandscape = false,
                isXiaomi = false,
            ),
            0.0001f,
        )
        // 非「标准」模式不做补偿，即便机型是小米（与 resolveScaleCompensation 一致）。
        assertEquals(
            1f,
            FreeWindowLauncher.deliverableLimitFraction(
                FreeWindowMode.COLOROS,
                isLandscape = false,
                isXiaomi = true,
            ),
            0.0001f,
        )
    }

    /**
     * 小米上请求 95% 宽不会得到 95%：逻辑矩形不能溢屏，可见宽被压到 70%×屏宽。
     * 这条同时钉住"不会再静默交出一个溢屏的逻辑矩形"。
     */
    @Test
    fun xiaomiRequestIsCappedByCompensation() {
        ShadowBuild.setManufacturer("Xiaomi")
        ShadowBuild.setBrand("Xiaomi")

        val bounds = FreeWindowLauncher.launchBounds(
            context,
            settings(widthFraction = 0.95f, heightFraction = 0.9f, topFraction = 0.05f),
        )

        val visibleWidth = bounds.width() * 0.7f
        assertTrue(
            "可见宽 $visibleWidth 不应超过屏宽 70%（${widthPx * 0.7f}）",
            visibleWidth <= widthPx * 0.7f + 1f,
        )
        assertTrue(
            "可见高 ${bounds.height() * 0.7f} 不应超过屏高 70%（${heightPx * 0.7f}）",
            bounds.height() * 0.7f <= heightPx * 0.7f + 1f,
        )
    }
}
