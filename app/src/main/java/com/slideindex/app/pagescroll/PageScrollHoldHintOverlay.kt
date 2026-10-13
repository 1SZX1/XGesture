package com.slideindex.app.pagescroll

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import com.slideindex.app.R
import com.slideindex.app.overlay.OverlayWindowTypes
import kotlin.math.max

/**
 * 「页面滚动」按住拖动时的小提示。
 *
 * 形态：贴着触发边的一条小竖胶囊 —— 上面一个上箭头、下面一个下箭头（当前方向高亮），
 * 右侧 1~2 条短杠表示当前速度档（慢 1 条 / 快 2 条）。
 *
 * 关键约束：**不吃触摸**（`FLAG_NOT_TOUCHABLE`）—— 此时用户手指正按在边缘上，
 * 提示只是"告诉你可以上下滑"，不能参与任何交互。
 */
internal class PageScrollHoldHintOverlay(
    private val context: Context,
) {

    private val density = context.resources.displayMetrics.density
    private val windowManager: WindowManager? =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val night: Boolean
        get() = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private var container: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var upIcon: ImageView? = null
    private var downIcon: ImageView? = null
    private var bars: List<View> = emptyList()

    var isShowing: Boolean = false
        private set

    fun show(anchorRawY: Float) {
        if (isShowing) return
        val manager = windowManager ?: return
        val sideLeft = PageScrollPrefs.read(context).lastSideName == PageScrollControlBar.Side.LEFT.name

        val icon = if (night) 0xFFC3C7CF.toInt() else 0xFF44474E.toInt()
        val accent = if (night) 0xFFA8C7FA.toInt() else 0xFF0B57D0.toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f * density
                setColor(if (night) 0xE61D2024.toInt() else 0xE6EFF1F4.toInt())
            }
            elevation = 8f * density
        }

        val arrows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        upIcon = chevron(180f, icon).also(arrows::addView)
        downIcon = chevron(0f, icon).also(arrows::addView)
        root.addView(arrows)

        val barColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, 0, 0)
        }
        bars = listOf(newBar(accent), newBar(accent)).onEach(barColumn::addView)
        root.addView(barColumn)

        val screenHeight = context.resources.displayMetrics.heightPixels
        val hintHeight = dp(52)
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            OverlayWindowTypes.fullscreenLauncherWindowType(context),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = if (sideLeft) Gravity.LEFT or Gravity.TOP else Gravity.RIGHT or Gravity.TOP
            x = dp(EDGE_MARGIN_DP)
            y = (anchorRawY - hintHeight / 2f).toInt()
                .coerceIn(dp(48), max(dp(48), screenHeight - hintHeight - dp(48)))
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            buttonBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }

        val added = runCatching { manager.addView(root, layoutParams) }
        if (added.isFailure) {
            Log.w(TAG, "提示无法挂载", added.exceptionOrNull())
            return
        }
        container = root
        params = layoutParams
        isShowing = true
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(120L).start()
        update(directionDown = true, fast = false)
    }

    /** 更新方向高亮与速度条数量。 */
    fun update(directionDown: Boolean, fast: Boolean) {
        val icon = if (night) 0xFFC3C7CF.toInt() else 0xFF44474E.toInt()
        val accent = if (night) 0xFFA8C7FA.toInt() else 0xFF0B57D0.toInt()
        upIcon?.imageTintList = android.content.res.ColorStateList.valueOf(
            if (directionDown) icon else accent,
        )
        downIcon?.imageTintList = android.content.res.ColorStateList.valueOf(
            if (directionDown) accent else icon,
        )
        bars.forEachIndexed { index, bar ->
            val on = index == 0 || fast
            bar.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(2).toFloat()
                setColor(if (on) accent else (if (night) 0x33FFFFFF else 0x1F000000))
            }
        }
    }

    fun hide() {
        val view = container ?: return
        container = null
        params = null
        isShowing = false
        upIcon = null
        downIcon = null
        bars = emptyList()
        view.animate().alpha(0f).setDuration(140L).withEndAction {
            runCatching { windowManager?.removeView(view) }
        }.start()
    }

    private fun chevron(rotationDeg: Float, tint: Int): ImageView =
        ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.ic_page_scroll_direction)
            rotation = rotationDeg
            imageTintList = android.content.res.ColorStateList.valueOf(tint)
        }

    private fun newBar(color: Int): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(4), dp(12)).apply { bottomMargin = dp(3) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(2).toFloat()
                setColor(color)
            }
        }

    private fun dp(value: Int): Int = max(1, (value * density).toInt())

    private companion object {
        const val TAG = "PageScrollHint"
        const val EDGE_MARGIN_DP = 10
    }
}
