package com.slideindex.app.ui.gesturepicker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.slideindex.app.R
import com.slideindex.app.macro.Macro
import com.slideindex.app.macro.MacroStore
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 悬浮面板（快速启动器 / 圆环启动器 / 蜂窝）里的宏选择层。
 *
 * ⚠️ 这些面板自己就是**悬浮窗**（window type 2038），**不能**在里面弹平台 Dialog
 * （`WindowDialog`/`Dialog` 都是 type 2，系统会抛 "Window type mismatch" 直接闪退）。
 * 所以这里改成"画在同一个界面里"的覆盖层。
 */
@Composable
fun MacroPickOverlay(
    onDismissRequest: () -> Unit,
    onPick: (Macro) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val macros = remember { MacroStore.all(context) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onDismissRequest),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(enabled = false) {}
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.macro_action_run_macro),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            if (macros.isEmpty()) {
                Text(
                    text = stringResource(R.string.macro_pick_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    macros.forEach { macro ->
                        Text(
                            text = macro.name.ifBlank { Macro.FALLBACK_NAME },
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(macro) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 「运行宏」需要先选一条宏：这是动作选择器里的二级选择弹窗。
 *
 * 只读一次存储（打开时读），选中后把整条宏回调出去 —— 绑定只保存宏 id（见 `GestureAction.RunMacro`），
 * 所以宏之后改名/改步骤都不需要重新绑定。
 *
 * ⚠️ 只能在**普通 Activity 窗口**里用；悬浮面板里请用 [MacroPickOverlay]。
 */
@Composable
fun MacroPickDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onPick: (Macro) -> Unit,
) {
    if (!show) return
    val context = LocalContext.current
    val macros = remember(show) { MacroStore.all(context) }

    WindowDialog(
        show = true,
        title = stringResource(R.string.macro_action_run_macro),
        onDismissRequest = onDismissRequest,
    ) {
        Column {
            if (macros.isEmpty()) {
                Text(
                    text = stringResource(R.string.macro_pick_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    macros.forEach { macro ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(macro) }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(
                                text = macro.name.ifBlank { Macro.FALLBACK_NAME },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = stringResource(
                                    R.string.macro_row_summary,
                                    macro.name.ifBlank { Macro.FALLBACK_NAME },
                                    macro.actionCount,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = onDismissRequest,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 44.dp),
                minHeight = 44.dp,
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}
