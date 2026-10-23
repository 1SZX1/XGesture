package com.slideindex.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import com.slideindex.app.settings.AppSettings
import com.slideindex.app.settings.FloatBallStyleType
import com.slideindex.app.ui.miuix.theme.resolveAppAccentArgb
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders float-ball appearance to a bitmap without Compose — used during drag.
 */
internal object FloatBallDragVisualRenderer {

    fun captureFromComposeTree(composeRoot: View?): Bitmap? {
        if (composeRoot == null) return null
        val queue = ArrayDeque<View>()
        queue.add(composeRoot)
        while (queue.isNotEmpty()) {
            val view = queue.removeFirst()
            if (view is FloatBallGifView && view.width > 0 && view.height > 0) {
                return view.snapshotCurrentFrame()
            }
            if (view is FloatBallBuiltinAnimView && view.width > 0 && view.height > 0) {
                return view.snapshotCurrentFrame()
            }
            if (view is ImageView && view.drawable != null && view.width > 0 && view.height > 0) {
                return snapshotDrawable(view.drawable, view.width, view.height)
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    queue.add(view.getChildAt(i))
                }
            }
        }
        return null
    }

    fun render(context: Context, settings: AppSettings): Bitmap {
        val density = context.resources.displayMetrics.density
        val ballSize = FloatBallLayout.ballSize(settings, density)
        val bitmap = createBitmap(ballSize.widthPx, ballSize.heightPx)
        val canvas = Canvas(bitmap)
        val alpha = settings.floatBallOpacity.coerceIn(0f, 1f)
        // 悬浮球默认样式与各类兜底色都跟"主题强调色"走（不是 themeColorArgb 那个默认紫）。
        val accentArgb = resolveAppAccentArgb(context, settings)
        // 「不处理」：位图本身就是原图比例，图片按 contain 画满（不裁、不变形）；
        // 圆球样式：图片按 cover 居中裁剪后，再交给 View 的圆形轮廓裁成球。
        val noCrop = FloatBallLayout.usesOriginalImageGeometry(settings)
        when (settings.floatBallStyleType) {
            FloatBallStyleType.DEFAULT -> drawDefault(canvas, ballSize, accentArgb, alpha)
            FloatBallStyleType.ANIMATED_PLANE,
            FloatBallStyleType.ANIMATED_PULSE,
            FloatBallStyleType.ANIMATED_ORBIT,
            -> drawBuiltinAnim(canvas, ballSize, alpha, settings.floatBallStyleType)
            FloatBallStyleType.CUSTOM_IMAGE -> drawUri(
                canvas, context, ballSize, alpha, settings.floatBallCustomImageUri,
                fallbackArgb = accentArgb,
                cover = !noCrop
            )
            FloatBallStyleType.SLIDESHOW -> {
                val uri = settings.floatBallSlideshowUris.firstOrNull().orEmpty()
                drawUri(canvas, context, ballSize, alpha, uri, fallbackArgb = accentArgb, cover = true)
            }
            FloatBallStyleType.GIF -> {
                val dragFrame = FloatBallGifDragSnapshot.copyForDrag(
                    uri = settings.floatBallGifUri,
                    targetPx = ballSize.minSidePx
                )
                if (dragFrame != null) {
                    drawBitmap(canvas, ballSize, alpha, dragFrame)
                    dragFrame.recycle()
                } else {
                    drawUri(
                        canvas, context, ballSize, alpha, settings.floatBallGifUri,
                        fallbackArgb = accentArgb,
                        cover = true
                    )
                }
            }
        }
        return bitmap
    }

    private fun snapshotDrawable(drawable: Drawable, width: Int, height: Int): Bitmap? {
        if (drawable is BitmapDrawable) {
            val source = drawable.bitmap ?: return null
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }
        return runCatching {
            val bitmap = createBitmap(width, height)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
            bitmap
        }.getOrNull()
    }

    private fun drawBuiltinAnim(
        canvas: Canvas,
        ballSize: FloatBallSize,
        alpha: Float,
        styleType: FloatBallStyleType
    ) {
        if (!FloatBallBuiltinAnimCatalog.isBuiltinAnimated(styleType)) {
            drawDefault(canvas, ballSize, 0xFF42A5F5.toInt(), alpha)
            return
        }
        val diameter = ballSize.minSidePx.toFloat()
        val left = (ballSize.widthPx - diameter) / 2f
        val top = (ballSize.heightPx - diameter) / 2f
        val clipPath = android.graphics.Path().apply {
            addCircle(left + diameter / 2f, top + diameter / 2f, diameter / 2f, android.graphics.Path.Direction.CW)
        }
        canvas.withClip(clipPath) {
            save()
            translate(left, top)
            FloatBallBuiltinAnimRenderer.draw(
                canvas = this,
                sizePx = ballSize.minSidePx,
                alpha = alpha,
                styleType = styleType,
                timeMs = android.os.SystemClock.uptimeMillis()
            )
            restore()
        }
    }

    private fun drawDefault(canvas: Canvas, ballSize: FloatBallSize, colorArgb: Int, alpha: Float) {
        val diameter = ballSize.minSidePx
        if (diameter <= 0) return
        canvas.save()
        canvas.translate((ballSize.widthPx - diameter) / 2f, (ballSize.heightPx - diameter) / 2f)
        FloatBallDefaultVisual.draw(canvas, diameter, colorArgb, alpha)
        canvas.restore()
    }

    private fun drawBitmap(canvas: Canvas, ballSize: FloatBallSize, alpha: Float, bitmap: Bitmap) {
        val dst = RectF(0f, 0f, ballSize.widthPx.toFloat(), ballSize.heightPx.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            this.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)
        }
        canvas.drawBitmap(bitmap, null, dst, paint)
    }

    private fun drawUri(
        canvas: Canvas,
        context: Context,
        ballSize: FloatBallSize,
        alpha: Float,
        uri: String,
        fallbackArgb: Int,
        cover: Boolean
    ) {
        val loaded = FloatBallImageLoader.loadBitmap(context, uri)
        if (loaded != null) {
            drawImage(
                canvas = canvas,
                ballSize = ballSize,
                alpha = alpha,
                bitmap = loaded,
                cover = cover
            )
            if (!loaded.isRecycled) {
                loaded.recycle()
            }
            return
        }
        drawDefault(canvas, ballSize, fallbackArgb, alpha)
    }

    /**
     * 等比绘制图片。
     *
     * [cover] = true：按「居中裁剪」铺满（圆球样式的语义，四角由外层圆形轮廓裁掉）；
     * [cover] = false：按「完整显示」缩进窗口（「不处理」的语义，绝不裁掉内容）。
     * 以前这里直接把位图拉伸到方形 dst，非方图在拖动时会变形，这里一并修正。
     */
    private fun drawImage(
        canvas: Canvas,
        ballSize: FloatBallSize,
        alpha: Float,
        bitmap: Bitmap,
        cover: Boolean
    ) {
        val srcWidth = bitmap.width.coerceAtLeast(1)
        val srcHeight = bitmap.height.coerceAtLeast(1)
        val scaleX = ballSize.widthPx.toFloat() / srcWidth
        val scaleY = ballSize.heightPx.toFloat() / srcHeight
        val scale = if (cover) max(scaleX, scaleY) else min(scaleX, scaleY)
        val drawWidth = srcWidth * scale
        val drawHeight = srcHeight * scale
        val left = (ballSize.widthPx - drawWidth) / 2f
        val top = (ballSize.heightPx - drawHeight) / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            this.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)
        }
        canvas.drawBitmap(
            bitmap,
            null,
            RectF(left, top, left + drawWidth, top + drawHeight),
            paint
        )
    }

    private fun applyAlpha(colorArgb: Int, alpha: Float): Int {
        val a = (alpha * 255f).roundToInt().coerceIn(0, 255)
        return (colorArgb and 0x00FFFFFF) or (a shl 24)
    }
}
