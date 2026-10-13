package com.slideindex.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.slideindex.app.R
import com.slideindex.app.settings.FreeWindowLayoutFractions
import com.slideindex.app.settings.FreeWindowLayoutLimits
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val TAB_PORTRAIT = 0
private const val TAB_LANDSCAPE = 1

/**
 * 小窗尺寸位置编辑层（对齐 LinkGo 的 `FullScreenEditorOverlay`）。
 *
 * - 挂在 App 根部，盖住整屏（含底栏/侧栏），半透明黑底 + 可拖拽/缩放的窗口预览；
 * - 顶部切竖屏/横屏会把宿主 Activity 真的转到该方向，画布就是真机当前方向的真实屏幕；
 * - 拖动窗口改位置、拖右下角手柄改尺寸，点“完成并保存”一次性写回两套参数。
 *
 * 方向下发与还原见 [FreeWindowPreviewOrientationSession]（幂等 + 关闭时延迟还原），
 * 这样转屏引起的外壳重建不会把方向来回推。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeWindowLayoutEditorOverlay(
    portrait: FreeWindowLayoutFractions,
    landscape: FreeWindowLayoutFractions,
    onDismiss: () -> Unit,
    onSave: (portrait: FreeWindowLayoutFractions, landscape: FreeWindowLayoutFractions) -> Unit,
    portraitCap: Float = 1f,
    landscapeCap: Float = 1f,
) {
    val activity = LocalActivity.current
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_PORTRAIT) }
    val portraitState = remember(portrait, portraitCap) {
        mutableStateOf(portrait.limitedTo(portraitCap))
    }
    val landscapeState = remember(landscape, landscapeCap) {
        mutableStateOf(landscape.limitedTo(landscapeCap))
    }
    val isLandscape = selectedTab == TAB_LANDSCAPE
    val currentLayout = if (isLandscape) landscapeState.value else portraitState.value
    // 当前预览方向的交付上限：拖动尺寸不许越过它（越过的那部分系统不会照做，见 FreeWindowLauncher）。
    val currentCap = if (isLandscape) landscapeCap else portraitCap

    fun applyLayout(transform: (FreeWindowLayoutFractions) -> FreeWindowLayoutFractions) {
        val target = if (isLandscape) landscapeState else portraitState
        target.value = transform(target.value)
    }

    DisposableEffect(selectedTab) {
        FreeWindowPreviewOrientationSession.apply(isLandscape, activity)
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose { FreeWindowPreviewOrientationSession.scheduleRelease(activity) }
    }
    BackHandler(enabled = true) { onDismiss() }

    // 组件维持 M3 分段控件 / 按钮，但配色统一走 Miuix 主题色，
    // 避免与预览窗口（同样用 Miuix 主色）出现「同一主题两种蓝」。
    val segmentedColors = SegmentedButtonDefaults.colors(
        activeContainerColor = MiuixTheme.colorScheme.primary,
        activeContentColor = MiuixTheme.colorScheme.onPrimary,
        inactiveContainerColor = MiuixTheme.colorScheme.surfaceContainer,
        inactiveContentColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f)),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val canvasWidthPx = constraints.maxWidth.toFloat()
            val canvasHeightPx = constraints.maxHeight.toFloat()
            val windowWidthPx = canvasWidthPx * currentLayout.widthFraction
            val windowHeightPx = canvasHeightPx * currentLayout.heightFraction
            val maxLeftPx = (canvasWidthPx - windowWidthPx).coerceAtLeast(0f)
            val maxTopPx = (canvasHeightPx - windowHeightPx).coerceAtLeast(0f)
            val offsetXPx = (currentLayout.leftFraction * canvasWidthPx).coerceIn(0f, maxLeftPx)
            val offsetYPx = (currentLayout.topFraction * canvasHeightPx).coerceIn(0f, maxTopPx)

            Box(
                modifier = Modifier
                    .offset { IntOffset(offsetXPx.roundToInt(), offsetYPx.roundToInt()) }
                    .size(
                        width = with(density) { windowWidthPx.toDp() },
                        height = with(density) { windowHeightPx.toDp() },
                    )
                    .clip(RoundedCornerShape(20.dp))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.22f))
                    .border(2.5.dp, MiuixTheme.colorScheme.primary, RoundedCornerShape(20.dp))
                    .pointerInput(selectedTab, canvasWidthPx, canvasHeightPx) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            applyLayout { layout ->
                                val wPx = layout.widthFraction * canvasWidthPx
                                val hPx = layout.heightFraction * canvasHeightPx
                                val limitX = (canvasWidthPx - wPx).coerceAtLeast(0f)
                                val limitY = (canvasHeightPx - hPx).coerceAtLeast(0f)
                                val newLeftPx = (layout.leftFraction * canvasWidthPx + dragAmount.x)
                                    .coerceIn(0f, limitX)
                                val newTopPx = (layout.topFraction * canvasHeightPx + dragAmount.y)
                                    .coerceIn(0f, limitY)
                                layout.copy(
                                    leftFraction = FreeWindowLayoutLimits
                                        .normalizeLeft(newLeftPx / canvasWidthPx),
                                    topFraction = FreeWindowLayoutLimits
                                        .normalizeTop(newTopPx / canvasHeightPx),
                                )
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${(currentLayout.widthFraction * 100).roundToInt()}% × " +
                        "${(currentLayout.heightFraction * 100).roundToInt()}%",
                    color = MiuixTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .size(48.dp)
                        .pointerInput(selectedTab, canvasWidthPx, canvasHeightPx, currentCap) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                applyLayout { layout ->
                                    val newWidthPx = FreeWindowLayoutLimits.normalizeWidth(
                                        layout.widthFraction + dragAmount.x / canvasWidthPx
                                    ).coerceAtMost(currentCap) * canvasWidthPx
                                    val newHeightPx = FreeWindowLayoutLimits.normalizeHeight(
                                        layout.heightFraction + dragAmount.y / canvasHeightPx
                                    ).coerceAtMost(currentCap) * canvasHeightPx
                                    val maxLeft =
                                        ((canvasWidthPx - newWidthPx) / canvasWidthPx).coerceAtLeast(0f)
                                    val maxTop =
                                        ((canvasHeightPx - newHeightPx) / canvasHeightPx).coerceAtLeast(0f)
                                    layout.copy(
                                        widthFraction = newWidthPx / canvasWidthPx,
                                        heightFraction = newHeightPx / canvasHeightPx,
                                        leftFraction = layout.leftFraction.coerceIn(0f, maxLeft),
                                        topFraction = layout.topFraction.coerceIn(0f, maxTop),
                                    )
                                }
                            }
                        },
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(10.dp)
                            .size(18.dp)
                            .border(
                                width = 3.dp,
                                color = MiuixTheme.colorScheme.primary,
                                shape = RoundedCornerShape(bottomEnd = 5.dp),
                            )
                    )
                }
            }
        }

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp, start = 16.dp, end = 16.dp)
                .widthIn(max = 380.dp),
        ) {
            listOf(
                stringResource(R.string.free_window_portrait_preview),
                stringResource(R.string.free_window_landscape_preview),
            ).forEachIndexed { index, label ->
                SegmentedButton(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                    colors = segmentedColors,
                ) {
                    Text(text = label, maxLines = 1)
                }
            }
        }

        Button(
            onClick = {
                onSave(portraitState.value, landscapeState.value)
                onDismiss()
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = MiuixTheme.colorScheme.primary,
                contentColor = MiuixTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
        ) {
            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.cd_action_confirm))
            Text(
                text = stringResource(R.string.free_window_preview_save),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * 把一个方向的预置夹进「这套算法能交付的上限」：小米竖屏 70% / 横屏 55.5%，其它机型 100%。
 *
 * 位置随之夹到「窗口仍然放得下」的范围，免得预览里就已经出屏。
 */
private fun FreeWindowLayoutFractions.limitedTo(cap: Float): FreeWindowLayoutFractions {
    val limited = cap.coerceIn(FreeWindowLayoutLimits.MIN_WIDTH_FRACTION, 1f)
    val width = FreeWindowLayoutLimits.normalizeWidth(widthFraction).coerceAtMost(limited)
    val height = FreeWindowLayoutLimits.normalizeHeight(heightFraction).coerceAtMost(limited)
    return FreeWindowLayoutFractions(
        widthFraction = width,
        heightFraction = height,
        leftFraction = FreeWindowLayoutLimits.normalizeLeft(leftFraction)
            .coerceAtMost((1f - width).coerceAtLeast(0f)),
        topFraction = FreeWindowLayoutLimits.normalizeTop(topFraction)
            .coerceAtMost((1f - height).coerceAtLeast(0f)),
    )
}
