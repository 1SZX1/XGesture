package com.slideindex.app.overlay.quickmenu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.slideindex.app.R
import com.slideindex.app.service.ScreenshotCaptureOutcome
import com.slideindex.app.service.SlideIndexAccessibilityService
import kotlin.math.roundToInt

/**
 * 「快捷菜单 → 屏幕取色」。
 *
 * ## 为什么是"放大镜 + 可拖动准星"而不是"点一下"
 *
 * 手指尖本身就是直径 ~9mm 的一块，点击报上来的坐标只是这块面积的**中心**，误差在
 * 高 DPI 屏上就是二三十个像素。所以业界取色器一律给放大镜：**放大镜负责"看清是哪颗像素"，
 * 手指负责"粗定位"**，两者配合才取得到 1 像素宽的东西（细边框、1px 分割线、图表里的单色块）。
 * 放大倍数是整数（由 `alignedSpanAndLens` 定），并且**最近邻放大**——`drawImage` 显式传
 * `FilterQuality.None` 关掉插值，因此放大镜里的方块就是地道的像素块，不是糊的渐变。
 *
 * ## 三件容易踩的事，这里都处理了
 *
 * 1. **坐标映射**：容器用 `aspectRatio(位图宽/高)` 撑开，于是容器与位图**同比例**，
 *    `ContentScale.Fit` 在同比例下正好铺满、没有 letterbox，视图坐标到位图像素就是一个纯缩放。
 *    这段换算全在 [QuickMenuColorPickerGeometry] 里，有单测覆盖（差一个像素肉眼看不出来）。
 * 2. **放大镜贴边**：源窗口裁剪到位图内（不补黑边），再由 `loupeContent` 让准星始终对着
 *    真正取到的那颗像素；放大镜整体也夹在容器内，不会画到图外面去。跨度取奇数 + 镜子边长取
 *    跨度的整数倍，这两条让"位图铺满镜子"和"准星落在正中"同时精确成立（见几何那边的注释）。
 * 3. **按钮落在容器内**：整块可交互区域（图 + 准星 + 放大镜 + 取值按钮）都在**同一个 Box** 里。
 *    早先的写法把按钮放在图的**下方**，图一高按钮就被挤出可视区，"取完色发现没地方复制"。
 *
 * ## 点一下也要能取色
 *
 * 只挂 `detectDragGestures` 是不够的：它要越过 touch slop 才回调 `onDragStart`，"按一下不动"
 * 这种最自然的取色动作它根本不响应。所以这里**叠了两层**手势——`detectTapGestures` 管按下即取，
 * `detectDragGestures` 管拖动实时跟手。
 *
 * 失败处理沿用「截屏识别」那条路：无障碍截屏失败时把**已有的**三条错误文案内联显示在屏内
 * （不用 toast——面板本身就是覆盖层窗口，系统 toast 会被它盖住）。
 */
@Composable
internal fun QuickMenuColorPickerScreen() {
    val context = LocalContext.current
    var screenshot by remember { mutableStateOf<Bitmap?>(null) }
    var sample by remember { mutableStateOf<SamplePoint?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    // 取色历史：进页面就读一次，之后每取到一个新颜色追加一条（去重与截断在 Codec 里）。
    var history by remember { mutableStateOf<List<Int>>(emptyList()) }
    LaunchedEffect(Unit) {
        history = QuickMenuColorHistoryStore.read(context)
    }
    val sampledArgb = sample?.argb
    LaunchedEffect(sampledArgb) {
        // alpha=0 的位置取出来没有意义（截图里未绘制区域就是全透明），不记进历史。
        if (sampledArgb != null && sampledArgb ushr 24 != 0) {
            history = QuickMenuColorHistoryStore.record(context, sampledArgb)
        }
    }

    // 三个失败文案各取一次；`captureFailed` 是带 %1$d 的模板，用 format 补错误码。
    val captureUnavailable = stringResource(R.string.quick_menu_scan_capture_unavailable)
    val captureThrottled = stringResource(R.string.quick_menu_scan_capture_throttled)
    val captureFailedTemplate = stringResource(R.string.quick_menu_scan_capture_failed)

    fun capture() {
        errorText = null
        sample = null
        val started = runCatching {
            SlideIndexAccessibilityService.captureDisplayBitmap { outcome ->
                when (outcome) {
                    is ScreenshotCaptureOutcome.Success -> {
                        screenshot = outcome.bitmap
                        errorText = null
                    }
                    ScreenshotCaptureOutcome.ServiceUnavailable -> errorText = captureUnavailable
                    ScreenshotCaptureOutcome.Throttled -> errorText = captureThrottled
                    is ScreenshotCaptureOutcome.Failed ->
                        errorText = captureFailedTemplate.format(outcome.errorCode)
                }
            }
        }.getOrDefault(false)
        // 没能发起时**不会**有回调，必须在这里补一条错误，否则页面永远停在"正在截图"的样子。
        if (!started) errorText = captureUnavailable
    }

    ToolScaffold {
        Text(
            text = stringResource(R.string.quick_menu_color_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 按钮文案只能是已有资源：抓新图与首次截图共用这个标题。
            AssistChip(
                onClick = { capture() },
                label = { Text(stringResource(R.string.quick_menu_color_title)) },
            )
            IconButton(onClick = { capture() }) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
            }
        }

        errorText?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        val bitmap = screenshot
        if (bitmap != null) {
            PickerSurface(
                bitmap = bitmap,
                sample = sample,
                onSample = { point, argb -> sample = SamplePoint(point.x, point.y, point.viewX, point.viewY, argb) },
            )
            ColorDetails(sample = sample, history = history)
        } else if (errorText == null) {
            // 还没截图：不摆空图占位，只给一行提示，用户点上面那个按钮即可。
            Text(
                text = stringResource(R.string.quick_menu_color_title),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 取样结果：位图像素坐标 + 容器内坐标 + 取到的颜色。 */
private data class SamplePoint(
    val x: Int,
    val y: Int,
    val viewX: Float,
    val viewY: Float,
    val argb: Int,
)

// ---------------------------------------------------------------- 图 + 准星 + 放大镜 + 按钮

/**
 * 整块可交互区：截图 → 显示 → 拖动取色，以及取色结果与历史。
 *
 * 图、准星、放大镜、"取值"按钮全在同一个 `Box` 里，所以**不管图多高，按钮都贴在图上、随图滚动**，
 * 不会像早先那样被挤到屏幕外。
 */
@Composable
private fun PickerSurface(
    bitmap: Bitmap,
    sample: SamplePoint?,
    onSample: (QuickMenuColorPickerGeometry.SamplePoint, Int) -> Unit,
) {
    val context = LocalContext.current
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val accent = MaterialTheme.colorScheme.primary

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            // ⚠️ 这一行是坐标换算成立的前提：容器与位图同比例 → ContentScale.Fit 正好铺满、无留白。
            .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black),
    ) {
        val density = LocalDensity.current
        // 容器像素尺寸（fractional part 直接砍掉：算像素坐标时用不到）。
        val viewport = QuickMenuColorPickerGeometry.Viewport(
            width = constraints.maxWidth,
            height = constraints.maxHeight,
        )
        val bitmapSize = remember(bitmap) {
            QuickMenuColorPickerGeometry.BitmapSize(bitmap.width, bitmap.height)
        }

        /**
         * 取色。`bitmap` 可能是**硬件位图**（`takeScreenshot` 返回的就是），
         * `getPixel` 对硬件位图有时会抛，所以这里先在软件位图上读一次并缓存。
         */
        val sampler = remember(bitmap) { ScreenSampler(bitmap) }

        fun sampleAt(x: Float, y: Float) {
            if (viewport.width <= 0 || viewport.height <= 0) return
            val point = QuickMenuColorPickerGeometry.viewOffsetToPixel(x, y, viewport, bitmapSize)
            val argb = sampler.argbAt(point.x, point.y) ?: return
            onSample(point, argb)
        }

        Image(
            bitmap = imageBitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )

        // 手势层：**两个** gesture detector 叠在一起，这是 `detectDragGestures` 的固有限制——
        // 它只在越过 touch slop 之后才回调 onDragStart，所以"按一下不动、抬手"这种最自然的取色
        // 动作它根本不响应。于是：
        //   * `detectTapGestures(onPress)`：手指一按下就取样（点一下即可取色）；
        //   * `detectDragGestures`：滑动过程中持续取样（实时跟手）。
        // 两者都在同一个 Box 上，先按下的那个先跑，互不消费对方的 down 事件。
        // ⚠️ key 里必须带上 viewport：闭包里的 viewport 是第一帧捕获的，布局尺寸变了要重建手势层，
        //    否则会拿旧尺寸去映射（表现为"拖动后准星和手指错位"）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bitmap, viewport) {
                    detectTapGestures(
                        onPress = { offset ->
                            sampleAt(offset.x, offset.y)
                            // 等待抬起：不消费事件，拖动交给下面那层。
                            tryAwaitRelease()
                        },
                    )
                }
                .pointerInput(bitmap, viewport) {
                    detectDragGestures(
                        onDragStart = { offset -> sampleAt(offset.x, offset.y) },
                        onDrag = { change, _ ->
                            // 用 change.position 而不是 `dragAmount` 累加：累加会丢帧漂移，
                            // 而 position 是"当前手指在哪"，天生自洽。
                            change.consume()
                            sampleAt(change.position.x, change.position.y)
                        },
                    )
                },
        )

        // 准星 + 放大镜：和上面的位图共用同一套坐标（两者都铺满容器）。
        Canvas(modifier = Modifier.fillMaxSize()) {
            val point = sample ?: return@Canvas
            val center = Offset(
                x = point.viewX.coerceIn(0f, size.width),
                y = point.viewY.coerceIn(0f, size.height),
            )
            val loupeSide = LOUPE_SIZE.toPx()
            // 跨度与镜子边长必须**一起**算（`alignedSpanAndLens`）：跨度定手感（镜子里看到多大一块
            // 屏幕），镜子边长再上取整到跨度的整数倍，于是放大倍数是整数——像素网格等宽、位图与
            // 镜子等大，准星中线也只差最后那不到半颗像素的量化误差。
            val (span, lensSide) = QuickMenuColorPickerGeometry.alignedSpanAndLens(
                bitmap = bitmapSize,
                lensSidePx = loupeSide.roundToInt(),
                span = LOUPE_SOURCE_SPAN,
                minSpan = MIN_LOUPE_SOURCE_SPAN,
                maxSpan = MAX_LOUPE_SOURCE_SPAN,
            )
            val content = QuickMenuColorPickerGeometry.loupeContent(
                x = point.x,
                y = point.y,
                span = span,
                lensSide = lensSide,
                bitmap = bitmapSize,
            )
            drawPickerOverlay(
                image = imageBitmap,
                content = content,
                center = center,
                loupeSide = lensSide.toFloat(),
                accent = accent,
                density = density,
            )
        }

        // 取值按钮贴在容器内（底部居中、半透明底），保证"取完色"和"复制"在一屏之内。
        if (sample != null) {
            PickedValueBar(
                argb = sample.argb,
                onCopy = { copyColor(context, "color", QuickMenuColorMath.toHex(sample.argb)) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(8.dp),
            )
        }
    }
}

/**
 * 一次性把手里的位图读成软件位图，随后的 `getPixel` 都在这张上读。
 *
 * 硬件位图（`Bitmap.Config.HARDWARE`）不能保证 `getPixel` 可用，也不该在拖动的每一帧里
 * 反复读它；截图只有一张，转一次的成本可以忽略。
 */
private class ScreenSampler(bitmap: Bitmap) {

    private val software: Bitmap? =
        if (bitmap.config == Bitmap.Config.HARDWARE) {
            runCatching {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            }.getOrNull()
        } else {
            bitmap
        }

    fun argbAt(x: Int, y: Int): Int? {
        val source = software ?: return null
        if (x < 0 || y < 0 || x >= source.width || y >= source.height) return null
        return runCatching { source.getPixel(x, y) }.getOrNull()
    }
}

// ---------------------------------------------------------------- 绘制

/** 放大镜的物理边长。96dp 是"够大能看清像素块、又不至于盖掉半个屏幕"的一档。 */
private val LOUPE_SIZE = 96.dp

/** 放大镜离开取样点的距离，避免被手指本身压住。 */
private val LOUPE_MARGIN = 24.dp

/**
 * 跨度的允许区间（像素），**必须都是奇数**。
 *
 * **手感由跨度决定**：跨度就是"镜子里能看到多大一块屏幕"。17 像素在 1080p 上约是屏宽的 1.6%，
 * 正好够"看清一颗像素 + 保留一点周围方位"；太小只剩两三颗像素、失去方位，太大则一颗像素只占
 * 几个屏幕像素，"像素块"就糊了。
 *
 * ⚠️ 为什么必须是奇数：[QuickMenuColorPickerGeometry.loupeSource] 用 `left = x - span/2` 把采样点
 * 放在窗口中间。奇数跨度下采样点正好是正中间那颗（左右邻域一样多），配合 `alignedSpanAndLens`
 * 的整数倍对齐，准星就能**精确**落在镜子正中；偶数跨度下左右邻域差一颗，准星会固定偏
 * `scale/2`（半个源像素 × 倍数）。
 */
private const val LOUPE_SOURCE_SPAN = 17
private const val MIN_LOUPE_SOURCE_SPAN = 15
private const val MAX_LOUPE_SOURCE_SPAN = 21

/** 图外压暗强度：只压暗不挖亮的话，放大镜那块"亮"才显得出来（和识码标记同一手法）。 */
private const val PICKER_DIM_ALPHA = 0.35f

/** 放大镜外圈的不透明底：让"亮"和压暗形成明确边界。 */
private const val LOUPE_OUTLINE_ALPHA = 0.85f

/**
 * 画准星与放大镜。
 *
 * 放大镜里的像素用 `drawImage` 的 `srcOffset / srcSize / dstOffset / dstSize` 重画，并且显式传
 * `FilterQuality.None`（最近邻）：方块就是地道的一颗颗像素，不会糊成渐变。这和
 * `ScanImageWithMarkers` 里"按原亮度重画标记区"是同一套手法，区别只是这里的源矩形比目标框小
 * 很多（所以是放大），以及必须关掉默认的双线性插值。
 */
private fun DrawScope.drawPickerOverlay(
    image: ImageBitmap,
    content: QuickMenuColorPickerGeometry.LoupeContent,
    center: Offset,
    loupeSide: Float,
    accent: Color,
    density: Density,
) {
    // 1) 整幅压暗：取样点在外面时，光靠十字线在花哨的截图上是看不清的。
    drawRect(color = Color.Black.copy(alpha = PICKER_DIM_ALPHA))

    // 2) 准星：黑色粗线 + 白色细线**叠画**。三件一起用是为了在任意底色上都能看见——
    //    纯白十字在浅色截图上会消失，纯黑十字在深色截图上会消失。
    val armLength = density.run { 14.dp.toPx() }
    val thin = density.run { 1.5.dp.toPx() }
    val thick = thin * 3f
    for (stroke in listOf(thick to Color.Black, thin to Color.White)) {
        val (width, color) = stroke
        drawLine(
            color = color,
            start = Offset(center.x - armLength, center.y),
            end = Offset(center.x + armLength, center.y),
            strokeWidth = width,
        )
        drawLine(
            color = color,
            start = Offset(center.x, center.y - armLength),
            end = Offset(center.x, center.y + armLength),
            strokeWidth = width,
        )
        drawCircle(
            color = color,
            radius = armLength * 0.5f,
            center = center,
            style = Stroke(width = width),
        )
    }

    // 3) 放大镜位置：默认在手指**上方**（手指压着下面，看得到上面）；贴顶时翻到下方；
    //    最后整体夹在容器内，绝不画到图外面去。
    val half = loupeSide / 2f
    val margin = density.run { LOUPE_MARGIN.toPx() }
    val above = center.y - margin - loupeSide
    val top = if (above >= 0f) above else center.y + margin
    val left = center.x - half
    val safeLeft = left.coerceIn(0f, (size.width - loupeSide).coerceAtLeast(0f))
    val safeTop = top.coerceIn(0f, (size.height - loupeSide).coerceAtLeast(0f))
    val loupeTopLeft = Offset(safeLeft, safeTop)
    val loupeCenter = Offset(safeLeft + half, safeTop + half)

    if (content.src.width > 0 && content.src.height > 0) {
        // 圆形裁剪：像素块必须留在镜子里，不能溢到图上（溢出去就成了"图上莫名其妙多一块"）。
        val lens = Path().apply {
            addOval(
                Rect(
                    left = loupeTopLeft.x,
                    top = loupeTopLeft.y,
                    right = loupeTopLeft.x + loupeSide,
                    bottom = loupeTopLeft.y + loupeSide,
                ),
            )
        }
        clipPath(lens) {
            // 先铺一层**和图上压暗层同色**的底，再画放大后的像素块。
            //
            // 几何那边已经保证位图严丝合缝铺满镜子（`dstOffset` 被夹在"铺满区间"内），所以正常
            // 情况下这一层完全被盖住、看不见。留着它是两道保险：
            //   * `drawImage` 的 `dstOffset/dstSize` 是整数，取整后可能差 1px 露一条极细的边；
            //   * 源矩形退化（极小位图）时几何会退回"保证准星居中"，那时镜边确实可能露空。
            // 用压暗同色（而不是纯黑）兜底，露出来的一丝看起来就是"图外的自然延续"，不会像黑边故障。
            drawRect(
                color = Color.Black.copy(alpha = PICKER_DIM_ALPHA),
                topLeft = loupeTopLeft,
                size = Size(loupeSide, loupeSide),
            )
            drawImage(
                image = image,
                srcOffset = IntOffset(content.src.x, content.src.y),
                srcSize = IntSize(content.src.width, content.src.height),
                dstOffset = IntOffset(
                    (loupeTopLeft.x + content.dstOffsetX).roundToInt(),
                    (loupeTopLeft.y + content.dstOffsetY).roundToInt(),
                ),
                dstSize = IntSize(loupeSide.roundToInt(), loupeSide.roundToInt()),
                // ⚠️ 必须显式关掉插值：Compose 的 drawImage 默认用 FilterQuality.Low（双线性），
                // 那样放大出来是糊的渐变，"看清是哪颗像素"这件事就白做了。这里要的是最近邻。
                filterQuality = FilterQuality.None,
            )
            drawPixelGrid(content = content, topLeft = loupeTopLeft)

            // 放大区中心的小环：和内圈同心，一眼能看出"镜子正中就是取样的那颗像素"。
            val ringRadius = density.run { 4.dp.toPx() }
            drawCircle(
                color = Color.White,
                radius = ringRadius,
                center = loupeCenter,
                style = Stroke(width = density.run { 1.dp.toPx() }),
            )
        }
    }

    // 4) 镜身外框：不透明底 + accent 圈，把"放大镜"从截图里拎出来。
    drawCircle(
        color = Color.Black.copy(alpha = LOUPE_OUTLINE_ALPHA),
        radius = half,
        center = loupeCenter,
        style = Stroke(width = density.run { 3.dp.toPx() }),
    )
    drawCircle(
        color = accent,
        radius = half,
        center = loupeCenter,
        style = Stroke(width = density.run { 1.5.dp.toPx() }),
    )
}

/** 放大镜里的像素网格：1 源像素一条线。用它确认"准星真的只差一颗像素"。 */
private fun DrawScope.drawPixelGrid(
    content: QuickMenuColorPickerGeometry.LoupeContent,
    topLeft: Offset,
) {
    if (content.scale <= 2f) return
    val gridColor = Color.White.copy(alpha = 0.25f)
    val lineWidth = 1f
    for (column in 1 until content.src.width) {
        val x = topLeft.x + content.dstOffsetX + column * content.scale
        drawLine(
            color = gridColor,
            start = Offset(x, topLeft.y),
            end = Offset(x, topLeft.y + content.src.height * content.scale),
            strokeWidth = lineWidth,
        )
    }
    for (row in 1 until content.src.height) {
        val y = topLeft.y + content.dstOffsetY + row * content.scale
        drawLine(
            color = gridColor,
            start = Offset(topLeft.x, y),
            end = Offset(topLeft.x + content.src.width * content.scale, y),
            strokeWidth = lineWidth,
        )
    }
}

// ---------------------------------------------------------------- 取色结果与历史

/** 图上那条"当前取到的色"的浮条：色块 + 一键复制 HEX。 */
@Composable
private fun PickedValueBar(argb: Int, onCopy: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            // 用 surfaceContainer 而不是纯 surface：浮条压在**截图**上，必须和截图有明确区分，
            // 否则一条靠色值撑宽的浮条在花哨的图上会"糊"进去。
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(width = 1.dp, color = MaterialTheme.colorScheme.outline, shape = CircleShape),
                )
                Text(
                    text = QuickMenuColorMath.toHex(argb),
                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                )
                IconButton(onClick = onCopy) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                }
            }
        }
    }
}

/** 取色结果（五种格式各一个复制按钮）+ 取色历史。 */
@Composable
private fun ColorDetails(sample: SamplePoint?, history: List<Int>) {
    val context = LocalContext.current

    val current = sample?.argb ?: return

    QuickMenuColorFormats.all(current).forEachIndexed { index, (label, value) ->
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (index == 0) {
                    // 第一行（HEX）的左侧留给**色块本身**：它是"当前取到的颜色"，
                    // 比再写一遍"HEX"更能说明这一列是什么。
                    Box(
                        modifier = Modifier
                            .size(width = 52.dp, height = 28.dp)
                            .padding(end = 8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(current))
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(6.dp),
                            ),
                    )
                } else {
                    // 格式名是通用缩写（HEX/RGB/HSL/HSV），不是需要翻译的文案，所以不占字符串资源。
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.width(52.dp),
                    )
                }
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                )
                IconButton(onClick = { copyColor(context, label, value) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                }
            }
        }
    }

    if (history.isNotEmpty()) {
        // 历史条：一行可横向滚动的色块，点一条即复制它的 HEX
        //（历史的价值就是"上次那个色再给我一次"）。不写标题——那需要新增字符串资源。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            history.forEach { color ->
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(color))
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(10.dp),
                        )
                        .clickable { copyColor(context, "color", QuickMenuColorMath.toHex(color)) },
                )
            }
        }
    }
}

/** 复制到剪贴板。`contentDescription = null` 的图标按钮点完没有反馈，所以失败要静默兜住、不崩。 */
private fun copyColor(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    runCatching { clipboard.setPrimaryClip(ClipData.newPlainText(label, text)) }
}
