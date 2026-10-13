package com.slideindex.app.macro

import android.content.Context
import com.slideindex.app.gesture.GestureAction

/**
 * 「运行宏」动作的落地点：把动作上的宏 id 换成宏体然后交给 [MacroEngine]。
 *
 * 单独成一层是为了让动作系统只依赖一个窄入口（动作里只存 id 字符串，宏被删掉也不影响绑定解析）。
 */
object MacroActionRunner {

    /**
     * @return true 表示已受理或至少不需要再往下传（宏不存在时返回 false，调用方按"无效动作"处理）
     */
    fun run(context: Context, action: GestureAction.RunMacro): Boolean {
        val macro = MacroStore.byId(context, action.macroId) ?: return false
        return MacroEngine(context).run(macro)
    }
}
