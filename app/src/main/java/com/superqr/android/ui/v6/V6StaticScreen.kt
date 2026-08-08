package com.superqr.android.ui.v6

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.util.Range
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.superqr.android.camera.ChromaSampleBuffers
import com.superqr.android.camera.ImageProxyChromaSampler
import com.superqr.android.camera.LumaFrameBuffer
import com.superqr.android.camera.V7AnalysisRateAccumulator
import com.superqr.android.vision.v7_capacity_lab.V7CapacityLabReceiver
import com.superqr.android.vision.v7_capacity_lab.V7ChannelMetrics
import com.superqr.android.vision.v7_capacity_lab.V7HighDensitySampler
import com.superqr.android.vision.v7_capacity_lab.V7LabManifest
import com.superqr.android.vision.v6.contract.V6Contract
import com.superqr.android.vision.v6.detection.V6StaticDetector
import com.superqr.android.vision.v6.diagnostic.V6CapturedFrameBundle
import com.superqr.android.vision.v6.diagnostic.V6FullDiagnosticExporter
import com.superqr.android.vision.v6.diagnostic.V6TransferStats
import com.superqr.android.vision.v6.diagnostic.V6TransferStatsCollector
import com.superqr.android.vision.v6.model.V6StaticResult
import com.superqr.android.vision.v6.transport.V6ReceiveUtils
import com.superqr.android.vision.v6.transport.V6SessionAccumulator
import com.superqr.android.vision.v6.transport.V6TransferPackage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private enum class V6ScannerState { IDLE, STARTING, SCANNING, COMPLETE, ERROR }

private enum class ScannerMode { V6_RECEIVE, V7_CAPACITY_LAB }

private data class CompletedUiTransfer(
    val pkg: V6TransferPackage,
    val sessionId: Int,
    val totalFrames: Int,
    val fileCrc32: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuperQRScannerScreen(
    analysisExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() },
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    diagnosticLogger: V6DiagnosticLogger = remember { V6DiagnosticLogger() },
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }

    // FILL_CENTER is intentional. Preview and ImageAnalysis are bound through the
    // same CameraX ViewPort below, so the visible preview and analyzed sensor area
    // are the same WYSIWYG crop.
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var currentResult by remember { mutableStateOf<V6StaticResult?>(null) }
    var previewGeometry by remember { mutableStateOf<V6PreviewOverlayGeometry?>(null) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var focusState by remember { mutableStateOf("AUTO") }
    var cameraBindingState by remember { mutableStateOf("IDLE") }

    val accumulator = remember { V6SessionAccumulator() }
    val statsCollector = remember { V6TransferStatsCollector().also { it.reset() } }
    var completedTransfer by remember { mutableStateOf<CompletedUiTransfer?>(null) }
    var completedCacheFile by remember { mutableStateOf<File?>(null) }
    var transferStats by remember { mutableStateOf<V6TransferStats?>(null) }

    var scannerState by remember { mutableStateOf(V6ScannerState.IDLE) }

    // ── V7 Capacity Lab mode ──────────────────────────────────────────
    var scannerMode by remember { mutableStateOf(ScannerMode.V6_RECEIVE) }
    var v7Receiver by remember { mutableStateOf<V7CapacityLabReceiver?>(null) }
    var v7Manifest by remember { mutableStateOf<V7LabManifest?>(null) }
    var v7ProfileName by remember { mutableStateOf("ref_40x40_v6_reference_4_seed42") }
    var v7ExpectedFrame by remember { mutableIntStateOf(0) }
    var v7SamplerMode by remember { mutableStateOf(V7HighDensitySampler.ProbeMode.CENTER_1) }
    var v7LastMetrics by remember { mutableStateOf<V7ChannelMetrics.FrameMetrics?>(null) }
    var v7CalibrationLabel by remember { mutableStateOf("—") }
    var v7ConfidenceSummary by remember { mutableStateOf("—") }
    var v7FrameCount by remember { mutableIntStateOf(0) }
    var v7AnalysisMs by remember { mutableDoubleStateOf(0.0) }
    var v7CameraFps by remember { mutableDoubleStateOf(0.0) }
    var v7LastSensorTs by remember { mutableLongStateOf(0L) }
    var v7AnalysisFps by remember { mutableDoubleStateOf(0.0) }
    val v7AnalysisRateAccum = remember { V7AnalysisRateAccumulator(64) }

    // Load V7 manifest
    LaunchedEffect(Unit) {
        try {
            val bytes = context.assets.open("v7_capacity_lab/lab_manifest.json")
                .use { it.readBytes() }
            v7Manifest = V7LabManifest.loadFromBytes(bytes)
        } catch (_: Throwable) {
        }
    }

    var showLab by remember { mutableStateOf(false) }
    var labTab by remember { mutableIntStateOf(0) }
    var selectedTestMode by remember { mutableStateOf("deterministic_random") }
    val selectedTestModeState = rememberUpdatedState(selectedTestMode)

    val fullDiagnosticArmed = remember { AtomicBoolean(false) }
    val diagnosticArmedAt = remember { AtomicLong(0L) }
    var diagnosticState by remember { mutableStateOf("IDLE") }
    var capturedZip by remember { mutableStateOf<File?>(null) }

    fun lockFocus() {
        val camera = boundCamera ?: return
        if (previewView.width <= 0 || previewView.height <= 0) return
        focusState = "LOCKING"
        try {
            val point = previewView.meteringPointFactory.createPoint(
                previewView.width / 2f,
                previewView.height / 2f,
            )
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                .disableAutoCancel()
                .build()
            val future = camera.cameraControl.startFocusAndMetering(action)
            future.addListener({
                focusState = try {
                    if (future.get().isFocusSuccessful) "LOCKED" else "FAILED"
                } catch (_: Throwable) {
                    "FAILED"
                }
                diagnosticLogger.log("INFO", "CAMERA", "FOCUS", "Focus state: $focusState")
            }, mainExecutor)
        } catch (_: Throwable) {
            focusState = "FAILED"
        }
    }

    fun unlockFocus() {
        try {
            boundCamera?.cameraControl?.cancelFocusAndMetering()
        } catch (_: Throwable) {
        }
        focusState = "AUTO"
    }

    // ── camera + detector refs (bound per scan session) ────────────────
    var detectorRef by remember { mutableStateOf<V6StaticDetector?>(null) }
    var boundSessionConfig by remember { mutableStateOf<SessionConfig?>(null) }

    // Monotonically increasing per-scan generation. The analyzer callback
    // captures a snapshot and only publishes results when the snapshot
    // still matches the current generation. Incrementing it invalidates
    // any in-flight main-executor dispatches from a previous scan.
    val scanGeneration = remember { AtomicInteger(0) }

    // ── start-scan helper ──────────────────────────────────────────────
    val doStartScan: () -> Unit = {
        scanGeneration.incrementAndGet()
        completedCacheFile?.delete(); completedCacheFile = null
        completedTransfer = null; transferStats = null
        currentResult = null; previewGeometry = null
        accumulator.reset(); statsCollector.reset()
        detectorRef?.close(); detectorRef = null
        boundSessionConfig = null
        v7LastMetrics = null
        v7CalibrationLabel = "—"
        v7ConfidenceSummary = "—"
        v7AnalysisRateAccum.reset()
        // Initialize V7 receiver if in V7 mode
        if (scannerMode == ScannerMode.V7_CAPACITY_LAB && v7Manifest != null) {
            val rec = V7CapacityLabReceiver(v7Manifest!!)
            rec.selectProfile(v7ProfileName)
            rec.probeMode = v7SamplerMode
            v7Receiver = rec
        }
        cameraBindingState = "STARTING"
        scannerState = V6ScannerState.STARTING
    }

    // ── resource-release helper (separate from scannerState) ──────────
    fun releaseCamera() {
        try {
            // 1. Stop new analyzer callbacks from entering detection.
            val det = detectorRef
            detectorRef = null
            val v7r = v7Receiver; v7Receiver = null

            // 2. Invalidate stale main-executor dispatches from this scan.
            scanGeneration.incrementAndGet()

            // 3. Clear live UI state immediately (completed result/stats
            //    are already frozen before this call by the completion path).
            currentResult = null
            previewGeometry = null
            v7AnalysisFps = 0.0
            v7AnalysisRateAccum.reset()

            // 4. Unbind CameraX session on the main thread (we are on main).
            val provider = try { ProcessCameraProvider.getInstance(context).get() } catch (_: Throwable) { null }
            val sc = boundSessionConfig
            if (provider != null && sc != null) {
                try { provider.unbind(sc) } catch (_: Throwable) {}
            }
            boundSessionConfig = null
            boundCamera = null

            // 5. Close detector and V7 receiver on the analysis executor so it serializes
            //    behind any callback that was already past the detectorRef
            //    null-check. After this runs, all callbacks have finished.
            if (det != null || v7r != null) {
                analysisExecutor.execute {
                    try { det?.close() } catch (_: Throwable) {}
                    try { v7r?.close() } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
    }

    // ── camera setup + binding (called from main executor via doOnLayout) ──
    fun prepareAndBindCamera(provider: ProcessCameraProvider, pv: PreviewView) {
        try {
            // Defensive: clear any prior bound session.
            try { provider.unbindAll() } catch (_: Throwable) {}
            boundCamera = null
            boundSessionConfig = null

            val targetRotation = pv.display?.rotation ?: Surface.ROTATION_0

            val preview = Preview.Builder()
                .setTargetRotation(targetRotation)
                .build()
                .also { it.surfaceProvider = pv.surfaceProvider }

            val analysis = ImageAnalysis.Builder()
                .setTargetRotation(targetRotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val viewPort = pv.viewPort
            if (viewPort == null) {
                cameraBindingState = "ERROR"
                scannerState = V6ScannerState.ERROR
                diagnosticLogger.log("ERROR", "CAMERA", "VIEWPORT_NULL",
                    "PreviewView ViewPort unavailable after layout; refusing non-WYSIWYG binding")
                return
            }

            val cameraInfo = try {
                provider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA)
            } catch (_: Throwable) { null }

            var supportedRanges = emptySet<Range<Int>>()
            if (cameraInfo != null) {
                val preBuilder = SessionConfig.Builder(listOf(preview, analysis))
                viewPort.let { preBuilder.setViewPort(it) }
                try {
                    cameraInfo.getSupportedFrameRateRanges(preBuilder.build())
                        .also { supportedRanges = it }
                } catch (_: Throwable) {}
            }

            val rangesLogLine = supportedRanges.joinToString(", ") { "[${it.lower},${it.upper}]" }
            diagnosticLogger.log("INFO", "CAMERA", "FPS_SUPPORTED", rangesLogLine)

            val chosenRange: Range<Int>?
            val chosenLabel: String
            when {
                supportedRanges.any { it.lower == 60 && it.upper == 60 } -> {
                    chosenRange = Range(60, 60); chosenLabel = "60-60"
                }
                supportedRanges.any { it.upper == 60 } -> {
                    val best = supportedRanges.filter { it.upper == 60 }.maxByOrNull { it.lower }
                    chosenRange = best
                    chosenLabel = best?.let { "${it.lower}-${it.upper}" } ?: "DEFAULT"
                }
                supportedRanges.any { it.lower == 30 && it.upper == 30 } -> {
                    chosenRange = Range(30, 30); chosenLabel = "30-30"
                }
                else -> { chosenRange = null; chosenLabel = "DEFAULT" }
            }

            val finalBuilder = SessionConfig.Builder(listOf(preview, analysis))
            viewPort.let { finalBuilder.setViewPort(it) }
            if (chosenRange != null) { finalBuilder.setFrameRateRange(chosenRange) }
            val sessionConfig = finalBuilder.build()
            var boundLabel = if (chosenRange != null) chosenLabel else "DEFAULT"

            detectorRef = V6StaticDetector()
            val lumaBuffer = LumaFrameBuffer()
            val chromaBuffers = ChromaSampleBuffers()
            val imageTransformFactory = ImageProxyTransformFactory().apply {
                setUsingCropRect(true); setUsingRotationDegrees(true)
            }

            var firstConfigRecorded = false

            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                // Capture the scan generation at callback entry. Results are
                // published only if this snapshot still matches the current generation.
                val myGen = scanGeneration.get()
                try {
                    val activeDetector = detectorRef ?: return@setAnalyzer
                    val sensorTs = imageProxy.imageInfo.timestamp
                    statsCollector.recordAnalyzerFrame(sensorTs)
                    val analyzerArrivalNs = System.nanoTime()
                    val lumaOk = lumaBuffer.packFrom(imageProxy)
                    if (!lumaOk) return@setAnalyzer

                    if (!firstConfigRecorded) {
                        firstConfigRecorded = true
                        val rangesStr = supportedRanges.joinToString(", ") { "[${it.lower},${it.upper}]" }
                        statsCollector.recordCameraConfig(
                            width = lumaBuffer.width, height = lumaBuffer.height,
                            supportedRanges = rangesStr, selectedRange = chosenLabel,
                            boundRange = boundLabel,
                            fallback = cameraBindingState == "WYSIWYG-FALLBACK"
                        )
                    }

                    val chromaReader = ImageProxyChromaSampler(imageProxy, chromaBuffers)
                    val rawResult = activeDetector.detect(
                        luma = lumaBuffer.bytes, width = lumaBuffer.width,
                        height = lumaBuffer.height, mode = selectedTestModeState.value,
                        chromaReader = chromaReader,
                    )
                    val result = rawResult.copy(analyzerArrivalNs = analyzerArrivalNs)

                    // ── V7 Capacity Lab payload analysis ──────────────
                    val v7Recv = v7Receiver
                    val v7SensorTs = sensorTs
                    val v7Result = if (v7Recv != null && result.borderFound && result.orientationResolved) {
                        val lumaW = lumaBuffer.width
                        val lumaH = lumaBuffer.height
                        v7Recv.analyze(result, lumaBuffer.bytes, lumaW, lumaH, chromaReader)
                    } else null

                    statsCollector.recordDetectorResult(
                        classificationSource = result.diagnosticPayload?.classificationSource,
                        borderFound = result.borderFound,
                        orientationResolved = result.orientationResolved,
                        detectorStartNs = result.detectorStartNs,
                        detectorEndNs = result.detectorEndNs
                    )

                    if (result.detectorStartNs > 0) {
                        statsCollector.recordStageTiming(result.detectorStartNs - analyzerArrivalNs)
                    }

                    val sourceTransform = try {
                        imageTransformFactory.getOutputTransform(imageProxy)
                    } catch (_: Throwable) { null }

                    val payload = result.diagnosticPayload
                    if (fullDiagnosticArmed.get()) {
                        val expired = System.currentTimeMillis() - diagnosticArmedAt.get() > 5000L
                        if (expired) {
                            fullDiagnosticArmed.set(false)
                            mainExecutor.execute { diagnosticState = "TIMEOUT" }
                        } else if (result.borderFound && result.orientationResolved &&
                            payload?.classificationSource == "FULL_DETECTION"
                        ) {
                            fullDiagnosticArmed.set(false)
                            val yPlane = imageProxy.planes[0]; val uPlane = imageProxy.planes[1]
                            val vPlane = imageProxy.planes[2]
                            fun copyBuffer(b: java.nio.ByteBuffer) = b.duplicate().let { d ->
                                ByteArray(d.remaining()).also { d.get(it) }
                            }
                            val bundle = V6CapturedFrameBundle(
                                timestamp = payload.timestamp,
                                imageWidth = imageProxy.width, imageHeight = imageProxy.height,
                                rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                                cropRect = android.graphics.Rect(imageProxy.cropRect),
                                yRowStride = yPlane.rowStride, yPixelStride = yPlane.pixelStride,
                                uRowStride = uPlane.rowStride, uPixelStride = uPlane.pixelStride,
                                vRowStride = vPlane.rowStride, vPixelStride = vPlane.pixelStride,
                                yPlaneBytes = copyBuffer(yPlane.buffer),
                                uPlaneBytes = copyBuffer(uPlane.buffer),
                                vPlaneBytes = copyBuffer(vPlane.buffer),
                                trackingState = result.trackingState,
                                ransacInliers = result.ransacInliers,
                                correctCount = result.colorCorrect,
                                incorrectCount = result.colorTotal - result.colorCorrect - result.colorUncertain,
                                uncertainCount = result.colorUncertain,
                                payload = payload, warpedLumaBytes = result.warpedLumaBytes,
                                frameTrace = activeDetector.getFrameTraceSnapshot(),
                            )
                            analysisExecutor.execute {
                                try {
                                    val f = V6FullDiagnosticExporter.exportToZip(bundle, context.cacheDir)
                                    mainExecutor.execute { capturedZip = f; diagnosticState = "CAPTURED" }
                                } catch (e: Throwable) {
                                    mainExecutor.execute { diagnosticState = "ERROR: ${e.message}" }
                                }
                            }
                        }
                    }

                    mainExecutor.execute {
                        // Gate: only publish if this callback's generation still
                        // matches the active scan. Stale dispatches from a previous
                        // scan are silently dropped.
                        if (myGen != scanGeneration.get()) return@execute
                        val uiDeliveryNs = System.nanoTime()
                        statsCollector.recordCrcOutcome(result.transportFrame, result.transportError)
                        currentResult = result.copy(uiDeliveryNs = uiDeliveryNs)

                        previewGeometry = if (sourceTransform != null) {
                            pv.outputTransform?.let { V6PreviewOverlayMapper.map(result, sourceTransform, it) }
                        } else null

                        // ── V7 metrics update ──────────────────────────
                        val vr = v7Result
                        if (vr != null && scannerMode == ScannerMode.V7_CAPACITY_LAB) {
                            v7LastMetrics = vr.metrics
                            v7ExpectedFrame = vr.expectedFrameIndex
                            v7CalibrationLabel = vr.calibrationStatus
                            v7FrameCount++
                            v7AnalysisMs = vr.metrics?.totalAnalysisUs?.div(1000.0) ?: 0.0
                            v7AnalysisRateAccum.recordCompletion(System.nanoTime())
                            v7AnalysisFps = v7AnalysisRateAccum.computeFps()
                            // Real camera delivered FPS from ImageProxy sensor timestamp delta
                            if (v7LastSensorTs > 0L) {
                                val deltaSec = (v7SensorTs - v7LastSensorTs) / 1_000_000_000.0
                                if (deltaSec > 0.0) {
                                    v7CameraFps = 1.0 / deltaSec
                                }
                            }
                            v7LastSensorTs = v7SensorTs
                            // Confidence summary from first 100 cells
                            val r = v7Receiver
                            if (r != null && r.classifier.bestSymbols.isNotEmpty()) {
                                val margins = (0 until minOf(100, r.gridSize * r.gridSize)).map {
                                    r.classifier.confidenceMargin(it)
                                }.filter { it > 0.0 }
                                v7ConfidenceSummary = if (margins.isNotEmpty())
                                    "μ=%.3f".format(margins.average()) else "—"
                            }
                        }

                        // ── V6 transport (V6_RECEIVE mode only) ────────
                        val frame = result.transportFrame
                        if (scannerMode == ScannerMode.V6_RECEIVE && frame != null && completedTransfer == null) {
                            try {
                                val pre = V6TransferStatsCollector.AccPreState(
                                    accumulator.getUniqueFrames(), accumulator.getDuplicateCount(),
                                    accumulator.getConflictCount(),
                                    accumulator.getCurrentSessionId(), accumulator.getTotalFrames()
                                )
                                val pkg = accumulator.addFrame(frame)
                                val post = V6TransferStatsCollector.AccPostState(
                                    accumulator.getUniqueFrames(), accumulator.getDuplicateCount(),
                                    accumulator.getConflictCount()
                                )
                                statsCollector.recordAccumulatorOutcome(pre, post, frame, pkg)

                                if (pkg != null) {
                                    val crc = java.util.zip.CRC32().apply { update(pkg.fileData) }.value
                                    val safeName = V6ReceiveUtils.sanitizeFilename(pkg.filename)
                                    val dir = File(context.cacheDir, "superqr_received").apply { mkdirs() }
                                    val cacheFile = File(dir, safeName)
                                    cacheFile.writeBytes(pkg.fileData)
                                    // 1. Freeze final stats + result BEFORE releasing camera.
                                    val frozenStats = statsCollector.buildSnapshot()
                                    completedCacheFile = cacheFile
                                    transferStats = frozenStats
                                    completedTransfer = CompletedUiTransfer(pkg, frame.sessionId, frame.totalFrames, crc)
                                    // 2. Release camera resources (clears currentResult/previewGeometry/detector).
                                    releaseCamera()
                                    // 3. Transition to COMPLETE — final result/stats survive.
                                    scannerState = V6ScannerState.COMPLETE
                                    cameraBindingState = "STOPPED"
                                }
                            } catch (e: Throwable) {
                                diagnosticLogger.log("ERROR", "TRANSPORT", "ACCUMULATOR", e.message ?: e.toString())
                            }
                        }
                    }
                } catch (e: Throwable) {
                    Log.e("V6StaticScreen", "Frame analysis error", e)
                    diagnosticLogger.log("ERROR", "CAMERA", "ANALYSIS", e.message ?: e.toString())
                } finally {
                    imageProxy.close()
                    statsCollector.recordAnalyzerClose()
                }
            }

            // ── bind ──────────────────────────────────────────────────────
            try {
                boundCamera = provider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, sessionConfig
                )
                boundSessionConfig = sessionConfig
                cameraBindingState = "WYSIWYG"
                scannerState = V6ScannerState.SCANNING
                diagnosticLogger.log("INFO", "CAMERA", "WYSIWYG_BOUND",
                    "SessionConfig bound: chosenRange=$chosenLabel, boundRange=$boundLabel, supportedRanges=$rangesLogLine")
            } catch (e: Throwable) {
                diagnosticLogger.log("WARN", "CAMERA", "FALLBACK",
                    "High-FPS SessionConfig binding failed: ${e.message}. Retrying.")
                val fbConfig = SessionConfig.Builder(listOf(preview, analysis))
                    .also { viewPort?.let { vp -> it.setViewPort(vp) } }
                    .build()
                boundCamera = provider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, fbConfig
                )
                boundSessionConfig = fbConfig
                cameraBindingState = "WYSIWYG-FALLBACK"
                boundLabel = "DEFAULT"
                scannerState = V6ScannerState.SCANNING
                diagnosticLogger.log("INFO", "CAMERA", "FALLBACK_BOUND", "Fallback bind succeeded")
            }
        } catch (e: Throwable) {
            cameraBindingState = "ERROR"
            scannerState = V6ScannerState.ERROR
            diagnosticLogger.log("ERROR", "CAMERA", "BIND", e.message ?: e.toString())
        }
    }

    // ── scanner state transitions ──────────────────────────────────────
    if (scannerState == V6ScannerState.STARTING) {
        LaunchedEffect(Unit) {
            mainExecutor.execute {
                val provider = try {
                    ProcessCameraProvider.getInstance(context).get()
                } catch (e: Throwable) {
                    scannerState = V6ScannerState.ERROR
                    cameraBindingState = "ERROR"
                    diagnosticLogger.log("ERROR", "CAMERA", "PROVIDER", e.message ?: e.toString())
                    return@execute
                }
                try { V6Contract.loadAndVerify(context) } catch (_: Throwable) {}
                // doOnLayout ensures ViewPort is available.
                previewView.doOnLayout { prepareAndBindCamera(provider, previewView) }
            }
        }
    }

    // ── background / ON_STOP: release camera, go to IDLE ──────────────
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                val st = scannerState
                if (st == V6ScannerState.STARTING || st == V6ScannerState.SCANNING) {
                    releaseCamera()
                    scannerState = V6ScannerState.IDLE
                    cameraBindingState = "STOPPED"
                    diagnosticLogger.log("INFO", "CAMERA", "BACKGROUND", "Scanner stopped on ON_STOP (was $st)")
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── full disposal (composition removed) ────────────────────────────
    DisposableEffect(Unit) {
        onDispose {
            releaseCamera()
            scannerState = V6ScannerState.IDLE
        }
    }

    // ── keep screen on while scanning ─────────────────────────────────
    DisposableEffect(scannerState) {
        try {
            val act = context as? android.app.Activity
            if (scannerState == V6ScannerState.SCANNING) {
                act?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        } catch (_: Throwable) {}
        onDispose {
            try {
                val act = context as? android.app.Activity
                act?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } catch (_: Throwable) {}
        }
    }

    // ── idle screen ────────────────────────────────────────────────────
    if (scannerState == V6ScannerState.IDLE || scannerState == V6ScannerState.ERROR) {
        Box(modifier = modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("SuperQR Scanner", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                if (scannerState == V6ScannerState.ERROR) {
                    Text("Camera binding failed", color = Color(0xFFF38BA8), fontSize = 14.sp)
                }

                // Mode selector
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = scannerMode == ScannerMode.V6_RECEIVE,
                        onClick = { scannerMode = ScannerMode.V6_RECEIVE },
                        label = { Text("V6 Receive", fontSize = 12.sp) },
                    )
                    FilterChip(
                        selected = scannerMode == ScannerMode.V7_CAPACITY_LAB,
                        onClick = { scannerMode = ScannerMode.V7_CAPACITY_LAB },
                        label = { Text("V7 Capacity Lab", fontSize = 12.sp) },
                    )
                }

                // V7 profile selector (only in V7 mode)
                if (scannerMode == ScannerMode.V7_CAPACITY_LAB) {
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(
                            value = v7ProfileName.takeLast(35),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Profile") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedLabelColor = Color(0xFF89B4FA),
                                unfocusedLabelColor = Color.White.copy(alpha = 0.6f),
                                focusedBorderColor = Color(0xFF89B4FA),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                            ),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            v7Manifest?.referenceProfiles?.keys?.forEach { name ->
                                DropdownMenuItem(
                                    text = {
                                        val p = v7Manifest?.referenceProfiles?.get(name)
                                        val label = if (p != null) "${p.gridSize}x${p.gridSize} ${p.paletteName}" else name
                                        Text(label, fontSize = 11.sp)
                                    },
                                    onClick = { v7ProfileName = name; expanded = false },
                                )
                            }
                        }
                    }

                    // Sampler mode
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = v7SamplerMode == V7HighDensitySampler.ProbeMode.CENTER_1,
                            onClick = { v7SamplerMode = V7HighDensitySampler.ProbeMode.CENTER_1 },
                            label = { Text("CENTER_1", fontSize = 10.sp) },
                        )
                        FilterChip(
                            selected = v7SamplerMode == V7HighDensitySampler.ProbeMode.CROSS_5,
                            onClick = { v7SamplerMode = V7HighDensitySampler.ProbeMode.CROSS_5 },
                            label = { Text("CROSS_5", fontSize = 10.sp) },
                        )
                    }
                }

                Button(onClick = { doStartScan() }) {
                    Text("START SCAN", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp))
                }
                if (onBack != null) {
                    TextButton(onClick = onBack) { Text("Back", color = Color.White) }
                }
            }
        }
        return
    }

    if (!hasCameraPermission) {
        Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("Grant Camera Permission")
            }
        }
        return
    }

    val completed = completedTransfer
    if (completed != null) {
        TransferCompleteScreen(
            completed = completed,
            stats = transferStats,
            onScanAnother = {
                doStartScan()
            },
            modifier = modifier,
        )
        return
    }

    val result = currentResult
    val source = result?.diagnosticPayload?.classificationSource
    val fresh = source == "FULL_DETECTION" || source == "TRACKED_RESAMPLED"
    val held = source == "TRACKED_HOMOGRAPHY"
    val freshTransport = fresh && result?.transportFrame != null
    val sessionActive = accumulator.getCurrentSessionId() != -1

    val normW = result?.diagnosticPayload?.normalizedAnalysisWidth ?: 0
    val normH = result?.diagnosticPayload?.normalizedAnalysisHeight ?: 0
    val normArea = (normW.toLong() * normH.toLong()).toDouble()
    val areaRatio = if (normArea > 0.0) (result?.contourArea ?: 0.0) / normArea else 0.0

    val guidance = when {
        cameraBindingState != "WYSIWYG" -> "CAMERA ALIGNING"
        sessionActive -> "RECEIVING"
        freshTransport -> "READING"
        result == null || !result.borderFound -> "CENTER SUPERQR"
        held -> "HOLD STEADY"
        !result.orientationResolved -> "ALIGN ALL 4 CORNERS"
        areaRatio in 0.0001..0.10 -> "MOVE CLOSER"
        areaRatio > 0.72 -> "MOVE FARTHER"
        fresh -> "GOOD POSITION"
        else -> "ALIGN SUPERQR"
    }

    val guidanceColor = when (guidance) {
        "RECEIVING", "READING", "GOOD POSITION" -> Color(0xFF4CAF50)
        "CAMERA ALIGNING" -> Color.LightGray
        else -> Color(0xFFFFB74D)
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )

        // This overlay now has two distinct meanings:
        // 1) white corner brackets = recommended placement only;
        // 2) green/amber polygon + matrix = EXACT geometry CameraX mapped from
        //    ImageAnalysis into the visible PreviewView.
        ScannerOverlay(
            geometry = previewGeometry,
            guidance = guidance,
            guidanceColor = guidanceColor,
            cameraAligned = cameraBindingState == "WYSIWYG",
            showDataGrid = scannerMode != ScannerMode.V7_CAPACITY_LAB,
            modifier = Modifier.fillMaxSize(),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    TextButton(onClick = onBack) { Text("‹", color = Color.White, fontSize = 28.sp) }
                }
                Text(if (scannerMode == ScannerMode.V6_RECEIVE) "V6 Scanner" else "V7 Capacity Lab", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(
                    text = when {
                        fresh -> "FRESH"
                        held -> "HELD"
                        else -> "SEARCH"
                    },
                    color = when {
                        fresh -> Color(0xFF2E7D32)
                        held -> Color(0xFFE65100)
                        else -> Color(0xFF616161)
                    },
                )
                Button(
                    onClick = { showLab = !showLab },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) { Text("Lab", fontSize = 11.sp) }
            }
        }

        if (showLab) {
            ScannerLabPanel(
                result = result,
                accumulator = accumulator,
                selectedMode = selectedTestMode,
                onModeChanged = { selectedTestMode = it },
                focusState = focusState,
                onLockFocus = ::lockFocus,
                onUnlockFocus = ::unlockFocus,
                diagnosticState = diagnosticState,
                hasCapturedZip = capturedZip != null,
                onDiagnostic = {
                    val existing = capturedZip
                    if (existing != null) {
                        shareZipFile(context, existing)
                    } else {
                        fullDiagnosticArmed.set(true)
                        diagnosticArmedAt.set(System.currentTimeMillis())
                        diagnosticState = "ARMED - waiting for fresh FULL_DETECTION"
                    }
                },
                onCopyReport = {
                    clipboard.setText(AnnotatedString(buildCompactDiagnosticReport(result, cameraBindingState, diagnosticLogger)))
                },
                selectedTab = labTab,
                onTabSelected = { labTab = it },
                onClose = { showLab = false },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(12.dp),
            )
        } else if (scannerMode == ScannerMode.V7_CAPACITY_LAB) {
            V7ScannerPanel(
                result = result,
                v7LastMetrics = v7LastMetrics,
                v7ProfileName = v7ProfileName,
                v7ExpectedFrame = v7ExpectedFrame,
                v7CalibrationLabel = v7CalibrationLabel,
                v7ConfidenceSummary = v7ConfidenceSummary,
                v7SamplerMode = v7SamplerMode,
                frameCount = v7FrameCount,
                v7AnalysisMs = v7AnalysisMs,
                v7CameraFps = v7CameraFps,
                v7AnalysisFps = v7AnalysisFps,
                v7Receiver = v7Receiver,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(12.dp),
            )
        } else {
            ReceiverSummary(
                result = result,
                accumulator = accumulator,
                cameraBindingState = cameraBindingState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp),
            )
        }
    }
}

@Composable
private fun ScannerOverlay(
    geometry: V6PreviewOverlayGeometry?,
    guidance: String,
    guidanceColor: Color,
    cameraAligned: Boolean,
    showDataGrid: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Canvas(Modifier.fillMaxSize()) {
            // Recommendation guide only. It deliberately does not pretend to be a
            // detector crop. The detector sees the entire visible PreviewView FOV.
            val side = size.minDimension * 0.72f
            val left = (size.width - side) / 2f
            val top = (size.height - side) / 2f
            val corner = side * 0.12f
            val guideColor = Color.White.copy(alpha = 0.42f)
            val sw = 2.dp.toPx()

            drawLine(guideColor, Offset(left, top), Offset(left + corner, top), sw)
            drawLine(guideColor, Offset(left, top), Offset(left, top + corner), sw)
            drawLine(guideColor, Offset(left + side, top), Offset(left + side - corner, top), sw)
            drawLine(guideColor, Offset(left + side, top), Offset(left + side, top + corner), sw)
            drawLine(guideColor, Offset(left, top + side), Offset(left + corner, top + side), sw)
            drawLine(guideColor, Offset(left, top + side), Offset(left, top + side - corner), sw)
            drawLine(guideColor, Offset(left + side, top + side), Offset(left + side - corner, top + side), sw)
            drawLine(guideColor, Offset(left + side, top + side), Offset(left + side, top + side - corner), sw)

            val g = geometry
            if (g != null && g.outerQuad.size == 4) {
                val exactColor = if (g.isFresh) Color(0xFF00E676) else Color(0xFFFFA726)
                for (i in 0 until 4) {
                    drawLine(
                        exactColor,
                        g.outerQuad[i],
                        g.outerQuad[(i + 1) % 4],
                        3.dp.toPx(),
                    )
                }

                // V6 20x20 payload grid — only shown in V6 mode.
                // In V7 mode the grid is misleading (V7 uses different cell count).
                if (g.isFresh && showDataGrid) {
                    val gridColor = Color(0xFF00E676).copy(alpha = 0.32f)
                    for ((a, b) in g.gridSegments) {
                        drawLine(gridColor, a, b, 0.8.dp.toPx())
                    }
                }
                // Pilot dots always shown.
                if (g.isFresh) {
                    for (pilot in g.pilotPoints) {
                        drawCircle(Color.Magenta.copy(alpha = 0.9f), 3.dp.toPx(), pilot)
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 58.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.72f),
            ) {
                Text(
                    guidance,
                    color = guidanceColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            Text(
                if (cameraAligned) "Green outline + matrix = exactly what SuperQR sees" else "Preparing aligned camera…",
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 10.sp,
            )
            Text(
                "Keep the whole outer border visible",
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun ReceiverSummary(
    result: V6StaticResult?,
    accumulator: V6SessionAccumulator,
    cameraBindingState: String,
    modifier: Modifier = Modifier,
) {
    val fresh = result?.diagnosticPayload?.classificationSource?.let { it == "FULL_DETECTION" || it == "TRACKED_RESAMPLED" } == true
    val validFrame = fresh && result?.transportFrame != null
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.78f)),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Camera: $cameraBindingState", color = if (cameraBindingState == "WYSIWYG") Color(0xFF4CAF50) else Color.White, fontSize = 11.sp)
                Text(
                    result?.diagnosticPayload?.classificationSource?.replace("_", " ") ?: "SEARCHING",
                    color = if (fresh) Color(0xFF4CAF50) else Color(0xFFFFB74D),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            val sid = accumulator.getCurrentSessionId()
            val total = accumulator.getTotalFrames()
            Text(
                if (sid == -1) "Waiting for V6 frames" else "Session $sid • ${accumulator.getUniqueFrames()}/$total frames • missing ${accumulator.getMissingFramesCount()}",
                color = Color.White,
                fontSize = 12.sp,
            )
            Text(
                if (validFrame) "CRC16 PASS • Frame ${result?.transportFrameId}" else "Last camera frame: ${if (fresh) "fresh, no valid transport frame" else "not fresh"}",
                color = if (validFrame) Color(0xFF4CAF50) else Color.White.copy(alpha = 0.65f),
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun ScannerLabPanel(
    result: V6StaticResult?,
    accumulator: V6SessionAccumulator,
    selectedMode: String,
    onModeChanged: (String) -> Unit,
    focusState: String,
    onLockFocus: () -> Unit,
    onUnlockFocus: () -> Unit,
    diagnosticState: String,
    hasCapturedZip: Boolean,
    onDiagnostic: () -> Unit,
    onCopyReport: () -> Unit,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.90f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TabRow(selectedTabIndex = selectedTab, modifier = Modifier.weight(1f), containerColor = Color.Transparent) {
                    listOf("TEST", "LIVE", "TOOLS").forEachIndexed { index, title ->
                        Tab(selected = selectedTab == index, onClick = { onTabSelected(index) }, text = { Text(title, fontSize = 10.sp) })
                    }
                }
                TextButton(onClick = onClose) { Text("X", color = Color.White) }
            }

            when (selectedTab) {
                0 -> {
                    Text("Static test pattern", color = Color.White, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf(
                            "Random" to "deterministic_random",
                            "Checker" to "checkerboard",
                            "Black" to "black",
                            "White" to "white",
                        ).forEach { (label, mode) ->
                            FilterChip(
                                selected = selectedMode == mode,
                                onClick = { onModeChanged(mode) },
                                label = { Text(label, fontSize = 9.sp) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                1 -> {
                    val source = result?.diagnosticPayload?.classificationSource ?: "—"
                    val fresh = source == "FULL_DETECTION" || source == "TRACKED_RESAMPLED"
                    Text("Receiving V6", color = Color.White, fontWeight = FontWeight.Bold)
                    Text("Source: $source • Fresh: ${if (fresh) "YES" else "NO"}", color = if (fresh) Color(0xFF4CAF50) else Color(0xFFFFB74D), fontSize = 11.sp)
                    Text("Session: ${accumulator.getCurrentSessionId().takeIf { it != -1 } ?: "—"}", color = Color.White, fontSize = 11.sp)
                    Text("Frames: ${accumulator.getUniqueFrames()} / ${accumulator.getTotalFrames().takeIf { it != -1 } ?: "—"}", color = Color.White, fontSize = 11.sp)
                    Text("Duplicates: ${accumulator.getDuplicateCount()} • Conflicts: ${accumulator.getConflictCount()}", color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp)
                    Text("Transport: ${if (fresh && result?.transportFrame != null) "CRC16 PASS" else "—"}", color = Color.White, fontSize = 11.sp)
                }
                else -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = onLockFocus, modifier = Modifier.weight(1f)) { Text("Focus & Lock", fontSize = 10.sp) }
                        Button(onClick = onUnlockFocus, modifier = Modifier.weight(1f)) { Text("Unlock", fontSize = 10.sp) }
                    }
                    Text("Focus: $focusState", color = Color.White, fontSize = 11.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = onDiagnostic, modifier = Modifier.weight(1f)) {
                            Text(if (hasCapturedZip) "Share ZIP" else "Capture ZIP", fontSize = 10.sp)
                        }
                        Button(onClick = onCopyReport, modifier = Modifier.weight(1f)) { Text("Copy Report", fontSize = 10.sp) }
                    }
                    Text("Diagnostic: $diagnosticState", color = Color.White.copy(alpha = 0.72f), fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun TransferCompleteScreen(
    completed: CompletedUiTransfer,
    stats: V6TransferStats?,
    onScanAnother: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pkg = completed.pkg
    Box(modifier.fillMaxSize().background(Color.Black).padding(18.dp)) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("TRANSFER COMPLETE", color = Color(0xFF4CAF50), fontSize = 24.sp, fontWeight = FontWeight.Bold)

            Card(colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.09f))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(pkg.filename, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("${pkg.fileSize} B", color = Color.White.copy(alpha = 0.8f))
                    Text("Session ${completed.sessionId}", color = Color.White.copy(alpha = 0.8f))
                    Text("Frames ${completed.totalFrames}/${completed.totalFrames}", color = Color.White.copy(alpha = 0.8f))
                    Text("File CRC32 PASS • 0x${"%08X".format(completed.fileCrc32)}", color = Color(0xFF4CAF50))
                    Text("Temporary app cache", color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
                }
            }

            // ── Transfer stats (debug / diagnostic only) ──
            val s = stats
            if (s != null) {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.07f))) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        // Render each line from formatSummary with section-header lines in bold/color.
                        for (line in s.formatSummary().lines()) {
                            val styledColor = when {
                                line.startsWith("──") -> Color(0xFF89B4FA)
                                else -> Color.White.copy(alpha = 0.72f)
                            }
                            val styledWeight = when {
                                line.startsWith("──") -> FontWeight.Bold
                                else -> FontWeight.Normal
                            }
                            Text(line, color = styledColor, fontSize = 10.sp, fontWeight = styledWeight)
                        }
                    }
                }
            }

            if (stats == null) {
                Text("No transfer stats collected", color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp)
            }

            Text("Decoded preview", color = Color.White, fontWeight = FontWeight.Bold)
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 360.dp)
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Text(
                    V6ReceiveUtils.generatePreview(pkg.fileData),
                    color = Color.White.copy(alpha = 0.82f),
                    fontSize = 12.sp
                )
            }
            Button(onClick = onScanAnother, modifier = Modifier.fillMaxWidth()) { Text("Scan Another") }
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(shape = CircleShape, color = color) {
        Text(text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp))
    }
}

private fun shareZipFile(context: Context, zipFile: File) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", zipFile)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    putExtra(Intent.EXTRA_STREAM, uri)
                    type = "application/zip"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Share V6 Full Diagnostic ZIP",
            )
        )
    } catch (e: Throwable) {
        Log.e("V6StaticScreen", "Share ZIP failed", e)
    }
}

private fun buildCompactDiagnosticReport(
    result: V6StaticResult?,
    cameraBindingState: String,
    logger: V6DiagnosticLogger,
): String = buildString {
    appendLine("=== SUPERQR V6 LIVE REPORT ===")
    appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
    appendLine("Camera binding: $cameraBindingState")
    if (result == null) {
        appendLine("Result: none")
    } else {
        val source = result.diagnosticPayload?.classificationSource ?: "N/A"
        appendLine("Tracking: ${result.trackingState}")
        appendLine("Classification source: $source")
        appendLine("Fresh: ${source == "FULL_DETECTION" || source == "TRACKED_RESAMPLED"}")
        appendLine("Border: ${result.borderFound}")
        appendLine("Orientation: ${result.orientationResolved}")
        appendLine("Quad: ${result.detectedQuad?.joinToString(" ") { "(${"%.1f".format(it[0])},${"%.1f".format(it[1])})" }}")
        appendLine("Normalized analysis: ${result.diagnosticPayload?.normalizedAnalysisWidth}x${result.diagnosticPayload?.normalizedAnalysisHeight}")
        val transportValid = (source == "FULL_DETECTION" || source == "TRACKED_RESAMPLED") && result.transportFrame != null
        appendLine("Fresh transport valid: $transportValid")
        if (transportValid) {
            appendLine("Session: ${result.transportSessionId}")
            appendLine("Frame: ${result.transportFrameId}/${result.transportTotalFrames}")
            appendLine("CRC16: ${result.transportCrc16Hex}")
        }
    }
    appendLine("--- LOGS ---")
    logger.getLogs().takeLast(40).forEach { log ->
        appendLine("[${log.timestamp}] ${log.level}/${log.category}: ${log.message}")
    }
}

@Deprecated(
    "Use SuperQRScannerScreen instead. V6StaticScreen is a backward-compatible alias.",
    ReplaceWith("SuperQRScannerScreen(analysisExecutor, lifecycleOwner, modifier, onBack, diagnosticLogger)")
)
@Composable
fun V6StaticScreen(
    analysisExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() },
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    diagnosticLogger: V6DiagnosticLogger = remember { V6DiagnosticLogger() },
) = SuperQRScannerScreen(analysisExecutor, lifecycleOwner, modifier, onBack, diagnosticLogger)
