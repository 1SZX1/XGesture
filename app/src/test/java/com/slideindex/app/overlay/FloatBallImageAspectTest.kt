package com.slideindex.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 自定义图片宽高比换算。
 *
 * 锁住两件事：
 * 1. 5.04:1 这种超宽图要算出真实比例（真机 bug：比例恒为 0，图片被压成方框里的一条细带）；
 * 2. EXIF 方向只对 90°/270° 互换宽高 —— 180° 不能换（旧实现写成 `in 90..270`，把 180 也换了）。
 */
class FloatBallImageAspectTest {

  private val wide = 1014
  private val thin = 201

  @Test
  fun landscape_image_uses_width_over_height() {
    assertEquals(5.0448f, aspectFromRawSize(wide, thin, rotationDegrees = 0)!!, 0.0001f)
  }

  @Test
  fun exif_90_swaps_axes() {
    assertEquals(
      201f / 1014f,
      aspectFromRawSize(wide, thin, rotationDegrees = 90)!!,
      0.0001f,
    )
  }

  @Test
  fun exif_270_swaps_axes() {
    assertEquals(
      201f / 1014f,
      aspectFromRawSize(wide, thin, rotationDegrees = 270)!!,
      0.0001f,
    )
  }

  @Test
  fun exif_180_keeps_axes() {
    assertEquals(5.0448f, aspectFromRawSize(wide, thin, rotationDegrees = 180)!!, 0.0001f)
  }

  @Test
  fun square_image_is_one() {
    assertEquals(1f, aspectFromRawSize(512, 512, rotationDegrees = 0)!!, 0.0001f)
  }

  @Test
  fun invalid_dimensions_return_null() {
    assertNull(aspectFromRawSize(0, thin, rotationDegrees = 0))
    assertNull(aspectFromRawSize(wide, 0, rotationDegrees = 0))
    assertNull(aspectFromRawSize(-1, -1, rotationDegrees = 90))
  }
}
