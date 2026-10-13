package com.slideindex.app.clipboard.monitor

/**
 * Based on [ClipboardListener](https://github.com/aa2013/ClipboardListener) (MIT).
 */
import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

internal fun copyAssetToExternalPrivateDir(
    context: Context,
    assetFileName: String,
): Boolean {
    var inputStream: InputStream? = null
    var outputStream: FileOutputStream? = null
    return try {
        inputStream = context.resources.assets.open(assetFileName)
        val outFile = File(context.getExternalFilesDir(null), assetFileName)
        outputStream = FileOutputStream(outFile)
        val buffer = ByteArray(1024)
        var length: Int
        while (inputStream.read(buffer).also { length = it } > 0) {
            outputStream.write(buffer, 0, length)
        }
        outputStream.flush()
        // Shizuku 用户服务是以 shell(uid 2000) 身份去 app_process 加载这个 DEX 的：
        // 文件默认落在 0660（只有 App 自己能读），shell 打不开 classpath 就会
        // ClassNotFoundException → ART abort，隐藏 API 通道起不来（表现为"未在监听"）。
        // 与 ModuleHookConfigWriter.makeHookReadable 同一套做法 —— 同目录的 hook_config.json
        // 就是这么拿到 0644、从而能被其它 uid 读取的。
        outFile.setReadable(true, false)
        true
    } catch (e: IOException) {
        Log.e("ClipboardMonitorAssets", "Failed to copy asset file: $assetFileName", e)
        false
    } finally {
        runCatching { inputStream?.close() }
        runCatching { outputStream?.close() }
    }
}

/** DEX entry class packaged in [LISTENER_ZIP_ASSET]. */
internal const val LISTENER_MAIN_CLASS = "top.coclyun.clipshare.clipboard_listener.ClipboardListener"

internal const val LISTENER_ZIP_ASSET = "listener.zip"
