package com.slideindex.app.screendraw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal enum class ScreenDrawTool { PEN, ERASER, LASER, SHAPE }

internal enum class ScreenDrawShape { RECTANGLE, OVAL, LINE, ARROW }

/**
 * 「屏幕绘制」画布，按一墨 2.0.5 的绘制链路重写：
 *
 * - 画笔：`bp8.c` 的中点二次贝塞尔 + `Stroke(Round/Round)`；
 * - 橡皮：`BlendMode.Clear`（这里用 PorterDuff.CLEAR 落在墨迹层）；
 * - 激光：`he8.e/f/g` 的三层 ribbon + 1200ms 衰减 + 头部光晕；
 * - 形状：`ji8.b` 的矩形/椭圆/直线/箭头。
 */
internal class ScreenDrawCanvasView(
    context: Context,
    private val onBack: () -> Unit,
) : View(context) {

    private data class Point(val x: Float, val y: Float, val timeMs: Long)

    private data class Stroke(
        val mode: ScreenDrawTool,
        val color: Int,
        val widthPx: Float,
        val points: MutableList<Point> = mutableListOf(),
        val shape: ScreenDrawShape? = null,
        val startX: Float = 0f,
        val startY: Float = 0f,
        var endX: Float = 0f,
        var endY: Float = 0f,
        val createdAt: Long = SystemClock.uptimeMillis(),
    )

    private val handler = Handler(Looper.getMainLooper())
    private val strokes = mutableListOf<Stroke>()
    private val redoStrokes = mutableListOf<Stroke>()
    private var backing: Bitmap? = null
    private var backingCanvas: Canvas? = null
    private var current: Stroke? = null
    private val density = resources.displayMetrics.density

    var tool: ScreenDrawTool = ScreenDrawTool.PEN
        set(value) {
            field = value
            invalidate()
        }
    var color: Int = DEFAULT_COLOR
        set(value) {
            field = value
            invalidate()
        }
    var widthPx: Float = 3f * density
        set(value) {
            field = value
            invalidate()
        }
    var shape: ScreenDrawShape = ScreenDrawShape.RECTANGLE
        set(value) {
            field = value
            invalidate()
        }

    var onContentChanged: (() -> Unit)? = null

    val hasContent: Boolean get() = strokes.isNotEmpty()

    init {
        setWillNotDraw(false)
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.TRANSPARENT)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        if (backing?.width == w && backing?.height == h) return
        backing?.recycle()
        backing = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        backingCanvas = Canvas(backing!!)
        redrawAll()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        backing?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        val now = SystemClock.uptimeMillis()
        strokes.forEach { stroke ->
            if (stroke.mode == ScreenDrawTool.LASER) {
                drawLaser(canvas, stroke, now)
            }
        }
        current?.let { drawStroke(canvas, it, now) }
        if (strokes.any { it.mode == ScreenDrawTool.LASER && now - it.createdAt < LASER_LIFETIME_MS }) {
            postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginStroke(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val stroke = current ?: return true
                if (stroke.shape != null) {
                    stroke.endX = event.x
                    stroke.endY = event.y
                } else {
                    val last = stroke.points.lastOrNull()
                    if (last == null || hypot(event.x - last.x, event.y - last.y) >= 1.5f) {
                        stroke.points.add(Point(event.x, event.y, SystemClock.uptimeMillis()))
                    }
                    stroke.endX = event.x
                    stroke.endY = event.y
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                current = null
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                current?.let { commit(it) }
                current = null
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                current = null
                invalidate()
                return true
            }
        }
        return true
    }

    private fun beginStroke(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        current = Stroke(
            mode = tool,
            color = color,
            widthPx = widthPx,
            points = mutableListOf(Point(x, y, now)),
            shape = if (tool == ScreenDrawTool.SHAPE) shape else null,
            startX = x,
            startY = y,
            endX = x,
            endY = y,
            createdAt = now,
        )
        invalidate()
    }

    private fun commit(stroke: Stroke) {
        strokes += stroke
        redoStrokes.clear()
        if (stroke.mode != ScreenDrawTool.LASER) {
            backingCanvas?.let { drawStroke(it, stroke, stroke.createdAt) }
        }
        onContentChanged?.invoke()
        invalidate()
    }

    fun undo() {
        if (strokes.isEmpty()) return
        val stroke = strokes.removeAt(strokes.lastIndex)
        redoStrokes += stroke
        redrawAll()
        invalidate()
        onContentChanged?.invoke()
    }

    fun redo() {
        if (redoStrokes.isEmpty()) return
        val stroke = redoStrokes.removeAt(redoStrokes.lastIndex)
        strokes += stroke
        if (stroke.mode != ScreenDrawTool.LASER) {
            backingCanvas?.let { drawStroke(it, stroke, stroke.createdAt) }
        }
        onContentChanged?.invoke()
        invalidate()
    }

    fun clear() {
        if (strokes.isEmpty()) return
        strokes.clear()
        redoStrokes.clear()
        current = null
        redrawAll()
        invalidate()
        onContentChanged?.invoke()
    }

    fun addText(x: Float, y: Float, text: String) {
        // 一墨的绘制工具栏没有文字工具；保留空实现兼容旧编辑器。
    }

    fun snapshotBitmap(): Bitmap? {
        val base = backing ?: return null
        val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(base, 0f, 0f, null)
        val now = SystemClock.uptimeMillis()
        strokes.forEach { if (it.mode == ScreenDrawTool.LASER) drawLaser(canvas, it, now) }
        return out
    }

    private fun redrawAll() {
        val canvas = backingCanvas ?: return
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val now = SystemClock.uptimeMillis()
        strokes.forEach { stroke ->
            if (stroke.mode != ScreenDrawTool.LASER) drawStroke(canvas, stroke, now)
        }
    }

    private fun drawStroke(canvas: Canvas, stroke: Stroke, now: Long) {
        when (stroke.mode) {
            ScreenDrawTool.LASER -> drawLaser(canvas, stroke, now)
            ScreenDrawTool.ERASER -> drawFreehand(canvas, stroke, eraserPaint(stroke.widthPx))
            ScreenDrawTool.PEN -> drawFreehand(canvas, stroke, penPaint(stroke.color, stroke.widthPx))
            ScreenDrawTool.SHAPE -> drawShape(canvas, stroke, penPaint(stroke.color, stroke.widthPx))
        }
    }

    private fun penPaint(color: Int, width: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        this.color = color
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun eraserPaint(width: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        color = Color.BLACK
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    /** 一墨 `bp8.c`：中点二次贝塞尔。 */
    private fun buildSmoothedPath(points: List<Point>): Path {
        val path = Path()
        if (points.isEmpty()) return path
        val first = points.first()
        path.moveTo(first.x, first.y)
        if (points.size == 1) return path
        if (points.size == 2) {
            path.lineTo(points[1].x, points[1].y)
            return path
        }
        var previous = first
        for (index in 1 until points.size) {
            val point = points[index]
            val midX = (previous.x + point.x) / 2f
            val midY = (previous.y + point.y) / 2f
            if (index == 1) {
                path.lineTo(midX, midY)
            } else {
                path.quadTo(previous.x, previous.y, midX, midY)
            }
            previous = point
        }
        path.lineTo(points.last().x, points.last().y)
        return path
    }

    private fun drawFreehand(canvas: Canvas, stroke: Stroke, paint: Paint) {
        val points = stroke.points
        if (points.isEmpty()) return
        if (points.size == 1) {
            val fill = Paint(paint).apply { style = Paint.Style.FILL }
            canvas.drawCircle(points[0].x, points[0].y, stroke.widthPx / 2f, fill)
            return
        }
        canvas.drawPath(buildSmoothedPath(points), paint)
    }

    private fun drawShape(canvas: Canvas, stroke: Stroke, paint: Paint) {
        val left = min(stroke.startX, stroke.endX)
        val right = max(stroke.startX, stroke.endX)
        val top = min(stroke.startY, stroke.endY)
        val bottom = max(stroke.startY, stroke.endY)
        when (stroke.shape) {
            ScreenDrawShape.RECTANGLE -> canvas.drawRect(left, top, right, bottom, paint)
            ScreenDrawShape.OVAL -> canvas.drawOval(RectF(left, top, right, bottom), paint)
            ScreenDrawShape.LINE -> canvas.drawLine(
                stroke.startX,
                stroke.startY,
                stroke.endX,
                stroke.endY,
                paint,
            )
            ScreenDrawShape.ARROW -> {
                canvas.drawLine(stroke.startX, stroke.startY, stroke.endX, stroke.endY, paint)
                val angle = atan2(
                    (stroke.endY - stroke.startY).toDouble(),
                    (stroke.endX - stroke.startX).toDouble(),
                )
                val head = 30f * density + stroke.widthPx
                val wing = 2.670353755551324
                val x1 = stroke.endX + (cos(angle + wing) * head).toFloat()
                val y1 = stroke.endY + (sin(angle + wing) * head).toFloat()
                val x2 = stroke.endX + (cos(angle - wing) * head).toFloat()
                val y2 = stroke.endY + (sin(angle - wing) * head).toFloat()
                val path = Path()
                path.moveTo(stroke.endX, stroke.endY)
                path.lineTo(x1, y1)
                path.moveTo(stroke.endX, stroke.endY)
                path.lineTo(x2, y2)
                canvas.drawPath(path, paint)
            }
            null -> Unit
        }
    }

    private fun drawLaser(canvas: Canvas, stroke: Stroke, now: Long) {
        if (stroke.points.isEmpty()) return
        val age = now - stroke.createdAt
        if (age >= LASER_LIFETIME_MS) return
        val fade = (1f - age / LASER_LIFETIME_MS.toFloat()).coerceIn(0f, 1f)
        if (stroke.points.size == 1) {
            drawLaserHead(canvas, stroke.points[0].x, stroke.points[0].y, stroke, fade)
            return
        }
        drawLaserLayer(canvas, stroke, fade, stroke.color, 3.2f, 0.18f)
        drawLaserLayer(canvas, stroke, fade, stroke.color, 1.6f, 0.72f)
        drawLaserLayer(canvas, stroke, fade, Color.WHITE, 0.55f, 0.95f)
        drawLaserHead(canvas, stroke.points.last().x, stroke.points.last().y, stroke, fade)
    }

    private fun drawLaserLayer(
        canvas: Canvas,
        stroke: Stroke,
        fade: Float,
        color: Int,
        widthFactor: Float,
        alphaFactor: Float,
    ) {
        val points = stroke.points
        val count = points.size
        val vertices = FloatArray(count * 4)
        val colors = IntArray(count * 2)
        val baseAlpha = (alphaFactor * fade * fade * 255f).toInt().coerceIn(0, 255)
        val paintColor = Color.argb(
            baseAlpha,
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )
        for (index in 0 until count) {
            val previous = points[(index - 1).coerceAtLeast(0)]
            val next = points[(index + 1).coerceAtMost(count - 1)]
            val dx = next.x - previous.x
            val dy = next.y - previous.y
            val length = hypot(dx, dy).coerceAtLeast(0.001f)
            val nx = -dy / length
            val ny = dx / length
            val age = (nowAge(stroke, points[index])).coerceIn(0f, 1f)
            val width = stroke.widthPx * widthFactor * (0.75f * age + 0.25f) * 0.5f
            vertices[index * 4] = points[index].x + nx * width
            vertices[index * 4 + 1] = points[index].y + ny * width
            vertices[index * 4 + 2] = points[index].x - nx * width
            vertices[index * 4 + 3] = points[index].y - ny * width
            colors[index * 2] = paintColor
            colors[index * 2 + 1] = paintColor
        }
        canvas.drawVertices(
            Canvas.VertexMode.TRIANGLE_STRIP,
            count * 2,
            vertices,
            0,
            null,
            0,
            colors,
            0,
            null,
            0,
            0,
            Paint(Paint.ANTI_ALIAS_FLAG),
        )
    }

    private fun nowAge(stroke: Stroke, point: Point): Float {
        val age = SystemClock.uptimeMillis() - point.timeMs
        return (1f - age / LASER_LIFETIME_MS.toFloat()).coerceIn(0f, 1f)
    }

    private fun drawLaserHead(canvas: Canvas, x: Float, y: Float, stroke: Stroke, fade: Float) {
        val radius = stroke.widthPx * 2.2f
        val layers = floatArrayOf(0.18f, 0.72f, 0.95f)
        val scales = floatArrayOf(0.75f, 1.0f, 1.25f)
        for (index in layers.indices) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(
                    (layers[index] * fade * fade * 255f).toInt().coerceIn(0, 255),
                    Color.red(stroke.color),
                    Color.green(stroke.color),
                    Color.blue(stroke.color),
                )
                style = Paint.Style.FILL
            }
            canvas.drawCircle(x, y, radius * scales[index], paint)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        backing?.recycle()
        backing = null
        backingCanvas = null
        super.onDetachedFromWindow()
    }

    companion object {
        const val DEFAULT_COLOR = 0xFFFF3B30.toInt()
        private const val LASER_LIFETIME_MS = 1200L
    }
}
