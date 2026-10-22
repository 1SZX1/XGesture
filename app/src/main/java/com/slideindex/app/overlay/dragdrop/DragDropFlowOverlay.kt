package com.slideindex.app.overlay.dragdrop

import android.content.ClipboardManager
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.core.graphics.drawable.toBitmap
import com.slideindex.app.R
import com.slideindex.app.overlay.FloatBallImageSearchPanel
import com.slideindex.app.overlay.FloatBallTextPick
import com.slideindex.app.overlay.OverlayCompose
import com.slideindex.app.overlay.OverlayComposeOwner
import com.slideindex.app.overlay.OverlayWindowTypes
import com.slideindex.app.screendraw.ScreenDrawOverlay
import com.slideindex.app.search.SearchEngineLauncher
import com.slideindex.app.settings.AppSettings
import com.slideindex.app.settings.DragDropFlowPosition
import com.slideindex.app.settings.SearchEngineCatalog
import com.slideindex.app.settings.SearchEngineConfig
import com.slideindex.app.settings.SearchEngineStore
import com.slideindex.app.settings.SearchEngineType
import com.slideindex.app.settings.SettingsRepository
import com.slideindex.app.ui.theme.OverlayAwareModuleTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全局拖拽流转（Drag & Drop Flow）悬浮窗控制器：
 * - 待机时在屏幕边缘挂载一个 1px × 1px 无感透明锚点，用于监听系统全局拖放广播（DRAG_FLAG_GLOBAL）；
 * - 捕获到用户在任意 App 拖拽图片或文字时（ACTION_DRAG_STARTED），瞬时扩展为全屏并呈现毛玻璃流转栏；
 * - 支持将内容释放至各目标引擎或【恢复】/【取消】。
 */
class DragDropFlowController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
) {
    companion object {
        private const val TAG = "DragDropFlow"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var composeView: ComposeView? = null
    private var composeOwner: OverlayComposeOwner? = null
    private var anchorParams: WindowManager.LayoutParams? = null
    private var fullParams: WindowManager.LayoutParams? = null

    private var settingsJob: Job? = null
    private var isExpanded = false

    private val visibleState = mutableStateOf(false)
    private val positionState = mutableStateOf(DragDropFlowPosition.BOTTOM)
    private val hapticEnabledState = mutableStateOf(true)
    private val itemsState = mutableStateOf<List<DragDropFlowItem>>(emptyList())
    private val dragPositionState = mutableStateOf<Offset?>(null)
    private var currentHoveredId: String? = null

    private var latestSettings: AppSettings = AppSettings()
    private var isHandlingDrop = false

    fun start() {
        settingsJob?.cancel()
        settingsJob = scope.launch {
            settingsRepository.settings.collectLatest { s ->
                latestSettings = s
                positionState.value = s.dragDropFlowPosition
                hapticEnabledState.value = s.dragDropFlowHapticEnabled
                withContext(Dispatchers.Main) {
                    if (s.dragDropFlowEnabled) {
                        ensureAnchorAttached()
                    } else {
                        destroyWindow()
                    }
                }
            }
        }
    }

    fun stop() {
        settingsJob?.cancel()
        settingsJob = null
        mainHandler.post {
            destroyWindow()
        }
    }

    private fun ensureAnchorAttached(): Boolean {
        if (composeView != null) return true

        val overlayContext = OverlayCompose.themedContext(context)
        val owner = OverlayComposeOwner()

        val view = OverlayCompose.createComposeView(overlayContext, owner).apply {
            visibility = View.VISIBLE
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

            setContent {
                OverlayAwareModuleTheme {
                    DragDropFlowBarLayout(
                        visible = visibleState.value,
                        position = positionState.value,
                        items = itemsState.value,
                        dragPosition = dragPositionState.value,
                        hapticEnabled = hapticEnabledState.value,
                        onHoverChanged = { currentHoveredId = it },
                        onCancel = { handleCancel() },
                        onRestore = { handleRestore() },
                        onSelectTarget = { /* handled on drop */ },
                    )
                }
            }

            // 核心全局拖拽监听
            setOnDragListener { _, event ->
                handleDragEvent(event)
            }
        }

        val anchor = WindowManager.LayoutParams(
            1,
            1,
            OverlayWindowTypes.overlayWindowType(context),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
        }

        val added = runCatching { windowManager.addView(view, anchor) }
            .onFailure { Log.e(TAG, "addView failed", it) }
            .isSuccess

        if (!added) {
            OverlayCompose.clearViewTreeOwners(view)
            owner.destroy()
            return false
        }

        this.composeView = view
        this.composeOwner = owner
        this.anchorParams = anchor
        this.isExpanded = false

        Log.d(TAG, "DragDropFlow anchor window attached (1px待机)")
        return true
    }

    private fun getActiveLayoutParams(position: DragDropFlowPosition): WindowManager.LayoutParams {
        val density = context.resources.displayMetrics.density
        return when (position) {
            DragDropFlowPosition.BOTTOM -> {
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    (175 * density).toInt(),
                    OverlayWindowTypes.overlayWindowType(context),
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                }
            }
            DragDropFlowPosition.LEFT -> {
                WindowManager.LayoutParams(
                    (120 * density).toInt(),
                    WindowManager.LayoutParams.MATCH_PARENT,
                    OverlayWindowTypes.overlayWindowType(context),
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                }
            }
            DragDropFlowPosition.RIGHT -> {
                WindowManager.LayoutParams(
                    (120 * density).toInt(),
                    WindowManager.LayoutParams.MATCH_PARENT,
                    OverlayWindowTypes.overlayWindowType(context),
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                }
            }
        }
    }

    private fun handleDragEvent(event: DragEvent): Boolean {
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                val desc = event.clipDescription ?: return false
                val hasImage = desc.hasMimeType("image/*")
                val hasAudio = desc.hasMimeType("audio/*") ||
                    desc.hasMimeType("audio/mp4")
                val hasFile = hasAudio || desc.hasMimeType("application/*")
                val hasText = desc.hasMimeType("text/*") ||
                    desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                    desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)

                if (!hasImage && !hasText && !hasFile) {
                    return false
                }

                // 准备候选项目列表
                itemsState.value = buildCandidateItems(hasImage = hasImage, hasText = hasText, hasFile = hasFile)
                currentHoveredId = null
                isHandlingDrop = false

                // 瞬时展开为边缘带状窗并展示流转栏
                expandToActiveRegion()
                visibleState.value = true
                return true
            }

            DragEvent.ACTION_DRAG_LOCATION -> {
                dragPositionState.value = Offset(event.x, event.y)
                return true
            }

            DragEvent.ACTION_DROP -> {
                isHandlingDrop = true
                val targetId = currentHoveredId
                val clipData = event.clipData

                when {
                    targetId == "__cancel__" -> {
                        Toast.makeText(context, "已取消拖拽", Toast.LENGTH_SHORT).show()
                        if (ScreenDrawOverlay.isShowing) {
                            ScreenDrawOverlay.dismiss()
                        }
                    }
                    targetId == "__restore__" -> {
                        Toast.makeText(context, "已恢复", Toast.LENGTH_SHORT).show()
                        if (ScreenDrawOverlay.isShowing) {
                            ScreenDrawOverlay.restoreFromDragFlow()
                        }
                    }
                    targetId != null && clipData != null -> {
                        processDrop(targetId, clipData)
                        if (ScreenDrawOverlay.isShowing) {
                            ScreenDrawOverlay.dismiss()
                        }
                    }
                }
                // 仅当命中流转栏项目时消费 Drop，避免拦截正常页面拖拽
                return targetId != null
            }

            DragEvent.ACTION_DRAG_ENDED -> {
                dragPositionState.value = null
                val droppedTarget = currentHoveredId
                currentHoveredId = null
                visibleState.value = false

                // 若未释放至流转栏即结束，且屏幕绘制处于临时隐藏，则销毁退出屏幕绘制
                if (!isHandlingDrop && ScreenDrawOverlay.isShowing) {
                    ScreenDrawOverlay.dismiss()
                }

                // 等待淡出动画完成后还原为 1px 待机
                mainHandler.postDelayed({
                    if (!visibleState.value) {
                        collapseToAnchor()
                    }
                }, 180)
                return true
            }

            DragEvent.ACTION_DRAG_ENTERED -> return true
            DragEvent.ACTION_DRAG_EXITED -> return true
            else -> return false
        }
    }

    private fun expandToActiveRegion() {
        val view = composeView ?: return
        val activeParams = getActiveLayoutParams(positionState.value)
        runCatching { windowManager.updateViewLayout(view, activeParams) }
        isExpanded = true
    }

    private fun collapseToAnchor() {
        val view = composeView ?: return
        val anchor = anchorParams ?: return
        if (isExpanded) {
            runCatching { windowManager.updateViewLayout(view, anchor) }
            isExpanded = false
        }
    }

    private fun handleCancel() {
        visibleState.value = false
        if (ScreenDrawOverlay.isShowing) {
            ScreenDrawOverlay.dismiss()
        }
        collapseToAnchor()
    }

    private fun handleRestore() {
        visibleState.value = false
        if (ScreenDrawOverlay.isShowing) {
            ScreenDrawOverlay.restoreFromDragFlow()
        }
        collapseToAnchor()
    }
    private fun buildCandidateItems(hasImage: Boolean, hasText: Boolean, hasFile: Boolean): List<DragDropFlowItem> {
        val pm = context.packageManager
        val list = mutableListOf<DragDropFlowItem>()

        if (hasImage) {
            // 1. 内置聚合以图搜图
            list.add(
                DragDropFlowItem(
                    id = "builtin_image_search",
                    title = "以图搜图",
                    iconVector = Icons.Outlined.ImageSearch,
                    iconTintColor = Color(0xFF2196F3),
                )
            )

            // 2. 常见购物/图片流转应用（若已安装）
            val appCandidates = listOf(
                Triple("com.taobao.taobao", "拍立淘", "com.taobao.taobao"),
                Triple("com.xunmeng.pinduoduo", "图片搜低价", "com.xunmeng.pinduoduo"),
                Triple("com.jingdong.app.mall", "拍照购", "com.jingdong.app.mall"),
                Triple("com.tencent.mm", "发送给微信", "com.tencent.mm"),
                Triple("com.tencent.mobileqq", "发送给QQ", "com.tencent.mobileqq"),
                Triple("com.xingin.xhs", "小红书发布", "com.xingin.xhs"),
            )

            for ((pkg, title, _) in appCandidates) {
                val icon = runCatching { pm.getApplicationIcon(pkg).toBitmap(96, 96) }.getOrNull()
                if (icon != null) {
                    list.add(
                        DragDropFlowItem(
                            id = "app_$pkg",
                            title = title,
                            iconBitmap = icon,
                        )
                    )
                }
            }

            // 3. 复制图片到剪贴板
            list.add(
                DragDropFlowItem(
                    id = "copy_image",
                    title = "复制图片",
                    iconVector = Icons.Outlined.ContentCopy,
                    iconTintColor = Color(0xFF4CAF50),
                )
            )

            // 4. 系统分享
            list.add(
                DragDropFlowItem(
                    id = "system_share_image",
                    title = "分享图片",
                    iconVector = Icons.Outlined.Share,
                    iconTintColor = Color(0xFFFF9800),
                )
            )
        } else if (hasFile) {
            // 通用文件/音频流转项：动态读取用户配置的文件分享引擎
            val engines = SearchEngineStore.fileSharePanelEngines(latestSettings.searchEngines)
            for (engine in engines) {
                val pkg = engine.targetPackage ?: continue
                val icon = (!engine.iconPath.isNullOrBlank()).let { hasPath ->
                    if (hasPath) runCatching { android.graphics.BitmapFactory.decodeFile(engine.iconPath) }.getOrNull() else null
                } ?: runCatching { pm.getApplicationIcon(pkg).toBitmap(96, 96) }.getOrNull()
                if (icon != null) {
                    list.add(
                        DragDropFlowItem(
                            id = "file_engine_${engine.id}",
                            title = engine.name,
                            iconBitmap = icon,
                        )
                    )
                }
            }

            // 复制文件
            list.add(
                DragDropFlowItem(
                    id = "copy_file",
                    title = "复制文件",
                    iconVector = Icons.Outlined.ContentCopy,
                    iconTintColor = Color(0xFF4CAF50),
                )
            )

            // 系统分享文件
            list.add(
                DragDropFlowItem(
                    id = "system_share_file",
                    title = "分享文件",
                    iconVector = Icons.Outlined.Share,
                    iconTintColor = Color(0xFFFF9800),
                )
            )
        } else {
            // 文本拖拽流转项
            // 1. 搜索引擎
            val engines = SearchEngineCatalog.defaultEngines(context)
            for (engine in engines.take(5)) {
                val pkgIcon = engine.targetPackage?.let { pkg ->
                    runCatching { pm.getApplicationIcon(pkg).toBitmap(96, 96) }.getOrNull()
                }
                list.add(
                    DragDropFlowItem(
                        id = "engine_${engine.id}",
                        title = engine.name,
                        iconBitmap = pkgIcon,
                        iconVector = if (pkgIcon == null) Icons.Outlined.Search else null,
                        iconTintColor = Color(0xFF2196F3),
                    )
                )
            }

            // 2. 翻译
            list.add(
                DragDropFlowItem(
                    id = "translate_text",
                    title = "翻译",
                    iconVector = Icons.Outlined.Translate,
                    iconTintColor = Color(0xFF9C27B0),
                )
            )

            // 3. 复制文本
            list.add(
                DragDropFlowItem(
                    id = "copy_text",
                    title = "复制文字",
                    iconVector = Icons.Outlined.ContentCopy,
                    iconTintColor = Color(0xFF4CAF50),
                )
            )

            // 4. 分享
            list.add(
                DragDropFlowItem(
                    id = "system_share_text",
                    title = "分享文字",
                    iconVector = Icons.Outlined.Share,
                    iconTintColor = Color(0xFFFF9800),
                )
            )
        }

        return list
    }

    private fun processDrop(targetId: String, clipData: android.content.ClipData) {
        scope.launch(Dispatchers.IO) {
            val item = clipData.getItemAt(0) ?: return@launch
            val uri = item.uri
            val text = item.text?.toString()

            withContext(Dispatchers.Main) {
                when {
                    targetId == "builtin_image_search" && uri != null -> {
                        val bitmap = decodeBitmapFromUri(uri)
                        if (bitmap != null) {
                            FloatBallImageSearchPanel.show(context, bitmap)
                        } else {
                            Toast.makeText(context, "无法读取拖放的图片", Toast.LENGTH_SHORT).show()
                        }
                    }
                    targetId.startsWith("file_engine_") && uri != null -> {
                        val engineId = targetId.removePrefix("file_engine_")
                        val engines = SearchEngineStore.fileSharePanelEngines(latestSettings.searchEngines)
                        val engine = engines.find { it.id == engineId }
                        val pkg = engine?.targetPackage
                        if (pkg != null) {
                            val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: ""
                            val mime = if (mimeType.isNotBlank()) mimeType else {
                                if (uri.toString().endsWith(".m4a", true)) "audio/mp4" else "*/*"
                            }
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = mime
                                putExtra(Intent.EXTRA_STREAM, uri)
                                val act = engine.targetActivity
                                if (!act.isNullOrBlank()) {
                                    component = android.content.ComponentName(pkg, act)
                                } else {
                                    setPackage(pkg)
                                }
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching {
                                context.startActivity(sendIntent)
                            }.onFailure {
                                context.startActivity(Intent.createChooser(sendIntent, "分享文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                    }
                    targetId.startsWith("app_") && uri != null -> {
                        val pkg = targetId.removePrefix("app_")
                        val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: ""
                        val isImage = mimeType.startsWith("image/") ||
                            uri.toString().endsWith(".png", true) ||
                            uri.toString().endsWith(".jpg", true) ||
                            uri.toString().endsWith(".webp", true)

                        if (isImage) {
                            val bitmap = decodeBitmapFromUri(uri)
                            if (bitmap != null) {
                                val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                                val comp = launchIntent?.component
                                if (comp != null) {
                                    FloatBallTextPick.shareScreenshotTo(context, bitmap, comp)
                                } else {
                                    Toast.makeText(context, "无法唤起目标应用", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            val mime = if (mimeType.isNotBlank()) mimeType else {
                                if (uri.toString().endsWith(".m4a", true)) "audio/mp4" else "*/*"
                            }
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = mime
                                putExtra(Intent.EXTRA_STREAM, uri)
                                setPackage(pkg)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching {
                                context.startActivity(sendIntent)
                            }.onFailure {
                                context.startActivity(Intent.createChooser(sendIntent, "分享文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                    }
                    targetId == "system_share_file" && uri != null -> {
                        val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                        val mime = if (!mimeType.isNullOrBlank()) mimeType else {
                            if (uri.toString().endsWith(".m4a", true)) "audio/mp4" else "*/*"
                        }
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = mime
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "分享文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    targetId == "copy_file" && uri != null -> {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = android.content.ClipData.newUri(context.contentResolver, "file", uri)
                        cm?.setPrimaryClip(clip)
                        Toast.makeText(context, "已复制文件到剪贴板", Toast.LENGTH_SHORT).show()
                    }
                    targetId == "copy_image" && uri != null -> {
                        val bitmap = decodeBitmapFromUri(uri)
                        if (bitmap != null) {
                            FloatBallTextPick.copyImage(context, bitmap)
                        }
                    }
                    targetId == "system_share_image" && uri != null -> {
                        val bitmap = decodeBitmapFromUri(uri)
                        if (bitmap != null) {
                            val shareUri = FloatBallTextPick.createShareImageUri(context, bitmap)
                            if (shareUri != null) {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "image/*"
                                    putExtra(Intent.EXTRA_STREAM, shareUri)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "分享图片").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                    }
                    targetId.startsWith("engine_") && !text.isNullOrBlank() -> {
                        val engineId = targetId.removePrefix("engine_")
                        val engine = SearchEngineCatalog.defaultEngines(context).find { it.id == engineId }
                        if (engine != null) {
                            SearchEngineLauncher.launch(context, engine, text, latestSettings)
                        }
                    }
                    targetId == "copy_text" && !text.isNullOrBlank() -> {
                        FloatBallTextPick.copyText(context, text)
                        Toast.makeText(context, "已复制文字到剪贴板", Toast.LENGTH_SHORT).show()
                    }
                    targetId == "translate_text" && !text.isNullOrBlank() -> {
                        FloatBallTextPick.translateText(context, text, "zh-CN")
                    }
                    targetId == "system_share_text" && !text.isNullOrBlank() -> {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(Intent.createChooser(intent, "分享文字").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }
    }

    private suspend fun decodeBitmapFromUri(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull()
    }

    private fun destroyWindow() {
        val view = composeView
        val owner = composeOwner
        composeView = null
        composeOwner = null
        anchorParams = null
        fullParams = null
        isExpanded = false

        if (view != null) {
            runCatching { windowManager.removeView(view) }
            OverlayCompose.teardownOverlayCompose(view, owner)
        }
    }
}
