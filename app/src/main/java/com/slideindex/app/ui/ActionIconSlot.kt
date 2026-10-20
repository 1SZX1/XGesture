package com.slideindex.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slideindex.app.gesture.GestureAction
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 动作图标：**优先全局覆盖**（动作类型 / 应用包名），否则回退到动作自带的图标。
 *
 * 全局覆盖可能是字体图标（Lucide/Remix/Tabler）或图片，所以不能简单返回 `ImageVector`，
 * 这里统一用 [ActionIconView] 渲染；没有覆盖时才画默认的矢量图标。
 */
@Composable
fun ActionIconSlot(
    action: GestureAction,
    sizeDp: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val override = remember(action) {
        GlobalIconStore.actionIcon(context, action.type.name)
            ?: (action as? GestureAction.LaunchApp)?.let {
                GlobalIconStore.appIcon(context, it.packageName)
            }
    }

    if (override != null) {
        ActionIconView(action = action, iconKey = override, sizeDp = sizeDp, modifier = modifier)
    } else {
        Box(
            modifier = modifier.size(sizeDp.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = gestureActionIcon(action, outlined = true),
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.size(sizeDp.dp),
            )
        }
    }
}
