package com.slideindex.app.overlay.quickmenu

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
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
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.slideindex.app.R
import com.slideindex.app.barcode.BarcodePoint
import com.slideindex.app.barcode.BarcodeScanResult
import com.slideindex.app.barcode.ZxingBarcodeScanner
import com.slideindex.app.util.finishWithoutTransition
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

/**
 * 实时相机扫码（「快捷菜单 → 识码解析 → 相机扫码」）。
 *
 * **为什么用 Camera2 而不是 CameraX**：本仓库依赖是离线解析的（`--offline`），而 CameraX 的
 * artifact 既不在版本目录里、也不在本地 Gradle 缓存中；`android.hardware.camera2` 是框架 API、
 * `com.google.zxing:core` 又已经是直接依赖，因此这条路**零新增依赖**即可实现，APK 体积不变。
 *
 * 数据流（这一版的核心，做法对齐 quickdash 用的那套成熟库）：
 *
 * ```
 * Camera2（会话只挂一路 YUV_420_888 分析流，取支持范围内的大尺寸）
 *   → 每帧取 Y 平面 → 转成显示朝向 → 降采样
 *   → ① 我们自己把它画成灰度预览（比例/旋转/取景全在手，不经过 HAL 的预览缩放）
 *   → ② 便宜的触发检查（单码读取器 + 只主二值化 + 只标准亮度）
 *   → 触发时把**同一帧**按全分辨率转成位图交回面板 → 面板对这张图做一次全格式扫描
 * ```
 *
 * **为什么预览要自己画**（这是被真机数据逼出来的结论）：这台机器给 YUV 的尺寸是 1920×1012（非标准），
 * 而预览 Surface 能拿到的尺寸相机并不支持，HAL 只能换模式再缩放 —— **方向对、比例错**（用户反馈）。
 * 先试过"让视图比例等于流比例"，也试过按屏幕比例裁分析帧，都没解决，因为 SurfaceView 只能把缓冲拉伸到
 * 视图矩形。自己画之后，"看到的就是被识别的那一帧"**由构造保证**，不再依赖任何关于 HAL 行为的假设。
 *
 * 触发策略：**扫到就定格**（库的做法），但保留"这一帧和上一帧差不多才抓"的稳定判据 —— 手机还在动时
 * 抓到的那一帧是糊的，而**定格图必须是清楚的**；它最多多等一帧（约 33ms），用户感觉不到。
 *
 * 交互对齐 quickdash 的扫码页：**取景框 + 手电筒 + 放大 + 点击对焦**。
 * - 放大通过 `SCALER_CROP_REGION` 实现，同时作用于分析帧，因此放大真的能帮助识别小码；
 * - 对焦用 `CONTROL_AF_REGIONS` + `CONTROL_AF_TRIGGER_START`，打完一次再交回连续对焦；
 * - 手电筒用 `FLASH_MODE_TORCH`，无闪光灯的机型按钮直接隐藏。
 *
 * 性能取舍：单帧里"只试二维码类码制"与"试全部码制"开销差一个量级，所以只在每
 * [ALL_FORMAT_EVERY_N_FRAMES] 帧做一次全码制尝试；另外 [DECODE_INTERVAL_MS] 限制解码频率。
 * 逐帧耗时用 [reportFrameCost] 打点 —— **卡顿只能靠测量定位，不能靠推断**（这上面栽过两次）。
 *
 * ⚠️ 本类**没有自动化测试**（相机必须真机验证）；几何与解码逻辑由 `QuickMenuScanFrame` /
 * `ZxingBarcodeScanner` 的既有测试覆盖。
 */
class QuickMenuScanActivity : ComponentActivity() {

    private lateinit var previewView: PreviewImageView
    private lateinit var torchButton: Button
    private lateinit var zoomButton: Button

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var requestBuilder: CaptureRequest.Builder? = null

    /** `prepareCamera` 里定下来的相机流尺寸；[openCamera] 直接用，不再重新挑。 */
    private var captureSize: Size? = null

    private var activeArray: Rect? = null
    private var maxZoomRatio = 1f
    private var zoomStepIndex = 0
    private var torchAvailable = false
    private var torchEnabled = false

    /** 传感器方向（度）。定格时要把分析帧摆正到"人看到的方向"，靠它算旋转量。 */
    private var sensorOrientation = 0

    /**
     * 复用的亮度缓冲。
     *
     * 每帧新建一个 1280×720（约 0.9MB）的数组看着不多，但 30fps 就是 27MB/s 的垃圾；
     * 分析尺寸一时提到 1920 时更是到 60MB/s 级别，直接把预览拖成"一直一顿一顿"（真机反馈）。
     * 解码是单线程 + `decoding` 守卫，所以复用同一块缓冲是安全的。
     */
    private var luminanceBuffer: ByteArray? = null

    private val workerThread = HandlerThread("quick-menu-scan").apply { start() }
    private val workerHandler = Handler(workerThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 只在 worker 线程读写，因此不需要原子类型。 */
    private var decoding = false
    private var frameCount = 0
    private var delivered = false

    /**
     * 对准门槛（用户反馈"刚打开还没调好角度就出结果了"之后加的）。
     *
     * 三个条件都满足才算"对准了"，此时**立刻定格这一帧并返回**：
     * 1. [previewStartedAtMs] 之后过了 [PREROLL_MS]（等 AE/AF 收敛，刚打开那几帧又暗又糊）；
     * 2. 帧差稳定（[previousSignature] 与当前帧的粗网格差 ≤ [MOTION_THRESHOLD]）；
     * 3. 同一个码连续 [QuickMenuScanFrame.STABLE_FRAMES] 帧都解出来（杀掉"一帧侥幸"）。
     *
     * 注意：**不用累积**——多码交给面板在定格图上一次性全码制重扫，这样标记与图永远同源、不会偏。
     */
    private var previewStartedAtMs = 0L
    private var previousSignature: IntArray? = null

    /**
     * 相机流在**显示朝向**下的宽/高。
     *
     * 用途有两个，而且必须一致：预览视图按它做信箱式布局（否则预览会被拉伸变形），检测与定格也按它
     * 认定画面边界（`QuickMenuScanFrame.displayCrop`）。因为它等于整条流的比例，裁剪实际上是恒等的
     * ——这正是"看到的就是被扫描的"。
     */
    private var displayAspect = 0f

    /**
     * 设备支持的 AE 目标帧率范围。
     *
     * 会话里**只有分析流、没有预览流**（预览是我们自己画的），而部分 HAL 在没有预览流时会把出帧率压得很低，
     * 所以显式指定一个 30fps 的范围。
     */
    private var fpsRanges: Array<Range<Int>>? = null

    // ---- 自绘预览的双缓冲：写入槽与绘制槽分开，避免主线程画到一半被下一帧覆写 ----
    private val previewPixels = arrayOfNulls<IntArray>(2)
    private val previewBitmaps = arrayOfNulls<Bitmap>(2)
    private var previewSlot = 0
    private var previewLuminance: ByteArray? = null

    /** 检测用的复用缓冲（与预览那块**必须分开**：两者会在同一帧里先后写入）。 */
    private var detectionLuminance: ByteArray? = null
    private var lastPreviewAtMs = 0L

    /**
     * 逐帧节流：两次解码之间至少隔这么久。
     *
     * 预览卡顿的唯一结构性解法是**降低每帧的工作频率**，而不是在每帧内部抠几毫秒——因为分析流
     * 一旦让 ImageReader 排队，相机 HAL 就会开始丢帧，那时再优化都来不及。约 12fps 的检测对
     * "瞄准后扫描"完全够用，而 CPU 大部分时间留给预览。
     */
    private var lastDecodeAtMs = 0L

    /** 逐帧耗时仪表（累计 30 帧打一次平均）：卡顿时用它定位到底是哪一段在吃时间，不靠猜。 */
    private var timedFrames = 0
    private var extractMsSum = 0L
    private var signatureMsSum = 0L
    private var decodeMsSum = 0L

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openCamera() else deliver(emptyList(), null)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 必须在 buildContentView 之前：预览要按**相机流**的显示比例绘制，比例得先知道。
        prepareCamera()
        setContentView(buildContentView())

        // 预览由我们自己画（不再依赖相机预览流），所以不需要等 Surface 就绪，直接开相机。
        if (hasCameraPermission()) {
            openCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        closeCamera()
        workerThread.quitSafely()
        // 用户直接返回（没扫到）时也要把空列表交回去，否则面板的回调会一直挂着。
        if (!delivered) {
            delivered = true
            onDecoded?.invoke(emptyList(), null)
            onDecoded = null
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun buildContentView(): View {
        previewView = PreviewImageView(this).apply {
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
        val closeButton = controlButton(getString(R.string.quick_menu_scan_close)) { deliver(emptyList(), null) }

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
        val manager = getSystemService(CameraManager::class.java) ?: return deliver(emptyList(), null)
        val picked = pickBackCamera(manager) ?: return deliver(emptyList(), null)
        val (cameraId, _) = picked

        torchButton.visibility = if (torchAvailable) View.VISIBLE else View.GONE
        // 尺寸在 prepareCamera 里就定好了（预览的信箱比例依赖它）；这里拿不到说明相机不可用。
        val size = captureSize ?: return deliver(emptyList(), null)

        val reader = runCatching {
            ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, MAX_QUEUED_FRAMES)
        }.getOrNull() ?: return deliver(emptyList(), null)
        reader.setOnImageAvailableListener({ onFrameAvailable() }, workerHandler)
        imageReader = reader

        runCatching {
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        cameraDevice = device
                        createSession(device, reader.surface)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        deliver(emptyList(), null)
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        Log.w(TAG, "camera error: $error")
                        device.close()
                        deliver(emptyList(), null)
                    }
                },
                workerHandler,
            )
        }.onFailure {
            Log.w(TAG, "openCamera failed", it)
            deliver(emptyList(), null)
        }
    }

    /**
     * 会话只挂**分析流**一个 target。
     *
     * 有意不挂相机自带的预览流：真机日志显示这台机器的 HAL 会把画面**非等比缩放**填进预览缓冲
     * （方向对、比例错），而预览改成我们自己画之后就不需要它了 —— 比例、旋转、取景全部可控。
     * 代价是必须在 [applyRepeatingRequest] 里显式指定帧率范围，否则部分 HAL 在没有预览流时会把出帧率压很低。
     */
    private fun createSession(device: CameraDevice, analysisSurface: Surface) {
        val builder = runCatching {
            device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(analysisSurface)
            }
        }.getOrNull() ?: return deliver(emptyList(), null)
        requestBuilder = builder

        runCatching {
            device.createCaptureSession(
                listOf(analysisSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        applyRepeatingRequest()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.w(TAG, "createCaptureSession failed")
                        deliver(emptyList(), null)
                    }
                },
                workerHandler,
            )
        }.onFailure {
            Log.w(TAG, "createCaptureSession threw", it)
            deliver(emptyList(), null)
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
            preferredFpsRange()?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            session.setRepeatingRequest(builder.build(), null, workerHandler)
        }.onFailure {
            Log.w(TAG, "applyRepeatingRequest failed", it)
            deliver(emptyList(), null)
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
     * 点击对焦：把触碰点映射回传感器坐标。
     *
     * 预览现在是我们自己画的（等比居中，上下可能有黑边），所以映射分三步：
     * 视图坐标 → 画面矩形内的归一化坐标 → **反旋转**回传感器朝向 → 当前裁剪区域内的传感器坐标。
     * 点在黑边上直接忽略（那里没有画面，点它没有意义）。
     */
    private fun focusAt(viewX: Float, viewY: Float) {
        val builder = requestBuilder ?: return
        val session = captureSession ?: return
        val array = activeArray ?: return
        val rect = previewView.imageRect
        if (rect.width() <= 0f || rect.height() <= 0f || !rect.contains(viewX, viewY)) return
        val crop = currentCropRegion() ?: array

        val displayPoint = BarcodePoint(
            x = ((viewX - rect.left) / rect.width()).coerceIn(0f, 1f),
            y = ((viewY - rect.top) / rect.height()).coerceIn(0f, 1f),
        )
        // 预览是"传感器坐标顺时针转 rotation 度"的结果，所以用负角度转回去
        val sensorPoint = QuickMenuScanFrame.rotatePoint(displayPoint, -previewRotationDegrees())

        val sensorX = crop.left + sensorPoint.x * crop.width()
        val sensorY = crop.top + sensorPoint.y * crop.height()
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
            if (delivered) return
            frameCount++
            // 前置等待从"预览首帧到达"算起，不是从 onCreate：否则会被相机启动时间吃掉。
            if (previewStartedAtMs == 0L) previewStartedAtMs = SystemClock.elapsedRealtime()
            val now = SystemClock.elapsedRealtime()
            val rotation = previewRotationDegrees()
            val rawY = copyRawLuminance(image) ?: return
            val rowStride = image.planes.firstOrNull()?.rowStride ?: image.width
            val step = triggerSampleStep(image.width, image.height, rotation)

            // ① 预览：**每一帧**都刷新。它是瞄准用的，必须比解码流畅，所以单独一套节流 + 复用缓冲。
            if (now - lastPreviewAtMs >= PREVIEW_INTERVAL_MS) {
                lastPreviewAtMs = now
                val frame = QuickMenuScanFrame.displayLuminance(
                    luminance = rawY,
                    width = image.width,
                    height = image.height,
                    rowStride = rowStride,
                    rotationDegrees = rotation,
                    displayAspect = displayAspect,
                    sampleStep = step,
                    into = previewLuminance,
                )
                previewLuminance = frame.pixels
                updatePreview(frame)
            }

            // ② 解码：单帧互斥 + 节流（真机日志证明"逐帧解码"是卡顿的唯一来源）
            if (decoding) return
            if (now - lastDecodeAtMs < DECODE_INTERVAL_MS) return
            lastDecodeAtMs = now
            decoding = true
            val afterExtract = SystemClock.elapsedRealtime()

            // 检测只看"预览实际能看到的那块"，并降到 TRIGGER_WIDTH 量级：
            // 逐帧成本 ≈ 像素数 × 解码次数，两个因子都要压住。
            val display = QuickMenuScanFrame.displayLuminance(
                luminance = rawY,
                width = image.width,
                height = image.height,
                rowStride = rowStride,
                rotationDegrees = rotation,
                displayAspect = displayAspect,
                sampleStep = step,
                into = detectionLuminance,
            )
            detectionLuminance = display.pixels
            if (frameCount == 1 || frameCount % 60 == 0) {
                Log.i(
                    TAG,
                    "帧：流=${image.width}x${image.height} rowStride=$rowStride " +
                        "检测图=${display.width}x${display.height} 抽样=$step 预览比例=$displayAspect",
                )
            }

            // 稳定度：粗网格亮度签名与上一帧比（几乎零成本，抖动在 8×8 上就看得出来）
            val signature = QuickMenuScanFrame.gridSignature(display.pixels, display.width, display.height)
            val motion = previousSignature?.let { QuickMenuScanFrame.meanAbsDiff(it, signature) }
                ?: Float.MAX_VALUE
            previousSignature = signature
            val afterSignature = SystemClock.elapsedRealtime()

            // 触发检查用**最便宜**的组合（单码读取器 + 只主二值化 + 只标准亮度）：真机实测
            // "多码读取器 + 两种二值化"在 1280×960 上要 130~157ms/帧，足以把预览拖卡。
            // 权威的多码识别交给面板在定格图上做（见类注释）。
            val fullPass = frameCount % ALL_FORMAT_EVERY_N_FRAMES == 0
            val results = ZxingBarcodeScanner.scanLuminanceForTrigger(
                luminance = display.pixels,
                width = display.width,
                height = display.height,
                qrOnly = !fullPass,
            )
            reportFrameCost(
                image = image,
                extractMs = afterExtract - now,
                signatureMs = afterSignature - afterExtract,
                decodeMs = SystemClock.elapsedRealtime() - afterSignature,
            )

            // **扫到就定格**（库的做法）：只要这一帧解出了东西、画面是稳的、已过前置等待，立刻抓。
            // 有意不做"同一个码连续 N 帧"：那会让触发取决于"哪个码先被认出"，一屏多个码时结果就会飘
            // （真机反馈"扫出来的结果一直在变"）。稳定判据只保留"这一帧和上一帧差不多"，
            // 因为它保护的正是**定格图清不清楚**——糊帧不仅认不出，还会被误当成"识别不准"。
            val prerollDone = SystemClock.elapsedRealtime() - previewStartedAtMs >= PREROLL_MS
            val steady = motion <= MOTION_THRESHOLD
            if (!prerollDone || !steady || results.isEmpty()) {
                // 每 30 帧留一条：现场调阈值时能直接看出卡在哪个条件上
                if (frameCount % 30 == 0) {
                    Log.i(TAG, "等待触发：preroll=$prerollDone motion=${motion.toInt()} n=${results.size}")
                }
                return
            }

            // 定格"预览能看到的那块"：检测图已经是显示朝向 + 裁剪过的，所以**点位不需要再旋转**，
            // 它与交回面板的那张图用的是同一套归一化坐标（这正是"标记和图永远对得上"的前提）。
            val frozen = freezeFrame(image, rotation)
            Log.i(
                TAG,
                "锁定：n=${results.size} motion=${motion.toInt()} 流=${image.width}x${image.height} " +
                    "定格图=${frozen?.width}x${frozen?.height}",
            )
            deliver(results, frozen)
        } catch (error: Exception) {
            Log.w(TAG, "frame decode failed", error)
        } finally {
            decoding = false
            runCatching { image.close() }
        }
    }

    /**
     * 把 Y 平面**原样**（含硬件对齐的行距）拷进复用缓冲。
     *
     * 为什么不像以前那样压成紧凑数组：下一步要按**旋转 + 裁剪**逐像素取值，保留原始行距正好能直接
     * 索引，省掉一次全图重排（而那是一次每帧都要跑的开销）。
     *
     * ⚠️ 返回值会被下一帧覆写（见 [luminanceBuffer]），调用方不得留存。数据不足时返回 null，而不是
     * 拿上一帧的残留像素凑数——宁可少处理一帧，也不要把旧画面当新画面。
     */
    private fun copyRawLuminance(image: Image): ByteArray? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null
        val size = plane.rowStride * height
        if (size <= 0) return null
        val buffer = plane.buffer
        if (buffer.remaining() < size) return null
        val data = luminanceBufferFor(size)
        if (data.size < size) return null
        buffer.get(data, 0, size)
        return data
    }

    /**
     * 需要顺时针旋转多少度，定格画面才是"人看到的方向"。
     *
     * 后置摄像头用 Camera2 的标准算法：`(传感器方向 - 屏幕方向 + 360) % 360`。
     * 拿不到屏幕方向时按 0 处理。
     */
    /** 屏幕旋转（度）。 */
    private fun displayRotationDegrees(): Int = when (display?.rotation ?: Surface.ROTATION_0) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun previewRotationDegrees(): Int {
        // 用 Activity 的 display 而不是 previewView.display：prepareCamera() 在 buildContentView()
        // **之前**调用（预览的信箱比例依赖它），那时 previewView 还没创建。
        val displayRotation = displayRotationDegrees()
        return ((sensorOrientation - displayRotation) % 360 + 360) % 360
    }

    /**
     * 定格"预览实际能看到的那块"画面：**先按显示方向转正，再按屏幕比例中心裁剪**。
     *
     * 顺序很重要：裁剪是在显示朝向下算的（`displayCrop` 的输入就是显示尺寸），所以必须先转正。
     * 这样得到的图，与检测读的那块画面、以及面板要标记的那张图，是**同一块**（坐标无需再换算）。
     */
    private fun freezeFrame(image: Image, rotationDegrees: Int): Bitmap? {
        val source = yuvToBitmap(image) ?: return null
        val rotated = if (rotationDegrees == 0) {
            source
        } else {
            runCatching {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            }.getOrElse {
                Log.w(TAG, "freezeFrame rotate failed", it)
                return source
            }.also { if (it !== source) source.recycle() }
        }

        val crop = QuickMenuScanFrame.displayCrop(rotated.width, rotated.height, displayAspect)
        return runCatching {
            val x = crop.x.coerceIn(0, (rotated.width - 1).coerceAtLeast(0))
            val y = crop.y.coerceIn(0, (rotated.height - 1).coerceAtLeast(0))
            val width = crop.width.coerceIn(1, rotated.width - x)
            val height = crop.height.coerceIn(1, rotated.height - y)
            if (width == rotated.width && height == rotated.height) {
                rotated
            } else {
                Bitmap.createBitmap(rotated, x, y, width, height).also { if (it !== rotated) rotated.recycle() }
            }
        }.getOrElse {
            Log.w(TAG, "freezeFrame crop failed", it)
            rotated
        }
    }

    /**
     * `YUV_420_888` → ARGB 位图。
     *
     * 逐像素走 Y/U/V 三个平面，**各自按自己的 `rowStride` / `pixelStride` 取值**——硬件对齐会让
     * 行步长大于宽度，这是 Android 上 YUV 处理最经典的坑（我取亮度平面时已经在 `extractLuminance`
     * 里踩过一次）；色度是半分辨率，所以列与行都要除以 2。
     */
    private fun yuvToBitmap(image: Image): Bitmap? {
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null
        val planes = image.planes
        if (planes.size < 3) return null
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        return runCatching {
            val pixels = IntArray(width * height)
            for (row in 0 until height) {
                val yRowBase = row * yPlane.rowStride
                val uvRow = row / 2
                val uRowBase = uvRow * uPlane.rowStride
                val vRowBase = uvRow * vPlane.rowStride
                for (column in 0 until width) {
                    val y = yPlane.buffer.get(yRowBase + column * yPlane.pixelStride).toInt() and 0xFF
                    val uvColumn = column / 2
                    val u = uPlane.buffer.get(uRowBase + uvColumn * uPlane.pixelStride).toInt() and 0xFF
                    val v = vPlane.buffer.get(vRowBase + uvColumn * vPlane.pixelStride).toInt() and 0xFF
                    pixels[row * width + column] = QuickMenuScanFrame.yuvToArgb(y, u, v)
                }
            }
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }.getOrElse {
            Log.w(TAG, "yuvToBitmap failed", it)
            null
        }
    }

    /**
     * 取一块可复用的亮度缓冲；尺寸变了才重新分配。
     *
     * 返回的数组**会被下一帧覆写**，调用方不得留存（解码在单线程 worker 上、且有 `decoding`
     * 守卫，所以不存在跨帧并发使用）。
     */
    private fun luminanceBufferFor(size: Int): ByteArray {
        val existing = luminanceBuffer
        if (existing != null && existing.size == size) return existing
        return ByteArray(size).also { luminanceBuffer = it }
    }

    /**
     * 累计若干帧的三段耗时并打一条平均值。
     *
     * 存在的理由：卡顿这类问题**必须靠测量定位**。我前两轮都是"推断哪里慢"，结果一次把尺寸提到
     * 1920 造成持续卡顿、一次自己加了逐像素除法——有这条日志就能一眼看出是 extract（取亮度）、
     * signature（帧差）还是 decode（识别）在吃时间。
     */
    private fun reportFrameCost(image: Image, extractMs: Long, signatureMs: Long, decodeMs: Long) {
        timedFrames++
        extractMsSum += extractMs
        signatureMsSum += signatureMs
        decodeMsSum += decodeMs
        if (timedFrames < COST_REPORT_FRAMES) return
        Log.i(
            TAG,
            "逐帧耗时(平均 ${timedFrames} 帧，${image.width}x${image.height})：" +
                "extract=${extractMsSum / timedFrames}ms " +
                "signature=${signatureMsSum / timedFrames}ms " +
                "decode=${decodeMsSum / timedFrames}ms（解码间隔下限 ${DECODE_INTERVAL_MS}ms）",
        )
        timedFrames = 0
        extractMsSum = 0
        signatureMsSum = 0
        decodeMsSum = 0
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

    /**
     * 交回结果并关掉相机页。
     *
     * 诊断日志是**故意**留的：这类"认出了几个 / 有没有定位点 / 有没有定格图"的事实，
     * 之前只能靠读代码推断（用户反馈时无法证实），现在一次 logcat 就能定论。
     */
    private fun deliver(results: List<BarcodeScanResult>, image: Bitmap?) {
        if (delivered) return
        delivered = true
        Log.i(
            TAG,
            "deliver: n=${results.size} withPoints=${results.count { it.points.isNotEmpty() }} image=${image != null}",
        )
        mainHandler.post {
            onDecoded?.invoke(results, image)
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
     * 相机相关的准备：从 `CameraCharacteristics` 里取方向、闪光灯、缩放范围，并**定下流尺寸**。
     *
     * 必须在 `buildContentView` 之前调用——预览要按相机流的显示比例做信箱式布局（见 [AspectSurfaceView]），
     * 比例得先知道；否则会先按满屏建立 Surface，再改尺寸就变成销毁重建（画面闪一下）。
     */
    private fun prepareCamera() {
        val manager = getSystemService(CameraManager::class.java) ?: return
        val characteristics = pickBackCamera(manager)?.second ?: return
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        torchAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        maxZoomRatio = (characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)
            .coerceAtLeast(1f)
        fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        val configurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val yuvSizes = configurationMap.getOutputSizes(ImageFormat.YUV_420_888)
        val size = chooseCaptureSize(sizes = yuvSizes, screenAspect = screenAspect())
        captureSize = size
        displayAspect = displayAspectOf(size)
        // 诊断（真机反馈"预览比例一直不对"）：把设备能力与我算出来的比例都打出来，
        // 这样"是旋转错、拉伸错还是取景错"能一次定论，不用再来回猜 HAL 的行为。
        Log.i(
            TAG,
            "相机参数：传感器方向=${sensorOrientation}° 显示旋转=${displayRotationDegrees()}° " +
                "算出旋转量=${previewRotationDegrees()}° 流=${size.width}x${size.height} " +
                "流显示比例=$displayAspect 屏幕比例=${screenAspect()}",
        )
        Log.i(
            TAG,
            "YUV 可选尺寸（${yuvSizes?.size ?: 0} 种）：" +
                (yuvSizes?.take(12)?.joinToString { "${it.width}x${it.height}" } ?: "无"),
        )
    }

    /**
     * 取相机流尺寸。评分顺序：**显示比例先接近屏幕**（黑边越小、越接近满屏），
     * 再在比例足够接近的那些里取面积最大的（定格图的真实像素越多越好）。
     */
    private fun chooseCaptureSize(sizes: Array<Size>?, screenAspect: Float): Size {
        if (sizes.isNullOrEmpty()) return Size(FALLBACK_CAPTURE_WIDTH, FALLBACK_CAPTURE_HEIGHT)
        val candidates = sizes.filter { maxOf(it.width, it.height) <= MAX_CAPTURE_LONG_SIDE }
            .ifEmpty { sizes.toList() }
        val closest = candidates.minByOrNull { abs(displayAspectOf(it) - screenAspect) }
            ?: return Size(FALLBACK_CAPTURE_WIDTH, FALLBACK_CAPTURE_HEIGHT)
        val bestGap = abs(displayAspectOf(closest) - screenAspect)
        return candidates
            .filter { abs(displayAspectOf(it) - screenAspect) <= bestGap + ASPECT_TOLERANCE }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: closest
    }

    /**
     * 相机流在**显示朝向**下的宽/高。
     *
     * 这个值同时决定两件事，所以只在这里算一次：预览的"信箱"比例，以及检测/定格要读的那块区域
     * （两者比例一致才不会变形，也才保证"看到的就是被扫描的"）。
     */
    private fun displayAspectOf(size: Size): Float {
        val display = QuickMenuScanFrame.displaySize(size.width, size.height, previewRotationDegrees())
        return if (display.height > 0) display.width.toFloat() / display.height else 0f
    }

    /** 屏幕宽/高（竖屏小于 1）；拿不到时返回 0（等于不做信箱）。 */
    private fun screenAspect(): Float {
        val metrics = resources.displayMetrics
        return if (metrics.heightPixels > 0 && metrics.widthPixels > 0) {
            metrics.widthPixels.toFloat() / metrics.heightPixels
        } else {
            0f
        }
    }

    /**
     * 检测用的抽样步长：把"预览那块画面"降到约 [TRIGGER_WIDTH] 宽。
     *
     * 逐帧成本 ≈ 像素数 × 解码次数。像素数由流尺寸决定（我们特意取大了），所以靠抽样把它降回来；
     * 而定格仍按全分辨率做——那是一次性的，只在触发那一下。
     */
    private fun triggerSampleStep(width: Int, height: Int, rotationDegrees: Int): Int {
        val display = QuickMenuScanFrame.displaySize(width, height, rotationDegrees)
        val crop = QuickMenuScanFrame.displayCrop(display.width, display.height, displayAspect)
        return ceil(crop.width.toDouble() / TRIGGER_WIDTH).toInt().coerceAtLeast(1)
    }

    /**
     * **我们自己画的**相机预览（灰度）。
     *
     * 为什么不用相机自带的预览流：真机日志显示这台机器给 YUV 的尺寸是 1920×1012（非标准），而我们能建出的
     * 预览 Surface 尺寸相机并不支持 —— HAL 只能换模式再缩放，于是**方向对、比例错**（用户反馈的原话）。
     * 试过"让视图比例等于流比例"也没用，因为 SurfaceView 只能把缓冲拉伸到视图矩形。
     *
     * 改成自己画之后：相机只出一路 YUV 流，预览由**同一帧**转成灰度图绘制，比例、旋转、取景全在我们手里，
     * 而且"看到的就是被识别的那一帧"**由构造保证**（不再依赖任何关于 HAL 裁剪/缩放行为的假设）。
     * 代价是灰度、且分辨率等于检测图（瞄准码完全够用）。
     */
    private class PreviewImageView(context: Context) : View(context) {

        private var frame: Bitmap? = null

        /** 画面在视图里实际占的矩形（保持比例居中）。点击对焦要用它把触碰坐标映射回去。 */
        val imageRect = RectF()

        fun setFrame(bitmap: Bitmap) {
            frame = bitmap
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val bitmap = frame ?: return
            if (bitmap.width <= 0 || bitmap.height <= 0 || width <= 0 || height <= 0) return
            // 等比缩放到"装得下"为止，居中留黑边：**不做非等比拉伸**
            val scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            val drawWidth = bitmap.width * scale
            val drawHeight = bitmap.height * scale
            val left = (width - drawWidth) / 2f
            val top = (height - drawHeight) / 2f
            imageRect.set(left, top, left + drawWidth, top + drawHeight)
            canvas.drawBitmap(bitmap, null, imageRect, null)
        }
    }

    /**
     * 用**我们自己**从相机帧算出的灰度图刷新预览。
     *
     * 双缓冲：写入槽与绘制槽交替，避免主线程 `onDraw` 画到一半被下一帧覆写（会看到横条撕裂）。
     */
    private fun updatePreview(display: QuickMenuScanFrame.DisplayLuminance) {
        val width = display.width
        val height = display.height
        if (width <= 0 || height <= 0) return
        val size = width * height
        val slot = previewSlot
        val pixels = previewPixels[slot]?.takeIf { it.size == size } ?: IntArray(size).also {
            previewPixels[slot] = it
        }
        val source = display.pixels
        for (index in 0 until size) {
            val value = source[index].toInt() and 0xFF
            pixels[index] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
        }
        val bitmap = previewBitmaps[slot]?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { previewBitmaps[slot] = it }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        previewSlot = 1 - slot
        previewView.post { previewView.setFrame(bitmap) }
    }

    /**
     * 挑一个尽量接近 30fps 的固定帧率范围。
     *
     * 会话里没有预览流时，部分 HAL 会自行把出帧率压得很低（那样检测与预览都会变慢），显式指定更保险。
     */
    private fun preferredFpsRange(): Range<Int>? {
        val ranges = fpsRanges ?: return null
        return ranges.filter { it.upper >= TARGET_FPS && it.lower >= TARGET_FPS }
            .minByOrNull { it.upper - it.lower }
            ?: ranges.filter { it.upper >= TARGET_FPS }.maxByOrNull { it.lower }
            ?: ranges.maxByOrNull { it.upper }
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

        /** 预览刷新间隔（约 20fps）：预览只需"看得清、跟得上手"，不必和解码同频。 */
        private const val PREVIEW_INTERVAL_MS = 50L

        /** 期望的相机出帧率（用来挑 AE 目标帧率范围）。 */
        private const val TARGET_FPS = 30

        /**
         * 相机流的长边上限，以及检测的目标宽度。
         *
         * **流尺寸决定定格图的真实像素**——离线实测已确认：合成放大读不出的码，真实像素够就能读出，
         * 所以取支持范围内尽量大的（上限 1920 用来压住逐帧拷贝、内存与 HAL 出帧速度）。
         * 检测不需要这么多像素：它在 [triggerSampleStep] 抽样后的小图上跑。
         */
        private const val MAX_CAPTURE_LONG_SIDE = 1920
        private const val TRIGGER_WIDTH = 960

        /** 挑流尺寸时，"显示比例足够接近屏幕"的容差：在这个范围内再比面积，避免为 1% 的比例差牺牲分辨率。 */
        private const val ASPECT_TOLERANCE = 0.02f
        private const val FALLBACK_CAPTURE_WIDTH = 1280
        private const val FALLBACK_CAPTURE_HEIGHT = 960
        private const val MAX_QUEUED_FRAMES = 2
        private const val ALL_FORMAT_EVERY_N_FRAMES = 5

        /**
         * 两次解码之间的最小间隔（约 12fps）。
         *
         * 预览卡顿唯一的**结构性**解法是降低"每帧干活"的频率：分析流一旦让 ImageReader 排队，
         * 相机 HAL 就开始丢帧，那时再抠每帧内部的几毫秒都晚了。12fps 的检测对"瞄准后扫描"够用，
         * 其余 CPU 全留给预览。
         */
        private const val DECODE_INTERVAL_MS = 80L

        /** 每处理这么多帧打一次耗时平均（见 [reportFrameCost]）。 */
        private const val COST_REPORT_FRAMES = 30

        /**
         * 前置等待：预览首帧到达后先等这么久再接受触发。
         *
         * 它**不是**"等用户对准"（用户要多久不确定），而是**等 AE/AF 收敛**——刚打开那几帧往往
         * 又暗又糊，容易读错。从"预览首帧"算起，否则会被相机启动时间吃掉。
         */
        private const val PREROLL_MS = 250L

        /**
         * 帧差稳定阈值（8×8 粗网格的平均绝对差，0..255）。
         *
         * 手持静止时通常是个位数，晃动时会到十几以上。太大则"晃着也触发"，太小则很难触发。
         * 现场调参看 `QuickMenuScan` 日志里的 `motion=` 值即可。
         */
        private const val MOTION_THRESHOLD = 8f

        private const val FOCUS_REGION_FRACTION = 0.08f
        private const val RETICLE_SIDE_FRACTION = 0.62f
        private const val RETICLE_ARM_FRACTION = 0.22f

        /** 可循环的放大档位（会按设备支持的最大数码变焦夹取）。 */
        private val zoomSteps = floatArrayOf(1f, 2f, 3f)

        /**
         * 结果回调（扫到的**所有**原文；用户取消时为空列表）。
         *
         * 从"只交回第一个"改成交回列表：同一帧里可能有多个码，交由界面决定是直接回填还是列出来让用户点选。
         *
         * 用静态回调而非 ActivityResult API，原因是调用方（快捷菜单面板）跑在 overlay 的
         * ComposeView 里、拿不到 `ActivityResultRegistry`；这与本仓库既有的
         * `SearchPanelImagePickerActivity` 做法一致。区别是这里在投递后**立即清空**引用，
         * 并且 `onDestroy` 兜底交付空列表，不会留下跨 Activity 的悬挂回调。
         */
        private var onDecoded: ((List<BarcodeScanResult>, Bitmap?) -> Unit)? = null

        fun launch(context: Context, onResult: (List<BarcodeScanResult>, Bitmap?) -> Unit) {
            onDecoded = onResult
            val intent = Intent(context, QuickMenuScanActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
