/*
 * 移植自 GestureEVO（https://github.com/LumiaGG/GestureEVO，GPL-3.0-only）
 *   源文件：inspire/imageeditor/model/EditAction.kt
 * 已按本项目的数据模型、主题与集成层改写（部分模块拆分为多文件、部分能力为本项目新增）；
 * 版权与许可声明见 THIRD_PARTY_NOTICES.md，文件级映射见 docs/ATTRIBUTIONS.md。
 */

package com.slideindex.app.imageeditor.model

sealed interface EditAction {
    val id: String

    data class Doodle(
        override val id: String,
        val points: List<EditorPoint>,
        val color: Int,
        val strokeWidth: Float,
        val isEraser: Boolean = false,
    ) : EditAction

    data class Shape(
        override val id: String,
        val type: ShapeType,
        val start: EditorPoint,
        val end: EditorPoint,
        val color: Int,
        val strokeWidth: Float,
    ) : EditAction

    data class Text(
        override val id: String,
        val text: String,
        val anchor: EditorPoint,
        val color: Int,
        val textSize: Float,
    ) : EditAction

    data class Mosaic(
        override val id: String,
        val points: List<EditorPoint>,
        val strokeWidth: Float,
    ) : EditAction

    data class NumberBadge(
        override val id: String,
        val number: Int,
        val anchor: EditorPoint,
        val color: Int,
        val style: NumberBadgeStyle,
        val badgeSize: Float,
    ) : EditAction
}
