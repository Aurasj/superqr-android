package com.superqr.android.camera

import android.content.Context
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CameraFrame(
    val lumaBytes: ByteArray,
    val width: Int,
    val height: Int,
    val sourceTransform: OutputTransform,
    val chromaReader: ImageProxyChromaSampler,
    val sensorTimestamp: Long,
    val arrivalNs: Long,
    val cameraFps: Double,
)

enum class CameraStatus { IDLE, STARTING, RUNNING, STOPPING, ERROR }

class CameraManager(
    private val context: Context,
    private val analysisExecutor: ExecutorService,
) {
    private val _cameraFps = MutableStateFlow(0.0)
    val cameraFps: StateFlow<Double> = _cameraFps.asStateFlow()

    private val _status = MutableStateFlow(CameraStatus.IDLE)
    val status: StateFlow<CameraStatus> = _status.asStateFlow()

    private val _resolutionLabel = MutableStateFlow("")
    val resolutionLabel: StateFlow<String> = _resolutionLabel.asStateFlow()

    val previewView: PreviewView = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FIT_CENTER
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }

    private var provider: ProcessCameraProvider? = null
    private var boundAnalysis: ImageAnalysis? = null
    private var boundCamera: Camera? = null

    // This token belongs to CameraManager only. It invalidates callbacks and
    // analyzer frames from an older CameraX binding; it is never exposed to the
    // session/vision layers.
    private val generation = AtomicInteger(0)
    private val cameraDeliveredRate = V7AnalysisRateAccumulator(64)
    private val transforms = ImageProxyTransformFactory().apply {
        setUsingCropRect(true)
        setUsingRotationDegrees(true)
    }

    var onFrame: ((CameraFrame) -> Unit)? = null

    companion object {
        const val TARGET_WIDTH = 1280
        const val TARGET_HEIGHT = 720
        const val MAX_ANALYSIS_PIXELS = 1280L * 960
    }

    fun start(lifecycleOwner: LifecycleOwner) {
        if (_status.value == CameraStatus.RUNNING || _status.value == CameraStatus.STARTING) return
        _status.value = CameraStatus.STARTING
        _resolutionLabel.value = ""
        val requestedGeneration = generation.incrementAndGet()

        val executor = ContextCompat.getMainExecutor(context)
        executor.execute {
            try {
                val p = ProcessCameraProvider.getInstance(context).get()
                if (generation.get() != requestedGeneration || _status.value != CameraStatus.STARTING) {
                    return@execute
                }
                provider = p
                previewView.doOnLayout {
                    if (generation.get() != requestedGeneration || _status.value != CameraStatus.STARTING) {
                        return@doOnLayout
                    }
                    bindCamera(p, lifecycleOwner, requestedGeneration)
                }
            } catch (_: Throwable) {
                if (generation.get() == requestedGeneration) {
                    _status.value = CameraStatus.ERROR
                }
            }
        }
    }

    fun stop() {
        if (_status.value == CameraStatus.IDLE) return
        _status.value = CameraStatus.STOPPING
        generation.incrementAndGet()
        boundAnalysis?.clearAnalyzer()
        boundAnalysis = null
        try {
            provider?.unbindAll()
        } catch (_: Throwable) {}
        boundCamera = null
        _cameraFps.value = 0.0
        _status.value = CameraStatus.IDLE
    }

    fun destroy() {
        stop()
        onFrame = null
    }

    private fun bindCamera(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        configuredGeneration: Int,
    ) {
        if (generation.get() != configuredGeneration || _status.value != CameraStatus.STARTING) return

        try {
            provider.unbindAll()

            val pv = previewView
            val rotation = pv.display?.rotation ?: Surface.ROTATION_0

            val resolutionSelector = ResolutionSelector.Builder()
                .setAllowedResolutionMode(ResolutionSelector.PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION)
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(TARGET_WIDTH, TARGET_HEIGHT),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                    ),
                )
                .build()

            val preview = Preview.Builder()
                .setTargetRotation(rotation)
                .build()
                .also { it.surfaceProvider = pv.surfaceProvider }

            val analysis = ImageAnalysis.Builder()
                .setTargetRotation(rotation)
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            // A shared ViewPort is a correctness requirement for Phase 0. If it
            // is unavailable we refuse to claim a running camera rather than
            // silently running analysis with a lying/missing preview.
            val viewPort = pv.viewPort
            if (viewPort == null) {
                _resolutionLabel.value = "VIEWPORT UNAVAILABLE"
                _status.value = CameraStatus.ERROR
                return
            }

            val useCases = listOf(preview, analysis)
            val info = provider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA)
            val base = SessionConfig.Builder(useCases)
                .apply { setViewPort(viewPort) }
                .build()
            val ranges = try {
                info.getSupportedFrameRateRanges(base)
            } catch (_: Throwable) {
                emptySet()
            }

            val chosen = ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
                ?: ranges.filter { it.lower <= 30 && it.upper >= 30 }
                    .minByOrNull { it.upper - it.lower }

            val config = SessionConfig.Builder(useCases).apply {
                setViewPort(viewPort)
                if (chosen != null) setFrameRateRange(chosen)
            }.build()

            val luma = LumaFrameBuffer()
            val chromaBuffers = ChromaSampleBuffers()
            val chromaReader = ImageProxyChromaSampler(chromaBuffers)

            analysis.setAnalyzer(analysisExecutor) { image ->
                processFrame(image, luma, chromaReader, configuredGeneration)
            }
            boundAnalysis = analysis

            boundCamera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                config,
            )

            if (generation.get() != configuredGeneration) {
                analysis.clearAnalyzer()
                provider.unbindAll()
                return
            }

            _resolutionLabel.value = "${TARGET_WIDTH}x${TARGET_HEIGHT} target"
            _status.value = CameraStatus.RUNNING
        } catch (_: Throwable) {
            boundAnalysis?.clearAnalyzer()
            boundAnalysis = null
            try { provider.unbindAll() } catch (_: Throwable) {}
            if (generation.get() == configuredGeneration) {
                _status.value = CameraStatus.ERROR
            }
        }
    }

    private fun processFrame(
        image: androidx.camera.core.ImageProxy,
        luma: LumaFrameBuffer,
        chroma: ImageProxyChromaSampler,
        configuredGeneration: Int,
    ) {
        if (generation.get() != configuredGeneration) {
            image.close()
            return
        }

        val sensorTs = image.imageInfo.timestamp
        val arrivalNs = System.nanoTime()
        cameraDeliveredRate.recordCompletion(sensorTs)
        val deliveredFps = cameraDeliveredRate.computeFps(sensorTs)
        _cameraFps.value = deliveredFps

        try {
            val crop = image.cropRect
            if (crop.width().toLong() * crop.height() > MAX_ANALYSIS_PIXELS) {
                _resolutionLabel.value = "REJECTED ${crop.width()}x${crop.height()}"
                return
            }

            if (!luma.packFrom(image)) return
            _resolutionLabel.value = "${luma.width}x${luma.height}"
            chroma.bind(image)

            val sourceTx = try {
                transforms.getOutputTransform(image)
            } catch (_: Throwable) {
                null
            } ?: return

            if (generation.get() != configuredGeneration) return

            onFrame?.invoke(
                CameraFrame(
                    lumaBytes = luma.bytes,
                    width = luma.width,
                    height = luma.height,
                    sourceTransform = sourceTx,
                    chromaReader = chroma,
                    sensorTimestamp = sensorTs,
                    arrivalNs = arrivalNs,
                    cameraFps = deliveredFps,
                )
            )
        } finally {
            image.close()
        }
    }
}
