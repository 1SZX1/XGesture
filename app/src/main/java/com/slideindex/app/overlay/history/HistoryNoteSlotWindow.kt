package com.slideindex.app.overlay.history

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.slideindex.app.R
import com.slideindex.app.overlay.OverlayCompose
import com.slideindex.app.overlay.OverlayComposeOwner
import com.slideindex.app.overlay.OverlayViewBackHandler
import com.slideindex.app.overlay.OverlayWindowTypes
import com.slideindex.app.stash.StashCoordinator
import com.slideindex.app.ui.theme.OverlayAwareModuleTheme
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 把手的「输入槽」（设计稿 `.slot`）：长按把手就地记一条，不必先打开面板。
 *
 * - 贴着把手弹出、从右往左**长出来**（设计稿把宽度从 72px 动画到 300px）；
 * - 独立小窗 + **抢焦点**，输入法才弹得出来（同 `FloatBallPickResultPanel` 的做法：
 *   平时 `NOT_FOCUSABLE | NOT_TOUCHABLE` 的被动壳，要输入时才清掉这两个 flag）；
 * - 存下走 [StashCoordinator.addText]：成功后**把手脉冲 + peek 预览都会自动发生**
 *   （事件源 `HistorySaveSignal`），这里只负责清空与收起。
 *
 * ⚠️ 位置由 `params.y` 定，输入法弹起时这个窗**不会**跟着上移（P7 的 IME 避让要处理）。
 */
internal class HistoryNoteSlotWindow(
    private val context: Context,
    private val windowManager: WindowManager,
) {
    private var composeView: ComposeView? = null
    private var owner: OverlayComposeOwner? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var backHandler: OverlayViewBackHandler? = null
    private val open = mutableStateOf(false)

    /** true = 把手贴右缘（输入槽靠右边缘、往左长出来）；false = 镜像。 */
    private val gravityEnd = mutableStateOf(true)

    /** 把手换边时调它：输入槽贴着新的一侧弹出（不做"值没变就返回"，旋屏后屏宽变了要重算）。 */
    fun setGravityEnd(end: Boolean) {
        gravityEnd.value = end
        applyPosition()
    }
    /** 每次打开 +1：已经开着时再按长按，也要能把焦点重新拉回输入框。 */
    private val focusEpoch = mutableIntStateOf(0)
    private var measuredSize = IntSize.Zero
    private var anchorCenterY = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    /** [handleCenterY] = 把手在屏幕上的纵向中心（px）。 */
    fun show(handleCenterY: Int) {
        anchorCenterY = handleCenterY
        if (!ensureWindow()) return
        setWindowInputActive(true)
        composeView?.visibility = View.VISIBLE
        open.value = true
        focusEpoch.intValue++
        applyPosition()
        // 等窗真正挂上/拿到焦点，再让输入框抢焦点（设计稿也是延时 150ms 再 focus）。
        mainHandler.postDelayed({
            if (open.value) composeView?.requestFocus()
        }, SLOT_FOCUS_DELAY_MS)
    }

    fun hide() {
        if (!open.value) return
        open.value = false
        setWindowInputActive(false)
        composeView?.clearFocus()
        mainHandler.postDelayed({
            if (!open.value) composeView?.visibility = View.GONE
        }, SLOT_HIDE_DELAY_MS)
    }

    fun destroy() {
        mainHandler.removeCallbacksAndMessages(null)
        backHandler?.detach()
        backHandler = null
        val view = composeView
        val params = layoutParams
        val previousOwner = owner
        composeView = null
        layoutParams = null
        owner = null
        if (view != null && params != null) {
            runCatching { windowManager.removeView(view) }
        }
        OverlayCompose.teardownOverlayCompose(view, previousOwner)
    }

    private fun setWindowInputActive(active: Boolean) {
        val view = composeView ?: return
        val params = layoutParams ?: return
        params.flags = if (active) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() and
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { windowManager.updateViewLayout(view, params) }
        if (active) {
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            backHandler?.refresh()
        } else {
            backHandler?.detach()
            backHandler = null
        }
    }

    private fun ensureWindow(): Boolean {
        if (composeView != null) return true
        val overlayContext = OverlayCompose.themedContext(context)
        val composeOwner = OverlayComposeOwner()
        val view = OverlayCompose.createComposeView(overlayContext, composeOwner).apply {
            visibility = View.GONE
            setContent {
                OverlayAwareModuleTheme {
                    HistoryNoteSlotContent(
                        openState = open,
                        focusEpochState = focusEpoch,
                        onSized = { width, height ->
                            measuredSize = IntSize(width, height)
                            applyPosition()
                        },
                        onSave = ::save,
                    )
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            OverlayWindowTypes.overlayWindowType(context),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        val added = runCatching { windowManager.addView(view, params) }
            .onFailure { Log.e(TAG, "addView failed", it) }
            .isSuccess
        if (!added) {
            OverlayCompose.clearViewTreeOwners(view)
            composeOwner.destroy()
            return false
        }
        composeView = view
        layoutParams = params
        owner = composeOwner
        backHandler = OverlayViewBackHandler(view) { hide() }.also {
            it.attach(requestViewFocus = false)
        }
        return true
    }

    private fun save(rawText: String) {
        val text = rawText.trim()
        // 设计稿 `drop()`：空内容直接收起，不存。
        if (text.isEmpty()) {
            hide()
            return
        }
        StashCoordinator.addText(text) { success ->
            if (success) hide()
        }
    }

    /** 贴着把手那一侧的外缘（设计稿 `right:10px`），纵向与把手居中。 */
    private fun applyPosition() {
        val params = layoutParams ?: return
        val view = composeView ?: return
        if (measuredSize.width <= 0 || measuredSize.height <= 0) {
            view.requestLayout()
            return
        }
        val screenWidthPx = context.resources.displayMetrics.widthPixels
        val density = context.resources.displayMetrics.density
        val marginPx = (SLOT_EDGE_MARGIN_DP * density).toInt()
        // 窗是 WRAP_CONTENT + gravity START，所以"宽度动画 72→300px"会自然朝屏幕内侧长：
        // 贴右缘时 x 随宽度往前推（往左长），贴左缘时 x 固定在 margin（往右长）。
        params.x = if (gravityEnd.value) {
            (screenWidthPx - measuredSize.width - marginPx).coerceAtLeast(0)
        } else {
            marginPx
        }
        params.y = (anchorCenterY - measuredSize.height / 2).coerceAtLeast(0)
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private companion object {
        const val TAG = "HistoryNoteSlot"
        const val SLOT_EDGE_MARGIN_DP = 10
        const val SLOT_FOCUS_DELAY_MS = 150L
        const val SLOT_HIDE_DELAY_MS = 220L
    }
}

/**
 * 输入槽的内容。
 *
 * ⚠️ 与面板输入条同一个坑：只能用 `BasicTextField(value/onValueChange)` 这个**已废弃**的重载
 * （本仓库解析到的 foundation 版本里没有 `TextFieldState` 重载），升级后一起换。
 */
@Suppress("DEPRECATION")
@Composable
private fun HistoryNoteSlotContent(
    openState: MutableState<Boolean>,
    focusEpochState: MutableIntState,
    onSized: (Int, Int) -> Unit,
    onSave: (String) -> Unit,
) {
    val open by openState
    val focusEpoch = focusEpochState.intValue
    val theme = historyTheme()
    val shape = RoundedCornerShape(HistoryRadii.pill)
    val focusRequester = remember { FocusRequester() }
    var text by remember { mutableStateOf("") }
    // 设计稿把宽度从 72px 动画到 300px —— 从右缘"长出来"的手感就靠它
    // （窗口是 WRAP_CONTENT，宽度一变就回调 onSized 重算 x，右缘因此纹丝不动）。
    val width by animateDpAsState(
        targetValue = if (open) SLOT_OPEN_WIDTH else SLOT_CLOSED_WIDTH,
        label = "noteSlotWidth",
    )
    val hint = stringResource(R.string.stash_composer_hint)
    val context = LocalContext.current

    LaunchedEffect(open, focusEpoch) {
        if (!open) {
            text = ""
            return@LaunchedEffect
        }
        delay(SLOT_FIELD_FOCUS_DELAY_MS)
        focusRequester.requestFocus()
    }

    Row(
        modifier = Modifier
            .width(width)
            .height(SLOT_HEIGHT)
            .onSizeChanged { onSized(it.width, it.height) }
            .shadow(6.dp, shape)
            .clip(shape)
            // 设计稿 .slot 是玻璃小条：--g-fill 底 + --g-border 描边。
            .background(theme.glassFill)
            .border(width = 1.dp, color = theme.glassBorder, shape = shape)
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 语音：设计稿 `.slot .row` 里第一个就是麦克风。
        HistoryVoiceMicButton(
            onResult = { recognized ->
                text = if (text.isBlank()) recognized else "$text $recognized"
            },
            onError = { messageResId ->
                // 输入槽是独立小窗，没有 snackbar；用 Toast 反馈（overlay 里既有的做法）。
                Toast.makeText(context, messageResId, Toast.LENGTH_SHORT).show()
            },
            size = 38.dp,
            iconSize = 18.dp,
        )
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .focusRequester(focusRequester),
            textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = HistoryFontSizes.body,
                            color = theme.text,
                        ),
            cursorBrush = SolidColor(theme.accentSolid),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave(text) }),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxHeight(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) {
                        Text(
                            text = hint,
                            style = HistoryPanelTypography.content(),
                            color = theme.sub,
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                }
            },
        )
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(HistoryRadii.pill))
                // 设计稿 `.ib.round { background: var(--accent-solid); color:#fff }`；
                // 空内容时是普通 `.ib`（透明底 + sub 色）。
                .background(if (text.isNotBlank()) theme.accentSolid else Color.Transparent)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onSave(text) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(R.string.stash_composer_send),
                tint = if (text.isNotBlank()) Color.White else theme.sub,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 设计稿 `.slot{width:72px}` / `.slot.open{width:300px}`、`.slot .row{height:50px}`。 */
private val SLOT_CLOSED_WIDTH = 72.dp
private val SLOT_OPEN_WIDTH = 300.dp
private val SLOT_HEIGHT = 52.dp
private const val SLOT_FIELD_FOCUS_DELAY_MS = 150L
