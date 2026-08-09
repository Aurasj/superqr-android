package com.superqr.android.ui.scanner

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Range
import android.view.Surface
import android.widget.Toast
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
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
import com.superqr.android.camera.V7MeasurementTracker
import com.superqr.android.ui.v6.V6PreviewOverlayGeometry
import com.superqr.android.ui.v6.V6PreviewOverlayMapper
import com.superqr.android.vision.v6.contract.V6Contract
import com.superqr.android.vision.v6.detection.V6StaticDetector
import com.superqr.android.vision.v6.model.V6StaticResult
import com.superqr.android.vision.v6.transport.V6ReceiveUtils
import com.superqr.android.vision.v7.transport.*
import com.superqr.android.vision.v7_capacity_lab.V7HighDensitySampler
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private enum class AdaptiveScannerState { IDLE, STARTING, SCANNING, COMPLETE, ERROR }
private data class AdaptiveCompleted(val pkg: V7TransferPackage, val file: File, val session: Int, val frames: Int)

/** One production scanner: V6-proven geometry + adaptive V7 payload. */
@Composable
fun AdaptiveSuperQRScannerScreen(
    analysisExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() },
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    LaunchedEffect(Unit) { if (!permission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    var state by remember { mutableStateOf(AdaptiveScannerState.IDLE) }
    var cameraState by remember { mutableStateOf("IDLE") }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var boundConfig by remember { mutableStateOf<SessionConfig?>(null) }
    var detectorRef by remember { mutableStateOf<V6StaticDetector?>(null) }
    var receiverRef by remember { mutableStateOf<V7TransferReceiver?>(null) }
    val generation = remember { AtomicInteger(0) }
    val accumulator = remember { V7SessionAccumulator() }
    val debugHistory = remember { V7DebugHistory(512) }
    val measurement = remember { V7MeasurementTracker() }
    val cameraDeliveredRate = remember { V7AnalysisRateAccumulator(64) }

    var result by remember { mutableStateOf<V6StaticResult?>(null) }
    var carrierOverlay by remember { mutableStateOf<V6PreviewOverlayGeometry?>(null) }
    var debugOverlay by remember { mutableStateOf<V7DebugOverlayGeometry?>(null) }
    var debugSnapshot by remember { mutableStateOf<V7DebugSnapshot?>(null) }
    var completed by remember { mutableStateOf<AdaptiveCompleted?>(null) }

    var activeProfile by remember { mutableStateOf(V7OpticalProfiles.default) }
    var calibrated by remember { mutableIntStateOf(0) }
    var validSamples by remember { mutableIntStateOf(0) }
    var confidentCells by remember { mutableIntStateOf(0) }
    var erasures by remember { mutableIntStateOf(0) }
    var lastFrame by remember { mutableIntStateOf(-1) }
    var lastError by remember { mutableStateOf<String?>(null) }
    var latestTransport by remember { mutableStateOf<V7TransportDiagnostics?>(null) }

    var analyzedFrames by remember { mutableIntStateOf(0) }
    var skippedErasures by remember { mutableIntStateOf(0) }
    var headerValidCount by remember { mutableIntStateOf(0) }
    var headerInvalidCount by remember { mutableIntStateOf(0) }
    var packAttempts by remember { mutableIntStateOf(0) }
    var crcAttempts by remember { mutableIntStateOf(0) }
    var crcCandidateAttempts by remember { mutableIntStateOf(0) }
    var crcPass by remember { mutableIntStateOf(0) }
    var crcFail by remember { mutableIntStateOf(0) }
    var parserRejects by remember { mutableIntStateOf(0) }
    var temporalRecoveredFrames by remember { mutableIntStateOf(0) }

    var measurementRunId by remember { mutableStateOf("") }
    var cameraFps by remember { mutableDoubleStateOf(0.0) }
    var analysisFps by remember { mutableDoubleStateOf(0.0) }
    var pipelineMs by remember { mutableDoubleStateOf(0.0) }
    var analysisMs by remember { mutableDoubleStateOf(0.0) }
    var profileMs by remember { mutableDoubleStateOf(0.0) }
    var samplingMs by remember { mutableDoubleStateOf(0.0) }
    var classificationMs by remember { mutableDoubleStateOf(0.0) }
    var transportMs by remember { mutableDoubleStateOf(0.0) }
    var usefulUniqueFps by remember { mutableDoubleStateOf(0.0) }
    var acceptedPayloadBytes by remember { mutableLongStateOf(0L) }
    var decodedPayloadKiBs by remember { mutableDoubleStateOf(0.0) }
    var measurementElapsedMs by remember { mutableDoubleStateOf(0.0) }
    var analysisExceptionCount by remember { mutableIntStateOf(0) }
    var lastAnalysisException by remember { mutableStateOf<String?>(null) }

    var showDebug by remember { mutableStateOf(false) }
    var overlayMode by remember { mutableStateOf(V7DebugOverlayMode.LIVE) }
    var focusState by remember { mutableStateOf("AUTO") }
    var forcedProfile by remember { mutableIntStateOf(-1) }
    var samplerMode by remember { mutableStateOf(V7HighDensitySampler.ProbeMode.CROSS_5) }

    var frozenBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var frozenOverlay by remember { mutableStateOf<V7DebugOverlayGeometry?>(null) }
    var frozenSnapshot by remember { mutableStateOf<V7DebugSnapshot?>(null) }

    fun resetLive() {
        accumulator.reset(); debugHistory.reset(); measurement.reset(); cameraDeliveredRate.reset()
        result = null; carrierOverlay = null; debugOverlay = null; debugSnapshot = null; completed = null
        activeProfile = V7OpticalProfiles.default; calibrated = 0; validSamples = 0; confidentCells = 0; erasures = 0
        lastFrame = -1; lastError = null; latestTransport = null
        analyzedFrames = 0; skippedErasures = 0; headerValidCount = 0; headerInvalidCount = 0; packAttempts = 0
        crcAttempts = 0; crcCandidateAttempts = 0; crcPass = 0; crcFail = 0; parserRejects = 0; temporalRecoveredFrames = 0
        val ms = measurement.snapshot()
        measurementRunId = ms.runId
        cameraFps = 0.0; analysisFps = 0.0; pipelineMs = 0.0; analysisMs = 0.0
        profileMs = 0.0; samplingMs = 0.0; classificationMs = 0.0; transportMs = 0.0
        usefulUniqueFps = 0.0; acceptedPayloadBytes = 0L; decodedPayloadKiBs = 0.0; measurementElapsedMs = 0.0
        analysisExceptionCount = 0; lastAnalysisException = null
        frozenBitmap = null; frozenOverlay = null; frozenSnapshot = null
    }

    fun releaseCamera() {
        val detector = detectorRef
        detectorRef = null; receiverRef = null; generation.incrementAndGet(); result = null; carrierOverlay = null
        try {
            val provider = ProcessCameraProvider.getInstance(context).get()
            boundConfig?.let { provider.unbind(it) }
        } catch (_: Throwable) {}
        boundConfig = null; boundCamera = null
        if (detector != null) analysisExecutor.execute { try { detector.close() } catch (_: Throwable) {} }
    }

    fun startScan() {
        if (!permission) { permissionLauncher.launch(Manifest.permission.CAMERA); return }
        releaseCamera(); resetLive(); generation.incrementAndGet(); state = AdaptiveScannerState.STARTING; cameraState = "STARTING"
    }

    fun stopScan() { releaseCamera(); state = AdaptiveScannerState.IDLE; cameraState = "STOPPED" }

    fun lockFocus() {
        val camera = boundCamera ?: return
        if (previewView.width <= 0 || previewView.height <= 0) return
        focusState = "LOCKING"
        try {
            val p = previewView.meteringPointFactory.createPoint(previewView.width / 2f, previewView.height / 2f)
            val action = FocusMeteringAction.Builder(p, FocusMeteringAction.FLAG_AF).disableAutoCancel().build()
            val future = camera.cameraControl.startFocusAndMetering(action)
            future.addListener({ focusState = try { if (future.get().isFocusSuccessful) "LOCKED" else "FAILED" } catch (_: Throwable) { "FAILED" } }, mainExecutor)
        } catch (_: Throwable) { focusState = "FAILED" }
    }

    fun unlockFocus() {
        try { boundCamera?.cameraControl?.cancelFocusAndMetering() } catch (_: Throwable) {}
        focusState = "AUTO"
    }

    fun buildReport(): V7DebugExporter.Report = V7DebugExporter.Report(
        measurementSchemaVersion = V7MeasurementTracker.SCHEMA_VERSION,
        runId = measurementRunId,
        profile = activeProfile,
        cameraState = cameraState,
        trackingState = result?.trackingState ?: "—",
        classificationSource = result?.diagnosticPayload?.classificationSource ?: "—",
        borderFound = result?.borderFound ?: false,
        orientationResolved = result?.orientationResolved ?: false,
        detectorMs = result?.processingTimeMs ?: 0.0,
        calibrated = calibrated,
        validSamples = validSamples,
        confidentCells = confidentCells,
        rawErasures = erasures,
        analyzedFrames = analyzedFrames,
        skippedErasures = skippedErasures,
        headerValid = headerValidCount,
        headerInvalid = headerInvalidCount,
        packAttempts = packAttempts,
        crcAttempts = crcAttempts,
        crcCandidateAttempts = crcCandidateAttempts,
        crcPass = crcPass,
        crcFail = crcFail,
        parserRejects = parserRejects,
        temporalRecoveredFrames = temporalRecoveredFrames,
        uniqueFrames = accumulator.getUniqueFrames(),
        duplicates = accumulator.getDuplicateCount(),
        conflicts = accumulator.getConflictCount(),
        cameraFps = cameraFps,
        analysisFps = analysisFps,
        pipelineMs = pipelineMs,
        analysisMs = analysisMs,
        profileMs = profileMs,
        samplingMs = samplingMs,
        classificationMs = classificationMs,
        transportMs = transportMs,
        usefulUniqueFps = usefulUniqueFps,
        acceptedPayloadBytes = acceptedPayloadBytes,
        decodedPayloadKiBs = decodedPayloadKiBs,
        measurementElapsedMs = measurementElapsedMs,
        analysisExceptionCount = analysisExceptionCount,
        lastAnalysisException = lastAnalysisException,
        focusState = focusState,
        lastError = lastError,
    )

    fun capture(share: Boolean = false) {
        val a = activity ?: return
        V7DebugExporter.captureWindow(a) { res ->
            res.onSuccess { file ->
                if (share) V7DebugExporter.shareFile(context, file, "image/png")
                else Toast.makeText(context, "Captured ${file.name}", Toast.LENGTH_SHORT).show()
            }.onFailure { Toast.makeText(context, "Capture failed: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    fun toggleFreeze() {
        if (frozenBitmap != null) {
            frozenBitmap = null; frozenOverlay = null; frozenSnapshot = null
            return
        }
        val bitmap = previewView.bitmap?.copy(Bitmap.Config.ARGB_8888, false)
        if (bitmap == null) {
            Toast.makeText(context, "Preview not ready to freeze", Toast.LENGTH_SHORT).show()
        } else {
            frozenBitmap = bitmap
            frozenOverlay = debugOverlay
            frozenSnapshot = debugSnapshot
        }
    }

    fun clearOpticalOverlay() {
        overlayMode = V7DebugOverlayMode.LIVE
        frozenBitmap = null; frozenOverlay = null; frozenSnapshot = null
    }

    fun exportDebugBundle() {
        val a = activity ?: return
        V7DebugExporter.captureWindow(a) { res ->
            val screen = res.getOrNull()
            try {
                val preview = frozenBitmap ?: previewView.bitmap
                val zip = V7DebugExporter.exportBundle(
                    context = context,
                    screenCapture = screen,
                    previewBitmap = preview,
                    report = buildReport(),
                    snapshot = frozenSnapshot ?: debugSnapshot,
                    events = debugHistory.snapshot(),
                    frameSummaries = debugHistory.summaries(),
                )
                V7DebugExporter.shareFile(context, zip, "application/zip")
            } catch (t: Throwable) {
                Toast.makeText(context, "Export failed: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun bindCamera(provider: ProcessCameraProvider, pv: PreviewView) {
        try {
            try { provider.unbindAll() } catch (_: Throwable) {}
            val rotation = pv.display?.rotation ?: Surface.ROTATION_0
            val preview = Preview.Builder().setTargetRotation(rotation).build().also { it.surfaceProvider = pv.surfaceProvider }
            val analysis = ImageAnalysis.Builder().setTargetRotation(rotation).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            val viewPort = pv.viewPort ?: run { state = AdaptiveScannerState.ERROR; cameraState = "ERROR • VIEWPORT"; return }

            val info = try { provider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA) } catch (_: Throwable) { null }
            var ranges = emptySet<Range<Int>>()
            if (info != null) try {
                val probe = SessionConfig.Builder(listOf(preview, analysis)).apply { setViewPort(viewPort) }.build()
                ranges = info.getSupportedFrameRateRanges(probe)
            } catch (_: Throwable) {}
            val chosen = when {
                ranges.any { it.lower == 60 && it.upper == 60 } -> Range(60, 60)
                ranges.any { it.upper == 60 } -> ranges.filter { it.upper == 60 }.maxByOrNull { it.lower }
                ranges.any { it.lower == 30 && it.upper == 30 } -> Range(30, 30)
                else -> null
            }
            val config = SessionConfig.Builder(listOf(preview, analysis)).apply { setViewPort(viewPort); if (chosen != null) setFrameRateRange(chosen) }.build()

            val detector = V6StaticDetector()
            val receiver = V7TransferReceiver().also {
                it.forcedProfileId = forcedProfile.takeIf { id -> id >= 0 }
                it.probeMode = samplerMode
            }
            detectorRef = detector; receiverRef = receiver
            val luma = LumaFrameBuffer(); val chroma = ChromaSampleBuffers()
            val transforms = ImageProxyTransformFactory().apply { setUsingCropRect(true); setUsingRotationDegrees(true) }

            analysis.setAnalyzer(analysisExecutor) { image ->
                val gen = generation.get()
                val sensorTs = image.imageInfo.timestamp
                val arrival = System.nanoTime()
                cameraDeliveredRate.recordCompletion(sensorTs)
                val deliveredFps = cameraDeliveredRate.computeFps(sensorTs)
                try {
                    val d = detectorRef ?: return@setAnalyzer
                    val r = receiverRef ?: return@setAnalyzer
                    r.forcedProfileId = forcedProfile.takeIf { it >= 0 }
                    r.probeMode = samplerMode
                    r.debugSnapshotEnabled = showDebug || overlayMode != V7DebugOverlayMode.LIVE || frozenBitmap != null
                    if (!luma.packFrom(image)) return@setAnalyzer
                    val chromaReader = ImageProxyChromaSampler(image, chroma)
                    val detected = d.detect(luma.bytes, luma.width, luma.height, "deterministic_random", chromaReader).copy(analyzerArrivalNs = arrival)
                    val decoded = r.analyze(detected, luma.bytes, luma.width, luma.height, chromaReader)
                    val sourceTx = try { transforms.getOutputTransform(image) } catch (_: Throwable) { null }
                    val accepted = decoded.acceptedFrame
                    if (accepted != null) measurement.recordAccepted(accepted.sessionId, accepted.frameId, accepted.payload.size)
                    var pkg: V7TransferPackage? = null
                    if (accepted != null) pkg = try { accumulator.addFrame(accepted) } catch (_: Throwable) { null }

                    val completedNs = System.nanoTime()
                    measurement.recordAnalysis(
                        completedNs = completedNs,
                        pipelineMs = (completedNs - arrival) / 1_000_000.0,
                        detectorMs = detected.processingTimeMs.toDouble(),
                        v7TotalMs = decoded.timing.totalUs / 1000.0,
                        v7ProfileMs = decoded.timing.profileUs / 1000.0,
                        v7SamplingMs = decoded.timing.samplingUs / 1000.0,
                        v7ClassificationMs = decoded.timing.classificationUs / 1000.0,
                        v7TransportMs = decoded.timing.transportUs / 1000.0,
                    )
                    val ms = measurement.snapshot(completedNs)

                    mainExecutor.execute {
                        if (gen != generation.get()) return@execute
                        result = detected
                        carrierOverlay = if (sourceTx != null) pv.outputTransform?.let { V6PreviewOverlayMapper.map(detected, sourceTx, it) } else null
                        activeProfile = decoded.profile; calibrated = decoded.calibratedCount; validSamples = decoded.validSamples
                        confidentCells = decoded.confidentCells; erasures = decoded.erasureCount; latestTransport = decoded.transport

                        measurementRunId = ms.runId
                        cameraFps = deliveredFps
                        analysisFps = ms.analysisFps
                        pipelineMs = ms.pipelineMs
                        analysisMs = ms.v7TotalMs
                        profileMs = ms.v7ProfileMs
                        samplingMs = ms.v7SamplingMs
                        classificationMs = ms.v7ClassificationMs
                        transportMs = ms.v7TransportMs
                        usefulUniqueFps = ms.usefulUniqueFps
                        acceptedPayloadBytes = ms.acceptedPayloadBytes
                        decodedPayloadKiBs = ms.decodedPayloadKiBs
                        measurementElapsedMs = ms.sessionElapsedMs
                        analysisExceptionCount = ms.analysisExceptionCount
                        lastAnalysisException = ms.lastAnalysisException
                        analyzedFrames = ms.analysisCompleted

                        val td = decoded.transport
                        debugHistory.record(analyzedFrames, decoded.profile, td, accepted)
                        if (td.headerAttempted) {
                            if (td.headerValid) headerValidCount++ else headerInvalidCount++
                        }
                        if (!td.packAttempted && td.rawErasures > 0) skippedErasures++
                        if (td.packAttempted) packAttempts++
                        if (td.crcAttempted) {
                            crcAttempts++
                            crcCandidateAttempts += td.crcCandidateAttempts
                            if (td.crcPassed) crcPass++
                            else if (td.rejectionReason?.contains("CRC32", ignoreCase = true) == true) crcFail++
                            else parserRejects++
                        }
                        if (td.crcPassed && td.candidatePassed?.startsWith("TEMPORAL") == true) temporalRecoveredFrames++
                        if (accepted != null) { lastFrame = accepted.frameId; lastError = null }
                        else lastError = td.rejectionReason

                        val opticalDebugActive = showDebug || overlayMode != V7DebugOverlayMode.LIVE
                        if (opticalDebugActive && frozenBitmap == null && sourceTx != null && decoded.debugSnapshot != null) {
                            val target = pv.outputTransform
                            if (target != null) {
                                debugSnapshot = decoded.debugSnapshot
                                debugOverlay = V7DebugOverlayMapper.map(detected, sourceTx, target, decoded.profile, decoded.debugSnapshot)
                            }
                        }

                        if (pkg != null && accepted != null) {
                            val safe = V6ReceiveUtils.sanitizeFilename(pkg.filename)
                            val dir = File(context.cacheDir, "superqr_received").apply { mkdirs() }
                            val out = File(dir, safe); out.writeBytes(pkg.fileData)
                            completed = AdaptiveCompleted(pkg, out, accepted.sessionId, accepted.totalFrames)
                            releaseCamera(); state = AdaptiveScannerState.COMPLETE; cameraState = "STOPPED"
                        }
                    }
                } catch (t: Throwable) {
                    measurement.recordException(t)
                    val ms = measurement.snapshot(System.nanoTime())
                    mainExecutor.execute {
                        if (gen != generation.get()) return@execute
                        measurementRunId = ms.runId
                        analysisExceptionCount = ms.analysisExceptionCount
                        lastAnalysisException = ms.lastAnalysisException
                    }
                } finally { image.close() }
            }

            try {
                boundCamera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, config)
                boundConfig = config; cameraState = if (chosen != null) "CAMERA ${chosen.lower}-${chosen.upper} FPS" else "CAMERA AUTO"; state = AdaptiveScannerState.SCANNING
            } catch (_: Throwable) {
                val fallback = SessionConfig.Builder(listOf(preview, analysis)).apply { setViewPort(viewPort) }.build()
                boundCamera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, fallback)
                boundConfig = fallback; cameraState = "CAMERA FALLBACK"; state = AdaptiveScannerState.SCANNING
            }
        } catch (e: Throwable) { state = AdaptiveScannerState.ERROR; cameraState = "ERROR • ${e.message ?: "camera"}" }
    }

    if (state == AdaptiveScannerState.STARTING) LaunchedEffect(Unit) {
        mainExecutor.execute {
            val provider = try { ProcessCameraProvider.getInstance(context).get() } catch (_: Throwable) { state = AdaptiveScannerState.ERROR; return@execute }
            try { V6Contract.loadAndVerify(context) } catch (_: Throwable) {}
            previewView.doOnLayout { bindCamera(provider, previewView) }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP && state in listOf(AdaptiveScannerState.STARTING, AdaptiveScannerState.SCANNING)) stopScan() }
        lifecycleOwner.lifecycle.addObserver(obs); onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    DisposableEffect(Unit) { onDispose { releaseCamera() } }
    DisposableEffect(state) {
        try { if (state == AdaptiveScannerState.SCANNING) activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (_: Throwable) {}
        onDispose { try { activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (_: Throwable) {} }
    }

    val done = completed
    if (state == AdaptiveScannerState.COMPLETE && done != null) {
        AdaptiveCompleteScreen(done, { startScan() }, modifier); return
    }

    if (state == AdaptiveScannerState.IDLE || state == AdaptiveScannerState.ERROR) {
        Box(modifier.fillMaxSize().background(Color(0xFF090B10)), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("SuperQR", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text("V7 optical receiver", color = Color.White.copy(alpha = .62f))
                Text("40×40 / 4c baseline • measurement enabled", color = Color(0xFF7CB7FF), fontSize = 11.sp)
                if (state == AdaptiveScannerState.ERROR) Text(cameraState, color = Color(0xFFFF7B72), fontSize = 11.sp)
                Button(onClick = { startScan() }, modifier = Modifier.fillMaxWidth()) { Text("SCAN SUPERQR", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 7.dp)) }
                if (onBack != null) TextButton(onClick = onBack) { Text("Back") }
            }
        }; return
    }

    if (!permission) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Grant camera permission") }
        }
        return
    }

    val src = result?.diagnosticPayload?.classificationSource ?: "SEARCH"
    val fresh = src == "FULL_DETECTION" || src == "TRACKED_RESAMPLED"
    val hasSession = accumulator.getCurrentSessionId() != -1
    val remaining = latestTransport?.remainingErasures ?: erasures
    val guidance = when {
        hasSession -> "RECEIVING"
        result == null || result?.borderFound != true -> "CENTER SUPERQR"
        result?.orientationResolved != true -> "SHOW ALL 4 CORNERS"
        calibrated < activeProfile.colorCount -> "CALIBRATING ${activeProfile.colorCount} COLORS"
        fresh && remaining == 0 -> "LOCKED"
        fresh -> "HOLD STEADY"
        else -> "ALIGN SUPERQR"
    }
    val guideColor = if (guidance == "RECEIVING" || guidance == "LOCKED") Color(0xFF7EE787) else Color(0xFFF2CC60)
    val isFrozen = frozenBitmap != null
    val overlayToDraw = if (isFrozen) frozenOverlay else debugOverlay

    Box(modifier.fillMaxSize().background(Color.Black)) {
        val frozen = frozenBitmap
        if (frozen == null) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        } else {
            Image(bitmap = frozen.asImageBitmap(), contentDescription = "Frozen camera debug frame", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        V7ScannerOverlay(carrierOverlay, overlayToDraw, overlayMode, guidance, guideColor, Modifier.fillMaxSize())

        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 7.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("SuperQR", color = Color.White, fontWeight = FontWeight.Bold)
                Text(activeProfile.label, color = Color(0xFF7CB7FF), fontSize = 9.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { showDebug = !showDebug }, label = { Text(if (showDebug) "Hide" else "Debug", fontSize = 10.sp) })
                TextButton(onClick = { stopScan() }) { Text("Stop", color = Color.White) }
            }
        }

        if (showDebug) {
            val ui = V7DebugUiState(
                profile = activeProfile,
                cameraState = cameraState,
                trackingState = result?.trackingState ?: "—",
                classificationSource = src,
                borderFound = result?.borderFound ?: false,
                orientationResolved = result?.orientationResolved ?: false,
                detectorMs = result?.processingTimeMs ?: 0.0,
                calibrated = calibrated,
                validSamples = validSamples,
                confident = confidentCells,
                erasures = erasures,
                analyzed = analyzedFrames,
                skippedErasures = skippedErasures,
                headerValid = headerValidCount,
                headerInvalid = headerInvalidCount,
                packAttempts = packAttempts,
                crcAttempts = crcAttempts,
                crcCandidateAttempts = crcCandidateAttempts,
                crcPass = crcPass,
                crcFail = crcFail,
                parserRejects = parserRejects,
                temporalRecoveredFrames = temporalRecoveredFrames,
                cameraFps = cameraFps,
                analysisFps = analysisFps,
                pipelineMs = pipelineMs,
                analysisMs = analysisMs,
                profileMs = profileMs,
                samplingMs = samplingMs,
                classificationMs = classificationMs,
                transportMs = transportMs,
                usefulUniqueFps = usefulUniqueFps,
                decodedPayloadKiBs = decodedPayloadKiBs,
                analysisExceptionCount = analysisExceptionCount,
                lastAnalysisException = lastAnalysisException,
                unique = accumulator.getUniqueFrames(),
                duplicates = accumulator.getDuplicateCount(),
                conflicts = accumulator.getConflictCount(),
                focusState = focusState,
                forcedProfile = forcedProfile,
                samplerMode = samplerMode,
                latestTransport = (frozenSnapshot ?: debugSnapshot)?.transport ?: latestTransport,
            )
            V7DebugPanel(
                ui = ui,
                overlayMode = overlayMode,
                frozen = isFrozen,
                onOverlayMode = { overlayMode = it },
                onForceProfile = { forcedProfile = it; receiverRef?.forcedProfileId = it.takeIf { id -> id >= 0 } },
                onSampler = { samplerMode = it; receiverRef?.probeMode = it },
                onLock = ::lockFocus,
                onUnlock = ::unlockFocus,
                onCapture = { capture(false) },
                onShare = { capture(true) },
                onFreeze = ::toggleFreeze,
                onExport = ::exportDebugBundle,
                onClose = { showDebug = false },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(10.dp),
            )
        } else if (overlayMode != V7DebugOverlayMode.LIVE || isFrozen) {
            V7OverlayStatusBar(
                mode = overlayMode,
                frozen = isFrozen,
                confident = confidentCells,
                total = activeProfile.cellCount,
                crcPass = crcPass,
                crcFail = crcFail,
                onOpenDebug = { showDebug = true },
                onLive = ::clearOpticalOverlay,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(10.dp),
            )
        } else {
            V7ReceiveCard(
                activeProfile, accumulator, calibrated, confidentCells, erasures, lastFrame, lastError,
                crcPass, crcFail, skippedErasures, temporalRecoveredFrames, cameraFps, analysisFps,
                usefulUniqueFps, decodedPayloadKiBs,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
            )
        }
    }
}

@Composable
private fun AdaptiveCompleteScreen(done: AdaptiveCompleted, again: () -> Unit, modifier: Modifier) {
    Box(modifier.fillMaxSize().background(Color(0xFF090B10)).padding(18.dp)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("TRANSFER COMPLETE", color = Color(0xFF7EE787), fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Card(colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = .07f))) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(done.pkg.filename, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(done.pkg.mimeType, color = Color(0xFF7CB7FF), fontSize = 11.sp)
                    Text("${done.pkg.fileSize} B • ${done.frames} frames • session ${done.session}", color = Color.White.copy(alpha = .78f))
                    Text("CRC32 PASS • 0x${"%08X".format(done.pkg.fileCrc32)}", color = Color(0xFF7EE787))
                }
            }
            Text("Preview", color = Color.White, fontWeight = FontWeight.Bold)
            Surface(color = Color.White.copy(alpha = .05f), shape = RoundedCornerShape(10.dp)) {
                Text(V6ReceiveUtils.generatePreview(done.pkg.fileData), color = Color.White.copy(alpha = .8f), fontSize = 12.sp, modifier = Modifier.padding(12.dp))
            }
            Button(onClick = again, modifier = Modifier.fillMaxWidth()) { Text("Scan another") }
        }
    }
}
