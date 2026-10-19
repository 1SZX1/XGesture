package com.slideindex.app.screendraw

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import com.slideindex.app.R
import com.slideindex.app.overlay.LocalFrostedGlassBackdrop
import com.slideindex.app.overlay.rememberOverlayBlurEnabled

internal enum class ScreenDrawOrientation { HORIZONTAL, VERTICAL }

internal enum class ScreenDrawOptions { SHAPE, BRUSH }

/**
 * 一墨 2.0.5 的绘制工具栏（12 项）。
 *
 * 横向：两行玻璃胶囊，每行 6 项；竖向：一列玻璃胶囊。
 * 形状/笔刷不再用 Popup，而是在工具栏下方新增一行。
 */
@Composable
internal fun ScreenDrawToolbarCompose(
    orientation: ScreenDrawOrientation,
    passthrough: Boolean,
    toolbarScreenY: Int,
    optionsMode: ScreenDrawOptions?,
    initialTool: ScreenDrawTool,
    initialShape: ScreenDrawShape,
    initialColor: Int,
    initialWidthDp: Float,
    onTool: (ScreenDrawTool) -> Unit,
    onShape: (ScreenDrawShape) -> Unit,
    onColor: (Int) -> Unit,
    onWidth: (Float) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onToggleOrientation: () -> Unit,
    onTogglePassthrough: () -> Unit,
    onMinimize: () -> Unit,
    onFinish: () -> Unit,
    onExit: () -> Unit,
    onShapeOptions: () -> Unit,
    onBrushOptions: () -> Unit,
    onBlurEnabled: (Boolean) -> Unit,
    onMoveBy: (Float, Float) -> Unit,
    onMoveFinished: () -> Unit,
) {
    var tool by remember { mutableStateOf(initialTool) }
    var shape by remember { mutableStateOf(initialShape) }
    var color by remember { mutableStateOf(initialColor) }
    var widthDp by remember { mutableStateOf(initialWidthDp) }
    val density = LocalDensity.current.density
    val screenHeightPx = LocalContext.current.resources.displayMetrics.heightPixels
    val blurEnabled = rememberOverlayBlurEnabled()

    LaunchedEffect(blurEnabled) { onBlurEnabled(blurEnabled) }

    fun select(next: ScreenDrawTool) {
        tool = next
        onTool(next)
    }

    fun selectShape(next: ScreenDrawShape) {
        shape = next
        onShape(next)
        select(ScreenDrawTool.SHAPE)
    }

    fun selectColor(next: Int) {
        color = next
        onColor(next)
    }

    fun selectWidth(next: Float) {
        widthDp = next
        onWidth(next * density)
    }

    val containerColor = if (blurEnabled) {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val borderColor = MaterialTheme.colorScheme.outline.copy(
        alpha = if (blurEnabled) 0.16f else 0.12f,
    )
    val capsuleShadow = if (blurEnabled) 12.dp else 6.dp

    Box {
        if (orientation == ScreenDrawOrientation.HORIZONTAL) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ToolbarCapsule(containerColor, borderColor, capsuleShadow, onMoveBy, onMoveFinished) {
                ToolButton(painterResource(R.drawable.ic_iconoir_edit_pencil), tool == ScreenDrawTool.PEN) {
                    select(ScreenDrawTool.PEN)
                }
                ToolButton(shapePainter(shape), tool == ScreenDrawTool.SHAPE) {
                    if (tool != ScreenDrawTool.SHAPE) {
                        select(ScreenDrawTool.SHAPE)
                    } else {
                        onShapeOptions()
                    }
                }
                ToolButton(painterResource(R.drawable.ic_remix_eraser), tool == ScreenDrawTool.ERASER) {
                    select(ScreenDrawTool.ERASER)
                }
                ToolButton(painterResource(R.drawable.ic_remix_magic_line), tool == ScreenDrawTool.LASER) {
                    select(ScreenDrawTool.LASER)
                }
                ToolButton(painterResource(R.drawable.ic_iconoir_palette), optionsMode == ScreenDrawOptions.BRUSH) {
                    onBrushOptions()
                }
                ToolButton(painterResource(R.drawable.ic_iconoir_undo), false) { onUndo() }
            }

            ToolbarCapsule(containerColor, borderColor, capsuleShadow, onMoveBy, onMoveFinished) {
                ToolButton(painterResource(R.drawable.ic_iconoir_trash), false) { onClear() }
                ToolButton(painterResource(R.drawable.ic_iconoir_refresh), false) { onToggleOrientation() }
                ToolButton(painterResource(R.drawable.ic_iconoir_one_finger_gesture), passthrough) {
                    onTogglePassthrough()
                }
                ToolButton(painterResource(R.drawable.ic_iconoir_reduce), false) { onMinimize() }
                ToolButton(painterResource(R.drawable.ic_iconoir_check), false) { onFinish() }
                ToolButton(painterResource(R.drawable.ic_iconoir_xmark), false) { onExit() }
            }
        }
    } else {
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier.verticalScroll(scroll),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ToolbarCapsule(
                containerColor = containerColor,
                borderColor = borderColor,
                shadowElevation = capsuleShadow,
                onMoveBy = onMoveBy,
                onMoveFinished = onMoveFinished,
                vertical = true,
            ) {
                ToolButton(painterResource(R.drawable.ic_iconoir_edit_pencil), tool == ScreenDrawTool.PEN) {
                    select(ScreenDrawTool.PEN)
                }
                ToolButton(shapePainter(shape), tool == ScreenDrawTool.SHAPE) {
                    if (tool != ScreenDrawTool.SHAPE) {
                        select(ScreenDrawTool.SHAPE)
                    } else {
                        onShapeOptions()
                    }
                }
                ToolButton(painterResource(R.drawable.ic_remix_eraser), tool == ScreenDrawTool.ERASER) {
                    select(ScreenDrawTool.ERASER)
                }
                ToolButton(painterResource(R.drawable.ic_remix_magic_line), tool == ScreenDrawTool.LASER) {
                    select(ScreenDrawTool.LASER)
                }
                ToolButton(painterResource(R.drawable.ic_iconoir_palette), optionsMode == ScreenDrawOptions.BRUSH) {
                    onBrushOptions()
                }
                ToolButton(painterResource(R.drawable.ic_iconoir_undo), false) { onUndo() }
                ToolButton(painterResource(R.drawable.ic_iconoir_trash), false) { onClear() }
                ToolButton(painterResource(R.drawable.ic_iconoir_refresh), false) { onToggleOrientation() }
                ToolButton(painterResource(R.drawable.ic_iconoir_one_finger_gesture), passthrough) {
                    onTogglePassthrough()
                }
                ToolButton(painterResource(R.drawable.ic_iconoir_reduce), false) { onMinimize() }
                ToolButton(painterResource(R.drawable.ic_iconoir_check), false) { onFinish() }
                ToolButton(painterResource(R.drawable.ic_iconoir_xmark), false) { onExit() }
            }
        }
        }
    }
}

@Composable
private fun GlassCapsule(
    containerColor: Color,
    borderColor: Color,
    shadowElevation: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .shadow(shadowElevation, shape)
            .clip(shape)
            .border(1.dp, borderColor, shape),
    ) {
        LocalFrostedGlassBackdrop(
            modifier = Modifier.matchParentSize(),
            cornerRadiusPx = with(LocalDensity.current) { 24.dp.toPx() },
            blurRadiusPx = 24,
            tintColor = containerColor.toArgb(),
            solidFallbackColor = MaterialTheme.colorScheme.surface.toArgb(),
        )
        content()
    }
}

@Composable
private fun ToolbarCapsule(
    containerColor: Color,
    borderColor: Color,
    shadowElevation: androidx.compose.ui.unit.Dp,
    onMoveBy: (Float, Float) -> Unit,
    onMoveFinished: () -> Unit,
    vertical: Boolean = false,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    GlassCapsule(containerColor, borderColor, shadowElevation) {
        val dragModifier = Modifier.pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onDragEnd = { onMoveFinished() },
                onDragCancel = { onMoveFinished() },
            ) { change, dragAmount ->
                change.consume()
                onMoveBy(dragAmount.x, dragAmount.y)
            }
        }
        if (vertical) {
            Column(
                modifier = Modifier.padding(4.dp).then(dragModifier),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                content()
            }
        } else {
            Row(
                modifier = Modifier.padding(4.dp).then(dragModifier),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                content()
            }
        }
    }
}

@Composable
private fun OptionCapsule(
    containerColor: Color,
    borderColor: Color,
    shadowElevation: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit,
) {
    GlassCapsule(containerColor, borderColor, shadowElevation) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    }
}

@Composable
private fun ShapeOption(
    value: ScreenDrawShape,
    current: ScreenDrawShape,
    onSelect: (ScreenDrawShape) -> Unit,
) {
    ToolButton(shapeIconPainter(value), value == current) { onSelect(value) }
}

@Composable
private fun BrushOptions(
    color: Int,
    widthDp: Float,
    onColor: (Int) -> Unit,
    onWidth: (Float) -> Unit,
) {
    COLORS.forEach { swatch ->
        ColorDot(swatch, color == swatch) { onColor(swatch) }
    }
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(22.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
    )
    WIDTHS.forEach { value ->
        WidthDot(value, widthDp == value) { onWidth(value) }
    }
}

@Composable
private fun ToolButton(
    icon: Painter,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(
                if (selected) scheme.primary.copy(alpha = 0.65f) else Color.Transparent,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (selected) Color.White else scheme.onSurface.copy(alpha = 0.82f),
        )
    }
}

@Composable
private fun ColorDot(color: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(Color(color))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}

@Composable
private fun WidthDot(widthDp: Float, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.65f) else Color.Transparent,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size((2f * widthDp + 4f).dp)
                .clip(CircleShape)
                .background(
                    if (selected) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                ),
        )
    }
}

@Composable
private fun shapePainter(shape: ScreenDrawShape): Painter = when (shape) {
    ScreenDrawShape.RECTANGLE -> painterResource(R.drawable.ic_iconoir_square)
    ScreenDrawShape.OVAL -> rememberVectorPainter(Icons.Outlined.Circle)
    ScreenDrawShape.LINE -> rememberVectorPainter(Icons.Outlined.Remove)
    ScreenDrawShape.ARROW -> rememberVectorPainter(Icons.Outlined.ArrowForward)
}

@Composable
private fun shapeIconPainter(shape: ScreenDrawShape): Painter = shapePainter(shape)

private val COLORS = intArrayOf(
    0xFFFF3B30.toInt(),
    0xFFFFCC00.toInt(),
    0xFF34C759.toInt(),
    0xFF0A84FF.toInt(),
    0xFFFFFFFF.toInt(),
)

private val WIDTHS = floatArrayOf(1.5f, 3f, 6f)
