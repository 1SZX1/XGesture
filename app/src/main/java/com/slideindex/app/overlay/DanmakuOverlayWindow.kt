package com.slideindex.app.overlay

import android.annotation.SuppressLint
import android.animation.ObjectAnimator
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import com.slideindex.app.R
import com.slideindex.app.message.MessageBubbleTypography
import com.slideindex.app.message.MessageThemeSpec
import com.slideindex.app.message.applyMessageThemeBackground
import com.slideindex.app.message.danmakuBackgroundResId
import com.slideindex.app.message.NotificationData
import kotlin.math.max
import kotlin.math.min

@SuppressLint("StaticFieldLeak") // Overlay singleton; views cleared on detach
object DanmakuOverlayWindow {
    private const val TAG = "DanmakuOverlay"
    private const val TRACK_HEIGHT_DP = 56f
    private const val ANIMATION_DURATION_MS = 5_500L
    private const val MAX_TRACKS = 6
    private const val ATTACH_RETRY_MS = 5_000L
    private const val TOP_INSET_DP = 72f

    /** 弹幕正文最大字数，超出直接截断，避免超长文本把条幅撑宽。 */
    private const val MAX_CONTENT_CHARS = 120

    /** 弹幕条最大宽度：横屏 60% 屏宽、竖屏 90% 屏宽，且不超过 [MAX_ITEM_WIDTH_DP]。 */
    private const val MAX_ITEM_WIDTH_RATIO_LANDSCAPE = 0.60f
    private const val MAX_ITEM_WIDTH_RATIO_PORTRAIT = 0.90f
    private const val MAX_ITEM_WIDTH_DP = 420f

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayContext: Context? = null
    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null
    private var rootLayoutParams: WindowManager.LayoutParams? = null
    private var attachFailedAt = 0L
    private val occupiedTracks = BooleanArray(MAX_TRACKS)
    private var screenWidth = 0
    private var screenHeight = 0
    private var bandHeightPx = 0

    /** 当前窗口高度实际能容纳的轨道数；横屏下小于 [MAX_TRACKS]，避免底部轨道被窗口裁掉。 */
    private var visibleTracks = MAX_TRACKS

    fun show(
        context: Context,
        data: NotificationData,
        theme: MessageThemeSpec,
        opacity: Float,
        maxLines: Int = 1,
        speedLevel: Int = com.slideindex.app.message.DanmakuSpeed.NORMAL,
        fontSizeLevel: Int = com.slideindex.app.message.SideBubbleFontSize.NORMAL
    ) {
        mainHandler.post {
            refreshGeometryIfRotated(context)
            if (!ensureAttached(context)) {
                Log.w(TAG, "show skipped: overlay not attached")
                return@post
            }
            val root = rootView ?: return@post
            val track = pickTrack()
            occupiedTracks[track] = true
            val item = createDanmakuItem(root.context, data, theme, opacity, track, maxLines, fontSizeLevel)
            val maxW = danmakuMaxWidthPx(root.context)
            item.measure(
                View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(bandHeightPx, View.MeasureSpec.AT_MOST)
            )
            (item.layoutParams as FrameLayout.LayoutParams).width =
                item.measuredWidth.coerceIn(1, maxW)
            root.addView(item)
            item.post {
                bringToFrontInternal()
                startScrollAnimation(item, track, speedLevel)
            }
        }
    }

    fun bringToFront() {
        mainHandler.post { bringToFrontInternal() }
    }

    fun detach() {
        mainHandler.post {
            val wm = windowManager
            val root = rootView
            if (wm != null && root != null) {
                runCatching { wm.removeView(root) }
            }
            windowManager = null
            rootView = null
            rootLayoutParams = null
            overlayContext = null
            attachFailedAt = 0L
            occupiedTracks.fill(false)
        }
    }

    /** 横竖屏切换后窗口尺寸会变；检测到尺寸变化就重挂一次，避免继续用旧屏宽。 */
    private fun refreshGeometryIfRotated(context: Context) {
        if (rootView == null) return
        val dm = context.resources.displayMetrics
        if (dm.widthPixels == screenWidth && dm.heightPixels == screenHeight) return
        runCatching { windowManager?.removeView(rootView) }
        rootView = null
        windowManager = null
        rootLayoutParams = null
        occupiedTracks.fill(false)
    }

    private fun bringToFrontInternal() {
        val wm = windowManager ?: return
        val root = rootView ?: return
        val params = rootLayoutParams ?: return
        if (!root.isAttachedToWindow) return
        runCatching {
            wm.removeView(root)
            wm.addView(root, params)
        }.onFailure { error ->
            Log.w(TAG, "bringToFront failed", error)
        }
    }

    private fun ensureAttached(context: Context): Boolean {
        if (rootView != null) return true
        val now = System.currentTimeMillis()
        if (attachFailedAt > 0L && now - attachFailedAt < ATTACH_RETRY_MS) {
            Log.w(TAG, "ensureAttached skipped: retry cooldown")
            return false
        }
        attachFailedAt = 0L

        val hostContext = MessageOverlayHost.resolveHostContext(context) ?: run {
            Log.w(TAG, "overlay permission not granted")
            return false
        }

        val wm = hostContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        val dm = hostContext.resources.displayMetrics
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels
        val density = dm.density
        val statusBarHeight = statusBarHeight(hostContext)
        val desiredBandHeight = (TRACK_HEIGHT_DP * MAX_TRACKS * density).toInt()
        val maxBandHeight = dm.heightPixels * 3 / 5
        bandHeightPx = min(desiredBandHeight, maxBandHeight).coerceAtLeast((TRACK_HEIGHT_DP * density).toInt())
        val trackHeightPx = (TRACK_HEIGHT_DP * density).toInt().coerceAtLeast(1)
        visibleTracks = (bandHeightPx / trackHeightPx).coerceIn(1, MAX_TRACKS)

        val params = OverlayWindowTypes.createPresentationParams(hostContext).apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = bandHeightPx
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = statusBarHeight + (TOP_INSET_DP * density).toInt()
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val root = FrameLayout(hostContext).apply {
            clipChildren = false
            clipToPadding = false
        }
        val attached = runCatching { wm.addView(root, params) }
            .onFailure { error ->
                attachFailedAt = System.currentTimeMillis()
                Log.e(TAG, "addView failed", error)
            }
            .isSuccess
        if (!attached) return false

        overlayContext = hostContext
        windowManager = wm
        rootView = root
        rootLayoutParams = params
        Log.d(TAG, "danmaku overlay attached bandHeight=$bandHeightPx y=${params.y}")
        return true
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource") // status_bar_height is the standard inset probe
    private fun statusBarHeight(context: Context): Int {
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
    }

    private fun pickTrack(): Int {
        for (i in 0 until visibleTracks) {
            if (!occupiedTracks[i]) return i
        }
        return 0
    }

    /** 弹幕条最大宽度（px）：横屏 60% 屏宽、竖屏 90% 屏宽，且不超过 420dp。 */
    private fun danmakuMaxWidthPx(context: Context): Int {
        val dm = context.resources.displayMetrics
        val ratio = if (dm.widthPixels > dm.heightPixels) {
            MAX_ITEM_WIDTH_RATIO_LANDSCAPE
        } else {
            MAX_ITEM_WIDTH_RATIO_PORTRAIT
        }
        return min(dm.widthPixels * ratio, MAX_ITEM_WIDTH_DP * dm.density).toInt().coerceAtLeast(1)
    }

    private fun createDanmakuItem(
        context: Context,
        data: NotificationData,
        theme: MessageThemeSpec,
        opacity: Float,
        track: Int,
        maxLines: Int,
        fontSizeLevel: Int
    ): RelativeLayout {
        val density = context.resources.displayMetrics.density
        val trackHeightPx = (TRACK_HEIGHT_DP * density).toInt()
        val topPx = track * trackHeightPx
        val titleSp = bubbleFontSizeLevelToSp(
            level = fontSizeLevel,
            normalSp = MessageBubbleTypography.TITLE_SP,
            smallSp = 8f,
            largeSp = 12f
        )
        val contentSp = bubbleFontSizeLevelToSp(
            level = fontSizeLevel,
            normalSp = MessageBubbleTypography.CONTENT_SP,
            smallSp = 10f,
            largeSp = 14f
        )
        val avatarSizePx = (MessageBubbleTypography.AVATAR_DP * density).toInt()
        val rowSpacingPx = (MessageBubbleTypography.ROW_SPACING_DP * density).toInt()
        val displayTheme = theme.copy(backgroundResId = theme.danmakuBackgroundResId())

        val container = RelativeLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = topPx
            }
            applyMessageThemeBackground(this, displayTheme, opacity)
            val padH = (displayTheme.paddingHorizontalDp * density).toInt()
            val padV = (displayTheme.paddingVerticalDp * density).toInt()
            setPadding(padH, padV, padH, padV)
            elevation = 8f * density
            clipChildren = false
            clipToPadding = false
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val iconView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(avatarSizePx, avatarSizePx).apply {
                marginEnd = rowSpacingPx
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            val icon = data.largeIcon ?: data.appIcon
            if (icon != null) {
                setImageBitmap(icon)
            } else {
                setImageResource(R.drawable.ic_notification)
            }
        }

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val titleText = data.title.ifBlank { data.packageName }
        val titleView = TextView(context)
        applyDanmakuTitleText(
            textView = titleView,
            text = titleText,
            colorArgb = displayTheme.contentColorArgb,
            titleSp = titleSp
        )
        textColumn.addView(titleView)

        // 换行压平 + 空白折叠 + 超长截断：避免多行/超长文本参与测量把条幅撑到整屏，右侧却空着。
        val content = data.content
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_CONTENT_CHARS)

        if (content.isNotBlank()) {
            val contentView = TextView(context)
            applyDanmakuContentText(
                textView = contentView,
                title = titleText,
                rawContent = content,
                titleColorArgb = displayTheme.titleColorArgb,
                contentSp = contentSp,
                maxLines = maxLines
            )
            textColumn.addView(contentView)
        }

        row.addView(iconView)
        row.addView(textColumn)
        container.addView(row)
        return container
    }

    private fun startScrollAnimation(item: View, track: Int, speedLevel: Int) {
        if (item.parent == null) {
            occupiedTracks[track] = false
            return
        }
        // show() 里已经按最大宽度测量并写死了 layoutParams.width，这里不再按整屏重测。
        val itemWidth = max(if (item.width > 0) item.width else item.measuredWidth, 1)
        val startX = screenWidth.toFloat()
        val endX = -(itemWidth + screenWidth * 0.12f)
        item.x = startX
        val durationMs = com.slideindex.app.message.DanmakuSpeed.durationMs(speedLevel)
        ObjectAnimator.ofFloat(item, View.X, startX, endX).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    mainHandler.post {
                        rootView?.removeView(item)
                        occupiedTracks[track] = false
                    }
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    mainHandler.post {
                        rootView?.removeView(item)
                        occupiedTracks[track] = false
                    }
                }
            })
        }.start()
    }
}
