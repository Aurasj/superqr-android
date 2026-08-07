package com.superqr.android.ui.v6

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.ImageProxyTransformFactory
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
import androidx.lifecycle.LifecycleOwner
import com.superqr.android.camera.ChromaSampleBuffers
import com.superqr.android.camera.ImageProxyChromaSampler
import com.superqr.android.camera.LumaFrameBuffer
import com.superqr.android.vision.v6.contract.V6Contract
import com.superqr.android.vision.v6.detection.V6StaticDetector
import com.superqr.android.vision.v6.diagnostic.V6CapturedFrameBundle
import com.superqr.android.vision.v6.diagnostic.V6FullDiagnosticExporter
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
import java.util.concurrent.atomic.AtomicLong

private data class CompletedUiTransfer(
    val pkg: V6TransferPackage,
    val sessionId: Int,
    val totalFrames: Int,
    val fileCrc32: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun V6StaticScreen(
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
    var cameraBindingState by remember { mutableStateOf("WAITING") }

    val accumulator = remember { V6SessionAccumulator() }
    var completedTransfer by remember { mutableStateOf<CompletedUiTransfer?>(null) }
    var completedCacheFile by remember { mutableStateOf<File?>(null) }

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

    DisposableEffect(hasCameraPermission, lifecycleOwner, previewView) {
        if (!hasCameraPermission) return@DisposableEffect onDispose {}

        val providerFuture = ProcessCameraProvider.getInstance(context)
        var detector: V6StaticDetector? = null
        var disposed = false

        val providerListener = Runnable {
            if (disposed) return@Runnable
            val provider = try {
                providerFuture.get()
            } catch (e: Throwable) {
                cameraBindingState = "ERROR"
                diagnosticLogger.log("ERROR", "CAMERA", "PROVIDER", e.message ?: e.toString())
                return@Runnable
            }

            previewView.doOnLayout {
                if (disposed) return@doOnLayout
                try {
                    provider.unbindAll()
                    V6Contract.loadAndVerify(context)

                    val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
                    val preview = Preview.Builder()
                        .setTargetRotation(targetRotation)
                        .build()
                        .also { it.surfaceProvider = previewView.surfaceProvider }

                    val analysis = ImageAnalysis.Builder()
                        .setTargetRotation(targetRotation)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    val viewPort = previewView.viewPort
                    if (viewPort == null) {
                        cameraBindingState = "ERROR"
                        diagnosticLogger.log(
                            "ERROR",
                            "CAMERA",
                            "VIEWPORT_NULL",
                            "PreviewView ViewPort unavailable after layout; refusing non-WYSIWYG binding",
                        )
                        return@doOnLayout
                    }

                    detector = V6StaticDetector()
                    val lumaBuffer = LumaFrameBuffer()
                    val chromaBuffers = ChromaSampleBuffers()
                    val imageTransformFactory = ImageProxyTransformFactory().apply {
                        setUsingCropRect(true)
                        setUsingRotationDegrees(true)
                    }

                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        try {
                            val activeDetector = detector ?: return@setAnalyzer
                            val lumaOk = lumaBuffer.packFrom(imageProxy)
                            if (!lumaOk) return@setAnalyzer

                            val chromaReader = ImageProxyChromaSampler(imageProxy, chromaBuffers)
                            val result = activeDetector.detect(
                                luma = lumaBuffer.bytes,
                                width = lumaBuffer.width,
                                height = lumaBuffer.height,
                                mode = selectedTestModeState.value,
                                chromaReader = chromaReader,
                            )

                            // Source transform is captured while ImageProxy is valid. The target
                            // transform is obtained on the UI thread from the actual PreviewView.
                            val sourceTransform = try {
                                imageTransformFactory.getOutputTransform(imageProxy)
                            } catch (_: Throwable) {
                                null
                            }

                            // Capture diagnostics only from a CURRENT full detection. This keeps
                            // raw YUV, homography, matrix samples and transport metadata from the
                            // exact same camera frame.
                            val payload = result.diagnosticPayload
                            if (fullDiagnosticArmed.get()) {
                                val expired = System.currentTimeMillis() - diagnosticArmedAt.get() > 5000L
                                if (expired) {
                                    fullDiagnosticArmed.set(false)
                                    mainExecutor.execute { diagnosticState = "TIMEOUT" }
                                } else if (
                                    result.borderFound &&
                                    result.orientationResolved &&
                                    payload?.classificationSource == "FULL_DETECTION"
                                ) {
                                    fullDiagnosticArmed.set(false)
                                    val yPlane = imageProxy.planes[0]
                                    val uPlane = imageProxy.planes[1]
                                    val vPlane = imageProxy.planes[2]

                                    fun copyBuffer(buffer: java.nio.ByteBuffer): ByteArray {
                                        val duplicate = buffer.duplicate()
                                        val out = ByteArray(duplicate.remaining())
                                        duplicate.get(out)
                                        return out
                                    }

                                    val bundle = V6CapturedFrameBundle(
                                        timestamp = payload.timestamp,
                                        imageWidth = imageProxy.width,
                                        imageHeight = imageProxy.height,
                                        rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                                        cropRect = android.graphics.Rect(imageProxy.cropRect),
                                        yRowStride = yPlane.rowStride,
                                        yPixelStride = yPlane.pixelStride,
                                        uRowStride = uPlane.rowStride,
                                        uPixelStride = uPlane.pixelStride,
                                        vRowStride = vPlane.rowStride,
                                        vPixelStride = vPlane.pixelStride,
                                        yPlaneBytes = copyBuffer(yPlane.buffer),
                                        uPlaneBytes = copyBuffer(uPlane.buffer),
                                        vPlaneBytes = copyBuffer(vPlane.buffer),
                                        trackingState = result.trackingState,
                                        ransacInliers = result.ransacInliers,
                                        correctCount = result.colorCorrect,
                                        incorrectCount = result.colorTotal - result.colorCorrect - result.colorUncertain,
                                        uncertainCount = result.colorUncertain,
                                        payload = payload,
                                        warpedLumaBytes = result.warpedLumaBytes,
                                        frameTrace = activeDetector.getFrameTraceSnapshot(),
                                    )

                                    analysisExecutor.execute {
                                        try {
                                            val file = V6FullDiagnosticExporter.exportToZip(bundle, context.cacheDir)
                                            mainExecutor.execute {
                                                capturedZip = file
                                                diagnosticState = "CAPTURED"
                                            }
                                        } catch (e: Throwable) {
                                            mainExecutor.execute { diagnosticState = "ERROR: ${e.message}" }
                                        }
                                    }
                                }
                            }

                            mainExecutor.execute {
                                currentResult = result

                                previewGeometry = if (sourceTransform != null) {
                                    val target = previewView.outputTransform
                                    if (target != null) {
                                        V6PreviewOverlayMapper.map(result, sourceTransform, target)
                                    } else {
                                        null
                                    }
                                } else {
                                    null
                                }

                                // Only a fresh, CRC-valid transport frame can enter the receiver.
                                val frame = result.transportFrame
                                if (frame != null && completedTransfer == null) {
                                    try {
                                        val sessionId = frame.sessionId
                                        val totalFrames = frame.totalFrames
                                        val pkg = accumulator.addFrame(frame)
                                        if (pkg != null) {
                                            val crc = java.util.zip.CRC32().apply { update(pkg.fileData) }.value
                                            val safeName = V6ReceiveUtils.sanitizeFilename(pkg.filename)
                                            val dir = File(context.cacheDir, "superqr_received").apply { mkdirs() }
                                            val cacheFile = File(dir, safeName)
                                            cacheFile.writeBytes(pkg.fileData)
                                            completedCacheFile = cacheFile
                                            completedTransfer = CompletedUiTransfer(
                                                pkg = pkg,
                                                sessionId = sessionId,
                                                totalFrames = totalFrames,
                                                fileCrc32 = crc,
                                            )
                                        }
                                    } catch (e: Throwable) {
                                        diagnosticLogger.log(
                                            "ERROR",
                                            "TRANSPORT",
                                            "ACCUMULATOR",
                                            e.message ?: e.toString(),
                                        )
                                    }
                                }
                            }
                        } catch (e: Throwable) {
                            Log.e("V6StaticScreen", "Frame analysis error", e)
                            diagnosticLogger.log("ERROR", "CAMERA", "ANALYSIS", e.message ?: e.toString())
                        } finally {
                            imageProxy.close()
                        }
                    }

                    // The key WYSIWYG invariant: Preview and ImageAnalysis share one ViewPort.
                    // CameraX therefore gives both use cases crop rects representing the same
                    // sensor region shown to the user.
                    val useCaseGroup = UseCaseGroup.Builder()
                        .addUseCase(preview)
                        .addUseCase(analysis)
                        .setViewPort(viewPort)
                        .build()

                    boundCamera = provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        useCaseGroup,
                    )
                    cameraBindingState = "WYSIWYG"
                    diagnosticLogger.log(
                        "INFO",
                        "CAMERA",
                        "WYSIWYG_BOUND",
                        "Preview and ImageAnalysis bound through shared PreviewView ViewPort",
                    )
                } catch (e: Throwable) {
                    cameraBindingState = "ERROR"
                    diagnosticLogger.log("ERROR", "CAMERA", "BIND", e.message ?: e.toString())
                }
            }
        }

        providerFuture.addListener(providerListener, mainExecutor)

        onDispose {
            disposed = true
            try {
                detector?.close()
                if (providerFuture.isDone) providerFuture.get().unbindAll()
            } catch (_: Throwable) {
            }
        }
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
            onScanAnother = {
                completedCacheFile?.delete()
                completedCacheFile = null
                completedTransfer = null
                accumulator.reset()
                previewGeometry = null
            },
            modifier = modifier,
        )
        return
    }

    val result = currentResult
    val source = result?.diagnosticPayload?.classificationSource
    val fresh = source == "FULL_DETECTION"
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
                Text("V6 Scanner", color = Color.White, fontWeight = FontWeight.Bold)
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

                // 20x20 data matrix projected with the detector's exact canonical
                // homography, then CameraX's exact ImageAnalysis->Preview transform.
                if (g.isFresh) {
                    val gridColor = Color(0xFF00E676).copy(alpha = 0.32f)
                    for ((a, b) in g.gridSegments) {
                        drawLine(gridColor, a, b, 0.8.dp.toPx())
                    }
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
    val fresh = result?.diagnosticPayload?.classificationSource == "FULL_DETECTION"
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
                    if (fresh) "FULL DETECTION" else if (result?.diagnosticPayload?.classificationSource == "TRACKED_HOMOGRAPHY") "HELD" else "SEARCHING",
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
                    val fresh = source == "FULL_DETECTION"
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
    onScanAnother: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pkg = completed.pkg
    Box(modifier.fillMaxSize().background(Color.Black).padding(18.dp)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
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
            Text("Decoded preview", color = Color.White, fontWeight = FontWeight.Bold)
            Box(
                Modifier.weight(1f).fillMaxWidth().background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp)).padding(10.dp)
            ) {
                Text(V6ReceiveUtils.generatePreview(pkg.fileData), color = Color.White.copy(alpha = 0.82f), fontSize = 12.sp)
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
        appendLine("Fresh: ${source == "FULL_DETECTION"}")
        appendLine("Border: ${result.borderFound}")
        appendLine("Orientation: ${result.orientationResolved}")
        appendLine("Quad: ${result.detectedQuad?.joinToString(" ") { "(${"%.1f".format(it[0])},${"%.1f".format(it[1])})" }}")
        appendLine("Normalized analysis: ${result.diagnosticPayload?.normalizedAnalysisWidth}x${result.diagnosticPayload?.normalizedAnalysisHeight}")
        val transportValid = source == "FULL_DETECTION" && result.transportFrame != null
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
