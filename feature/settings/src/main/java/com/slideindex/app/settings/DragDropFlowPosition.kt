package com.slideindex.app.settings

enum class DragDropFlowPosition(val id: Int) {
    BOTTOM(0),
    LEFT(1),
    RIGHT(2);

    companion object {
        fun fromId(id: Int): DragDropFlowPosition = entries.find { it.id == id } ?: BOTTOM
    }
}
