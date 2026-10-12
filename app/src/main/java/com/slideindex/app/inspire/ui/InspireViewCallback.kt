/*
 * 移植自 GestureEVO（https://github.com/LumiaGG/GestureEVO，GPL-3.0-only）
 *   源文件：inspire/ui/InspireView.kt（该文件内联的 interface InspireViewCallBack）
 * 已按本项目的数据模型、主题与集成层改写（部分模块拆分为多文件、部分能力为本项目新增）；
 * 版权与许可声明见 THIRD_PARTY_NOTICES.md，文件级映射见 docs/ATTRIBUTIONS.md。
 */

package com.slideindex.app.inspire.ui

import android.graphics.Rect

interface InspireViewCallback {
    fun onDragMove(dragSelectRect: Rect) = Unit
    fun onDragUp(dragSelectRect: Rect)
    fun onDragCancel() = Unit
    fun onCopyText(dragSelectRect: Rect, isPress: Boolean) = Unit
    fun onSaveImage(dragSelectRect: Rect, isPress: Boolean) = Unit
    fun onSharImage(dragSelectRect: Rect, isPress: Boolean) = Unit
    fun onPinImage(dragSelectRect: Rect) = Unit
    fun onPinImageLongPress(dragSelectRect: Rect) = Unit
}
