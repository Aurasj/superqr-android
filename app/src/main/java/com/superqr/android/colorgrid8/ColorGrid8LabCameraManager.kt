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
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8YuvFrame
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    private val _geometryMs = MutableStateFlow(0.0)
    val geometryMs: StateFlow<Double> = _geometryMs.asStateFlow()
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
    private val processor = ColorGrid8FrameProcessor(redetectEveryFrames = 30)
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null

    private var yDestination = ByteArray(0)
    private var uDestination = ByteArray(0)
    private var vDestination = ByteArray(0)
    private var yRowScratch = ByteArray(0)
    private var uRowScratch = ByteArray(0)
    private var vRowScratch = ByteArray(0)

    fun setProfile(value: ColorGrid8Profile) {
        if (_status.value == Status.RUNNING || _status.value == Status.STARTING) return
        profile = value
        processor.resetTracking()
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
        processor.resetTracking()
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
        processor.resetTracking()
        _cameraFps.value = 0.0
        _pipelineMs.value = 0.0
        _geometryMs.value = 0.0
        _warpMs.value = 0.0
        _acquisitionMode.value = "SEARCHING"
        _stage.value = "CAMERA"
        _failure.value = "camera stopped"
        _finderCandidates.value = 0
        _geometryLocked.value = false
        _headerStatus.value = "NOT_REACHED"
        _detectedProfile.value = ""
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
        _cameraFps.value = fps
        val started = System.nanoTime()
        try {
            val packed = pack(image)
            if (packed == null) {
                val totalMs = (System.nanoTime() - started) / 1_000_000.0
                _pipelineMs.value = totalMs
                _stage.value = "CAMERA"
                _failure.value = "YUV_420_888 plane packing failed"
                if (generation.get() == configuredGeneration) {
                    onSample?.invoke(Sample(null, fps, totalMs, totalMs))
                }
                return
            }
            val packMs = (System.nanoTime() - started) / 1_000_000.0
            _resolution.value = "${packed.width}x${packed.height} Y • ${packed.chromaWidth}x${packed.chromaHeight} UV"
            val currentProfile = profile
            val result = processor.process(currentProfile, packed)
            val totalMs = (System.nanoTime() - started) / 1_000_000.0
            _pipelineMs.value = totalMs
            _geometryMs.value = result.geometryMs
            _warpMs.value = result.warpAndMeanMs
            _acquisitionMode.value = result.acquisitionMode
            _stage.value = result.stage.name
            _failure.value = result.failure ?: ""
            _finderCandidates.value = result.finderCandidates
            _geometryLocked.value = result.geometryLocked
            _headerStatus.value = result.headerStatus
            _expectedProfile.value = result.expectedProfile
            _detectedProfile.value = result.detectedProfile ?: ""
            if (generation.get() == configuredGeneration) {
                onSample?.invoke(Sample(result, fps, packMs, totalMs))
            }
        } catch (failure: Throwable) {
            _pipelineMs.value = (System.nanoTime() - started) / 1_000_000.0
            _stage.value = "CAMERA"
            _failure.value = "${failure::class.java.simpleName}: ${failure.message ?: "camera analysis failed"}"
        } finally {
            image.close()
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

        // YUV_420_888 chroma is half-resolution in both dimensions. Match the
        // platform/CTS crop convention: chroma crop origin and extent are the
        // luma crop divided by two. Camera outputs used here are even-sized.
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
