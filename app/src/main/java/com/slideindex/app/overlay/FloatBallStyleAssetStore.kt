package com.slideindex.app.overlay

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import androidx.core.net.toUri
import java.io.File

/**
 * 将用户从文档选择器获得的 URI 复制到应用私有目录，避免重装/权限失效后悬浮球样式丢失。
 */
internal object FloatBallStyleAssetStore {
    private const val ASSET_DIR = "float_ball_assets"
    private const val GIF_FILE = "float_ball.gif"
    private const val CUSTOM_IMAGE_FILE = "float_ball_custom"
    private const val SLIDE_PREFIX = "float_ball_slide_"

    fun importGif(context: Context, sourceUri: Uri): String? =
        importReplacing(context, sourceUri, GIF_FILE)

    fun importCustomImage(context: Context, sourceUri: Uri): String? =
        importReplacing(
            context = context,
            sourceUri = sourceUri,
            destName = CUSTOM_IMAGE_FILE + extensionFor(context, sourceUri)
        )

    fun importSlideshow(context: Context, sourceUris: List<Uri>): List<String> {
        if (sourceUris.isEmpty()) return emptyList()
        val dir = assetDir(context)
        dir.listFiles()
            ?.filter { it.name.startsWith(SLIDE_PREFIX) }
            ?.forEach { it.delete() }
        return sourceUris.mapIndexedNotNull { index, uri ->
            importReplacing(
                context = context,
                sourceUri = uri,
                destName = "$SLIDE_PREFIX$index${extensionFor(context, uri)}",
                reuseDir = dir
            )
        }
    }

    fun resolveReadableUri(context: Context, stored: String): Uri? {
        if (stored.isBlank()) return null
        val uri = stored.toUri()
        return if (canRead(context, uri)) uri else null
    }

    /**
     * 读取图片原图宽高比（width / height，按 EXIF 方向校正后的**显示方向**）。
     *
     * 只解文件头（[BitmapFactory.Options.inJustDecodeBounds]），不解像素：导入时算一次并落盘，
     * 布局期只读设置里的 Float —— 窗口尺寸是同步算的，不能在布局线程解码整张图。
     * 返回 null 表示读不出来（损坏文件 / 不支持的格式），调用方应把旧宽高比清成 0。
     */
    fun readAspect(context: Context, uri: Uri): Float? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // ⚠️ 不能写成 `openInputStream(uri)?.use { decodeStream(...) } ?: return null`：
        // inJustDecodeBounds 模式下 decodeStream **必然返回 null**，use 会把这个 null 当成结果传出来，
        // 于是永远被判定为"流打不开"提前返回 —— 真机实锤：宽高比恒为 0，图片退化成方框里的一条细带。
        // 必须把"流是否打开成功"和"decodeStream 的返回值"分开判断，只看 options.outWidth/outHeight。
        val input = context.contentResolver.openInputStream(uri) ?: return@runCatching null
        input.use { BitmapFactory.decodeStream(it, null, options) }
        aspectFromRawSize(
            rawWidthPx = options.outWidth,
            rawHeightPx = options.outHeight,
            rotationDegrees = exifRotationDegrees(context, uri),
        )
    }.getOrNull()

    private fun exifRotationDegrees(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            when (
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
    }.getOrDefault(0)

    fun canRead(context: Context, stored: String): Boolean {
        if (stored.isBlank()) return false
        return canRead(context, stored.toUri())
    }

    private fun canRead(context: Context, uri: Uri): Boolean =
        runCatching {
            when (uri.scheme) {
                "file" -> {
                    val path = uri.path ?: return@runCatching false
                    val file = File(path)
                    file.exists() && file.canRead() && file.length() > 0L
                }
                else -> context.contentResolver.openInputStream(uri)?.use { } != null
            }
        }.getOrDefault(false)

    private fun importReplacing(
        context: Context,
        sourceUri: Uri,
        destName: String,
        reuseDir: File? = null
    ): String? = runCatching {
        val dir = reuseDir ?: assetDir(context)
        dir.mkdirs()
        val dest = File(dir, destName)
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: return@runCatching null
        check(dest.exists() && dest.length() > 0L)
        dest.toUri().toString()
    }.getOrNull()

    private fun assetDir(context: Context): File = File(context.filesDir, ASSET_DIR)

    private fun extensionFor(context: Context, uri: Uri): String {
        val mime = context.contentResolver.getType(uri).orEmpty()
        return when {
            mime.contains("gif", ignoreCase = true) -> ".gif"
            mime.contains("png", ignoreCase = true) -> ".png"
            mime.contains("webp", ignoreCase = true) -> ".webp"
            mime.contains("jpeg", ignoreCase = true) || mime.contains("jpg", ignoreCase = true) -> ".jpg"
            else -> ".img"
        }
    }
}

/**
 * 由「BitmapFactory 读到的原始宽高」和「EXIF 旋转角度」算出**显示方向**的宽高比。
 *
 * BitmapFactory 不应用 EXIF，而实际显示（ImageDecoder）会应用，所以 90°/270° 要互换宽高；
 * 180° 只是上下颠倒，宽高不用换（以前写成 `in 90..270`，把 180 也换掉了，是个隐性错误）。
 * 宽或高非法（读文件头失败）时返回 null。
 */
internal fun aspectFromRawSize(
    rawWidthPx: Int,
    rawHeightPx: Int,
    rotationDegrees: Int,
): Float? {
    if (rawWidthPx <= 0 || rawHeightPx <= 0) return null
    val swap = rotationDegrees % 180 != 0
    val width = if (swap) rawHeightPx else rawWidthPx
    val height = if (swap) rawWidthPx else rawHeightPx
    if (width <= 0 || height <= 0) return null
    return width.toFloat() / height.toFloat()
}
