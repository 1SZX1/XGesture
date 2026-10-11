package com.slideindex.app.overlay.quickmenu

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.slideindex.app.R
import com.slideindex.app.barcode.ZxingBarcodeScanner
import com.slideindex.app.util.finishWithoutTransition
import kotlin.math.abs
import kotlin.math.min

/**
 * 实时相机扫码（「快捷菜单 → 识码解析 → 相机扫码」）。
 *
 * **为什么用 Camera2 而不是 CameraX**：本仓库依赖是离线解析的（`--offline`），而 CameraX 的
 * artifact 既不在版本目录里、也不在本地 Gradle 缓存中；`android.hardware.camera2` 是框架 API、
 * `com.google.zxing:core` 又已经是直接依赖，因此这条路**零新增依赖**即可实现，APK 体积不变。
 *
 * 数据流：`Camera2 预览 → ImageReader(YUV_420_888) → 取 plane[0] 亮度 →
 * ZxingBarcodeScanner.scanLuminance(...)`。取亮度平面而不是 JPEG，省掉每帧的 JPEG 编码与 RGB
 * 转换；ZXing 的二维码识别对旋转不敏感，所以不需要按设备方向旋转帧。
 *
 * 交互对齐 quickdash 的扫码页：**取景框 + 手电筒 + 放大 + 点击对焦**。
 * - 放大通过 `SCALER_CROP_REGION` 实现，同时作用于分析帧，因此放大真的能帮助识别小码；
 * - 对焦用 `CONTROL_AF_REGIONS` + `CONTROL_AF_TRIGGER_START`，打完一次再交回连续对焦；
 * - 手电筒用 `FLASH_MODE_TORCH`，无闪光灯的机型按钮直接隐藏。
 *
 * 性能取舍：单帧里"只试二维码类码制"与"试全部码制"开销差一个量级，所以只在每
 * [ALL_FORMAT_EVERY_N_FRAMES] 帧做一次全码制尝试。
 *
 * ⚠️ 本类**没有自动化测试**（相机必须真机验证）；解码逻辑由 `ZxingBarcodeScanner` 的既有测试覆盖。
 */
class QuickMenuScanActivity : ComponentActivity() {

    private lateinit var previewView: SurfaceView
    private lateinit var torchButton: Button
    private lateinit var zoomButton: Button

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var requestBuilder: CaptureRequest.Builder? = null

    private var activeArray: Rect? = null
    private var maxZoomRatio = 1f
    private var zoomStepIndex = 0
    private var torchAvailable = false
    private var torchEnabled = false

    private val workerThread = HandlerThread("quick-menu-scan").apply { start() }
    private val workerHandler = Handler(workerThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 只在 worker 线程读写，因此不需要原子类型。 */
    private var decoding = false
    private var frameCount = 0
    private var delivered = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openCamera() else deliver(null)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())

        previewView.holder.addCallback(
            object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    if (hasCameraPermission()) {
                        openCamera()
                    } else {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    closeCamera()
                }
            },
        )
    }

    override fun onDestroy() {
        closeCamera()
        workerThread.quitSafely()
        // 用户直接返回（没扫到）时也要把 null 交回去，否则面板的回调会一直挂着。
        if (!delivered) {
            delivered = true
            onDecoded?.invoke(null)
            onDecoded = null
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun buildContentView(): View {
        previewView = SurfaceView(this).apply {
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    focusAt(event.x, event.y)
                }
                true
            }
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                previewView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                ScanReticleView(this@QuickMenuScanActivity),
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        torchButton = controlButton(getString(R.string.quick_menu_scan_torch)) { toggleTorch() }
        zoomButton = controlButton(zoomLabel()) { cycleZoom() }
        val closeButton = controlButton(getString(R.string.quick_menu_scan_close)) { deliver(null) }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(torchButton)
            addView(zoomButton)
            addView(closeButton)
        }
        root.addView(
            controls,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply { setMargins(0, 48, 24, 0) },
        )
        return root
    }

    private fun controlButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            alpha = 0.85f
            setOnClickListener { onClick() }
        }

    private fun zoomLabel(): String = "${zoomSteps[zoomStepIndex].toInt()}x"

    // ---------------------------------------------------------------- 相机

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (isFinishing || cameraDevice != null) return
        val manager = getSystemService(CameraManager::class.java) ?: return deliver(null)
        val picked = pickBackCamera(manager) ?: return deliver(null)
        val (cameraId, characteristics) = picked

        torchAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        torchButton.visibility = if (torchAvailable) View.VISIBLE else View.GONE
        activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        maxZoomRatio = (characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)
            .coerceAtLeast(1f)

        val configurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return deliver(null)
        val size = chooseAnalysisSize(configurationMap.getOutputSizes(ImageFormat.YUV_420_888))

        val reader = runCatching {
            ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, MAX_QUEUED_FRAMES)
        }.getOrNull() ?: return deliver(null)
        reader.setOnImageAvailableListener({ onFrameAvailable() }, workerHandler)
        imageReader = reader

        runCatching {
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        cameraDevice = device
                        val previewSurface = previewView.holder.surface
                        if (!previewSurface.isValid) {
                            deliver(null)
                            return
                        }
                        createSession(device, previewSurface, reader.surface)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        deliver(null)
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        Log.w(TAG, "camera error: $error")
                        device.close()
                        deliver(null)
                    }
                },
                workerHandler,
            )
        }.onFailure {
            Log.w(TAG, "openCamera failed", it)
            deliver(null)
        }
    }

    private fun createSession(device: CameraDevice, previewSurface: Surface, analysisSurface: Surface) {
        val builder = runCatching {
            device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                addTarget(analysisSurface)
            }
        }.getOrNull() ?: return deliver(null)
        requestBuilder = builder

        runCatching {
            device.createCaptureSession(
                listOf(previewSurface, analysisSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        applyRepeatingRequest()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.w(TAG, "createCaptureSession failed")
                        deliver(null)
                    }
                },
                workerHandler,
            )
        }.onFailure {
            Log.w(TAG, "createCaptureSession threw", it)
            deliver(null)
        }
    }

    /** 按当前的手电筒/缩放状态重建重复请求。 */
    private fun applyRepeatingRequest() {
        val session = captureSession ?: return
        val builder = requestBuilder ?: return
        runCatching {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.set(
                CaptureRequest.FLASH_MODE,
                if (torchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF,
            )
            builder.set(CaptureRequest.SCALER_CROP_REGION, currentCropRegion())
            session.setRepeatingRequest(builder.build(), null, workerHandler)
        }.onFailure {
            Log.w(TAG, "applyRepeatingRequest failed", it)
            deliver(null)
        }
    }

    /** 当前缩放对应的裁剪区域；放大同时作用于分析帧，所以真的能帮助识别小码。 */
    private fun currentCropRegion(): Rect? {
        val array = activeArray ?: return null
        val ratio = zoomSteps[zoomStepIndex].coerceIn(1f, maxZoomRatio)
        if (ratio <= 1f) return array
        val cropWidth = (array.width() / ratio).toInt().coerceAtLeast(1)
        val cropHeight = (array.height() / ratio).toInt().coerceAtLeast(1)
        val left = array.left + (array.width() - cropWidth) / 2
        val top = array.top + (array.height() - cropHeight) / 2
        return Rect(left, top, left + cropWidth, top + cropHeight)
    }

    private fun toggleTorch() {
        if (!torchAvailable) return
        torchEnabled = !torchEnabled
        torchButton.alpha = if (torchEnabled) 1f else 0.85f
        applyRepeatingRequest()
    }

    private fun cycleZoom() {
        zoomStepIndex = (zoomStepIndex + 1) % zoomSteps.size
        zoomButton.text = zoomLabel()
        applyRepeatingRequest()
    }

    /**
     * 点击对焦：把视图坐标映射回传感器坐标（要算上当前裁剪区域），
     * 触发一次 AF，然后把连续对焦交回去。
     */
    private fun focusAt(viewX: Float, viewY: Float) {
        val builder = requestBuilder ?: return
        val session = captureSession ?: return
        val array = activeArray ?: return
        val viewWidth = previewView.width
        val viewHeight = previewView.height
        if (viewWidth <= 0 || viewHeight <= 0) return
        val crop = currentCropRegion() ?: array

        val sensorX = crop.left + (viewX / viewWidth * crop.width())
        val sensorY = crop.top + (viewY / viewHeight * crop.height())
        val halfWidth = (crop.width() * FOCUS_REGION_FRACTION).toInt().coerceAtLeast(1)
        val halfHeight = (crop.height() * FOCUS_REGION_FRACTION).toInt().coerceAtLeast(1)
        val left = (sensorX - halfWidth).toInt().coerceIn(crop.left, (crop.right - 1).coerceAtLeast(crop.left))
        val top = (sensorY - halfHeight).toInt().coerceIn(crop.top, (crop.bottom - 1).coerceAtLeast(crop.top))
        val region = MeteringRectangle(
            left,
            top,
            (halfWidth * 2).coerceAtMost(crop.width()),
            (halfHeight * 2).coerceAtMost(crop.height()),
            MeteringRectangle.METERING_WEIGHT_MAX,
        )

        runCatching {
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            session.capture(builder.build(), null, workerHandler)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            applyRepeatingRequest()
        }.onFailure { Log.w(TAG, "focusAt failed", it) }
    }

    // ---------------------------------------------------------------- 帧解码

    private fun onFrameAvailable() {
        val reader = imageReader ?: return
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            if (decoding || delivered) return
            decoding = true
            frameCount++
            val luminance = extractLuminance(image) ?: return
            val results = ZxingBarcodeScanner.scanLuminance(
                luminance = luminance,
                width = image.width,
                height = image.height,
                qrOnly = frameCount % ALL_FORMAT_EVERY_N_FRAMES != 0,
            )
            results.firstOrNull()?.text?.takeIf { it.isNotBlank() }?.let { deliver(it) }
        } catch (error: Exception) {
            Log.w(TAG, "frame decode failed", error)
        } finally {
            decoding = false
            runCatching { image.close() }
        }
    }

    /**
     * 取 Y 平面为紧凑的亮度数组。
     *
     * `rowStride` 通常大于 `width`（硬件对齐），必须逐行拷；`pixelStride` 一般恒为 1。
     */
    private fun extractLuminance(image: Image): ByteArray? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        if (pixelStride == 1 && rowStride == width) {
            if (buffer.remaining() < width * height) return null
            return ByteArray(width * height).also { buffer.get(it, 0, it.size) }
        }

        val data = ByteArray(width * height)
        val row = ByteArray(rowStride)
        for (y in 0 until height) {
            val position = y * rowStride
            if (position >= buffer.limit()) break
            buffer.position(position)
            val available = min(rowStride, buffer.remaining())
            if (available <= 0) break
            buffer.get(row, 0, available)
            if (pixelStride == 1) {
                System.arraycopy(row, 0, data, y * width, min(width, available))
            } else {
                var source = 0
                val rowOffset = y * width
                for (x in 0 until width) {
                    if (source >= available) break
                    data[rowOffset + x] = row[source]
                    source += pixelStride
                }
            }
        }
        return data
    }

    private fun closeCamera() {
        runCatching { captureSession?.close() }
        captureSession = null
        requestBuilder = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { imageReader?.close() }
        imageReader = null
    }

    private fun deliver(text: String?) {
        if (delivered) return
        delivered = true
        mainHandler.post {
            onDecoded?.invoke(text)
            onDecoded = null
            if (!isFinishing) finishWithoutTransition()
        }
    }

    private fun pickBackCamera(manager: CameraManager): Pair<String, CameraCharacteristics>? {
        val ids = runCatching { manager.cameraIdList }.getOrNull().orEmpty()
        val backId = ids.firstOrNull { id ->
            runCatching {
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }.getOrDefault(false)
        } ?: ids.firstOrNull() ?: return null
        val characteristics = runCatching { manager.getCameraCharacteristics(backId) }.getOrNull() ?: return null
        return backId to characteristics
    }

    /**
     * 取接近 [TARGET_ANALYSIS_WIDTH] 的分析尺寸。
     *
     * 用全分辨率解码虽然更快命中，但单帧开销会大到让预览卡顿；1280 宽足够解常见二维码。
     */
    private fun chooseAnalysisSize(sizes: Array<Size>?): Size {
        if (sizes.isNullOrEmpty()) return Size(TARGET_ANALYSIS_WIDTH, DEFAULT_ANALYSIS_HEIGHT)
        val landscape = sizes.filter { it.width >= MIN_ANALYSIS_WIDTH && it.width >= it.height }
        val candidates = landscape.ifEmpty { sizes.toList() }
        return candidates.minByOrNull { abs(it.width - TARGET_ANALYSIS_WIDTH) }
            ?: Size(TARGET_ANALYSIS_WIDTH, DEFAULT_ANALYSIS_HEIGHT)
    }

    /** 取景框：四角括号，只画描边，不挡预览。 */
    private class ScanReticleView(context: Context) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            strokeCap = Paint.Cap.ROUND
            color = Color.WHITE
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val side = min(width, height) * RETICLE_SIDE_FRACTION
            val left = (width - side) / 2f
            val top = (height - side) / 2f
            val right = left + side
            val bottom = top + side
            val arm = side * RETICLE_ARM_FRACTION
            // 四个角，各两笔
            canvas.drawLine(left, top, left + arm, top, paint)
            canvas.drawLine(left, top, left, top + arm, paint)
            canvas.drawLine(right, top, right - arm, top, paint)
            canvas.drawLine(right, top, right, top + arm, paint)
            canvas.drawLine(left, bottom, left + arm, bottom, paint)
            canvas.drawLine(left, bottom, left, bottom - arm, paint)
            canvas.drawLine(right, bottom, right - arm, bottom, paint)
            canvas.drawLine(right, bottom, right, bottom - arm, paint)
        }
    }

    companion object {
        private const val TAG = "QuickMenuScan"
        private const val TARGET_ANALYSIS_WIDTH = 1280
        private const val DEFAULT_ANALYSIS_HEIGHT = 720
        private const val MIN_ANALYSIS_WIDTH = 640
        private const val MAX_QUEUED_FRAMES = 2
        private const val ALL_FORMAT_EVERY_N_FRAMES = 5
        private const val FOCUS_REGION_FRACTION = 0.08f
        private const val RETICLE_SIDE_FRACTION = 0.62f
        private const val RETICLE_ARM_FRACTION = 0.22f

        /** 可循环的放大档位（会按设备支持的最大数码变焦夹取）。 */
        private val zoomSteps = floatArrayOf(1f, 2f, 3f)

        /**
         * 结果回调（扫到原文，或用户取消时为 null）。
         *
         * 用静态回调而非 ActivityResult API，原因是调用方（快捷菜单面板）跑在 overlay 的
         * ComposeView 里、拿不到 `ActivityResultRegistry`；这与本仓库既有的
         * `SearchPanelImagePickerActivity` 做法一致。区别是这里在投递后**立即清空**引用，
         * 并且 `onDestroy` 兜底交付 null，不会留下跨 Activity 的悬挂回调。
         */
        private var onDecoded: ((String?) -> Unit)? = null

        fun launch(context: Context, onResult: (String?) -> Unit) {
            onDecoded = onResult
            val intent = Intent(context, QuickMenuScanActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
