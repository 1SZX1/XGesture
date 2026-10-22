package com.slideindex.app.settings

enum class DragDropFlowPosition(val id: Int) {
    BOTTOM(0),
    LEFT(1),
    RIGHT(2),

    /**
     * 环形罗盘形态。
     *
     * 严格说它不是“位置”——BOTTOM/LEFT/RIGHT 是条状栏贴哪条边，而 RING 是把整个
     * 流转栏换成屏幕中央的圆盘。沿用同一个设置项，是为了让位置/形态共用一套
     * 读写链路，而不是再开一个几乎重复的设置字段。
     */
    RING(3);

    companion object {
        fun fromId(id: Int): DragDropFlowPosition = entries.find { it.id == id } ?: BOTTOM
    }
}
