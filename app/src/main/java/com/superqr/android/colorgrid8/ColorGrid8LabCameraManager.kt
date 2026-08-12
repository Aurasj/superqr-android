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
import com.superqr.android.camera.LumaPlanePacker
import com.superqr.android.vision.v7_capacity_lab.colorgrid8.ColorGrid8FrameProcessor
import com.superqr.android.vision.v7_capacity_lab.colorgrid8.ColorGrid8ProcessResult
import com.superqr.android.vision.v7_capacity_lab.colorgrid8.ColorGrid8Profile
import com.superqr.android.vision.v7_capacity_lab.colorgrid8.ColorGrid8YuvFrame
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

    data class Sample(
        val result: ColorGrid8ProcessResult,
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

    val previewView = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FIT_CENTER
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }

    var onSample: ((Sample) -> Unit)? = null

    @Volatile
    private var profile = ColorGrid8Profile(168, 144, 30)
    private val generation = AtomicInteger(0)
    private val deliveredRate = AnalysisRateAccumulator(64)
    private val processor = ColorGrid8FrameProcessor(redetectEveryFrames = 10)
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
            val chosen = ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
                ?: ranges.filter { it.lower <= 30 && it.upper >= 30 }.minByOrNull { it.upper - it.lower }
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
            val packed = pack(image) ?: return
            val packMs = (System.nanoTime() - started) / 1_000_000.0
            _resolution.value = "${packed.width}x${packed.height} Y • ${packed.chromaWidth}x${packed.chromaHeight} UV"
            val currentProfile = profile
            val result = processor.process(currentProfile, packed)
            val totalMs = (System.nanoTime() - started) / 1_000_000.0
            _pipelineMs.value = totalMs
            if (result != null && generation.get() == configuredGeneration) {
                _geometryMs.value = result.geometryMs
                _warpMs.value = result.warpAndMeanMs
                _acquisitionMode.value = result.acquisitionMode
                onSample?.invoke(Sample(result, fps, packMs, totalMs))
            }
        } catch (_: Throwable) {
            _pipelineMs.value = (System.nanoTime() - started) / 1_000_000.0
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
        val normalizedWidth = LumaPlanePacker.normalizedWidth(rawWidth, rawHeight, rotation)
        val normalizedHeight = LumaPlanePacker.normalizedHeight(rawWidth, rawHeight, rotation)
        val yPlane = image.planes[0]
        ensureYBuffers(
            destination = normalizedWidth * normalizedHeight,
            row = rawWidth * yPlane.pixelStride,
        )
        if (!LumaPlanePacker.pack(
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

        val chromaRawWidth = (rawWidth + 1) / 2
        val chromaRawHeight = (rawHeight + 1) / 2
        val chromaWidth = LumaPlanePacker.normalizedWidth(chromaRawWidth, chromaRawHeight, rotation)
        val chromaHeight = LumaPlanePacker.normalizedHeight(chromaRawWidth, chromaRawHeight, rotation)
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        ensureChromaBuffers(
            destination = chromaWidth * chromaHeight,
            uRow = chromaRawWidth * uPlane.pixelStride,
            vRow = chromaRawWidth * vPlane.pixelStride,
        )
        val chromaCropLeft = crop.left / 2
        val chromaCropTop = crop.top / 2
        if (!LumaPlanePacker.pack(
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
        if (!LumaPlanePacker.pack(
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
