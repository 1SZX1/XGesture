package com.slideindex.app.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.slideindex.app.R
import com.slideindex.app.imageeditor.ImageEditorLaunchCache
import com.slideindex.app.imageeditor.SlideIndexImageEditorActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「相册图片编辑」动作的中转 Activity：**系统照片选择器 → 内置图片编辑器**。
 *
 * 为什么要中转：动作在 overlay 的 Compose 树里执行，那里没有 `ActivityResultRegistryOwner`，
 * 起不了系统选图（仓库里所有选图调用都在 Activity 里，见 `StashComposerImageTrampolineActivity`
 * 的同类说明）。所以这里只做一件事：拉起 `PickVisualMedia`，拿到图后交给编辑器。
 *
 * 几个刻意的选择：
 * - `PickVisualMedia`（系统照片选择器）：**不需要任何读图权限**，设备没有照片选择器时由
 *   `ActivityResultContracts` 自动回退到 `ACTION_OPEN_DOCUMENT`。
 * - 解码用 `ImageDecoder` 而不是 `BitmapFactory`：它**自动按 EXIF 摆正**相机竖拍的照片，
 *   而编辑器自己的 `BitmapFactory.decodeFile` 不处理 EXIF（直接把原图路径递过去会躺倒）。
 * - 长边压到 [MaxDimension]：编辑器是从文件整张解码的（没有采样），把 4000+ 的原图直接丢过去
 *   容易 OOM；2048 与仓库里其他图片链路（钉图 / 闪念加图）保持同一档。
 * - 落盘交给 `ImageEditorLaunchCache.put`，编辑器通过文件路径跨进程读取（同进程时也能走静态缓存），
 *   与悬浮球/取词面板进编辑器是同一条路。
 */
class GalleryImageEditTrampolineActivity : ComponentActivity() {

    private val pickLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) {
            Log.i(TAG, "选择器返回空 → 用户取消")
            finish()
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeOriented(uri) }
            if (bitmap == null) {
                Log.w(TAG, "解码失败 uri=$uri")
                Toast.makeText(
                    this@GalleryImageEditTrampolineActivity,
                    R.string.gesture_action_gallery_image_edit_load_failed,
                    Toast.LENGTH_SHORT,
                ).show()
                finish()
                return@launch
            }
            val editorPath = withContext(Dispatchers.IO) {
                ImageEditorLaunchCache.put(applicationContext, bitmap)
            }
            Log.i(TAG, "进编辑器 path=$editorPath size=${bitmap.width}x${bitmap.height}")
            SlideIndexImageEditorActivity.launch(this@GalleryImageEditTrampolineActivity, editorPath)
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 重建（旋转 / 深度回收）时不要再起一次选择器：结果会由 ActivityResultRegistry 补投递，
        // 重复 launch 只会让用户看到第二个相册。
        if (savedInstanceState != null) return
        runCatching {
            pickLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }.onFailure {
            Log.w(TAG, "拉起系统图片选择器失败", it)
            Toast.makeText(
                this,
                R.string.gesture_action_gallery_image_edit_load_failed,
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        }
    }

    private fun decodeOriented(uri: Uri): Bitmap? = runCatching {
        val source = ImageDecoder.createSource(contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val maxSide = maxOf(info.size.width, info.size.height)
            if (maxSide > MaxDimension) {
                decoder.setTargetSampleSize((maxSide + MaxDimension - 1) / MaxDimension)
            }
            // 编辑器要拿 Canvas 改像素：必须软件位图，否则硬件位图在绘制/裁剪时会抛。
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.onFailure {
        Log.w(TAG, "ImageDecoder 解码异常 uri=$uri", it)
    }.getOrNull()

    companion object {
        private const val TAG = "GalleryImageEdit"

        /** 送进编辑器的图片长边上限（与钉图 / 闪念加图同一档）。 */
        private const val MaxDimension = 2048

        /**
         * 从动作执行处调用（overlay 进程）：拉起本中转 Activity。
         *
         * @return 是否成功发起；`false` 时由调用方把动作按失败处理。
         */
        fun launch(context: Context): Boolean = runCatching {
            val intent = Intent(context, GalleryImageEditTrampolineActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrElse {
            Log.w(TAG, "启动中转 Activity 失败", it)
            false
        }
    }
}
