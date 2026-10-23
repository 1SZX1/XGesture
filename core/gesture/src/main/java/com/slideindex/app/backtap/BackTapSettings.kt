package com.slideindex.app.backtap

import com.slideindex.app.gesture.GestureAction

enum class BackTapMode(val storageId: Int) {
    ALWAYS(0),
    SCREEN_OFF(1),
    SCREEN_ON(2),
    ;

    companion object {
        fun fromId(id: Int): BackTapMode = entries.firstOrNull { it.storageId == id } ?: SCREEN_ON
    }
}

data class BackTapSettings(
    val enabled: Boolean = false,
    val sensitivity: Int = 5,
    val range: Int = 5,
    val mode: BackTapMode = BackTapMode.SCREEN_ON,
    val pauseWhileCharging: Boolean = false,
    val vibrationFeedbackEnabled: Boolean = true,
    val action: GestureAction = GestureAction.None,
)
