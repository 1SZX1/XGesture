/*
 * FV「自定义手势」参考实现 —— 第 3 层：手绘面板（FooView com.fooview.android.gesture.GesturePanel）
 *
 * 复刻策略：**同样继承平台 android.gesture.GestureOverlayView**，并用与 FV 完全相同的
 * 参数配置它（笔宽 20px、单笔/多笔模式、淡出、颜色…），这样轨迹绘制与淡出动画在同一
 * 台设备上与 FV 逐像素一致；本类只额外做两件事：
 *   1) 记录原始触点（给终点箭头算方向用）；
 *   2) onDraw 里补画「起点绿点 + 终点蓝箭头」（FV 只在图标手势库的弹窗里显示）。
 *
 * 与 FV 数值一一对应（见 05-ui-spec.md）：
 *   setGestureStrokeWidth(20.0f)      原始 px，不随密度
 *   setGestureStrokeType(1)           MULTIPLE（编辑弹窗会改成 0 = SINGLE）
 *   setFadeEnabled(true) / setFadeOffset(1000L)
 *   起点圆半径 30.0f（原始 px），颜色 #FF689F38
 *   终点箭头线长 dp(20)，颜色 #FF0288D1，两条 45° 线
 */
package com.slideindex.app.fvgesture

import android.content.Context
import android.gesture.Gesture
import android.gesture.GestureOverlayView
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PathMeasure
import android.util.AttributeSet

class FvGesturePanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GestureOverlayView(context, attrs) {

    companion object {
        /** 起止点标记颜色（FV: R.color.gesture_start_point / gesture_end_arrow） */
        const val COLOR_START_POINT = 0xFF689F38.toInt()
        const val COLOR_END_ARROW = 0xFF0288D1.toInt()

        /** 笔宽（原始 px，不随密度；FV 原值 20.0f） */
        const val GESTURE_STROKE_WIDTH = 20f

        /** 起点圆半径（原始 px；FV 原值 30.0f） */
        const val START_POINT_RADIUS = 30f

        /** 终点箭头线长（dp；FV 用 n5.q.a(20)） */
        const val END_ARROW_LENGTH_DP = 20

        /**
         * GestureOverlayView.setGestureStrokeType 的取值。
         * FV 自己也是直接传字面量（`setGestureStrokeType(1)` / `(0)`），因为平台没有暴露成对的常量名。
         */
        const val STROKE_TYPE_SINGLE = 0
        const val STROKE_TYPE_MULTIPLE = 1
    }

    /** 是否绘制起止点标记。FV: GesturePanel.f9660q，仅图标手势库（type == 1）时为 true */
    var markersEnabled: Boolean = false

    /** FV: f9657n —— 「识别完成后延迟多少 ms 清除轨迹」，默认 400；-1 表示不清 */
    var fadingTime: Int = 400

    /** 等待「量出尺寸后」再回显的轨迹（见 [setGestureAfterLayout]） */
    private var pendingGesture: Gesture? = null
    private var pendingClear = false

    private val markerPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        strokeWidth = GESTURE_STROKE_WIDTH
    }

    init {
        configure()
    }

    /** FV: GesturePanel.j(Context) */
    private fun configure() {
        setGestureStrokeWidth(GESTURE_STROKE_WIDTH)
        setGestureStrokeType(STROKE_TYPE_MULTIPLE)   // FV 初始化值；编辑弹窗会改成 SINGLE
        setFadeEnabled(true)
        setFadeOffset(1000L)
        setEventsInterceptionEnabled(false)
    }

    /** 供编辑弹窗使用：完全照抄 q1.m 构造里的那几行 */
    fun configureForEditDialog(showMarkers: Boolean) {
        markersEnabled = showMarkers
        fadingTime = -1
        fadeOffset = Int.MAX_VALUE.toLong()      // ★ 轨迹永久保留（FV 原值 2147483647）
        gestureColor = 0xFFFF0000.toInt()
        uncertainGestureColor = 0xFFFF0000.toInt()
        gesture = Gesture()
        setGestureStrokeType(STROKE_TYPE_SINGLE)
        // ★★ 必须注册一个（内容可以是空的）OnGesturePerformedListener：
        // 平台把这个监听器是否存在当作 mHandleGestureActions；未注册时抬手会走
        // 「SINGLE ⇒ 立即 clear」分支，刚画完的轨迹会**瞬间消失**（用户实测到的 bug）。
        // FV 的 q1.m 也注册了一个只打日志的监听器，我们注册空实现即可。
        // 证据：docs/reverse/fv-custom-gesture/06-android-gesture-framework-spec.md §6.7 对照表。
        addOnGesturePerformedListener { _, _ -> }
    }

    override fun clear(animated: Boolean) {
        super.clear(animated)
    }

    /**
     * 弹窗回显专用入口：**等本 View 量出真实宽高之后再**调用平台的 `setGesture()`。
     *
     * 为什么需要它：平台 `setGesture()` 用**调用当时的 `getWidth()/getHeight()`** 把轨迹平移居中
     * （源码见 docs/reverse/fv-custom-gesture/06-android-gesture-framework-spec.md §6.3）；
     * 若在布局完成前调用（宽高为 0），轨迹会被挪到左上角（用户实测现象）。
     *
     * 这里把请求排队，等 [onSizeChanged] 拿到真实尺寸后再真正 set。
     */
    fun setGestureAfterLayout(target: Gesture?) {
        pendingGesture = target
        pendingClear = target == null
        applyPendingGestureIfPossible()
    }

    private fun applyPendingGestureIfPossible() {
        if (width <= 0 || height <= 0) return
        if (pendingClear) {
            pendingClear = false
            pendingGesture = null
            clear(false)
            invalidate()
            return
        }
        val target = pendingGesture ?: return
        pendingGesture = null
        gesture = target
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyPendingGestureIfPossible()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!markersEnabled) return
        // ★ 起止点标记直接画在**平台自己那条已经平移居中的 path** 上（`getGesturePath()`），
        // 而不是自己再算一遍偏移 —— 这样无论 fresh 绘制还是 setGesture 回显，
        // 绿点/箭头都必然贴着红轨迹（此前自己算偏移，回显时会与轨迹错开）。
        val drawnPath = gesturePath ?: return
        val measure = PathMeasure(drawnPath, false)
        val length = measure.length
        if (length <= 0f) return
        val pos = FloatArray(2)
        val tan = FloatArray(2)

        // 起点绿点（FV: 半径 30 原始 px，颜色 #FF689F38）
        if (measure.getPosTan(0f, pos, null)) {
            markerPaint.color = COLOR_START_POINT
            canvas.drawCircle(pos[0], pos[1], START_POINT_RADIUS, markerPaint)
        }

        // 终点蓝箭头（FV: 线长 dp(20)，方向取轨迹末端切线；两条 45° 线组成箭头）
        if (measure.getPosTan(length, pos, tan)) {
            val angle = Math.toDegrees(kotlin.math.atan2(tan[1].toDouble(), tan[0].toDouble())).toFloat()
            canvas.save()
            canvas.translate(pos[0], pos[1])
            canvas.rotate(angle)
            val len = dp(context, END_ARROW_LENGTH_DP).toFloat()
            markerPaint.color = COLOR_END_ARROW
            canvas.drawLine(0f, 0f, len, -len, markerPaint)
            canvas.drawLine(0f, 0f, -len, -len, markerPaint)
            canvas.restore()
        }
    }
}

