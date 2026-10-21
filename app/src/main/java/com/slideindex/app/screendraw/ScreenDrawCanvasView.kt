package com.slideindex.app.screendraw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal enum class ScreenDrawTool {
    PEN,
    ERASER,
    SHAPE,
    TEXT,
    MOSAIC,
    NUMBER,
    BOX_SELECT,
    LASER,
}

internal enum class ScreenDrawShape {
    RECTANGLE,
    OVAL,
    TRIANGLE,
    LINE,
    DASHED_LINE,
    ARROW,
}

internal enum class ScreenDrawNumberStyle {
    SOLID_CIRCLE,  // 样式 1：实心圆底白字
    HOLLOW_CIRCLE, // 样式 2：空心圆圈黑字
    PURE_NUMBER,   // 样式 3：纯数字
}

internal enum class ScreenDrawEraserMode {
    STROKE, // 整笔擦除
    PIXEL,  // 像素擦除
}

/**
 * 屏幕绘制画布：
 * - 自由画笔 (二次贝塞尔平滑)
 * - 形状 (矩形、椭圆、三角形、实线、虚线、箭头)
 * - 文字 (虚线边框、左上角删除叉号、右下角旋转与等比缩放手柄，双击重新调起输入法编辑)
 * - 马赛克 (基于底图或网格的像素化打码)
 * - 序号 (实心圆、空心圆、纯数字自增角标)
 * - 框选矩形 (区域聚焦高亮，支持拖拽移动、四个边/四个角拉伸调整大小，右下角叉号关闭)
 * - 橡皮擦 (整笔擦除与像素擦除)
 * - 激光笔
 * - 完整的撤销 (Undo) 与重做 (Redo)
 */
internal class ScreenDrawCanvasView(
    context: Context,
    private val onBack: () -> Unit,
) : View(context) {

    internal data class Point(val x: Float, val y: Float, val timeMs: Long = SystemClock.uptimeMillis())

    internal sealed class Element {
        abstract val id: Long
    }

    internal data class Freehand(
        override val id: Long = SystemClock.uptimeMillis(),
        val isMosaic: Boolean = false,
        val isEraser: Boolean = false,
        val color: Int = Color.RED,
        val widthPx: Float = 6f,
        val points: MutableList<Point> = mutableListOf(),
    ) : Element()

    internal data class ShapeItem(
        override val id: Long = SystemClock.uptimeMillis(),
        var shape: ScreenDrawShape,
        var color: Int,
        var widthPx: Float,
        var cx: Float,
        var cy: Float,
        var width: Float,
        var height: Float,
        var rotationDeg: Float = 0f,
        var isSelected: Boolean = false,
    ) : Element()

    internal data class TextItem(
        override val id: Long = SystemClock.uptimeMillis(),
        var text: String,
        var x: Float,
        var y: Float,
        var fontSizeSp: Float,
        var color: Int,
        var rotationDeg: Float = 0f,
        var isSelected: Boolean = false,
    ) : Element()

    internal data class NumberItem(
        override val id: Long = SystemClock.uptimeMillis(),
        val number: Int,
        var x: Float,
        var y: Float,
        var style: ScreenDrawNumberStyle,
        var color: Int,
        var sizePx: Float,
        var rotationDeg: Float = 0f,
        var isSelected: Boolean = false,
    ) : Element()

    internal data class BoxSelectItem(
        override val id: Long = SystemClock.uptimeMillis(),
        var rect: RectF,
        val color: Int = 0xFFFF9500.toInt(),
    ) : Element()

    internal data class LaserStroke(
        val color: Int,
        val widthPx: Float,
        val points: MutableList<Point> = mutableListOf(),
        val createdAt: Long = SystemClock.uptimeMillis(),
    )

    private val density = resources.displayMetrics.density
    private val elements = mutableListOf<Element>()
    private val redoElements = mutableListOf<Element>()
    private val laserStrokes = mutableListOf<LaserStroke>()

    // 配置属性
    var tool: ScreenDrawTool = ScreenDrawTool.PEN
        set(value) {
            field = value
            deselectAll()
            invalidate()
        }

    var color: Int = DEFAULT_COLOR
        set(value) {
            field = value
            selectedText()?.let { it.color = value }
            selectedShape()?.let { it.color = value }
            selectedNumber()?.let { it.color = value }
            invalidate()
        }

    var widthPx: Float = 3f * density
        set(value) {
            field = value
            selectedShape()?.let { it.widthPx = value }
            invalidate()
        }

    var shape: ScreenDrawShape = ScreenDrawShape.RECTANGLE
        set(value) {
            field = value
            selectedShape()?.let { it.shape = value }
            invalidate()
        }

    var eraserMode: ScreenDrawEraserMode = ScreenDrawEraserMode.STROKE
    var eraserRadiusPx: Float = 20f * density

    var textFontSizeSp: Float = 24f
        set(value) {
            field = value
            selectedText()?.let { it.fontSizeSp = value }
            invalidate()
        }

    var numberStyle: ScreenDrawNumberStyle = ScreenDrawNumberStyle.SOLID_CIRCLE
        set(value) {
            field = value
            selectedNumber()?.let { it.style = value }
            invalidate()
        }

    var numberSizePx: Float = 48f * density
        set(value) {
            field = value
            selectedNumber()?.let { it.sizePx = value }
            invalidate()
        }
    var nextNumberIndex: Int = 1

    var mosaicRadiusPx: Float = 28f * density

    var backgroundSnapshot: Bitmap? = null
        set(value) {
            field = value
            updateMosaicShader()
            invalidate()
        }

    private var mosaicShader: BitmapShader? = null

    // 绘制过程临时图元
    private var currentFreehand: Freehand? = null
    private var currentShape: ShapeItem? = null
    private var currentBoxSelect: BoxSelectItem? = null
    private var currentLaser: LaserStroke? = null

    // 框选调整手势控制
    private enum class BoxHitMode {
        NONE,
        MOVE_BODY,
        EDGE_LEFT,
        EDGE_RIGHT,
        EDGE_TOP,
        EDGE_BOTTOM,
        CORNER_TL,
        CORNER_TR,
        CORNER_BL,
        CORNER_BR,
    }
    private var activeBoxMode = BoxHitMode.NONE
    private var activeBoxItem: BoxSelectItem? = null
    private var boxTouchDownX = 0f
    private var boxTouchDownY = 0f
    private var boxInitialRect = RectF()

    // 形状、文字、序号通用交互控制状态
    private enum class TransformTouchMode { NONE, MOVE, ROTATE_SCALE }
    private var activeTransformMode = TransformTouchMode.NONE
    private var activeTransformElement: Element? = null
    private var transformTouchDownX = 0f
    private var transformTouchDownY = 0f
    private var transformInitialX = 0f
    private var transformInitialY = 0f
    private var transformInitialDist = 1f
    private var transformInitialAngle = 0.0
    private var transformInitialRotation = 0f
    private var transformInitialSize1 = 0f
    private var transformInitialSize2 = 0f
    private var shapeTouchDownX = 0f
    private var shapeTouchDownY = 0f

    // 双击检测
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    // 回调通知
    var onTouchDown: (() -> Unit)? = null
    var onContentChanged: (() -> Unit)? = null
    var onRequestTextInput: ((x: Float, y: Float, initial: String, onDone: (String) -> Unit) -> Unit)? = null
    var onRequestDragAndDrop: ((cropRect: RectF?) -> Boolean)? = null

    private val gestureDetector = android.view.GestureDetector(
        context,
        object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                val box = elements.filterIsInstance<BoxSelectItem>().lastOrNull()
                if (box != null && box.rect.contains(e.x, e.y)) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    onRequestDragAndDrop?.invoke(RectF(box.rect))
                    return
                }
                if (hasContent && activeTransformMode == TransformTouchMode.NONE && activeBoxMode == BoxHitMode.NONE) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    onRequestDragAndDrop?.invoke(null)
                }
            }
        }
    )

    val hasContent: Boolean
        get() = elements.isNotEmpty() || laserStrokes.isNotEmpty()

    val canUndo: Boolean get() = elements.isNotEmpty()
    val canRedo: Boolean get() = redoElements.isNotEmpty()

    init {
        setWillNotDraw(false)
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.TRANSPARENT)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun updateMosaicShader() {
        val src = backgroundSnapshot ?: return
        if (src.width <= 0 || src.height <= 0) return
        val scale = 0.05f
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, w, h, false)
        val pixelated = Bitmap.createScaledBitmap(small, src.width, src.height, false)
        small.recycle()
        mosaicShader = BitmapShader(pixelated, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

    fun selectedText(): TextItem? = elements.filterIsInstance<TextItem>().find { it.isSelected }
    fun selectedShape(): ShapeItem? = elements.filterIsInstance<ShapeItem>().find { it.isSelected }
    fun selectedNumber(): NumberItem? = elements.filterIsInstance<NumberItem>().find { it.isSelected }

    fun currentBoxSelectRect(): RectF? {
        val box = elements.filterIsInstance<BoxSelectItem>().lastOrNull() ?: currentBoxSelect
        if (box != null && box.rect.width() > 10f && box.rect.height() > 10f) {
            return RectF(box.rect)
        }
        return null
    }

    fun deselectAll() {
        var changed = false
        elements.forEach { el ->
            when (el) {
                is TextItem -> if (el.isSelected) { el.isSelected = false; changed = true }
                is ShapeItem -> if (el.isSelected) { el.isSelected = false; changed = true }
                is NumberItem -> if (el.isSelected) { el.isSelected = false; changed = true }
                else -> Unit
            }
        }
        if (changed) invalidate()
    }

    fun deselectAllText() = deselectAll()

    fun undo() {
        if (elements.isEmpty()) return
        val last = elements.removeAt(elements.lastIndex)
        redoElements.add(last)
        if (last is NumberItem && nextNumberIndex > 1) {
            nextNumberIndex--
        }
        invalidate()
        onContentChanged?.invoke()
    }

    fun redo() {
        if (redoElements.isEmpty()) return
        val item = redoElements.removeAt(redoElements.lastIndex)
        elements.add(item)
        if (item is NumberItem) {
            nextNumberIndex = max(nextNumberIndex, item.number + 1)
        }
        invalidate()
        onContentChanged?.invoke()
    }

    fun clear() {
        if (elements.isEmpty() && laserStrokes.isEmpty()) return
        redoElements.clear()
        elements.clear()
        laserStrokes.clear()
        nextNumberIndex = 1
        activeBoxMode = BoxHitMode.NONE
        activeBoxItem = null
        currentBoxSelect = null
        activeTransformMode = TransformTouchMode.NONE
        activeTransformElement = null
        invalidate()
        onContentChanged?.invoke()
    }

    fun addTextAt(x: Float, y: Float, text: String) {
        deselectAllText()
        val item = TextItem(
            text = text,
            x = x,
            y = y,
            fontSizeSp = textFontSizeSp,
            color = color,
            rotationDeg = 0f,
            isSelected = true,
        )
        elements.add(item)
        redoElements.clear()
        invalidate()
        onContentChanged?.invoke()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onTouchDown?.invoke()
                if (handleActionDown(x, y)) return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (handleActionMove(x, y)) return true
            }
            MotionEvent.ACTION_UP -> {
                handleActionUp(x, y)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handleActionCancel()
                return true
            }
        }
        return true
    }

    fun selectedElement(): Element? = elements.find {
        (it as? TextItem)?.isSelected == true ||
        (it as? ShapeItem)?.isSelected == true ||
        (it as? NumberItem)?.isSelected == true
    }

    private fun handleActionDown(x: Float, y: Float): Boolean {
        // 1. 检查是否点击了框选矩形的关闭叉号按钮
        val box = elements.filterIsInstance<BoxSelectItem>().lastOrNull()
        if (box != null) {
            val closeBtn = getBoxSelectCloseButtonCenter(box.rect)
            if (hypot(x - closeBtn.x, y - closeBtn.y) <= 28f * density) {
                elements.remove(box)
                invalidate()
                onContentChanged?.invoke()
                return true
            }

            // 检查是否命中了已有框选的边、角或框体内部进行调整/移动
            val boxHit = hitTestBoxSelect(box, x, y)
            if (boxHit != BoxHitMode.NONE) {
                activeBoxMode = boxHit
                activeBoxItem = box
                boxTouchDownX = x
                boxTouchDownY = y
                boxInitialRect.set(box.rect)
                return true
            }
        }

        // 2. 检查当前已选中的图元（TextItem, ShapeItem, NumberItem）的手柄命中（关闭或旋转缩放）
        val curSelected = selectedElement()
        if (curSelected != null) {
            val dim = getElementDimensions(curSelected)
            if (dim != null) {
                val delBtn = getDeleteButtonCenter(dim)
                if (hypot(x - delBtn.x, y - delBtn.y) <= 26f * density) {
                    elements.remove(curSelected)
                    if (curSelected is NumberItem && nextNumberIndex > 1) {
                        nextNumberIndex--
                    }
                    invalidate()
                    onContentChanged?.invoke()
                    return true
                }

                val handleBtn = getRotateScaleHandleCenter(dim)
                if (hypot(x - handleBtn.x, y - handleBtn.y) <= 28f * density) {
                    activeTransformMode = TransformTouchMode.ROTATE_SCALE
                    activeTransformElement = curSelected
                    transformTouchDownX = x
                    transformTouchDownY = y
                    transformInitialDist = hypot(x - dim.cx, y - dim.cy).coerceAtLeast(10f)
                    transformInitialAngle = atan2((y - dim.cy).toDouble(), (x - dim.cx).toDouble())
                    transformInitialRotation = dim.rotationDeg
                    when (curSelected) {
                        is TextItem -> transformInitialSize1 = curSelected.fontSizeSp
                        is NumberItem -> transformInitialSize1 = curSelected.sizePx
                        is ShapeItem -> {
                            transformInitialSize1 = curSelected.width
                            transformInitialSize2 = curSelected.height
                        }
                        else -> Unit
                    }
                    return true
                }
            }
        }

        // 3. 检查是否命中画布上的已有图元（支持双击编辑文字、单击选中并直接平移）
        val hitElement = elements.reversed().firstOrNull { isPointInsideElement(it, x, y) }
        if (hitElement != null) {
            if (hitElement is TextItem) {
                val now = SystemClock.uptimeMillis()
                val isDoubleTap = (now - lastTapTime < 450L) && (hypot(x - lastTapX, y - lastTapY) < 40f * density)
                lastTapTime = now
                lastTapX = x
                lastTapY = y
                if (isDoubleTap) {
                    onRequestTextInput?.invoke(hitElement.x, hitElement.y, hitElement.text) { newText ->
                        if (newText.isNotBlank()) {
                            hitElement.text = newText
                            invalidate()
                            onContentChanged?.invoke()
                        }
                    }
                    return true
                }
            }

            // 选中该图元并立即进入平移拖拽模式
            deselectAll()
            when (hitElement) {
                is TextItem -> hitElement.isSelected = true
                is ShapeItem -> hitElement.isSelected = true
                is NumberItem -> hitElement.isSelected = true
                else -> Unit
            }
            val dim = getElementDimensions(hitElement)
            if (dim != null) {
                activeTransformMode = TransformTouchMode.MOVE
                activeTransformElement = hitElement
                transformTouchDownX = x
                transformTouchDownY = y
                transformInitialX = dim.cx
                transformInitialY = dim.cy
            }
            invalidate()
            return true
        } else {
            // 点击空白处，取消所有选中
            deselectAll()
        }

        // 4. 根据当前工具分发绘制行为
        when (tool) {
            ScreenDrawTool.TEXT -> {
                onRequestTextInput?.invoke(x, y, "") { newText ->
                    if (newText.isNotBlank()) {
                        addTextAt(x, y, newText)
                    }
                }
                return true
            }
            ScreenDrawTool.NUMBER -> {
                deselectAll()
                val num = NumberItem(
                    number = nextNumberIndex++,
                    x = x,
                    y = y,
                    style = numberStyle,
                    color = color,
                    sizePx = numberSizePx,
                    isSelected = true,
                )
                elements.add(num)
                redoElements.clear()
                invalidate()
                onContentChanged?.invoke()
                return true
            }
            ScreenDrawTool.PEN -> {
                val stroke = Freehand(
                    isMosaic = false,
                    isEraser = false,
                    color = color,
                    widthPx = widthPx,
                    points = mutableListOf(Point(x, y)),
                )
                currentFreehand = stroke
                invalidate()
                return true
            }
            ScreenDrawTool.MOSAIC -> {
                val stroke = Freehand(
                    isMosaic = true,
                    isEraser = false,
                    color = Color.TRANSPARENT,
                    widthPx = mosaicRadiusPx * 2f,
                    points = mutableListOf(Point(x, y)),
                )
                currentFreehand = stroke
                invalidate()
                return true
            }
            ScreenDrawTool.ERASER -> {
                if (eraserMode == ScreenDrawEraserMode.STROKE) {
                    eraseStrokeAt(x, y)
                } else {
                    val stroke = Freehand(
                        isMosaic = false,
                        isEraser = true,
                        color = Color.BLACK,
                        widthPx = eraserRadiusPx * 2f,
                        points = mutableListOf(Point(x, y)),
                    )
                    currentFreehand = stroke
                    invalidate()
                }
                return true
            }
            ScreenDrawTool.SHAPE -> {
                shapeTouchDownX = x
                shapeTouchDownY = y
                currentShape = ShapeItem(
                    shape = shape,
                    color = color,
                    widthPx = widthPx,
                    cx = x,
                    cy = y,
                    width = 0f,
                    height = 0f,
                    rotationDeg = 0f,
                    isSelected = false,
                )
                invalidate()
                return true
            }
            ScreenDrawTool.BOX_SELECT -> {
                elements.removeAll { it is BoxSelectItem }
                currentBoxSelect = BoxSelectItem(
                    rect = RectF(x, y, x, y),
                    color = 0xFFFF9500.toInt(),
                )
                invalidate()
                return true
            }
            ScreenDrawTool.LASER -> {
                val stroke = LaserStroke(
                    color = color,
                    widthPx = widthPx * 1.5f,
                    points = mutableListOf(Point(x, y)),
                )
                currentLaser = stroke
                laserStrokes.add(stroke)
                invalidate()
                return true
            }
        }
    }

    private fun handleActionMove(x: Float, y: Float): Boolean {
        // 1. 处理框选矩形的拖动移动与边/角缩放拉伸
        val box = activeBoxItem
        if (box != null && activeBoxMode != BoxHitMode.NONE) {
            val dx = x - boxTouchDownX
            val dy = y - boxTouchDownY
            val minSize = 30f * density
            when (activeBoxMode) {
                BoxHitMode.MOVE_BODY -> {
                    val w = boxInitialRect.width()
                    val h = boxInitialRect.height()
                    val newL = (boxInitialRect.left + dx).coerceIn(0f, (width - w).coerceAtLeast(0f))
                    val newT = (boxInitialRect.top + dy).coerceIn(0f, (height - h).coerceAtLeast(0f))
                    box.rect.set(newL, newT, newL + w, newT + h)
                }
                BoxHitMode.EDGE_LEFT -> {
                    box.rect.left = (boxInitialRect.left + dx).coerceIn(0f, box.rect.right - minSize)
                }
                BoxHitMode.EDGE_RIGHT -> {
                    box.rect.right = (boxInitialRect.right + dx).coerceIn(box.rect.left + minSize, width.toFloat())
                }
                BoxHitMode.EDGE_TOP -> {
                    box.rect.top = (boxInitialRect.top + dy).coerceIn(0f, box.rect.bottom - minSize)
                }
                BoxHitMode.EDGE_BOTTOM -> {
                    box.rect.bottom = (boxInitialRect.bottom + dy).coerceIn(box.rect.top + minSize, height.toFloat())
                }
                BoxHitMode.CORNER_TL -> {
                    box.rect.left = (boxInitialRect.left + dx).coerceIn(0f, box.rect.right - minSize)
                    box.rect.top = (boxInitialRect.top + dy).coerceIn(0f, box.rect.bottom - minSize)
                }
                BoxHitMode.CORNER_TR -> {
                    box.rect.right = (boxInitialRect.right + dx).coerceIn(box.rect.left + minSize, width.toFloat())
                    box.rect.top = (boxInitialRect.top + dy).coerceIn(0f, box.rect.bottom - minSize)
                }
                BoxHitMode.CORNER_BL -> {
                    box.rect.left = (boxInitialRect.left + dx).coerceIn(0f, box.rect.right - minSize)
                    box.rect.bottom = (boxInitialRect.bottom + dy).coerceIn(box.rect.top + minSize, height.toFloat())
                }
                BoxHitMode.CORNER_BR -> {
                    box.rect.right = (boxInitialRect.right + dx).coerceIn(box.rect.left + minSize, width.toFloat())
                    box.rect.bottom = (boxInitialRect.bottom + dy).coerceIn(box.rect.top + minSize, height.toFloat())
                }
                BoxHitMode.NONE -> Unit
            }
            invalidate()
            return true
        }

        // 2. 通用图元变换处理（TextItem, ShapeItem, NumberItem 的移动与旋转/缩放）
        val transformElement = activeTransformElement
        if (transformElement != null) {
            when (activeTransformMode) {
                TransformTouchMode.MOVE -> {
                    val dx = x - transformTouchDownX
                    val dy = y - transformTouchDownY
                    when (transformElement) {
                        is TextItem -> {
                            transformElement.x = transformInitialX + dx
                            transformElement.y = transformInitialY + dy
                        }
                        is ShapeItem -> {
                            transformElement.cx = transformInitialX + dx
                            transformElement.cy = transformInitialY + dy
                        }
                        is NumberItem -> {
                            transformElement.x = transformInitialX + dx
                            transformElement.y = transformInitialY + dy
                        }
                        else -> Unit
                    }
                    invalidate()
                    return true
                }
                TransformTouchMode.ROTATE_SCALE -> {
                    val (cx, cy) = when (transformElement) {
                        is TextItem -> transformElement.x to transformElement.y
                        is ShapeItem -> transformElement.cx to transformElement.cy
                        is NumberItem -> transformElement.x to transformElement.y
                        else -> 0f to 0f
                    }
                    val currentDist = hypot(x - cx, y - cy)
                    val scaleFactor = currentDist / transformInitialDist
                    val currentAngle = atan2((y - cy).toDouble(), (x - cx).toDouble())
                    val deltaAngleDeg = Math.toDegrees(currentAngle - transformInitialAngle).toFloat()
                    val newRotation = (transformInitialRotation + deltaAngleDeg) % 360f

                    when (transformElement) {
                        is TextItem -> {
                            transformElement.fontSizeSp = (transformInitialSize1 * scaleFactor).coerceIn(12f, 120f)
                            transformElement.rotationDeg = newRotation
                        }
                        is NumberItem -> {
                            transformElement.sizePx = (transformInitialSize1 * scaleFactor).coerceIn(24f * density, 180f * density)
                            transformElement.rotationDeg = newRotation
                        }
                        is ShapeItem -> {
                            if (transformElement.shape == ScreenDrawShape.LINE || transformElement.shape == ScreenDrawShape.DASHED_LINE) {
                                transformElement.width = (transformInitialSize1 * scaleFactor).coerceAtLeast(16f * density)
                            } else {
                                transformElement.width = (transformInitialSize1 * scaleFactor).coerceAtLeast(16f * density)
                                transformElement.height = (transformInitialSize2 * scaleFactor).coerceAtLeast(16f * density)
                            }
                            transformElement.rotationDeg = newRotation
                        }
                        else -> Unit
                    }
                    invalidate()
                    return true
                }
                TransformTouchMode.NONE -> Unit
            }
        }

        // 3. 处理正在绘制的新形状
        val shp = currentShape
        if (shp != null) {
            val dx = x - shapeTouchDownX
            val dy = y - shapeTouchDownY
            if (shp.shape == ScreenDrawShape.LINE || shp.shape == ScreenDrawShape.DASHED_LINE || shp.shape == ScreenDrawShape.ARROW) {
                shp.cx = (shapeTouchDownX + x) / 2f
                shp.cy = (shapeTouchDownY + y) / 2f
                shp.width = hypot(dx, dy)
                shp.height = maxOf(shp.widthPx * 2f, 20f * density)
                shp.rotationDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
            } else {
                shp.cx = (shapeTouchDownX + x) / 2f
                shp.cy = (shapeTouchDownY + y) / 2f
                shp.width = abs(dx)
                shp.height = abs(dy)
                shp.rotationDeg = 0f
            }
            invalidate()
            return true
        }

        // 4. 处理自由画笔与马赛克
        val freehand = currentFreehand
        if (freehand != null) {
            val last = freehand.points.lastOrNull()
            if (last == null || hypot(x - last.x, y - last.y) >= 1.5f) {
                freehand.points.add(Point(x, y))
            }
            invalidate()
            return true
        }

        if (tool == ScreenDrawTool.ERASER && eraserMode == ScreenDrawEraserMode.STROKE) {
            eraseStrokeAt(x, y)
            return true
        }

        val drawingBox = currentBoxSelect
        if (drawingBox != null) {
            drawingBox.rect.set(
                min(drawingBox.rect.left, x),
                min(drawingBox.rect.top, y),
                max(drawingBox.rect.left, x),
                max(drawingBox.rect.top, y),
            )
            invalidate()
            return true
        }

        val laser = currentLaser
        if (laser != null) {
            laser.points.add(Point(x, y))
            invalidate()
            return true
        }
        return false
    }

    private fun handleActionUp(x: Float, y: Float) {
        if (activeBoxMode != BoxHitMode.NONE) {
            activeBoxMode = BoxHitMode.NONE
            activeBoxItem = null
            onContentChanged?.invoke()
            return
        }

        if (activeTransformMode != TransformTouchMode.NONE) {
            activeTransformMode = TransformTouchMode.NONE
            activeTransformElement = null
            onContentChanged?.invoke()
            return
        }

        currentFreehand?.let { stroke ->
            if (stroke.points.isNotEmpty()) {
                elements.add(stroke)
                redoElements.clear()
                onContentChanged?.invoke()
            }
            currentFreehand = null
            invalidate()
        }

        currentShape?.let { shp ->
            if (shp.width > 6f * density || shp.height > 6f * density) {
                deselectAll()
                shp.isSelected = true
                elements.add(shp)
                redoElements.clear()
                onContentChanged?.invoke()
            }
            currentShape = null
            invalidate()
        }

        currentBoxSelect?.let { box ->
            val l = min(box.rect.left, x)
            val r = max(box.rect.left, x)
            val t = min(box.rect.top, y)
            val b = max(box.rect.top, y)
            if (r - l > 10f * density && b - t > 10f * density) {
                box.rect.set(l, t, r, b)
                elements.add(box)
                redoElements.clear()
                onContentChanged?.invoke()
            }
            currentBoxSelect = null
            invalidate()
        }

        currentLaser = null
    }

    private fun handleActionCancel() {
        activeBoxMode = BoxHitMode.NONE
        activeBoxItem = null
        activeTransformMode = TransformTouchMode.NONE
        activeTransformElement = null
        currentFreehand = null
        currentShape = null
        currentBoxSelect = null
        currentLaser = null
        invalidate()
    }

    /** 框选矩形命中测试（四个角、四个边、框体内部） */
    private fun hitTestBoxSelect(box: BoxSelectItem, x: Float, y: Float): BoxHitMode {
        val r = box.rect
        val cornerSlop = 28f * density
        val edgeSlop = 20f * density

        // 四个角优先判定
        if (hypot(x - r.left, y - r.top) <= cornerSlop) return BoxHitMode.CORNER_TL
        if (hypot(x - r.right, y - r.top) <= cornerSlop) return BoxHitMode.CORNER_TR
        if (hypot(x - r.left, y - r.bottom) <= cornerSlop) return BoxHitMode.CORNER_BL
        if (hypot(x - r.right, y - r.bottom) <= cornerSlop) return BoxHitMode.CORNER_BR

        // 四个边缘判定
        if (Math.abs(x - r.left) <= edgeSlop && y in (r.top - edgeSlop)..(r.bottom + edgeSlop)) return BoxHitMode.EDGE_LEFT
        if (Math.abs(x - r.right) <= edgeSlop && y in (r.top - edgeSlop)..(r.bottom + edgeSlop)) return BoxHitMode.EDGE_RIGHT
        if (Math.abs(y - r.top) <= edgeSlop && x in (r.left - edgeSlop)..(r.right + edgeSlop)) return BoxHitMode.EDGE_TOP
        if (Math.abs(y - r.bottom) <= edgeSlop && x in (r.left - edgeSlop)..(r.right + edgeSlop)) return BoxHitMode.EDGE_BOTTOM

        // 框内平移
        if (r.contains(x, y)) return BoxHitMode.MOVE_BODY

        return BoxHitMode.NONE
    }

    /** 整笔擦除逻辑 */
    private fun eraseStrokeAt(x: Float, y: Float) {
        val threshold = (eraserRadiusPx + 10f * density)
        val iterator = elements.listIterator(elements.size)
        while (iterator.hasPrevious()) {
            val element = iterator.previous()
            val hit = when (element) {
                is Freehand -> element.points.any { hypot(it.x - x, it.y - y) <= threshold }
                is ShapeItem, is TextItem, is NumberItem -> isPointInsideElement(element, x, y)
                is BoxSelectItem -> false
            }
            if (hit) {
                iterator.remove()
                redoElements.add(element)
                invalidate()
                onContentChanged?.invoke()
                break
            }
        }
    }

    // ================= 绘制流程 =================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawElements(canvas, elements)

        // 正在绘制的临时图元
        currentFreehand?.let { drawFreehand(canvas, it) }
        currentShape?.let { drawShape(canvas, it) }
        currentBoxSelect?.let { drawBoxSelect(canvas, it) }

        // 激光笔绘制
        val now = SystemClock.uptimeMillis()
        laserStrokes.removeAll { now - it.createdAt >= LASER_LIFETIME_MS }
        laserStrokes.forEach { drawLaser(canvas, it, now) }
        if (laserStrokes.isNotEmpty()) {
            postInvalidateOnAnimation()
        }
    }

    fun snapshotBitmap(): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        // 导出时不绘制选中的虚线边框与手柄，也不绘制框选辅助矩形
        val savedSelected = elements.map { el ->
            when (el) {
                is TextItem -> el to el.isSelected
                is ShapeItem -> el to el.isSelected
                is NumberItem -> el to el.isSelected
                else -> el to false
            }
        }
        savedSelected.forEach { (el, _) ->
            when (el) {
                is TextItem -> el.isSelected = false
                is ShapeItem -> el.isSelected = false
                is NumberItem -> el.isSelected = false
                else -> Unit
            }
        }
        val exportList = elements.filter { it !is BoxSelectItem }
        drawElements(canvas, exportList)
        savedSelected.forEach { (el, sel) ->
            when (el) {
                is TextItem -> el.isSelected = sel
                is ShapeItem -> el.isSelected = sel
                is NumberItem -> el.isSelected = sel
                else -> Unit
            }
        }
        return out
    }

    private fun drawElements(canvas: Canvas, list: List<Element>) {
        list.forEach { el ->
            when (el) {
                is Freehand -> drawFreehand(canvas, el)
                is ShapeItem -> drawShape(canvas, el)
                is TextItem -> drawTextItem(canvas, el)
                is NumberItem -> drawNumberItem(canvas, el)
                is BoxSelectItem -> drawBoxSelect(canvas, el)
            }
        }
    }

    private fun drawFreehand(canvas: Canvas, stroke: Freehand) {
        if (stroke.points.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke.widthPx
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            if (stroke.isEraser) {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            } else if (stroke.isMosaic) {
                val shader = mosaicShader
                if (shader != null) {
                    this.shader = shader
                } else {
                    color = 0xAA888888.toInt()
                    pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
                }
            } else {
                color = stroke.color
            }
        }
        val path = buildSmoothedPath(stroke.points)
        canvas.drawPath(path, paint)
    }

    private fun buildSmoothedPath(points: List<Point>): Path {
        val path = Path()
        if (points.isEmpty()) return path
        val first = points.first()
        path.moveTo(first.x, first.y)
        if (points.size == 1) {
            path.lineTo(first.x + 0.1f, first.y + 0.1f)
            return path
        }
        var prev = first
        for (i in 1 until points.size) {
            val p = points[i]
            val mx = (prev.x + p.x) / 2f
            val my = (prev.y + p.y) / 2f
            if (i == 1) {
                path.lineTo(mx, my)
            } else {
                path.quadTo(prev.x, prev.y, mx, my)
            }
            prev = p
        }
        path.lineTo(points.last().x, points.last().y)
        return path
    }

    private fun drawShape(canvas: Canvas, item: ShapeItem) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = item.color
            strokeWidth = item.widthPx
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val w = item.width
        val h = item.height
        val pad = 12f * density
        val boxW = maxOf(w, 24f * density) + pad * 2f
        val boxH = maxOf(h, 24f * density) + pad * 2f

        canvas.save()
        canvas.translate(item.cx, item.cy)
        canvas.rotate(item.rotationDeg)

        when (item.shape) {
            ScreenDrawShape.RECTANGLE -> {
                canvas.drawRect(-w / 2f, -h / 2f, w / 2f, h / 2f, paint)
            }
            ScreenDrawShape.OVAL -> {
                canvas.drawOval(RectF(-w / 2f, -h / 2f, w / 2f, h / 2f), paint)
            }
            ScreenDrawShape.TRIANGLE -> {
                val path = Path().apply {
                    moveTo(0f, -h / 2f)
                    lineTo(-w / 2f, h / 2f)
                    lineTo(w / 2f, h / 2f)
                    close()
                }
                canvas.drawPath(path, paint)
            }
            ScreenDrawShape.LINE -> {
                canvas.drawLine(-w / 2f, 0f, w / 2f, 0f, paint)
            }
            ScreenDrawShape.DASHED_LINE -> {
                paint.pathEffect = DashPathEffect(floatArrayOf(14f * density / 2f, 10f * density / 2f), 0f)
                canvas.drawLine(-w / 2f, 0f, w / 2f, 0f, paint)
            }
            ScreenDrawShape.ARROW -> {
                canvas.drawLine(-w / 2f, 0f, w / 2f, 0f, paint)
                val head = 20f * density + item.widthPx
                val wing = 2.670353755551324
                val x1 = (w / 2f + cos(wing) * head).toFloat()
                val y1 = (sin(wing) * head).toFloat()
                val x2 = (w / 2f + cos(-wing) * head).toFloat()
                val y2 = (sin(-wing) * head).toFloat()
                val path = Path().apply {
                    moveTo(w / 2f, 0f)
                    lineTo(x1, y1)
                    moveTo(w / 2f, 0f)
                    lineTo(x2, y2)
                }
                canvas.drawPath(path, paint)
            }
        }

        if (item.isSelected) {
            drawSelectionBoxAndHandles(canvas, boxW, boxH)
        }
        canvas.restore()
    }

    /** 绘制文字图元（含选中状态虚线框、左上删除、右下缩放旋转手柄） */
    private fun drawTextItem(canvas: Canvas, item: TextItem) {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = item.color
            textSize = item.fontSizeSp * density
            textAlign = Paint.Align.CENTER
        }
        val (w, h) = getTextDimensions(item)

        canvas.save()
        canvas.translate(item.x, item.y)
        canvas.rotate(item.rotationDeg)

        // 绘制文字
        val fontMetrics = textPaint.fontMetrics
        val textY = -(fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText(item.text, 0f, textY, textPaint)

        // 若处于选中态，绘制编辑框与手柄
        if (item.isSelected) {
            drawSelectionBoxAndHandles(canvas, w, h)
        }
        canvas.restore()
    }

    /** 绘制序号角标 */
    private fun drawNumberItem(canvas: Canvas, item: NumberItem) {
        val r = item.sizePx / 2f
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = item.sizePx * 0.58f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val text = item.number.toString()
        val fm = textPaint.fontMetrics
        val ty = -(fm.ascent + fm.descent) / 2f

        canvas.save()
        canvas.translate(item.x, item.y)
        canvas.rotate(item.rotationDeg)

        when (item.style) {
            ScreenDrawNumberStyle.SOLID_CIRCLE -> {
                val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = item.color
                    style = Paint.Style.FILL
                    setShadowLayer(4f * density, 0f, 2f * density, 0x44000000)
                }
                canvas.drawCircle(0f, 0f, r, circlePaint)
                textPaint.color = Color.WHITE
                canvas.drawText(text, 0f, ty, textPaint)
            }
            ScreenDrawNumberStyle.HOLLOW_CIRCLE -> {
                val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = item.color
                    style = Paint.Style.STROKE
                    strokeWidth = 2.5f * density
                }
                canvas.drawCircle(0f, 0f, r, circlePaint)
                textPaint.color = item.color
                canvas.drawText(text, 0f, ty, textPaint)
            }
            ScreenDrawNumberStyle.PURE_NUMBER -> {
                textPaint.color = item.color
                canvas.drawText(text, 0f, ty, textPaint)
            }
        }

        if (item.isSelected) {
            val boxSize = item.sizePx + 16f * density
            drawSelectionBoxAndHandles(canvas, boxSize, boxSize)
        }
        canvas.restore()
    }

    /** 统一绘制图元选中时的虚线外框、左上角关闭按钮 (✕) 与右下角旋转缩放手柄 (⤢) */
    private fun drawSelectionBoxAndHandles(canvas: Canvas, w: Float, h: Float) {
        val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * density
            pathEffect = DashPathEffect(floatArrayOf(10f * density / 2f, 6f * density / 2f), 0f)
        }
        val rect = RectF(-w / 2f, -h / 2f, w / 2f, h / 2f)
        canvas.drawRect(rect, dashPaint)

        // 左上角关闭按钮 (✕)
        val btnR = 12f * density
        val delPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            setShadowLayer(4f * density, 0f, 2f * density, 0x44000000)
        }
        canvas.drawCircle(-w / 2f, -h / 2f, btnR, delPaint)
        val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF333333.toInt()
            strokeWidth = 2f * density
            strokeCap = Paint.Cap.ROUND
        }
        val crossR = 4.5f * density
        canvas.drawLine(-w / 2f - crossR, -h / 2f - crossR, -w / 2f + crossR, -h / 2f + crossR, crossPaint)
        canvas.drawLine(-w / 2f + crossR, -h / 2f - crossR, -w / 2f - crossR, -h / 2f + crossR, crossPaint)

        // 右下角旋转/缩放手柄 (⤢)
        val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            setShadowLayer(4f * density, 0f, 2f * density, 0x44000000)
        }
        canvas.drawCircle(w / 2f, h / 2f, btnR, handlePaint)
        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF333333.toInt()
            strokeWidth = 1.8f * density
            strokeCap = Paint.Cap.ROUND
        }
        val aR = 5f * density
        canvas.drawLine(w / 2f - aR, h / 2f - aR, w / 2f + aR, h / 2f + aR, arrowPaint)
        canvas.drawLine(w / 2f - aR, h / 2f - aR, w / 2f - aR + 3f * density, h / 2f - aR, arrowPaint)
        canvas.drawLine(w / 2f - aR, h / 2f - aR, w / 2f - aR, h / 2f - aR + 3f * density, arrowPaint)
        canvas.drawLine(w / 2f + aR, h / 2f + aR, w / 2f + aR - 3f * density, h / 2f + aR, arrowPaint)
        canvas.drawLine(w / 2f + aR, h / 2f + aR, w / 2f + aR, h / 2f + aR - 3f * density, arrowPaint)
    }

    private data class ElementDimensions(
        val cx: Float,
        val cy: Float,
        val w: Float,
        val h: Float,
        val rotationDeg: Float,
    )

    private fun getElementDimensions(element: Element): ElementDimensions? {
        return when (element) {
            is TextItem -> {
                val (w, h) = getTextDimensions(element)
                ElementDimensions(element.x, element.y, w, h, element.rotationDeg)
            }
            is ShapeItem -> {
                val pad = 12f * density
                val w = maxOf(element.width, 24f * density) + pad * 2f
                val h = maxOf(element.height, 24f * density) + pad * 2f
                ElementDimensions(element.cx, element.cy, w, h, element.rotationDeg)
            }
            is NumberItem -> {
                val size = element.sizePx + 16f * density
                ElementDimensions(element.x, element.y, size, size, element.rotationDeg)
            }
            else -> null
        }
    }

    private fun getDeleteButtonCenter(dim: ElementDimensions): Point {
        val rad = Math.toRadians(dim.rotationDeg.toDouble())
        val lx = -dim.w / 2f
        val ly = -dim.h / 2f
        val gx = dim.cx + (lx * cos(rad) - ly * sin(rad)).toFloat()
        val gy = dim.cy + (lx * sin(rad) + ly * cos(rad)).toFloat()
        return Point(gx, gy)
    }

    private fun getRotateScaleHandleCenter(dim: ElementDimensions): Point {
        val rad = Math.toRadians(dim.rotationDeg.toDouble())
        val lx = dim.w / 2f
        val ly = dim.h / 2f
        val gx = dim.cx + (lx * cos(rad) - ly * sin(rad)).toFloat()
        val gy = dim.cy + (lx * sin(rad) + ly * cos(rad)).toFloat()
        return Point(gx, gy)
    }

    private fun isPointInsideElement(element: Element, px: Float, py: Float): Boolean {
        val dim = getElementDimensions(element) ?: return false
        val rad = -Math.toRadians(dim.rotationDeg.toDouble())
        val dx = px - dim.cx
        val dy = py - dim.cy
        val lx = (dx * cos(rad) - dy * sin(rad)).toFloat()
        val ly = (dx * sin(rad) + dy * cos(rad)).toFloat()
        val hitPad = 14f * density
        val halfW = dim.w / 2f + hitPad
        val halfH = dim.h / 2f + hitPad
        return lx in -halfW..halfW && ly in -halfH..halfH
    }

    private fun getTextBounds(item: TextItem, textPaint: Paint): Rect {
        val bounds = Rect()
        textPaint.getTextBounds(item.text, 0, item.text.length, bounds)
        return bounds
    }

    private fun getTextDimensions(item: TextItem): Pair<Float, Float> {
        val p = Paint().apply { textSize = item.fontSizeSp * density }
        val bounds = Rect()
        p.getTextBounds(item.text, 0, item.text.length, bounds)
        val pad = 12f * density
        return (bounds.width() + pad * 2f) to (bounds.height() + pad * 2f)
    }

    /** 绘制框选聚焦矩形、暗化遮罩与边缘/边角拖动手柄 */
    private fun drawBoxSelect(canvas: Canvas, item: BoxSelectItem) {
        val rect = item.rect
        if (rect.width() <= 0 || rect.height() <= 0) return

        // 外部黑色半透明遮罩
        canvas.save()
        canvas.clipOutRect(rect)
        canvas.drawColor(0x99000000.toInt())
        canvas.restore()

        // 矩形高亮边框
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = item.color
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
        }
        canvas.drawRoundRect(rect, 4f * density, 4f * density, strokePaint)

        // 绘制四个角和四个边中点的控制手柄指示
        val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            setShadowLayer(3f * density, 0f, 1f * density, 0x66000000)
        }
        val handleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = item.color
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        val dotR = 5f * density
        val corners = listOf(
            Point(rect.left, rect.top),
            Point(rect.right, rect.top),
            Point(rect.left, rect.bottom),
            Point(rect.right, rect.bottom),
        )
        corners.forEach { c ->
            canvas.drawCircle(c.x, c.y, dotR, handlePaint)
            canvas.drawCircle(c.x, c.y, dotR, handleBorderPaint)
        }

        // 边框中点手柄小药丸
        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            setShadowLayer(2f * density, 0f, 1f * density, 0x55000000)
        }
        val midX = (rect.left + rect.right) / 2f
        val midY = (rect.top + rect.bottom) / 2f
        val pillLen = 14f * density
        val pillThick = 3f * density
        // 上下边中点
        canvas.drawRoundRect(RectF(midX - pillLen / 2, rect.top - pillThick / 2, midX + pillLen / 2, rect.top + pillThick / 2), 2f * density, 2f * density, pillPaint)
        canvas.drawRoundRect(RectF(midX - pillLen / 2, rect.bottom - pillThick / 2, midX + pillLen / 2, rect.bottom + pillThick / 2), 2f * density, 2f * density, pillPaint)
        // 左右边中点
        canvas.drawRoundRect(RectF(rect.left - pillThick / 2, midY - pillLen / 2, rect.left + pillThick / 2, midY + pillLen / 2), 2f * density, 2f * density, pillPaint)
        canvas.drawRoundRect(RectF(rect.right - pillThick / 2, midY - pillLen / 2, rect.right + pillThick / 2, midY + pillLen / 2), 2f * density, 2f * density, pillPaint)

        // 右下角关闭按钮 (✕)
        val btnCenter = getBoxSelectCloseButtonCenter(rect)
        val btnR = 14f * density
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            setShadowLayer(4f * density, 0f, 2f * density, 0x55000000)
        }
        canvas.drawCircle(btnCenter.x, btnCenter.y, btnR, bgPaint)
        val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF222222.toInt()
            strokeWidth = 2.2f * density
            strokeCap = Paint.Cap.ROUND
        }
        val cr = 5f * density
        canvas.drawLine(btnCenter.x - cr, btnCenter.y - cr, btnCenter.x + cr, btnCenter.y + cr, crossPaint)
        canvas.drawLine(btnCenter.x + cr, btnCenter.y - cr, btnCenter.x - cr, btnCenter.y + cr, crossPaint)
    }

    private fun getBoxSelectCloseButtonCenter(rect: RectF): Point {
        val offset = 22f * density
        val x = (rect.right + offset).coerceAtMost(width - 20f * density)
        val y = (rect.bottom + offset).coerceAtMost(height - 20f * density)
        return Point(x, y)
    }

    /** 激光笔光晕层绘制 */
    private fun drawLaser(canvas: Canvas, stroke: LaserStroke, now: Long) {
        if (stroke.points.isEmpty()) return
        val age = now - stroke.createdAt
        if (age >= LASER_LIFETIME_MS) return
        val fade = (1f - age / LASER_LIFETIME_MS.toFloat()).coerceIn(0f, 1f)
        val points = stroke.points
        if (points.size == 1) {
            val p = points[0]
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = stroke.color
                alpha = (fade * 255).toInt()
            }
            canvas.drawCircle(p.x, p.y, stroke.widthPx, paint)
            return
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = buildSmoothedPath(points)

        paint.color = stroke.color
        paint.strokeWidth = stroke.widthPx * 2.8f
        paint.alpha = (0.22f * fade * 255).toInt()
        canvas.drawPath(path, paint)

        paint.strokeWidth = stroke.widthPx * 1.4f
        paint.alpha = (0.75f * fade * 255).toInt()
        canvas.drawPath(path, paint)

        paint.color = Color.WHITE
        paint.strokeWidth = stroke.widthPx * 0.5f
        paint.alpha = (0.95f * fade * 255).toInt()
        canvas.drawPath(path, paint)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val DEFAULT_COLOR = 0xFFFF3B30.toInt()
        private const val LASER_LIFETIME_MS = 1200L
    }
}
