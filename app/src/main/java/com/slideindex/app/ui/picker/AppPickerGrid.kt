package com.slideindex.app.ui.picker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slideindex.app.data.AppInfo
import com.slideindex.app.freezer.FreezerOperations
import com.slideindex.app.ui.Md3PickerAppLeading
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 挑应用网格的列数：与快捷启动器 / 蜂窝添加页保持一致。 */
const val APP_PICKER_GRID_COLUMNS = 4

/**
 * 挑应用页面的统一网格（动作选择、小组件面板、快捷启动器添加都走它）。
 *
 * 格子内容复用 [Md3PickerAppLeading]，所以分身黄点、冻结/暂停角标、置灰与其它页面一致。
 * 选中态用右上角勾选圈表示，键统一是 `(包名, userId)`——主实例与分身互不影响。
 */
fun LazyListScope.appPickerGridItems(
    apps: List<AppInfo>,
    selectedKeys: Set<String>,
    onAppClick: (AppInfo) -> Unit,
    columns: Int = APP_PICKER_GRID_COLUMNS,
) {
    val rows = apps.chunked(columns)
    items(
        count = rows.size,
        key = { rowIndex -> rows[rowIndex].joinToString("|") { FreezerOperations.keyOf(it) } },
    ) { rowIndex ->
        val row = rows[rowIndex]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            row.forEach { app ->
                AppPickerGridCell(
                    app = app,
                    selected = FreezerOperations.keyOf(app) in selectedKeys,
                    onClick = { onAppClick(app) },
                    modifier = Modifier.weight(1f),
                )
            }
            repeat(columns - row.size) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AppPickerGridCell(
    app: AppInfo,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Md3PickerAppLeading(app)
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(10.dp),
                    )
                }
            }
        }
        Text(
            text = app.label,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 5.dp, start = 2.dp, end = 2.dp),
        )
    }
}
