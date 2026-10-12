package com.slideindex.app.overlay.quickmenu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QuickMenuCalcPad]（键盘状态机）与 [formatCalcResult]（结果整形）的单测。
 *
 * 这两块是纯 JVM 件，不碰 Android / Compose，所以每个键的行为都能在这里钉住 —— 计算器页本身
 * 只剩"把状态画出来"，出错只可能是布局问题（要看真机）。
 */
class QuickMenuCalculatorScreenTest {

    // ------------------------------------------------------------ 小工具

    /** 逐键按下，只关心最终状态。 */
    private fun pressAll(vararg keys: CalcKey): CalcPadState =
        keys.fold(CalcPadState()) { state, key -> QuickMenuCalcPad.press(state, key).state }

    /** 依次敲一串数字（`"12.5"` → `1` `2` `.` `5`）。 */
    private fun digits(text: String): Array<CalcKey> = text.map { ch ->
        if (ch == '.') CalcKey.Dot else CalcKey.Digit(ch.toString())
    }.toTypedArray()

    private fun type(text: String): CalcPadState = pressAll(*digits(text))

    // ------------------------------------------------------------ 输入

    @Test
    fun typingDigits_replacesInitialZero() {
        val state = pressAll(CalcKey.Digit("0"), CalcKey.Digit("5"))
        // 初始那个 0 应该被 5 顶掉，而不是留下 "05"
        assertEquals("5", state.expression)
        assertEquals("5", state.display)
    }

    @Test
    fun typingDigits_appendsToCurrentOperand() {
        val state = type("123")
        assertEquals("123", state.expression)
        assertEquals("123", state.display)
    }

    @Test
    fun dot_insertsOnlyOnePerOperand() {
        val state = pressAll(*digits("1.2"), CalcKey.Dot, CalcKey.Digit("3"))
        assertEquals("1.23", state.expression)
    }

    @Test
    fun dot_afterOperator_startsWithLeadingZero() {
        // `5×.` 应该变成 `5×0.` 而不是按不出点（QuickDash 在这里会因为 display=="0" 而卡住）
        val state = pressAll(CalcKey.Digit("5"), CalcKey.Op("×"), CalcKey.Dot)
        assertEquals("5×0.", state.expression)
        assertEquals("0.", state.display)
    }

    @Test
    fun operator_replacesPreviousOperator() {
        val state = pressAll(CalcKey.Digit("5"), CalcKey.Op("+"), CalcKey.Op("×"))
        assertEquals("5×", state.expression)
        // 按下运算符后主显示清 0，等着输入下一个操作数
        assertEquals("0", state.display)
    }

    @Test
    fun operator_keepsUnaryMinusOfNextOperand() {
        // `5×-` 里的 `-` 是负号（引擎认这种写法），再按 `+` 只该顶掉这个负号，
        // 不该把 `×` 也一起吃掉、变成孤零零的 `5+`。
        val state = pressAll(
            CalcKey.Digit("5"), CalcKey.Op("×"), CalcKey.ToggleSign, CalcKey.Op("+"),
        )
        assertEquals("5×+", state.expression)
    }

    // ------------------------------------------------------------ 退格 / 清空

    @Test
    fun backspace_onSingleDigit_fallsBackToZero() {
        val state = pressAll(CalcKey.Digit("7"), CalcKey.Backspace)
        assertEquals("", state.expression)
        assertEquals("0", state.display)
    }

    @Test
    fun backspace_afterOperator_restoresPreviousOperand() {
        // QuickDash 这里会把主显示删成 `1`（display 与 expression 各删一个字符），
        // 我们的约定是"主显示永远等于表达式末尾操作数"，所以应该回到 12。
        val state = pressAll(*digits("12"), CalcKey.Op("+"), CalcKey.Backspace)
        assertEquals("12", state.expression)
        assertEquals("12", state.display)
    }

    @Test
    fun backspace_dropsLoneMinusSign() {
        val state = pressAll(CalcKey.Digit("3"), CalcKey.Backspace, CalcKey.ToggleSign, CalcKey.Backspace)
        // 只剩一个孤零零的负号没有意义，应该回到初始状态
        assertEquals("", state.expression)
        assertEquals("0", state.display)
    }

    @Test
    fun backspace_loneSignAfterOperator_keepsPrefix() {
        // 只在"整个表达式就是 `-`"时才复位；`5×-` 退格应当退回 `5×`，不能把算式全清掉
        val state = pressAll(CalcKey.Digit("5"), CalcKey.Op("×"), CalcKey.ToggleSign, CalcKey.Backspace)
        assertEquals("5×", state.expression)
        assertEquals("0", state.display)
    }

    @Test
    fun clear_resetsEverything() {
        val state = pressAll(*digits("12"), CalcKey.Op("+"), CalcKey.Digit("7"), CalcKey.Clear)
        assertEquals("", state.expression)
        assertEquals("0", state.display)
    }

    // ------------------------------------------------------------ 正负号 / 百分号

    @Test
    fun toggleSign_flipsOnlyCurrentOperand() {
        val state = pressAll(*digits("12"), CalcKey.Op("+"), CalcKey.Digit("7"), CalcKey.ToggleSign)
        assertEquals("12+-7", state.expression)
        assertEquals("-7", state.display)
    }

    @Test
    fun toggleSign_twiceRestoresOperand() {
        val state = pressAll(*digits("12"), CalcKey.Op("+"), CalcKey.Digit("7"), CalcKey.ToggleSign, CalcKey.ToggleSign)
        assertEquals("12+7", state.expression)
    }

    @Test
    fun toggleSign_onEmptyPad_isNoop() {
        val state = pressAll(CalcKey.ToggleSign)
        assertEquals("", state.expression)
        assertEquals("0", state.display)
    }

    @Test
    fun toggleSign_afterOperator_addsSignForNextNumber() {
        // 光标停在运算符后面时还没有数字，按 ± 是"给下一个数补个负号"（`12+` → `12+-`）
        val state = pressAll(*digits("12"), CalcKey.Op("+"), CalcKey.ToggleSign)
        assertEquals("12+-", state.expression)
        assertEquals("-", state.display)
        // 再按一次是取消（不能攒成 `12+--`）
        assertEquals(
            "12+",
            pressAll(*digits("12"), CalcKey.Op("+"), CalcKey.ToggleSign, CalcKey.ToggleSign).expression,
        )
    }

    @Test
    fun toggleSign_afterOperatorThenDigits() {
        // `5×-` 后面接着输数字，主显示应当是负的（引擎把 `×-3` 读成乘 -3）
        val state = pressAll(
            CalcKey.Digit("5"), CalcKey.Op("×"), CalcKey.ToggleSign, CalcKey.Digit("3"),
        )
        assertEquals("5×-3", state.expression)
        assertEquals("-3", state.display)
        val outcome = QuickMenuCalcPad.press(state, CalcKey.Equals)
        assertEquals("-15", outcome.state.display)
    }

    @Test
    fun toggleSign_afterBinaryMinus_addsItsOwnSign() {
        // `12-7` 里那个 `-` 是二元减号，不是 7 的符号：按 ± 应当补出 `12--7`（= 19），
        // 而不是把它当成"7 已经有负号了、取消掉"（那会变成 `127`）。
        val state = pressAll(*digits("12"), CalcKey.Op("-"), CalcKey.Digit("7"), CalcKey.ToggleSign)
        assertEquals("12--7", state.expression)
        assertEquals("-7", state.display)
    }

    @Test
    fun dot_afterLoneSign_keepsSignAndAddsZero() {
        val state = pressAll(
            CalcKey.Digit("5"), CalcKey.Op("×"), CalcKey.ToggleSign, CalcKey.Dot, CalcKey.Digit("5"),
        )
        assertEquals("5×-0.5", state.expression)
        assertEquals("-0.5", state.display)
    }

    @Test
    fun toggleSign_threeTimesEndsWithCanceledSign() {
        // 连续按 ± 必须是"翻一次符号"，三次之后只剩一个负号，不能攒成 `12+--7`
        val state = pressAll(
            *digits("12"), CalcKey.Op("+"), CalcKey.Digit("7"),
            CalcKey.ToggleSign, CalcKey.ToggleSign, CalcKey.ToggleSign,
        )
        assertEquals("12+-7", state.expression)
        assertEquals("-7", state.display)
    }

    @Test
    fun percent_dividesOnlyCurrentOperand() {
        // QuickDash 的 `%` 会把整个表达式替换成百分号化后的那个数（`100+50` → `0.5`），我们只换末尾操作数
        val state = pressAll(*digits("100"), CalcKey.Op("+"), *digits("50"), CalcKey.Percent)
        assertEquals("100+0.5", state.expression)
        assertEquals("0.5", state.display)
    }

    @Test
    fun percent_onWholeNumber_stripsTrailingZeros() {
        val state = pressAll(*digits("50"), CalcKey.Percent)
        assertEquals("0.5", state.expression)
    }

    @Test
    fun percent_withNoOperand_isNoop() {
        val state = pressAll(*digits("12"), CalcKey.Op("+"), CalcKey.Percent)
        assertEquals("12+", state.expression)
    }

    // ------------------------------------------------------------ 上一次计算

    @Test
    fun lastCalc_archivedByEquals() {
        // `=` 就是"确认收尾"：一按就把 `算式 = 结果` 归档成最顶那一行（不是等用户接着输入才记）
        val state = pressAll(*digits("12"), CalcKey.Op("+"), *digits("5"), CalcKey.Equals)
        assertEquals("12+5 = 17", state.lastCalc?.label)
        assertEquals("17", state.display)
        assertTrue(state.justEvaluated)
    }

    @Test
    fun lastCalc_keptWhenNextEntryStarts() {
        // 接着输入数字 / 小数点 / 左括号 / 运算符都是"开始新的一条"，归档那一行留着不动（方便回去取）
        fun afterEquals(key: CalcKey): CalcPadState =
            pressAll(*digits("12"), CalcKey.Op("+"), *digits("5"), CalcKey.Equals, key)

        assertEquals("12+5 = 17", afterEquals(CalcKey.Digit("3")).lastCalc?.label)
        assertEquals("12+5 = 17", afterEquals(CalcKey.Dot).lastCalc?.label)
        assertEquals("12+5 = 17", afterEquals(CalcKey.OpenParen).lastCalc?.label)
        assertEquals("12+5 = 17", afterEquals(CalcKey.Op("×")).lastCalc?.label)
        // 数字 / 小数点 / 左括号都是另起一条新算式；运算符则是从结果继续算
        assertEquals("3", afterEquals(CalcKey.Digit("3")).expression)
        assertEquals("0.", afterEquals(CalcKey.Dot).expression)
        assertEquals("(", afterEquals(CalcKey.OpenParen).expression)
        assertEquals("17×", afterEquals(CalcKey.Op("×")).expression)
    }

    @Test
    fun lastCalc_doubleEqualsKeepsOneCleanEntry() {
        // 连按两次 `=`：第二次归档的还是同一条，不能冒出 `17 = 17` 这种垃圾行
        val twice = pressAll(*digits("12"), CalcKey.Op("+"), *digits("5"), CalcKey.Equals, CalcKey.Equals)
        assertEquals("12+5 = 17", twice.lastCalc?.label)
        assertEquals("17", twice.display)
        // 算完一条再算下一条：归档更新成最新的一条，连按 `=` 也不会把它改成垃圾
        val kept = pressAll(
            *digits("12"), CalcKey.Op("+"), *digits("5"), CalcKey.Equals, CalcKey.Op("+"),
            CalcKey.Digit("3"), CalcKey.Equals, CalcKey.Equals,
        )
        assertEquals("17+3 = 20", kept.lastCalc?.label)
    }

    @Test
    fun lastCalc_notArchivedWhenResultEqualsExpression() {
        // `5=` 的"算式"就是结果本身，归档出来是 `5 = 5` 这种废话：不归档（此前也没有别的记录）
        assertNull(pressAll(CalcKey.Digit("5"), CalcKey.Equals).lastCalc)
        // `√9` 之后再按 `=` 也不会写出 `3 = 3`
        assertNull(pressAll(CalcKey.Digit("9"), CalcKey.Sqrt, CalcKey.Equals).lastCalc)
    }

    @Test
    fun lastCalc_keptWhenOperatorFollowsEquals() {
        val state = pressAll(*digits("12"), CalcKey.Op("+"), *digits("5"), CalcKey.Equals, CalcKey.Op("+"))
        assertEquals("12+5 = 17", state.lastCalc?.label)
        // 记录下来了，同时算式已经进入"从 17 继续算"
        assertEquals("17+", state.expression)
        assertEquals("0", state.display)
    }

    @Test
    fun lastCalc_keptWhileUserKeepsTyping() {
        // 接着输入（数字、运算符）不该改写已归档的那一行，它一直留着方便回去取
        val typed = pressAll(
            *digits("12"), CalcKey.Op("+"), *digits("5"), CalcKey.Equals, CalcKey.Op("+"), *digits("3"),
        )
        assertEquals("12+5 = 17", typed.lastCalc?.label)
        val again = pressAll(
            *digits("12"), CalcKey.Op("+"), *digits("5"), CalcKey.Equals, CalcKey.Op("+"), *digits("3"),
            CalcKey.Equals, CalcKey.Op("×"),
        )
        // 第二次按 `=` 结束（17+3=20）会把它更新成最新的一条
        assertEquals("17+3 = 20", again.lastCalc?.label)
    }

    @Test
    fun lastCalc_openLastCalculation_fillsResultBack() {
        val done = pressAll(*digits("12"), CalcKey.Op("+"), *digits("5"), CalcKey.Equals)
        val afterOp = QuickMenuCalcPad.press(done, CalcKey.Op("×")).state
        val restored = QuickMenuCalcPad.openLastCalculation(afterOp)
        // 点那一行 = 把上次结果填回算式，状态与"刚按下等号"一致，所以能接着按运算符
        assertEquals("17", restored.expression)
        assertEquals("17", restored.display)
        assertTrue(restored.justEvaluated)
        assertEquals("12+5 = 17", restored.lastCalc?.label)
        val continued = QuickMenuCalcPad.press(restored, CalcKey.Op("×")).state
        assertEquals("17×", continued.expression)
    }

    @Test
    fun lastCalc_openWithoutEntry_isNoop() {
        val state = pressAll(CalcKey.Digit("7"))
        assertEquals(state, QuickMenuCalcPad.openLastCalculation(state))
    }

    @Test
    fun lastCalc_keptWhenBackspaceDropsTheOperator() {
        // 按运算符之后退格只删掉那个运算符（和本类其它退格用例同一个规则：`12+` 退格回到 `12`，
        // 见 backspace_afterOperator_restoresPreviousOperand），所以 `17+` 退格得到 `17` —— 结果还在，
        // 可以接着改；要真清空请按 `C`。这一条要钉住的是：归档那一行不因为一次退格就消失。
        val state = pressAll(
            *digits("12"), CalcKey.Op("+"), *digits("5"), CalcKey.Equals, CalcKey.Op("+"), CalcKey.Backspace,
        )
        assertEquals("17", state.expression)
        assertEquals("17", state.display)
        assertEquals("12+5 = 17", state.lastCalc?.label)
    }

    @Test
    fun lastCalc_keptWhenOperatorFollowsRestoredResult() {
        // 点上一行把 `17` 填回来、接着按运算符：归档那条不能被改写成没有信息量的 `17 = 17`
        val done = pressAll(*digits("12"), CalcKey.Op("+"), *digits("5"), CalcKey.Equals)
        val recorded = QuickMenuCalcPad.press(done, CalcKey.Op("+")).state
        val restored = QuickMenuCalcPad.openLastCalculation(recorded)
        val continued = QuickMenuCalcPad.press(restored, CalcKey.Op("×")).state
        assertEquals("17×", continued.expression)
        assertEquals("12+5 = 17", continued.lastCalc?.label)
    }

    @Test
    fun lastCalc_notArchivedBySqrt() {
        // `√` 只把结果放进大数字，归档要按 `=`；这里也没按过 `=`，所以顶行仍是空的
        val state = pressAll(CalcKey.Digit("9"), CalcKey.Sqrt, CalcKey.Op("+"))
        assertEquals("3+", state.expression)
        assertNull(state.lastCalc)
    }

    @Test
    fun lastCalc_clearedByClearKey() {
        // `C` 是唯一会把它清掉的键：按了 C 就是想从头来
        val state = pressAll(*digits("12"), CalcKey.Op("+"), *digits("5"), CalcKey.Equals, CalcKey.Op("+"), CalcKey.Clear)
        assertEquals(null, state.lastCalc)
    }

    @Test
    fun lastCalc_divisionByZeroKeepsPreviousEntry() {
        // 算不出来（除零）不更新记录，也不报错；之前那条还在
        val state = pressAll(
            *digits("12"), CalcKey.Op("+"), *digits("5"), CalcKey.Equals, CalcKey.Op("+"),
            CalcKey.Digit("5"), CalcKey.Op("÷"), CalcKey.Digit("0"), CalcKey.Equals, CalcKey.Op("+"),
        )
        assertEquals("12+5 = 17", state.lastCalc?.label)
    }

    // ------------------------------------------------------------ 求值

    @Test
    fun equals_respectsOperatorPrecedence() {
        val outcome = listOf(*digits("2"), CalcKey.Op("+"), *digits("3"), CalcKey.Op("×"), *digits("4"))
            .fold(CalcPadState()) { state, key -> QuickMenuCalcPad.press(state, key).state }
        val done = QuickMenuCalcPad.press(outcome, CalcKey.Equals)
        assertEquals("14", done.state.display)
        assertEquals("14", done.lastResult)
        assertTrue(done.state.justEvaluated)
    }

    @Test
    fun equals_thenOperator_continuesFromResult() {
        val state = pressAll(*digits("2"), CalcKey.Op("+"), *digits("3"), CalcKey.Equals, CalcKey.Op("×"))
        assertEquals("5×", state.expression)
        assertEquals("0", state.display)
    }

    @Test
    fun equals_thenDigit_startsFreshExpression() {
        val state = pressAll(CalcKey.Digit("2"), CalcKey.Op("+"), CalcKey.Digit("3"), CalcKey.Equals, CalcKey.Digit("9"))
        assertEquals("9", state.expression)
        assertEquals("9", state.display)
    }

    @Test
    fun equals_withDivisionByZero_keepsCurrentState() {
        val typed = pressAll(CalcKey.Digit("5"), CalcKey.Op("÷"), CalcKey.Digit("0"))
        val outcome = QuickMenuCalcPad.press(typed, CalcKey.Equals)
        // 引擎抛 ArithmeticException → evaluateExpression 返回 null → 不崩、不改状态、不进历史
        assertEquals(typed, outcome.state)
        assertNull(outcome.lastResult)
    }

    @Test
    fun equals_withTrailingOperator_isIgnored() {
        val typed = pressAll(CalcKey.Digit("5"), CalcKey.Op("+"))
        val outcome = QuickMenuCalcPad.press(typed, CalcKey.Equals)
        assertEquals(typed, outcome.state)
        assertNull(outcome.lastResult)
    }

    @Test
    fun equals_onEmptyPad_isIgnored() {
        val outcome = QuickMenuCalcPad.press(CalcPadState(), CalcKey.Equals)
        assertEquals(CalcPadState(), outcome.state)
        assertNull(outcome.lastResult)
    }

    // ------------------------------------------------------------ 括号

    @Test
    fun parens_multiplyParenthesizedSum() {
        val state = pressAll(
            CalcKey.OpenParen,
            *digits("1"),
            CalcKey.Op("+"),
            *digits("2"),
            CalcKey.CloseParen,
            CalcKey.Op("×"),
            *digits("3"),
        )
        assertEquals("(1+2)×3", state.expression)
        assertEquals("3", state.display)
    }

    @Test
    fun closeParen_withoutOpenParen_isIgnored() {
        val state = pressAll(*digits("12"), CalcKey.CloseParen)
        assertEquals("12", state.expression)
        // "忽略"就得是整键忽略：主显示不能跟着被清成 0（否则算式是 12、显示是 0，两者对不上）
        assertEquals("12", state.display)
    }

    // ------------------------------------------------------------ 开方

    @Test
    fun sqrt_ofPerfectSquare_isExact() {
        val state = pressAll(CalcKey.Digit("9"), CalcKey.Sqrt)
        // 整数结果不写成 `3.0`（和引擎 `9÷3 → 3` 的写法一致），显示区就不会出现 `3.0 = 3.0`
        assertEquals("3", state.expression)
        assertEquals("3", state.display)
        assertTrue(state.justEvaluated)
    }

    @Test
    fun sqrt_ofNegative_isNoop() {
        val negated = pressAll(CalcKey.Digit("9"), CalcKey.ToggleSign)
        assertEquals("-9", negated.expression)
        // 负数开方没有实数解：保持原状，不显示 NaN / 乱码
        assertEquals(negated, pressAll(CalcKey.Digit("9"), CalcKey.ToggleSign, CalcKey.Sqrt))
    }

    @Test
    fun sqrt_onEmptyPad_isNoop() {
        assertEquals(CalcPadState(), pressAll(CalcKey.Sqrt))
    }

    // ------------------------------------------------------------ 显示区模型
    //
    // 显示区曾经同时堆三个数字（算式 `6÷4` + 大数字 `4` + 实时预览 `= 1.5`），用户看不出哪个是结果。
    // 问题在**层级**不在"有没有实时结果"：算式行是配角小字、大数字是主角，同一个数字不写两遍。
    // 四态：① 只打数字（不显示算式行）② 输入过运算符但没算完（算式行出现，大数字是正在输入的）
    // ③ 算式完整可求值（大数字=实时结果，不用按 `=`）④ 按 `=`（归档到顶行、大数字保留结果、给复制按钮）。

    @Test
    fun display_onlyDigits_hidesExpressionLine() {
        // ① 只打了数字：算式行不渲染（也不占位），大数字就是正在输入的那个数
        val model = calcDisplayModel(pressAll(CalcKey.Digit("6")))
        assertNull(model.expressionLine)
        assertEquals("6", model.bigNumber)
        assertFalse(model.showCopy)
        assertNull(model.lastCalcLabel)
    }

    @Test
    fun display_incompleteExpression_showsExpressionLineAndEchoesInput() {
        // ② `6÷`：算式行这时才出现；算式没算完，大数字不能给半成品结果 —— 回显左边那个 6，
        //    它是"用户输入"的回显（不是算出来的值），所以不给复制按钮
        val model = calcDisplayModel(pressAll(CalcKey.Digit("6"), CalcKey.Op("÷")))
        assertEquals("6÷", model.expressionLine)
        assertEquals("6", model.bigNumber)
        assertFalse(model.showCopy)
    }

    @Test
    fun display_incompleteAfterEqualsLikeState_keepsOperandEcho() {
        // ② `6+`：同上，大数字仍是 6 而不是 6（+0），也不给复制按钮
        val model = calcDisplayModel(pressAll(CalcKey.Digit("6"), CalcKey.Op("+")))
        assertEquals("6+", model.expressionLine)
        assertEquals("6", model.bigNumber)
        assertFalse(model.showCopy)
    }

    @Test
    fun display_openParen_showsExpressionLine() {
        // ② 左括号也算"输入过运算符"：算式行出现，末尾还没有数可回显（回显左边那半截的值）
        val model = calcDisplayModel(pressAll(CalcKey.Digit("6"), CalcKey.Op("÷"), CalcKey.OpenParen))
        assertEquals("6÷(", model.expressionLine)
        assertEquals("6", model.bigNumber)
        assertFalse(model.showCopy)
    }

    @Test
    fun display_completeExpression_showsLiveResultBeforeEquals() {
        // ③ `6÷4`：算式完整可求值 → 大数字直接是实时结果，不用按 `=`
        val model = calcDisplayModel(pressAll(CalcKey.Digit("6"), CalcKey.Op("÷"), CalcKey.Digit("4")))
        assertEquals("6÷4", model.expressionLine)
        assertEquals("1.5", model.bigNumber)
        // 大数字是算出来的值 → 可复制（判据是"从哪来"，不是"按没按过 `=`"）
        assertTrue(model.showCopy)
        // 只是还没按 `=`，所以顶行还没有归档
        assertNull(model.lastCalcLabel)
    }

    @Test
    fun display_copyButtonFollowsWhereTheValueComesFrom() {
        // 复制按钮 = "大数字是算出来的值"：实时结果 / `=` 之后 / `√` 都给
        assertTrue(calcDisplayModel(pressAll(CalcKey.Digit("6"), CalcKey.Op("÷"), CalcKey.Digit("4"))).showCopy)
        assertTrue(
            calcDisplayModel(
                pressAll(CalcKey.Digit("6"), CalcKey.Op("÷"), CalcKey.Digit("4"), CalcKey.Equals),
            ).showCopy,
        )
        assertTrue(calcDisplayModel(pressAll(CalcKey.Digit("9"), CalcKey.Sqrt)).showCopy)
        // 输入态（大数字就是用户正在输的那个数）一律不给
        assertFalse(calcDisplayModel(pressAll(CalcKey.Digit("6"))).showCopy)
        assertFalse(calcDisplayModel(pressAll(CalcKey.Digit("6"), CalcKey.Op("÷"))).showCopy)
        assertFalse(
            calcDisplayModel(
                pressAll(CalcKey.Digit("5"), CalcKey.Op("×"), CalcKey.ToggleSign, CalcKey.Digit("3")),
            ).showCopy,
        )
        // 算式还没写完（`6÷(`）也不给
        assertFalse(calcDisplayModel(pressAll(CalcKey.Digit("6"), CalcKey.Op("÷"), CalcKey.OpenParen)).showCopy)
        // 算不出来（除零）不给
        assertFalse(
            calcDisplayModel(pressAll(CalcKey.Digit("6"), CalcKey.Op("÷"), CalcKey.Digit("0"))).showCopy,
        )
    }

    @Test
    fun display_afterEquals_archivesRowAndShowsResultWithCopy() {
        // ④ 按 `=`：`算式 = 结果` 归档到最顶那一行，算式行收起（同一个结果不写两遍），
        //    大数字保留结果，复制按钮还在
        val model = calcDisplayModel(
            pressAll(CalcKey.Digit("6"), CalcKey.Op("÷"), CalcKey.Digit("4"), CalcKey.Equals),
        )
        assertEquals("6÷4 = 1.5", model.lastCalcLabel)
        assertNull(model.expressionLine)
        assertEquals("1.5", model.bigNumber)
        assertTrue(model.showCopy)
        // 大数字是结果，不是末尾那个操作数
        assertNotEquals("4", model.bigNumber)
    }

    @Test
    fun display_doubleEquals_keepsOneCleanArchiveRow() {
        // 连按两次 `=`：归档还是同一条 `6÷4 = 1.5`（不会变成 `1.5 = 1.5`），大数字与复制按钮都不变
        val once = calcDisplayModel(
            pressAll(CalcKey.Digit("6"), CalcKey.Op("÷"), CalcKey.Digit("4"), CalcKey.Equals),
        )
        val twice = calcDisplayModel(
            pressAll(CalcKey.Digit("6"), CalcKey.Op("÷"), CalcKey.Digit("4"), CalcKey.Equals, CalcKey.Equals),
        )
        assertEquals(once, twice)
    }

    @Test
    fun display_afterEqualsDoesNotChangeTheResultValue() {
        // `=` 只是"确认收尾"：按下前后大数字都是实时结果那一个值
        val before = calcDisplayModel(pressAll(CalcKey.Digit("6"), CalcKey.Op("÷"), CalcKey.Digit("4")))
        val after = calcDisplayModel(
            pressAll(CalcKey.Digit("6"), CalcKey.Op("÷"), CalcKey.Digit("4"), CalcKey.Equals),
        )
        assertEquals(before.bigNumber, after.bigNumber)
    }

    @Test
    fun display_afterEqualsWithZeroResult_hidesCopyButton() {
        // 结果是 0：没什么可复制的，和早先一样不给按钮
        val model = calcDisplayModel(
            pressAll(CalcKey.Digit("0"), CalcKey.Op("×"), CalcKey.Digit("5"), CalcKey.Equals),
        )
        assertEquals("0×5 = 0", model.lastCalcLabel)
        assertEquals("0", model.bigNumber)
        assertFalse(model.showCopy)
    }

    @Test
    fun display_afterEqualsThenDigit_startsANewEntry() {
        // ④→① 按完等号又开始输数字：顶行归档留着，回到"只打数字"形态（无算式行、无按钮）
        val model = calcDisplayModel(
            pressAll(*digits("12"), CalcKey.Op("+"), *digits("5"), CalcKey.Equals, CalcKey.Digit("3")),
        )
        assertEquals("12+5 = 17", model.lastCalcLabel)
        assertNull(model.expressionLine)
        assertEquals("3", model.bigNumber)
        assertFalse(model.showCopy)
    }

    @Test
    fun display_afterEqualsThenOperator_continuesFromResult() {
        // `=` 之后按运算符：从结果继续算（`17×`），大数字回显这个 17
        val model = calcDisplayModel(
            pressAll(*digits("12"), CalcKey.Op("+"), *digits("5"), CalcKey.Equals, CalcKey.Op("×")),
        )
        assertEquals("12+5 = 17", model.lastCalcLabel)
        assertEquals("17×", model.expressionLine)
        assertEquals("17", model.bigNumber)
        assertFalse(model.showCopy)
    }

    @Test
    fun display_divisionByZero_fallsBackToOperandInsteadOfNaN() {
        // 算不出来（除零）：大数字回到正在输入的那个数，绝不显示 NaN / 报错文本；也不给复制
        val model = calcDisplayModel(pressAll(CalcKey.Digit("6"), CalcKey.Op("÷"), CalcKey.Digit("0")))
        assertEquals("6÷0", model.expressionLine)
        assertEquals("0", model.bigNumber)
        assertFalse(model.showCopy)
    }

    @Test
    fun display_approximateLiveResult_isShown() {
        // `1÷3` 的实时结果是"末位被截断"的近似值：显示区照样要能看到它（而不是退回操作数 `3`），
        // 它同样是"算出来的值"，所以可复制 —— 复制到的就是屏幕上那个带 `…` 的近似值
        val model = calcDisplayModel(pressAll(CalcKey.Digit("1"), CalcKey.Op("÷"), CalcKey.Digit("3")))
        assertEquals("1÷3", model.expressionLine)
        assertEquals("0.333333333333…", model.bigNumber)
        assertTrue(model.showCopy)
    }

    @Test
    fun display_afterSqrt_showsPlainResultWithoutRepeatingIt() {
        // `√9`：前缀运算符，算完整 → 大数字 `3`（不是 `3.0`）；算式行不重复写同一个数
        val model = calcDisplayModel(pressAll(CalcKey.Digit("9"), CalcKey.Sqrt))
        assertNull(model.expressionLine)
        assertEquals("3", model.bigNumber)
        assertTrue(model.showCopy)
        assertNull(model.lastCalcLabel)
    }

    @Test
    fun display_emptyPad_showsZeroWithoutExtras() {
        val model = calcDisplayModel(CalcPadState())
        assertNull(model.lastCalcLabel)
        assertNull(model.expressionLine)
        assertEquals("0", model.bigNumber)
        assertFalse(model.showCopy)
    }

    @Test
    fun display_signedOperand_isEchoedWithItsSign() {
        // 带符号操作数回显：`5×-3` 时大数字是 `-3`（用户按 `±` 就是在改这个数，得看得见）
        val model = calcDisplayModel(
            pressAll(CalcKey.Digit("5"), CalcKey.Op("×"), CalcKey.ToggleSign, CalcKey.Digit("3")),
        )
        assertEquals("5×-3", model.expressionLine)
        assertEquals("-3", model.bigNumber)
        assertFalse(model.showCopy)
    }

    // ------------------------------------------------------------ 结果整形

    @Test
    fun formatCalcResult_keepsPlainNumbers() {
        assertEquals("14", formatCalcResult("14"))
        assertEquals("0.5", formatCalcResult("0.5"))
        // 负数结果（`0-5` / `12+-7`）必须能继续参与运算，不能被当成非法文本丢掉
        assertEquals("-3.25", formatCalcResult("-3.25"))
        // 引擎在百分号短语里会带空格数字，空格要清掉
        assertEquals("12", formatCalcResult(" 12"))
    }

    @Test
    fun formatCalcResult_rejectsNonNumbers() {
        assertNull(formatCalcResult(null))
        assertNull(formatCalcResult(""))
        assertNull(formatCalcResult("   "))
        // 引擎对"末位被截断"的结果会追加 `…`：它是展示用的近似值，不能继续参与运算
        assertNull(formatCalcResult("0.333…"))
        assertNull(formatCalcResult("1/3"))
    }

    @Test
    fun formatCalcResult_rejectsOverlongValues() {
        val long = "1".repeat(25)
        assertNull(formatCalcResult(long))
        assertEquals("1".repeat(24), formatCalcResult("1".repeat(24)))
    }
}
