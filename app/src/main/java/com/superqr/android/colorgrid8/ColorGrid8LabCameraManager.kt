package com.superqr.android.colorgrid8

import android.content.Context
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.LifecycleOwner
import com.superqr.android.camera.AnalysisRateAccumulator
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8FrameProcessor
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8ProcessResult
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8Profile
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8Stage
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8YuvFrame
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min

/** Dedicated ColorGrid8 LAB camera path. It never enters production transfer. */
class ColorGrid8LabCameraManager(
    private val context: Context,
    private val analysisExecutor: ExecutorService,
) {
    enum class Status { IDLE, STARTING, RUNNING, STOPPING, ERROR }

    /** One camera frame; result is null only when YUV plane packing itself failed. */
    data class Sample(
        val result: ColorGrid8ProcessResult?,
        val cameraFps: Double,
        val packMs: Double,
        val totalWithPackMs: Double,
    )

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _cameraFps = MutableStateFlow(0.0)
    val cameraFps: StateFlow<Double> = _cameraFps.asStateFlow()
    private val _pipelineMs = MutableStateFlow(0.0)
    val pipelineMs: StateFlow<Double> = _pipelineMs.asStateFlow()
    private val _finderMs = MutableStateFlow(0.0)
    val finderMs: StateFlow<Double> = _finderMs.asStateFlow()
    private val _geometryMs = MutableStateFlow(0.0)
    val geometryMs: StateFlow<Double> = _geometryMs.asStateFlow()
    private val _orientationMs = MutableStateFlow(0.0)
    val orientationMs: StateFlow<Double> = _orientationMs.asStateFlow()
    private val _headerMs = MutableStateFlow(0.0)
    val headerMs: StateFlow<Double> = _headerMs.asStateFlow()
    private val _pilotMs = MutableStateFlow(0.0)
    val pilotMs: StateFlow<Double> = _pilotMs.asStateFlow()
    private val _payloadMs = MutableStateFlow(0.0)
    val payloadMs: StateFlow<Double> = _payloadMs.asStateFlow()
    private val _transportMs = MutableStateFlow(0.0)
    val transportMs: StateFlow<Double> = _transportMs.asStateFlow()
    private val _warpMs = MutableStateFlow(0.0)
    val warpMs: StateFlow<Double> = _warpMs.asStateFlow()
    private val _resolution = MutableStateFlow("")
    val resolution: StateFlow<String> = _resolution.asStateFlow()
    private val _acquisitionMode = MutableStateFlow("SEARCHING")
    val acquisitionMode: StateFlow<String> = _acquisitionMode.asStateFlow()
    private val _stage = MutableStateFlow("CAMERA")
    val stage: StateFlow<String> = _stage.asStateFlow()
    private val _failure = MutableStateFlow("waiting for camera frame")
    val failure: StateFlow<String> = _failure.asStateFlow()
    private val _finderCandidates = MutableStateFlow(0)
    val finderCandidates: StateFlow<Int> = _finderCandidates.asStateFlow()
    private val _geometryLocked = MutableStateFlow(false)
    val geometryLocked: StateFlow<Boolean> = _geometryLocked.asStateFlow()
    private val _headerStatus = MutableStateFlow("NOT_REACHED")
    val headerStatus: StateFlow<String> = _headerStatus.asStateFlow()
    private val _expectedProfile = MutableStateFlow("")
    val expectedProfile: StateFlow<String> = _expectedProfile.asStateFlow()
    private val _detectedProfile = MutableStateFlow("")
    val detectedProfile: StateFlow<String> = _detectedProfile.asStateFlow()

    private val _trackedFrames = MutableStateFlow(0L)
    val trackedFrames: StateFlow<Long> = _trackedFrames.asStateFlow()
    private val _roiFrames = MutableStateFlow(0L)
    val roiFrames: StateFlow<Long> = _roiFrames.asStateFlow()
    private val _fullRedetections = MutableStateFlow(0L)
    val fullRedetections: StateFlow<Long> = _fullRedetections.asStateFlow()
    private val _trackingFailures = MutableStateFlow(0L)
    val trackingFailures: StateFlow<Long> = _trackingFailures.asStateFlow()
    private val _decodedTransportFps = MutableStateFlow(0.0)
    val decodedTransportFps: StateFlow<Double> = _decodedTransportFps.asStateFlow()

    val previewView = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FIT_CENTER
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }

    var onSample: ((Sample) -> Unit)? = null

    @Volatile
    private var profile = ColorGrid8Profile(
        336,
        288,
        60,
        version = com.superqr.android.vision.lab.colorgrid8.ColorGrid8Spec.TRANSFER_HEADER_VERSION,
    )
    private val generation = AtomicInteger(0)
    private val deliveredRate = AnalysisRateAccumulator(64)
    private val transportRate = AnalysisRateAccumulator(64)
    private val processor = ColorGrid8FrameProcessor(maxRedetectInterval = 60)
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null

    private var yDestination = ByteArray(0)
    private var uDestination = ByteArray(0)
    private var vDestination = ByteArray(0)
    private var yRowScratch = ByteArray(0)
    private var uRowScratch = ByteArray(0)
    private var vRowScratch = ByteArray(0)

    // Latency percentile buffers
    private val finderLatency = DoubleArray(120)
    private var finderCount = 0
    private val headerLatency = DoubleArray(120)
    private var headerCount = 0
    private val pilotLatency = DoubleArray(120)
    private var pilotCount = 0
    private val payloadLatency = DoubleArray(120)
    private var payloadCount = 0
    private val pipelineLatency = DoubleArray(120)
    private var pipelineCount = 0

    // Native latency percentile buffers
    private val nativeHeaderLatency = DoubleArray(120)
    private var nativeHeaderCount = 0
    private val nativePilotLatency = DoubleArray(120)
    private var nativePilotCount = 0
    private val nativePayloadLatency = DoubleArray(120)
    private var nativePayloadCount = 0
    private val nativeTotalLatency = DoubleArray(120)
    private var nativeTotalCount = 0
    private val jniOverheadLatency = DoubleArray(120)
    private var jniOverheadCount = 0

    private var totalFramesSeen = 0L
    private var headerSuccessCount = 0L

    // UI update throttling state (target ~4-5 Hz / 200 ms)
    private var lastUiPublishNs = 0L
    private var lastObservedStage = "CAMERA"
    private var lastObservedHeaderStatus = "NOT_REACHED"

    companion object {
        private const val UI_THROTTLE_INTERVAL_NS = 200_000_000L // 200 ms = 5 Hz
    }

    fun setProfile(value: ColorGrid8Profile) {
        if (_status.value == Status.RUNNING || _status.value == Status.STARTING) return
        profile = value
        processor.resetTracking()
        resetDiagnostics()
    }

    private fun resetDiagnostics() {
        finderCount = 0
        headerCount = 0
        pilotCount = 0
        payloadCount = 0
        pipelineCount = 0
        nativeHeaderCount = 0
        nativePilotCount = 0
        nativePayloadCount = 0
        nativeTotalCount = 0
        jniOverheadCount = 0
        totalFramesSeen = 0L
        headerSuccessCount = 0L
        transportRate.reset()
    }

    fun start(owner: LifecycleOwner) {
        if (_status.value == Status.STARTING || _status.value == Status.RUNNING) return
        _status.value = Status.STARTING
        _resolution.value = ""
        _acquisitionMode.value = "SEARCHING"
        _stage.value = "CAMERA"
        _failure.value = "waiting for camera frame"
        _finderCandidates.value = 0
        _geometryLocked.value = false
        _headerStatus.value = "NOT_REACHED"
        _expectedProfile.value = "${profile.profileId}@${profile.fps}fps ${profile.cols}x${profile.rows}"
        _detectedProfile.value = ""
        _trackedFrames.value = 0L
        _roiFrames.value = 0L
        _fullRedetections.value = 0L
        _trackingFailures.value = 0L
        _decodedTransportFps.value = 0.0
        processor.resetTracking()
        resetDiagnostics()
        val requestedGeneration = generation.incrementAndGet()
        ContextCompat.getMainExecutor(context).execute {
            try {
                val cameraProvider = ProcessCameraProvider.getInstance(context).get()
                if (generation.get() != requestedGeneration || _status.value != Status.STARTING) return@execute
                provider = cameraProvider
                previewView.doOnLayout {
                    if (generation.get() == requestedGeneration && _status.value == Status.STARTING) {
                        bind(cameraProvider, owner, requestedGeneration)
                    }
                }
            } catch (_: Throwable) {
                if (generation.get() == requestedGeneration) _status.value = Status.ERROR
            }
        }
    }

    fun stop() {
        if (_status.value == Status.IDLE) return
        _status.value = Status.STOPPING
        generation.incrementAndGet()
        analysis?.clearAnalyzer()
        analysis = null
        try { provider?.unbindAll() } catch (_: Throwable) {}
        deliveredRate.reset()
        transportRate.reset()
        processor.resetTracking()
        _cameraFps.value = 0.0
        _pipelineMs.value = 0.0
        _finderMs.value = 0.0
        _geometryMs.value = 0.0
        _orientationMs.value = 0.0
        _headerMs.value = 0.0
        _pilotMs.value = 0.0
        _payloadMs.value = 0.0
        _transportMs.value = 0.0
        _warpMs.value = 0.0
        _acquisitionMode.value = "SEARCHING"
        _stage.value = "CAMERA"
        _failure.value = "camera stopped"
        _finderCandidates.value = 0
        _geometryLocked.value = false
        _headerStatus.value = "NOT_REACHED"
        _detectedProfile.value = ""
        _decodedTransportFps.value = 0.0
        _status.value = Status.IDLE
    }

    fun destroy() {
        stop()
        onSample = null
        processor.close()
    }

    private fun bind(cameraProvider: ProcessCameraProvider, owner: LifecycleOwner, configuredGeneration: Int) {
        try {
            cameraProvider.unbindAll()
            val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
            val selector = ResolutionSelector.Builder()
                .setAllowedResolutionMode(ResolutionSelector.PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION)
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1280, 960),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                    )
                )
                .build()
            val preview = Preview.Builder()
                .setTargetRotation(rotation)
                .setResolutionSelector(selector)
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetRotation(rotation)
                .setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
            val viewPort = previewView.viewPort ?: run { _status.value = Status.ERROR; return }
            val useCases = listOf(preview, imageAnalysis)
            val info = cameraProvider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA)
            val baseConfig = SessionConfig.Builder(useCases).apply { setViewPort(viewPort) }.build()
            val ranges = try { info.getSupportedFrameRateRanges(baseConfig) } catch (_: Throwable) { emptySet() }
            val targetFps = profile.fps
            val chosen = ranges.firstOrNull { it.lower == targetFps && it.upper == targetFps }
                ?: ranges.filter { it.lower <= targetFps && it.upper >= targetFps }
                    .minByOrNull { it.upper - it.lower }
                ?: ranges.filter { it.upper <= targetFps }.maxByOrNull { it.upper }
            val config = SessionConfig.Builder(useCases).apply {
                setViewPort(viewPort)
                if (chosen != null) setFrameRateRange(chosen)
            }.build()
            imageAnalysis.setAnalyzer(analysisExecutor) { image -> process(image, configuredGeneration) }
            analysis = imageAnalysis
            cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, config)
            if (generation.get() != configuredGeneration) {
                imageAnalysis.clearAnalyzer(); cameraProvider.unbindAll(); return
            }
            _status.value = Status.RUNNING
        } catch (_: Throwable) {
            analysis?.clearAnalyzer(); analysis = null
            try { cameraProvider.unbindAll() } catch (_: Throwable) {}
            if (generation.get() == configuredGeneration) _status.value = Status.ERROR
        }
    }

    private fun process(image: ImageProxy, configuredGeneration: Int) {
        if (generation.get() != configuredGeneration) { image.close(); return }
        val timestamp = image.imageInfo.timestamp
        deliveredRate.recordCompletion(timestamp)
        val fps = deliveredRate.computeFps(timestamp)

        val started = System.nanoTime()
        try {
            val packed = pack(image)
            if (packed == null) {
                val totalMs = (System.nanoTime() - started) / 1_000_000.0
                publishThrottled(
                    nowNs = System.nanoTime(),
                    fps = fps,
                    totalMs = totalMs,
                    stage = "CAMERA",
                    failure = "YUV_420_888 plane packing failed",
                    isTransition = true,
                )
                if (generation.get() == configuredGeneration) {
                    onSample?.invoke(Sample(null, fps, totalMs, totalMs))
                }
                return
            }
            val packMs = (System.nanoTime() - started) / 1_000_000.0
            val resolutionStr = "${packed.width}x${packed.height} Y • ${packed.chromaWidth}x${packed.chromaHeight} UV"
            val currentProfile = profile
            val result = processor.process(currentProfile, packed)
            val totalMs = (System.nanoTime() - started) / 1_000_000.0

            totalFramesSeen++
            if (result.headerStatus == "VALID") {
                headerSuccessCount++
            }
            if (result.transportFrame != null) {
                transportRate.recordCompletion(timestamp)
            }
            val transportFps = transportRate.computeFps(timestamp)

            recordLatency(finderLatency, finderCount++, result.finderMs)
            recordLatency(headerLatency, headerCount++, result.headerMs)
            if (result.pilotMs > 0.0) recordLatency(pilotLatency, pilotCount++, result.pilotMs)
            if (result.payloadMs > 0.0) recordLatency(payloadLatency, payloadCount++, result.payloadMs)
            recordLatency(pipelineLatency, pipelineCount++, totalMs)

            if (result.nativeDecoderActive) {
                recordLatency(nativeHeaderLatency, nativeHeaderCount++, result.nativeHeaderMs)
                recordLatency(nativePilotLatency, nativePilotCount++, result.nativePilotMs)
                recordLatency(nativePayloadLatency, nativePayloadCount++, result.nativePayloadMs)
                recordLatency(nativeTotalLatency, nativeTotalCount++, result.nativeTotalMs)
                recordLatency(jniOverheadLatency, jniOverheadCount++, result.jniOverheadMs)
            }

            val currentStage = result.stage.name
            val currentHeader = result.headerStatus
            val isTransition = currentStage != lastObservedStage || currentHeader != lastObservedHeaderStatus
            lastObservedStage = currentStage
            lastObservedHeaderStatus = currentHeader

            if (totalFramesSeen % 60L == 0L) {
                android.util.Log.i("ColorGrid8Diag", buildDiagnosticReport())
            }

            publishThrottled(
                nowNs = System.nanoTime(),
                fps = fps,
                totalMs = totalMs,
                stage = currentStage,
                failure = result.failure ?: "",
                isTransition = isTransition,
                result = result,
                resolution = resolutionStr,
                transportFps = transportFps,
            )

            if (generation.get() == configuredGeneration) {
                onSample?.invoke(Sample(result, fps, packMs, totalMs))
            }
        } catch (failure: Throwable) {
            val totalMs = (System.nanoTime() - started) / 1_000_000.0
            publishThrottled(
                nowNs = System.nanoTime(),
                fps = fps,
                totalMs = totalMs,
                stage = "CAMERA",
                failure = "${failure::class.java.simpleName}: ${failure.message ?: "camera analysis failed"}",
                isTransition = true,
            )
        } finally {
            image.close()
        }
    }

    private fun publishThrottled(
        nowNs: Long,
        fps: Double,
        totalMs: Double,
        stage: String,
        failure: String,
        isTransition: Boolean,
        result: ColorGrid8ProcessResult? = null,
        resolution: String = "",
        transportFps: Double = 0.0,
    ) {
        if (!isTransition && (nowNs - lastUiPublishNs) < UI_THROTTLE_INTERVAL_NS) {
            return
        }
        lastUiPublishNs = nowNs
        _cameraFps.value = fps
        _pipelineMs.value = totalMs
        _stage.value = stage
        _failure.value = failure

        if (resolution.isNotEmpty()) {
            _resolution.value = resolution
        }
        if (result != null) {
            _finderMs.value = result.finderMs
            _geometryMs.value = result.geometryMs
            _orientationMs.value = result.orientationMs
            _headerMs.value = result.headerMs
            _pilotMs.value = result.pilotMs
            _payloadMs.value = result.payloadMs
            _transportMs.value = result.transportMs
            _warpMs.value = result.warpAndMeanMs
            _acquisitionMode.value = result.acquisitionMode
            _finderCandidates.value = result.finderCandidates
            _geometryLocked.value = result.geometryLocked
            _headerStatus.value = result.headerStatus
            _expectedProfile.value = result.expectedProfile
            _detectedProfile.value = result.detectedProfile ?: ""
            _trackedFrames.value = result.trackedFrames
            _roiFrames.value = result.roiFrames
            _fullRedetections.value = result.fullRedetections
            _trackingFailures.value = result.trackingFailures
            _decodedTransportFps.value = transportFps
        }
    }

    private fun recordLatency(buffer: DoubleArray, count: Int, value: Double) {
        buffer[count % buffer.size] = value
    }

    private fun calculatePercentile(buffer: DoubleArray, count: Int, percentileRank: Int): Double {
        if (count == 0) return 0.0
        val n = min(count, buffer.size)
        val copy = buffer.copyOf(n)
        copy.sort()
        val index = ((n - 1) * percentileRank) / 100
        return copy[index.coerceIn(0, n - 1)]
    }

    fun buildDiagnosticReport(measuredFileKibS: Double = 0.0): String {
        val fP50 = calculatePercentile(finderLatency, finderCount, 50)
        val fP95 = calculatePercentile(finderLatency, finderCount, 95)
        val hP50 = calculatePercentile(headerLatency, headerCount, 50)
        val hP95 = calculatePercentile(headerLatency, headerCount, 95)
        val pilP50 = calculatePercentile(pilotLatency, pilotCount, 50)
        val pilP95 = calculatePercentile(pilotLatency, pilotCount, 95)
        val payP50 = calculatePercentile(payloadLatency, payloadCount, 50)
        val payP95 = calculatePercentile(payloadLatency, payloadCount, 95)
        val totP50 = calculatePercentile(pipelineLatency, pipelineCount, 50)
        val totP95 = calculatePercentile(pipelineLatency, pipelineCount, 95)

        val natHP50 = calculatePercentile(nativeHeaderLatency, nativeHeaderCount, 50)
        val natHP95 = calculatePercentile(nativeHeaderLatency, nativeHeaderCount, 95)
        val natPilP50 = calculatePercentile(nativePilotLatency, nativePilotCount, 50)
        val natPilP95 = calculatePercentile(nativePilotLatency, nativePilotCount, 95)
        val natPayP50 = calculatePercentile(nativePayloadLatency, nativePayloadCount, 50)
        val natPayP95 = calculatePercentile(nativePayloadLatency, nativePayloadCount, 95)
        val natTotP50 = calculatePercentile(nativeTotalLatency, nativeTotalCount, 50)
        val natTotP95 = calculatePercentile(nativeTotalLatency, nativeTotalCount, 95)
        val jniP50 = calculatePercentile(jniOverheadLatency, jniOverheadCount, 50)
        val jniP95 = calculatePercentile(jniOverheadLatency, jniOverheadCount, 95)

        val headerSuccessRate = if (totalFramesSeen > 0) headerSuccessCount.toDouble() / totalFramesSeen * 100.0 else 0.0
        val isNative = com.superqr.android.vision.lab.colorgrid8.ColorGrid8NativeDecoder.isNativeLoaded

        return buildString {
            appendLine("COLORGRID8 ANDROID PHASE 3")
            appendLine("native decoder: ${if (isNative) "ON (ARM64 NEON)" else "OFF (Kotlin/OpenCV fallback)"}")
            appendLine("profile: ${_expectedProfile.value}")
            appendLine("camera resolution: ${_resolution.value}")
            appendLine("camera FPS: %.2f".format(_cameraFps.value))
            appendLine("decoded transport frames/sec: %.2f".format(_decodedTransportFps.value))
            appendLine("finder mode: ${_acquisitionMode.value}")
            appendLine("tracked frames: ${_trackedFrames.value}")
            appendLine("ROI refinements: ${_roiFrames.value}")
            appendLine("full redetections: ${_fullRedetections.value}")
            appendLine("orientation lock: ${if (_geometryLocked.value) "LOCKED" else "SEARCHING"}")
            appendLine("header success rate: %.1f%%".format(headerSuccessRate))
            appendLine("finder p50/p95: %.2f ms / %.2f ms".format(fP50, fP95))
            appendLine("header p50/p95: %.2f ms / %.2f ms".format(hP50, hP95))
            appendLine("pilot p50/p95: %.2f ms / %.2f ms".format(pilP50, pilP95))
            appendLine("payload p50/p95: %.2f ms / %.2f ms".format(payP50, payP95))
            appendLine("total pipeline p50/p95: %.2f ms / %.2f ms".format(totP50, totP95))
            if (isNative && nativeTotalCount > 0) {
                appendLine("native header p50/p95: %.2f ms / %.2f ms".format(natHP50, natHP95))
                appendLine("native pilot p50/p95: %.2f ms / %.2f ms".format(natPilP50, natPilP95))
                appendLine("native payload p50/p95: %.2f ms / %.2f ms".format(natPayP50, natPayP95))
                appendLine("native total p50/p95: %.2f ms / %.2f ms".format(natTotP50, natTotP95))
                appendLine("JNI overhead p50/p95: %.2f ms / %.2f ms".format(jniP50, jniP95))
            }
            appendLine("GC/allocation note: zero per-frame buffer allocations (reused Mats, ByteArrays, IntArrays)")
            appendLine("measured file KiB/s: %.2f".format(measuredFileKibS))
        }
    }

    private fun pack(image: ImageProxy): ColorGrid8YuvFrame? {
        if (image.planes.size < 3) return null
        val crop = image.cropRect
        val rawWidth = crop.width()
        val rawHeight = crop.height()
        val rotation = image.imageInfo.rotationDegrees
        val normalizedWidth = ColorGrid8PlanePacker.normalizedWidth(rawWidth, rawHeight, rotation)
        val normalizedHeight = ColorGrid8PlanePacker.normalizedHeight(rawWidth, rawHeight, rotation)
        val yPlane = image.planes[0]
        ensureYBuffers(
            destination = normalizedWidth * normalizedHeight,
            row = (rawWidth - 1) * yPlane.pixelStride + 1,
        )
        if (!ColorGrid8PlanePacker.pack(
                source = yPlane.buffer,
                cropLeft = crop.left,
                cropTop = crop.top,
                rawWidth = rawWidth,
                rawHeight = rawHeight,
                rowStride = yPlane.rowStride,
                pixelStride = yPlane.pixelStride,
                rotationDegrees = rotation,
                rowBuffer = yRowScratch,
                destination = yDestination,
            )) return null

        val chromaRawWidth = rawWidth / 2
        val chromaRawHeight = rawHeight / 2
        if (chromaRawWidth <= 0 || chromaRawHeight <= 0) return null
        val chromaWidth = ColorGrid8PlanePacker.normalizedWidth(chromaRawWidth, chromaRawHeight, rotation)
        val chromaHeight = ColorGrid8PlanePacker.normalizedHeight(chromaRawWidth, chromaRawHeight, rotation)
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        if (uPlane.rowStride != vPlane.rowStride || uPlane.pixelStride != vPlane.pixelStride) return null
        ensureChromaBuffers(
            destination = chromaWidth * chromaHeight,
            uRow = (chromaRawWidth - 1) * uPlane.pixelStride + 1,
            vRow = (chromaRawWidth - 1) * vPlane.pixelStride + 1,
        )
        val chromaCropLeft = crop.left / 2
        val chromaCropTop = crop.top / 2
        if (!ColorGrid8PlanePacker.pack(
                source = uPlane.buffer,
                cropLeft = chromaCropLeft,
                cropTop = chromaCropTop,
                rawWidth = chromaRawWidth,
                rawHeight = chromaRawHeight,
                rowStride = uPlane.rowStride,
                pixelStride = uPlane.pixelStride,
                rotationDegrees = rotation,
                rowBuffer = uRowScratch,
                destination = uDestination,
            )) return null
        if (!ColorGrid8PlanePacker.pack(
                source = vPlane.buffer,
                cropLeft = chromaCropLeft,
                cropTop = chromaCropTop,
                rawWidth = chromaRawWidth,
                rawHeight = chromaRawHeight,
                rowStride = vPlane.rowStride,
                pixelStride = vPlane.pixelStride,
                rotationDegrees = rotation,
                rowBuffer = vRowScratch,
                destination = vDestination,
            )) return null
        return ColorGrid8YuvFrame(
            width = normalizedWidth,
            height = normalizedHeight,
            y = yDestination,
            chromaWidth = chromaWidth,
            chromaHeight = chromaHeight,
            u = uDestination,
            v = vDestination,
        )
    }

    private fun ensureYBuffers(destination: Int, row: Int) {
        if (yDestination.size < destination) yDestination = ByteArray(destination)
        if (yRowScratch.size < row) yRowScratch = ByteArray(row)
    }

    private fun ensureChromaBuffers(destination: Int, uRow: Int, vRow: Int) {
        if (uDestination.size < destination) uDestination = ByteArray(destination)
        if (vDestination.size < destination) vDestination = ByteArray(destination)
        if (uRowScratch.size < uRow) uRowScratch = ByteArray(uRow)
        if (vRowScratch.size < vRow) vRowScratch = ByteArray(vRow)
    }
}
