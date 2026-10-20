package com.slideindex.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun LocalFrostedGlassBackdrop(
    modifier: Modifier = Modifier,
    cornerRadiusPx: Float = 0f,
    blurRadiusPx: Int = 80,
    @ColorInt tintColor: Int = 0x331C1C1E,
    enabled: Boolean = true,
    /**
     * 不许糊时的实色降级底色。默认跟随主题，因为绝大多数调用点（取词 / 翻译 / 搜索 / 历史 /
     * 音量 / 剪贴板 / 图片搜索）的前景色本身也跟随主题，浅色底 + 深色字自洽。
     *
     * ⚠️ 前景色**写死为白**的面板必须显式传深色（见 [com.slideindex.app.overlay.OhoQuickToolsPanel]），
     * 否则浅色主题下会渲染成白底白字 —— 也就是用户报的「关闭悬浮窗模糊后快速工具面板毫无可读性」。
     */
    @ColorInt solidFallbackColor: Int? = null
) {
    if (!enabled) return

    if (!canBlurInOverlay()) {
        SolidPanelBackdrop(
            modifier = modifier,
            cornerRadiusPx = cornerRadiusPx,
            solidColor = solidFallbackColor
        )
        return
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            FrameLayout(ctx).apply {
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: Outline) {
                        outline.setRoundRect(0, 0, v.width, v.height, cornerRadiusPx)
                    }
                }
                clipToOutline = true
                viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        viewTreeObserver.removeOnPreDrawListener(this)
                        setupBackgroundBlur(this@apply, cornerRadiusPx, blurRadiusPx, tintColor)
                        return true
                    }
                })
            }
        },
        update = { view ->
            setupBackgroundBlur(view, cornerRadiusPx, blurRadiusPx, tintColor)
        }
    )
}

/**
 * 模糊不可用（总开关关闭 / 系统不支持跨窗模糊 / 反射失败）时的实色降级。
 *
 * 这层 backdrop 往往是面板**唯一**的背景来源（取词等面板的卡片自身没有背景色），
 * 所以不能简单跳过，否则面板会塌成半透明 tint 甚至全透明。
 *
 * [solidColor] 为 null 时用主题容器色；写死白色前景的面板要显式传深色。
 */
@Composable
private fun SolidPanelBackdrop(
    modifier: Modifier,
    cornerRadiusPx: Float,
    @ColorInt solidColor: Int? = null
) {
    val corner = with(LocalDensity.current) { cornerRadiusPx.toDp() }
    val color = solidColor?.let { Color(it) } ?: MiuixTheme.colorScheme.surfaceContainer
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(color),
    )
}

/**
 * 面板内 backdrop 这条路上能否真的糊：**用户总开关 × 系统跨窗模糊能力**，走
 * [OverlayBlurGate] 这个唯一决策点。
 *
 * 只问用户开关是不够的：ROM 关掉跨窗模糊时 [setupBackgroundBlur] 的反射会静默失败
 * （只打一条日志），面板就只剩 tint 甚至什么都没有 —— 与「关闭模糊后塌成浅色底」是同一类
 * 现象。所以系统那半边必须一起问，问不到就按实色降级。
 */
@Composable
internal fun canBlurInOverlay(): Boolean {
    if (!rememberOverlayBlurEnabled()) return false
    val context = LocalContext.current
    val windowManager = remember(context) {
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    }
    return OverlayBlurGate.isSystemBlurEnabled(windowManager)
}

internal fun setupBackgroundBlur(view: View, cornerRadiusPx: Float, blurRadiusPx: Int, @ColorInt tintColor: Int) {
    runCatching {
        val blurDrawable = view.background?.takeIf {
            it.javaClass.name.contains("BackgroundBlurDrawable")
        } ?: run {
            val d = LocalFrostedGlassDrawable.createBlurDrawable(view) ?: return
            view.background = d
            d
        }
        val setBlurRadius = blurDrawable.javaClass.getMethod("setBlurRadius", java.lang.Integer.TYPE)
        val setCornerRadius = blurDrawable.javaClass.getMethod("setCornerRadius", java.lang.Float.TYPE)
        val setColor = blurDrawable.javaClass.getMethod("setColor", java.lang.Integer.TYPE)
        setBlurRadius.invoke(blurDrawable, blurRadiusPx)
        setCornerRadius.invoke(blurDrawable, cornerRadiusPx)
        setColor.invoke(blurDrawable, tintColor)
    }.onFailure { Log.w("LocalFrostedGlass", "setupBackgroundBlur failed", it) }
}

/**
 * Reusable Canvas-level helper for drawing hardware BackgroundBlurDrawable
 * inside custom View onDraw(canvas) or overlay renderers.
 */
internal class LocalFrostedGlassDrawable(private val viewProvider: () -> View?) {
    constructor(view: View) : this({ view })
    private var blurDrawable: Drawable? = null
    private var lastViewRootImpl: Any? = null
    fun reset() {
        blurDrawable = null
        lastViewRootImpl = null
    }

    // 反射取 ViewRootImpl：拿系统私有字段做模糊背景，失败已用 runCatching 兜住。
    @android.annotation.SuppressLint("PrivateApi")
    private fun ensureDrawable(): Drawable? {
        val view = viewProvider() ?: return null
        if (!view.isAttachedToWindow) return null
        val currentVri = runCatching {
            val getViewRootImplMethod = View::class.java.getDeclaredMethod("getViewRootImpl")
            getViewRootImplMethod.isAccessible = true
            getViewRootImplMethod.invoke(view)
        }.getOrNull() ?: return null

        if (blurDrawable != null && lastViewRootImpl === currentVri) {
            return blurDrawable
        }

        lastViewRootImpl = currentVri
        blurDrawable = runCatching {
            val createMethod = currentVri.javaClass.getMethod("createBackgroundBlurDrawable")
            createMethod.invoke(currentVri) as? Drawable
        }.getOrNull()
        return blurDrawable
    }

    // @JvmOverloads：蜂窝浮层（HoneycombOverlayView.java）仍按 6 参调用，默认 enabled = true。
    @JvmOverloads
    fun draw(
        canvas: Canvas,
        bounds: RectF,
        cornerRadiusPx: Float,
        blurRadiusPx: Int,
        @ColorInt tintColor: Int,
        alpha: Float = 1f,
        /** 调用点表达的意图（如全局模糊总开关）；false 时直接不画，由调用点走实色兜底。 */
        enabled: Boolean = true
    ): Boolean {
        if (!enabled) return false
        if (bounds.isEmpty || alpha <= 0.001f) return false
        val drawable = ensureDrawable() ?: return false
        return runCatching {
            val setBlurRadius = drawable.javaClass.getMethod("setBlurRadius", java.lang.Integer.TYPE)
            val setCornerRadius = drawable.javaClass.getMethod("setCornerRadius", java.lang.Float.TYPE)
            val setColor = drawable.javaClass.getMethod("setColor", java.lang.Integer.TYPE)
            setBlurRadius.invoke(drawable, blurRadiusPx)
            setCornerRadius.invoke(drawable, cornerRadiusPx)
            setColor.invoke(drawable, tintColor)
            drawable.setBounds(
                bounds.left.toInt(),
                bounds.top.toInt(),
                bounds.right.toInt(),
                bounds.bottom.toInt()
            )
            drawable.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
            drawable.draw(canvas)
            true
        }.getOrDefault(false)
    }

    companion object {
        fun createBlurDrawable(view: View): Drawable? {
            return runCatching {
                val getViewRootImplMethod = View::class.java.getDeclaredMethod("getViewRootImpl")
                getViewRootImplMethod.isAccessible = true
                val viewRootImpl = getViewRootImplMethod.invoke(view) ?: return null
                val createMethod = viewRootImpl.javaClass.getMethod("createBackgroundBlurDrawable")
                createMethod.invoke(viewRootImpl) as? Drawable
            }.getOrNull()
        }
    }
}
