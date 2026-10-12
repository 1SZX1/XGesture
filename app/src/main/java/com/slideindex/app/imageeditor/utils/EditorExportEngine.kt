/*
 * 移植自 GestureEVO（https://github.com/LumiaGG/GestureEVO，GPL-3.0-only）
 *   源文件：inspire/imageeditor/utils/EditorExportEngine.kt
 * 已按本项目的数据模型、主题与集成层改写（部分模块拆分为多文件、部分能力为本项目新增）；
 * 版权与许可声明见 THIRD_PARTY_NOTICES.md，文件级映射见 docs/ATTRIBUTIONS.md。
 */

package com.slideindex.app.imageeditor.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.slideindex.app.imageeditor.model.EditAction
import kotlin.math.max
import kotlin.math.roundToInt

object EditorExportEngine {
    fun exportBitmap(sourceBitmap: Bitmap, actions: List<EditAction>, scaleFactor: Float): Bitmap {
        val scale = max(scaleFactor, 0.1f)
        val output = Bitmap.createBitmap(
            max(1, (sourceBitmap.width * scale).roundToInt()),
            max(1, (sourceBitmap.height * scale).roundToInt()),
            Bitmap.Config.ARGB_8888,
        )
        val mosaicBitmap = EditorRenderUtils.generateMosaicBitmap(sourceBitmap)
        val canvas = Canvas(output)
        canvas.scale(scale, scale)
        canvas.drawBitmap(sourceBitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        val layer = canvas.saveLayer(0f, 0f, sourceBitmap.width.toFloat(), sourceBitmap.height.toFloat(), null)
        actions.forEach { action ->
            EditorRenderUtils.drawAction(canvas, action, mosaicBitmap)
        }
        canvas.restoreToCount(layer)
        if (!mosaicBitmap.isRecycled) mosaicBitmap.recycle()
        return output
    }
}
