package com.superqr.android.camera

import android.content.Context
import android.util.Range
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.superqr.android.vision.v6.detection.V6StaticDetector
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CameraFrame(
    val lumaBytes: ByteArray,
    val width: Int,
    val height: Int,
    val chromaReader: ImageProxyChromaSampler,
    val sourceTransform: OutputTransform,
    val sensorTimestamp: Long,
    val arrivalNs: Long,
    val cameraFps: Double,
    val generation: Int,
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

    val previewView: PreviewView = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }

    private var provider: ProcessCameraProvider? = null
    private var boundConfig: SessionConfig? = null
    private var boundCamera: Camera? = null
    private var detector: V6StaticDetector? = null
    private val generation = AtomicInteger(0)
    private val cameraDeliveredRate = V7AnalysisRateAccumulator(64)
    private val transforms = ImageProxyTransformFactory().apply {
        setUsingCropRect(true)
        setUsingRotationDegrees(true)
    }

    var onFrame: ((CameraFrame) -> Unit)? = null

    fun start(lifecycleOwner: LifecycleOwner) {
        if (_status.value == CameraStatus.RUNNING) return
        _status.value = CameraStatus.STARTING
        generation.incrementAndGet()

        val executor = ContextCompat.getMainExecutor(context)
        executor.execute {
            try {
                val p = ProcessCameraProvider.getInstance(context).get()
                provider = p
                bindCamera(p, lifecycleOwner)
            } catch (e: Throwable) {
                _status.value = CameraStatus.ERROR
            }
        }
    }

    fun stop() {
        _status.value = CameraStatus.STOPPING
        val d = detector
        detector = null
        generation.incrementAndGet()
        try {
            val p = provider ?: return
            boundConfig?.let { p.unbind(it) }
        } catch (_: Throwable) {}
        boundConfig = null
        boundCamera = null
        val executor = ContextCompat.getMainExecutor(context)
        executor.execute { d?.let { analysisExecutor.execute { try { it.close() } catch (_: Throwable) {} } } }
        _status.value = CameraStatus.IDLE
    }

    fun destroy() {
        stop()
        onFrame = null
    }

    private fun bindCamera(provider: ProcessCameraProvider, lifecycleOwner: LifecycleOwner) {
        try {
            try { provider.unbindAll() } catch (_: Throwable) {}

            val pv = previewView
            val rotation = pv.display?.rotation ?: Surface.ROTATION_0

            val preview = Preview.Builder()
                .setTargetRotation(rotation)
                .build()
                .also { it.surfaceProvider = pv.surfaceProvider }

            val analysis = ImageAnalysis.Builder()
                .setTargetRotation(rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val viewPort = pv.viewPort
                ?: run { _status.value = CameraStatus.ERROR; return }

            val info = try {
                provider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA)
            } catch (_: Throwable) { null }

            var ranges = emptySet<Range<Int>>()
            if (info != null) {
                try {
                    val probe = SessionConfig.Builder(listOf(preview, analysis))
                        .apply { setViewPort(viewPort) }
                        .build()
                    ranges = info.getSupportedFrameRateRanges(probe)
                } catch (_: Throwable) {}
            }

            val chosen = when {
                ranges.any { it.lower == 60 && it.upper == 60 } -> Range(60, 60)
                ranges.any { it.upper == 60 } -> ranges.filter { it.upper == 60 }.maxByOrNull { it.lower }
                ranges.any { it.lower == 30 && it.upper == 30 } -> Range(30, 30)
                else -> null
            }

            val config = SessionConfig.Builder(listOf(preview, analysis))
                .apply {
                    setViewPort(viewPort)
                    if (chosen != null) setFrameRateRange(chosen)
                }
                .build()

            val d = V6StaticDetector()
            detector = d
            val luma = LumaFrameBuffer()
            val chroma = ImageProxyChromaSampler(ChromaSampleBuffers())

            analysis.setAnalyzer(analysisExecutor) { image ->
                processFrame(image, d, luma, chroma)
            }

            try {
                boundCamera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    config,
                )
                boundConfig = config
                _status.value = CameraStatus.RUNNING
            } catch (_: Throwable) {
                val fallback = SessionConfig.Builder(listOf(preview, analysis))
                    .apply { setViewPort(viewPort) }
                    .build()
                boundCamera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    fallback,
                )
                boundConfig = fallback
                _status.value = CameraStatus.RUNNING
            }
        } catch (e: Throwable) {
            _status.value = CameraStatus.ERROR
        }
    }

    private fun processFrame(
        image: ImageProxy,
        d: V6StaticDetector,
        luma: LumaFrameBuffer,
        chroma: ImageProxyChromaSampler,
    ) {
        val gen = generation.get()
        val sensorTs = image.imageInfo.timestamp
        val arrivalNs = System.nanoTime()
        cameraDeliveredRate.recordCompletion(sensorTs)
        _cameraFps.value = cameraDeliveredRate.computeFps(sensorTs)

        try {
            if (!luma.packFrom(image)) return
            chroma.bind(image)
            val sourceTx = try {
                transforms.getOutputTransform(image)
            } catch (_: Throwable) { null }

            val deliveredFps = cameraDeliveredRate.computeFps(sensorTs)
            val frame = CameraFrame(
                lumaBytes = luma.bytes,
                width = luma.width,
                height = luma.height,
                chromaReader = chroma,
                sourceTransform = sourceTx ?: return,
                sensorTimestamp = sensorTs,
                arrivalNs = arrivalNs,
                cameraFps = deliveredFps,
                generation = gen,
            )
            onFrame?.invoke(frame)
        } finally {
            image.close()
        }
    }
}
