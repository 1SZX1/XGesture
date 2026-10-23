package com.slideindex.app.overlay

import android.util.DisplayMetrics
import com.slideindex.app.settings.AppSettings
import com.slideindex.app.settings.FloatBallPositionMode
import com.slideindex.app.settings.FloatBallSettings
import com.slideindex.app.settings.FloatBallSide
import com.slideindex.app.settings.FloatBallStyleType
import com.slideindex.app.settings.FreeWindowMode
import com.slideindex.app.settings.FreeWindowSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 自定义图片「不处理」的窗口/命中几何。
 *
 * 必须走 Robolectric：命中矩形是 `android.graphics.Rect`，普通 JVM 单测里它被 stub 成 0，
 * 断言会变成"0 == 0"的假通过（`FloatBallBothLinesHitRectTest` 就是为此加了 runner）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class FloatBallNoCropGeometryTest {
  private val metrics = DisplayMetrics().apply {
    density = 3f
    widthPixels = 1080
    heightPixels = 2400
  }

  private fun settings(aspect: Float = 2f, noCrop: Boolean = true) = AppSettings(
    freeWindow = FreeWindowSettings(freeWindowModeId = FreeWindowMode.STANDARD.id),
    floatBall = FloatBallSettings(
      floatBallPositionMode = FloatBallPositionMode.RIGHT,
      floatBallStyleType = FloatBallStyleType.CUSTOM_IMAGE,
      floatBallCustomImageUri = "file:///float_ball_custom.png",
      floatBallCustomImageNoCrop = noCrop,
      floatBallCustomImageAspect = aspect,
      floatBallSizeDp = 48f,
    ),
  )

  @Test
  fun hit_rect_is_as_wide_as_the_original_aspect() {
    val settings = settings()
    val hit = FloatBallSceneState(settings).ballHitRect(
      settings,
      metrics,
      FloatBallSide.RIGHT,
      metrics.widthPixels,
      metrics.heightPixels,
    )

    // 高度 = 48dp × 3 = 144；宽度 = 144 × 2 = 288；右贴边。
    assertEquals(288, hit.width())
    assertEquals(144, hit.height())
    assertEquals(1080, hit.right)
    assertEquals(792, hit.left)
  }

  @Test
  fun hit_rect_stays_square_when_switch_is_off() {
    val settings = settings(noCrop = false)
    val hit = FloatBallSceneState(settings).ballHitRect(
      settings,
      metrics,
      FloatBallSide.RIGHT,
      metrics.widthPixels,
      metrics.heightPixels,
    )

    assertEquals(144, hit.width())
    assertEquals(144, hit.height())
  }
}
