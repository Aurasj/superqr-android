package com.superqr.android.transfer

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
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import zxingcpp.BarcodeReader

/** Dedicated production camera path: CameraX -> ZXing-C++ -> V40-L transport bytes. */
class TransferCameraManager(
    private val context: Context,
    private val analysisExecutor: ExecutorService,
) {
    enum class Status { IDLE, STARTING, RUNNING, STOPPING, ERROR }

    data class QrSample(
        val bytes: ByteArray,
        val decodeMs: Double,
        val cameraFps: Double,
    )

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _cameraFps = MutableStateFlow(0.0)
    val cameraFps: StateFlow<Double> = _cameraFps.asStateFlow()

    private val _decodeMs = MutableStateFlow(0.0)
    val decodeMs: StateFlow<Double> = _decodeMs.asStateFlow()

    private val _resolution = MutableStateFlow("")
    val resolution: StateFlow<String> = _resolution.asStateFlow()

    val previewView = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FIT_CENTER
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }

    var onQrDecoded: ((QrSample) -> Unit)? = null

    private val generation = AtomicInteger(0)
    private val deliveredRate = AnalysisRateAccumulator(64)
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null

    private val reader = BarcodeReader(
        BarcodeReader.Options(
            formats = setOf(BarcodeReader.Format.QR_CODE),
            tryHarder = true,
            tryRotate = false,
            tryInvert = false,
            tryDownscale = false,
            tryDenoise = true,
            maxNumberOfSymbols = 1,
            returnErrors = false,
            textMode = BarcodeReader.TextMode.PLAIN,
        )
    )

    fun start(owner: LifecycleOwner) {
        if (_status.value == Status.STARTING || _status.value == Status.RUNNING) return
        _status.value = Status.STARTING
        _resolution.value = ""
        val requestedGeneration = generation.incrementAndGet()
        ContextCompat.getMainExecutor(context).execute {
            try {
                val cameraProvider = ProcessCameraProvider.getInstance(context).get()
                if (generation.get() != requestedGeneration || _status.value != Status.STARTING) {
                    return@execute
                }
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
        _cameraFps.value = 0.0
        _decodeMs.value = 0.0
        _status.value = Status.IDLE
    }

    fun destroy() {
        stop()
        onQrDecoded = null
    }

    private fun bind(
        cameraProvider: ProcessCameraProvider,
        owner: LifecycleOwner,
        configuredGeneration: Int,
    ) {
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
                .build()

            val viewPort = previewView.viewPort ?: run {
                _status.value = Status.ERROR
                return
            }
            val useCases = listOf(preview, imageAnalysis)
            val info = cameraProvider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA)
            val baseConfig = SessionConfig.Builder(useCases).apply { setViewPort(viewPort) }.build()
            val ranges = try { info.getSupportedFrameRateRanges(baseConfig) } catch (_: Throwable) { emptySet() }
            val chosen = ranges.firstOrNull {
                it.lower == ProductionQrContract.CAMERA_TARGET_FPS &&
                    it.upper == ProductionQrContract.CAMERA_TARGET_FPS
            } ?: ranges.filter {
                it.lower <= ProductionQrContract.CAMERA_TARGET_FPS &&
                    it.upper >= ProductionQrContract.CAMERA_TARGET_FPS
            }.minByOrNull { it.upper - it.lower }

            val config = SessionConfig.Builder(useCases).apply {
                setViewPort(viewPort)
                if (chosen != null) setFrameRateRange(chosen)
            }.build()

            imageAnalysis.setAnalyzer(analysisExecutor) { image ->
                process(image, configuredGeneration)
            }
            analysis = imageAnalysis
            cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, config)
            if (generation.get() != configuredGeneration) {
                imageAnalysis.clearAnalyzer()
                cameraProvider.unbindAll()
                return
            }
            _status.value = Status.RUNNING
        } catch (_: Throwable) {
            analysis?.clearAnalyzer()
            analysis = null
            try { cameraProvider.unbindAll() } catch (_: Throwable) {}
            if (generation.get() == configuredGeneration) _status.value = Status.ERROR
        }
    }

    private fun process(image: ImageProxy, configuredGeneration: Int) {
        if (generation.get() != configuredGeneration) {
            image.close()
            return
        }
        val timestamp = image.imageInfo.timestamp
        deliveredRate.recordCompletion(timestamp)
        val fps = deliveredRate.computeFps(timestamp)
        _cameraFps.value = fps
        _resolution.value = "${image.cropRect.width()}x${image.cropRect.height()}"

        try {
            val started = System.nanoTime()
            val result = reader.read(image).firstOrNull {
                it.format == BarcodeReader.Format.QR_CODE &&
                    it.error == null &&
                    (it.bytes?.isNotEmpty() == true)
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000.0
            _decodeMs.value = elapsed
            if (result != null && generation.get() == configuredGeneration) {
                onQrDecoded?.invoke(QrSample(result.bytes!!.copyOf(), elapsed, fps))
            }
        } catch (_: Throwable) {
            // A failed optical frame is expected; the rotating sender will show it again.
        } finally {
            image.close()
        }
    }
}
