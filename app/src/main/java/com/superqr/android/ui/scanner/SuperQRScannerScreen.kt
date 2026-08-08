package com.superqr.android.ui.scanner

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import com.superqr.android.ui.v6.V6PreviewOverlayGeometry
import com.superqr.android.ui.v6.V6PreviewOverlayMapper
import com.superqr.android.vision.v6.contract.V6Contract
import com.superqr.android.vision.v6.detection.V6StaticDetector
import com.superqr.android.vision.v6.model.V6StaticResult
import com.superqr.android.vision.v6.transport.V6ReceiveUtils
import com.superqr.android.vision.v7.transport.V7SessionAccumulator
import com.superqr.android.vision.v7.transport.V7TransferPackage
import com.superqr.android.vision.v7.transport.V7TransferReceiver
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private enum class ScannerState { IDLE, STARTING, SCANNING, COMPLETE, ERROR }

private data class CompletedTransfer(
    val pkg: V7TransferPackage,
    val cacheFile: File,
    val sessionId: Int,
    val totalFrames: Int,
)

/**
 * Single production SuperQR scanner.
 *
 * Product protocol is V7 only. The proven V6 detector remains the carrier
 * acquisition/tracking implementation under the hood; there is no V6/V7 mode
 * selector and no second CameraX stack.
 */
@Composable
fun SuperQRScannerScreen(
    analysisExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() },
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCameraPermission = it
    }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var scannerState by remember { mutableStateOf(ScannerState.IDLE) }
    var cameraBindingState by remember { mutableStateOf("IDLE") }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var boundSessionConfig by remember { mutableStateOf<SessionConfig?>(null) }
    var detectorRef by remember { mutableStateOf<V6StaticDetector?>(null) }
    var receiverRef by remember { mutableStateOf<V7TransferReceiver?>(null) }
    val accumulator = remember { V7SessionAccumulator() }
    val generation = remember { AtomicInteger(0) }

    var currentResult by remember { mutableStateOf<V6StaticResult?>(null) }
    var previewGeometry by remember { mutableStateOf<V6PreviewOverlayGeometry?>(null) }
    var completedTransfer by remember { mutableStateOf<CompletedTransfer?>(null) }

    var calibrationCount by remember { mutableIntStateOf(0) }
    var validSamples by remember { mutableIntStateOf(0) }
    var erasureCount by remember { mutableIntStateOf(1600) }
    var lastFrameId by remember { mutableIntStateOf(-1) }
    var lastTransportError by remember { mutableStateOf<String?>(null) }
    var analysisMs by remember { mutableDoubleStateOf(0.0) }
    var cameraFps by remember { mutableDoubleStateOf(0.0) }
    var analysisFps by remember { mutableDoubleStateOf(0.0) }
    var lastSensorTs by remember { mutableLongStateOf(0L) }
    val analysisRate = remember { V7AnalysisRateAccumulator(64) }

    var showDebug by remember { mutableStateOf(false) }
    var focusState by remember { mutableStateOf("AUTO") }

    fun resetLiveState() {
        accumulator.reset()
        currentResult = null
        previewGeometry = null
        completedTransfer = null
        calibrationCount = 0
        validSamples = 0
        erasureCount = 1600
        lastFrameId = -1
        lastTransportError = null
        analysisMs = 0.0
        cameraFps = 0.0
        analysisFps = 0.0
        lastSensorTs = 0L
        analysisRate.reset()
    }

    fun releaseCamera() {
        val detector = detectorRef
        detectorRef = null
        receiverRef = null
        generation.incrementAndGet()
        currentResult = null
        previewGeometry = null
        analysisRate.reset()
        lastSensorTs = 0L
        try {
            val provider = ProcessCameraProvider.getInstance(context).get()
            val config = boundSessionConfig
            if (config != null) provider.unbind(config)
        } catch (_: Throwable) {}
        boundSessionConfig = null
        boundCamera = null
        if (detector != null) {
            analysisExecutor.execute { try { detector.close() } catch (_: Throwable) {} }
        }
    }

    fun startScan() {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        releaseCamera()
        resetLiveState()
        generation.incrementAndGet()
        scannerState = ScannerState.STARTING
        cameraBindingState = "STARTING"
    }

    fun stopScan() {
        releaseCamera()
        scannerState = ScannerState.IDLE
        cameraBindingState = "STOPPED"
    }

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
                } catch (_: Throwable) { "FAILED" }
            }, mainExecutor)
        } catch (_: Throwable) {
            focusState = "FAILED"
        }
    }

    fun unlockFocus() {
        try { boundCamera?.cameraControl?.cancelFocusAndMetering() } catch (_: Throwable) {}
        focusState = "AUTO"
    }

    fun bindCamera(provider: ProcessCameraProvider, pv: PreviewView) {
        try {
            try { provider.unbindAll() } catch (_: Throwable) {}
            val rotation = pv.display?.rotation ?: Surface.ROTATION_0
            val preview = Preview.Builder().setTargetRotation(rotation).build().also {
                it.surfaceProvider = pv.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setTargetRotation(rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val viewPort = pv.viewPort
            if (viewPort == null) {
                scannerState = ScannerState.ERROR
                cameraBindingState = "ERROR: VIEWPORT"
                return
            }

            val cameraInfo = try { provider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA) } catch (_: Throwable) { null }
            var supported = emptySet<Range<Int>>()
            if (cameraInfo != null) {
                try {
                    val probe = SessionConfig.Builder(listOf(preview, analysis)).apply { setViewPort(viewPort) }.build()
                    supported = cameraInfo.getSupportedFrameRateRanges(probe)
                } catch (_: Throwable) {}
            }
            val chosen = when {
                supported.any { it.lower == 60 && it.upper == 60 } -> Range(60, 60)
                supported.any { it.upper == 60 } -> supported.filter { it.upper == 60 }.maxByOrNull { it.lower }
                supported.any { it.lower == 30 && it.upper == 30 } -> Range(30, 30)
                else -> null
            }

            val config = SessionConfig.Builder(listOf(preview, analysis)).apply {
                setViewPort(viewPort)
                if (chosen != null) setFrameRateRange(chosen)
            }.build()

            val detector = V6StaticDetector()
            val receiver = V7TransferReceiver()
            detectorRef = detector
            receiverRef = receiver
            val luma = LumaFrameBuffer()
            val chroma = ChromaSampleBuffers()
            val transformFactory = ImageProxyTransformFactory().apply {
                setUsingCropRect(true)
                setUsingRotationDegrees(true)
            }

            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                val myGeneration = generation.get()
                val sensorTs = imageProxy.imageInfo.timestamp
                val arrival = System.nanoTime()
                try {
                    val activeDetector = detectorRef ?: return@setAnalyzer
                    val activeReceiver = receiverRef ?: return@setAnalyzer
                    if (!luma.packFrom(imageProxy)) return@setAnalyzer
                    val chromaReader = ImageProxyChromaSampler(imageProxy, chroma)
                    val detected = activeDetector.detect(
                        luma = luma.bytes,
                        width = luma.width,
                        height = luma.height,
                        mode = "deterministic_random",
                        chromaReader = chromaReader,
                    ).copy(analyzerArrivalNs = arrival)

                    val decoded = activeReceiver.analyze(
                        geometry = detected,
                        lumaBytes = luma.bytes,
                        width = luma.width,
                        height = luma.height,
                        chromaReader = chromaReader,
                    )
                    val sourceTransform = try { transformFactory.getOutputTransform(imageProxy) } catch (_: Throwable) { null }
                    val accepted = decoded.acceptedFrame
                    var complete: V7TransferPackage? = null
                    if (accepted != null) {
                        complete = try { accumulator.addFrame(accepted) } catch (_: Throwable) { null }
                    }

                    mainExecutor.execute {
                        if (myGeneration != generation.get()) return@execute
                        currentResult = detected
                        previewGeometry = if (sourceTransform != null) {
                            pv.outputTransform?.let { V6PreviewOverlayMapper.map(detected, sourceTransform, it) }
                        } else null
                        calibrationCount = decoded.calibratedCount
                        validSamples = decoded.validSamples
                        erasureCount = decoded.erasureCount
                        analysisMs = decoded.timing.totalUs / 1000.0
                        lastTransportError = decoded.transportError
                        if (accepted != null) lastFrameId = accepted.frameId

                        if (lastSensorTs > 0L) {
                            val delta = (sensorTs - lastSensorTs) / 1_000_000_000.0
                            if (delta > 0) cameraFps = 1.0 / delta
                        }
                        lastSensorTs = sensorTs
                        analysisRate.recordCompletion(System.nanoTime())
                        analysisFps = analysisRate.computeFps()

                        if (complete != null) {
                            val safe = V6ReceiveUtils.sanitizeFilename(complete.filename)
                            val dir = File(context.cacheDir, "superqr_received").apply { mkdirs() }
                            val output = File(dir, safe)
                            output.writeBytes(complete.fileData)
                            completedTransfer = CompletedTransfer(
                                pkg = complete,
                                cacheFile = output,
                                sessionId = accepted?.sessionId ?: -1,
                                totalFrames = accepted?.totalFrames ?: -1,
                            )
                            releaseCamera()
                            scannerState = ScannerState.COMPLETE
                            cameraBindingState = "STOPPED"
                        }
                    }
                } catch (_: Throwable) {
                    // The live scanner tolerates bad optical frames; diagnostics remain visible.
                } finally {
                    imageProxy.close()
                }
            }

            try {
                boundCamera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, config)
                boundSessionConfig = config
                cameraBindingState = if (chosen != null) "WYSIWYG ${chosen.lower}-${chosen.upper} FPS" else "WYSIWYG"
                scannerState = ScannerState.SCANNING
            } catch (_: Throwable) {
                val fallback = SessionConfig.Builder(listOf(preview, analysis)).apply { setViewPort(viewPort) }.build()
                boundCamera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, fallback)
                boundSessionConfig = fallback
                cameraBindingState = "WYSIWYG FALLBACK"
                scannerState = ScannerState.SCANNING
            }
        } catch (e: Throwable) {
            scannerState = ScannerState.ERROR
            cameraBindingState = "ERROR: ${e.message ?: "camera"}"
        }
    }

    if (scannerState == ScannerState.STARTING) {
        LaunchedEffect(Unit) {
            mainExecutor.execute {
                val provider = try { ProcessCameraProvider.getInstance(context).get() } catch (_: Throwable) {
                    scannerState = ScannerState.ERROR
                    cameraBindingState = "ERROR: PROVIDER"
                    return@execute
                }
                try { V6Contract.loadAndVerify(context) } catch (_: Throwable) {}
                previewView.doOnLayout { bindCamera(provider, previewView) }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && scannerState in listOf(ScannerState.STARTING, ScannerState.SCANNING)) {
                stopScan()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) { onDispose { releaseCamera() } }
    DisposableEffect(scannerState) {
        try {
            val activity = context as? android.app.Activity
            if (scannerState == ScannerState.SCANNING) {
                activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        } catch (_: Throwable) {}
        onDispose {
            try { (context as? android.app.Activity)?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (_: Throwable) {}
        }
    }

    val complete = completedTransfer
    if (scannerState == ScannerState.COMPLETE && complete != null) {
        TransferCompleteScreen(
            completed = complete,
            onScanAnother = { startScan() },
            modifier = modifier,
        )
        return
    }

    if (scannerState == ScannerState.IDLE || scannerState == ScannerState.ERROR) {
        Box(modifier.fillMaxSize().background(Color(0xFF0B0B0F)), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(28.dp),
            ) {
                Text("SuperQR", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text("V7 offline optical receiver", color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
                Text("40×40 • 4 colors • proven V6 carrier tracking", color = Color(0xFF89B4FA), fontSize = 11.sp)
                if (scannerState == ScannerState.ERROR) {
                    Text(cameraBindingState, color = Color(0xFFF38BA8), fontSize = 11.sp)
                }
                Button(onClick = { startScan() }, modifier = Modifier.fillMaxWidth()) {
                    Text("SCAN SUPERQR", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 7.dp))
                }
                if (onBack != null) TextButton(onClick = onBack) { Text("Back") }
            }
        }
        return
    }

    if (!hasCameraPermission) {
        Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Grant Camera Permission") }
        }
        return
    }

    val result = currentResult
    val source = result?.diagnosticPayload?.classificationSource ?: "SEARCHING"
    val fresh = source == "FULL_DETECTION" || source == "TRACKED_RESAMPLED"
    val held = source == "TRACKED_HOMOGRAPHY"
    val hasSession = accumulator.getCurrentSessionId() != -1
    val guidance = when {
        hasSession -> "RECEIVING"
        result == null || !result.borderFound -> "CENTER SUPERQR"
        !result.orientationResolved -> "SHOW ALL 4 CORNERS"
        held -> "HOLD STEADY"
        fresh && calibrationCount == 4 -> "GOOD POSITION"
        fresh -> "CALIBRATING COLORS"
        else -> "ALIGN SUPERQR"
    }
    val guidanceColor = when (guidance) {
        "RECEIVING", "GOOD POSITION" -> Color(0xFF4CAF50)
        else -> Color(0xFFFFB74D)
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        ProductionOverlay(previewGeometry, guidance, guidanceColor, Modifier.fillMaxSize())

        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("SuperQR V7", color = Color.White, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { showDebug = !showDebug }, label = { Text("Debug", fontSize = 10.sp) })
                TextButton(onClick = { stopScan() }) { Text("Stop", color = Color.White) }
            }
        }

        if (showDebug) {
            DebugPanel(
                result = result,
                cameraBindingState = cameraBindingState,
                calibrationCount = calibrationCount,
                validSamples = validSamples,
                erasureCount = erasureCount,
                cameraFps = cameraFps,
                analysisFps = analysisFps,
                analysisMs = analysisMs,
                focusState = focusState,
                onLockFocus = ::lockFocus,
                onUnlockFocus = ::unlockFocus,
                onClose = { showDebug = false },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
            )
        } else {
            ReceiveCard(
                accumulator = accumulator,
                calibrationCount = calibrationCount,
                erasureCount = erasureCount,
                lastFrameId = lastFrameId,
                lastTransportError = lastTransportError,
                cameraFps = cameraFps,
                analysisFps = analysisFps,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(14.dp),
            )
        }
    }
}

@Composable
private fun ProductionOverlay(
    geometry: V6PreviewOverlayGeometry?,
    guidance: String,
    guidanceColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val side = size.minDimension * 0.72f
            val left = (size.width - side) / 2f
            val top = (size.height - side) / 2f
            val corner = side * 0.12f
            val guide = Color.White.copy(alpha = 0.35f)
            val sw = 2.dp.toPx()
            drawLine(guide, Offset(left, top), Offset(left + corner, top), sw)
            drawLine(guide, Offset(left, top), Offset(left, top + corner), sw)
            drawLine(guide, Offset(left + side, top), Offset(left + side - corner, top), sw)
            drawLine(guide, Offset(left + side, top), Offset(left + side, top + corner), sw)
            drawLine(guide, Offset(left, top + side), Offset(left + corner, top + side), sw)
            drawLine(guide, Offset(left, top + side), Offset(left, top + side - corner), sw)
            drawLine(guide, Offset(left + side, top + side), Offset(left + side - corner, top + side), sw)
            drawLine(guide, Offset(left + side, top + side), Offset(left + side, top + side - corner), sw)

            val g = geometry
            if (g != null && g.outerQuad.size == 4) {
                val exact = if (g.isFresh) Color(0xFF00E676) else Color(0xFFFFA726)
                for (i in 0 until 4) drawLine(exact, g.outerQuad[i], g.outerQuad[(i + 1) % 4], 3.dp.toPx())
                if (g.isFresh) for (pilot in g.pilotPoints) drawCircle(Color.Magenta, 3.dp.toPx(), pilot)
            }
        }
        Surface(
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 52.dp),
            shape = RoundedCornerShape(20.dp),
            color = Color.Black.copy(alpha = 0.72f),
        ) {
            Text(guidance, color = guidanceColor, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun ReceiveCard(
    accumulator: V7SessionAccumulator,
    calibrationCount: Int,
    erasureCount: Int,
    lastFrameId: Int,
    lastTransportError: String?,
    cameraFps: Double,
    analysisFps: Double,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.82f))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("V7 • 40×40 • CROSS_5", color = Color(0xFF89B4FA), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("CAL $calibrationCount/4", color = if (calibrationCount == 4) Color(0xFF4CAF50) else Color(0xFFFFB74D), fontSize = 10.sp)
            }
            val total = accumulator.getTotalFrames()
            val unique = accumulator.getUniqueFrames()
            Text(
                if (total > 0) "Receiving • $unique/$total frames • missing ${accumulator.getMissingFramesCount()}"
                else "Waiting for first CRC-valid V7 frame",
                color = Color.White,
                fontSize = 13.sp,
            )
            if (total > 0) LinearProgressIndicator(progress = { accumulator.getProgress().toFloat() }, modifier = Modifier.fillMaxWidth())
            Text(
                "Last frame: ${if (lastFrameId >= 0) lastFrameId else "—"} • erasures $erasureCount • ${lastTransportError ?: "CRC ready"}",
                color = if (lastTransportError == null) Color.White.copy(alpha = 0.72f) else Color(0xFFF38BA8),
                fontSize = 10.sp,
            )
            Text("Camera ${"%.1f".format(cameraFps)} fps • Analysis ${"%.1f".format(analysisFps)} fps",
                color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
        }
    }
}

@Composable
private fun DebugPanel(
    result: V6StaticResult?,
    cameraBindingState: String,
    calibrationCount: Int,
    validSamples: Int,
    erasureCount: Int,
    cameraFps: Double,
    analysisFps: Double,
    analysisMs: Double,
    focusState: String,
    onLockFocus: () -> Unit,
    onUnlockFocus: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.91f))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("DEBUG • V6 carrier + V7 payload", color = Color(0xFF89B4FA), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                TextButton(onClick = onClose) { Text("X") }
            }
            Text("Camera: $cameraBindingState", color = Color.White, fontSize = 10.sp)
            Text("Tracking: ${result?.trackingState ?: "—"} • Source: ${result?.diagnosticPayload?.classificationSource ?: "—"}", color = Color.White, fontSize = 10.sp)
            Text("Border: ${result?.borderFound ?: false} • Orientation: ${result?.orientationResolved ?: false}", color = Color.White, fontSize = 10.sp)
            Text("V6 detector: ${"%.2f".format(result?.processingTimeMs ?: 0.0)} ms • color ${result?.colorCorrect ?: 0}/${result?.colorTotal ?: 0} uncertain ${result?.colorUncertain ?: 0}", color = Color.White.copy(alpha = 0.75f), fontSize = 10.sp)
            Text("V7: samples $validSamples/1600 • erasures $erasureCount • calibration $calibrationCount/4", color = Color.White, fontSize = 10.sp)
            Text("Camera ${"%.1f".format(cameraFps)} fps • Analysis ${"%.1f".format(analysisFps)} fps • V7 ${"%.2f".format(analysisMs)} ms", color = Color.White, fontSize = 10.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onLockFocus, modifier = Modifier.weight(1f)) { Text("Focus & Lock", fontSize = 10.sp) }
                Button(onClick = onUnlockFocus, modifier = Modifier.weight(1f)) { Text("Unlock", fontSize = 10.sp) }
            }
            Text("Focus: $focusState", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
        }
    }
}

@Composable
private fun TransferCompleteScreen(
    completed: CompletedTransfer,
    onScanAnother: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(Color(0xFF0B0B0F)).padding(18.dp)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("TRANSFER COMPLETE", color = Color(0xFF4CAF50), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Card(colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.08f))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(completed.pkg.filename, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(completed.pkg.mimeType, color = Color(0xFF89B4FA), fontSize = 11.sp)
                    Text("${completed.pkg.fileSize} B • ${completed.totalFrames} frames", color = Color.White.copy(alpha = 0.8f))
                    Text("Session ${completed.sessionId}", color = Color.White.copy(alpha = 0.8f))
                    Text("File CRC32 PASS • 0x${"%08X".format(completed.pkg.fileCrc32)}", color = Color(0xFF4CAF50))
                    Text("Saved temporarily: ${completed.cacheFile.name}", color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp)
                }
            }
            Text("Preview", color = Color.White, fontWeight = FontWeight.Bold)
            Surface(color = Color.White.copy(alpha = 0.05f), shape = RoundedCornerShape(10.dp)) {
                Text(
                    V6ReceiveUtils.generatePreview(completed.pkg.fileData),
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp).padding(10.dp),
                )
            }
            Button(onClick = onScanAnother, modifier = Modifier.fillMaxWidth()) { Text("Scan Another") }
        }
    }
}
