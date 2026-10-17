/*
 * FV「自定义手势」参考实现 —— 第 5 层：运行时触发（FooViewService$c3 的最小等价物）
 *
 * 与 FV 的对应：
 *   FooViewService.p1 / GesturePoint 累积  ->  FvGestureTrigger.onTouch()
 *   C3:1731  q1.o.h(1).H(g, true)         ->  onMovePreview（可选，实时预览）
 *   C3:3371  q1.o.h(1).G(g)               ->  onUpRecognition（决定动作）
 *   C3:3371 之后的 want 状态机             ->  TriggerResult
 *
 * 行为（与 FV 完全一致，见 docs/reverse/fv-custom-gesture/03-runtime-pipeline.md §2.3）：
 *   UP 时
 *     id > 0  -> 命中：执行该手势绑定的动作（[Listener.onTriggered] 直接拿到 `GestureAction`）
 *     id == 0 -> 画的是直线：交给本次拖动原本配置的动作（[Listener.onStraightLine]）
 *     id < 0  -> 曲线但没命中：本次什么都不做（[Listener.onNoMatch]）
 *
 * ⚠ 本类只负责「采样 + 判定 + 回调」，**不做任何触摸接线**；具体挂到哪个控件上由调用方决定。
 * 悬浮球那条路目前走的是 `FvFloatBallBridge.resolveCustomGesture()`（同一套三态判定）。
 */
package com.slideindex.app.fvgesture

import android.gesture.Gesture
import android.gesture.GesturePoint
import android.gesture.GestureStroke
import android.view.MotionEvent
import com.slideindex.app.gesture.GestureAction

class FvGestureTrigger(
    private val gestureType: Int = FvGestureLibrary.TYPE_ICON,
    /** FV 只在“拖动悬浮球”时才走自定义手势识别；调用方用它判断本次交互是否属于拖动 */
    private val isDragInteraction: () -> Boolean = { true }
) {

    interface Listener {
        /** 命中：动作负载由手势自己携带（镜像 ID 已在库内归一为基 ID） */
        fun onTriggered(gestureId: Int, action: GestureAction)

        /** 画了折线/曲线但没命中（FV: want = 0，什么都不做） */
        fun onNoMatch()

        /** 画的是直线（FV: H() == 0）——调用方应执行本次拖动原本配置的动作 */
        fun onStraightLine()

        /** 可选：MOVE 过程中的实时预览（需要 showGestureTracking 打开） */
        fun onPreview(gestureId: Int, action: GestureAction?) {}
    }

    var listener: Listener? = null

    /** 是否开启 MOVE 实时预览（对应偏好 showGestureTracking） */
    var previewEnabled: Boolean = false

    private val points = ArrayList<GesturePoint>()
    private var lastPreviewId = 0

    /** 把触摸事件喂进来；坐标请传屏幕 raw 坐标（FV 就是这么做的） */
    fun onTouch(event: MotionEvent, rawX: Float, rawY: Float) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                points.clear()
                points.add(GesturePoint(rawX, rawY, System.currentTimeMillis()))
                lastPreviewId = 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (points.isEmpty()) return
                points.add(GesturePoint(rawX, rawY, System.currentTimeMillis()))
                if (previewEnabled) preview()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (points.isEmpty()) return
                points.add(GesturePoint(rawX, rawY, System.currentTimeMillis()))
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    points.clear()
                    return
                }
                if (isDragInteraction()) trigger()
                points.clear()
                lastPreviewId = 0
            }
        }
    }

    private fun buildGesture(): Gesture = Gesture().apply { addStroke(GestureStroke(points)) }

    /** FV: C3:1731 —— 实时预览 */
    private fun preview() {
        val lib = FvGestureLibrary.of(gestureType)
        if (!lib.isReady || lib.userCount == 0) return
        val id = lib.recognize(buildGesture(), rawId = true)
        if (id > 0) {
            if (id != lastPreviewId) {
                lastPreviewId = id
                listener?.onPreview(id, lib.actionOf(id))
            }
        } else if (lastPreviewId != 0) {
            lastPreviewId = 0
            listener?.onPreview(0, null)
        }
    }

    /** FV: C3:3371 —— 抬手判定 */
    private fun trigger() {
        val lib = FvGestureLibrary.of(gestureType)
        if (!lib.isReady || lib.userCount == 0) return
        val id = lib.recognizeForTrigger(buildGesture())
        when {
            id > 0 -> lib.actionOf(id)?.let { listener?.onTriggered(id, it) } ?: listener?.onNoMatch()
            id == 0 -> listener?.onStraightLine()
            else -> listener?.onNoMatch()
        }
    }
}
