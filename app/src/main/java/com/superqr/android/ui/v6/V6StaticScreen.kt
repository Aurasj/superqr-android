package com.superqr.android.ui.v6

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.lifecycle.LifecycleOwner
import com.superqr.android.camera.ChromaSampleBuffers
import com.superqr.android.camera.ImageProxyChromaSampler
import com.superqr.android.camera.LumaFrameBuffer
import com.superqr.android.vision.v6.contract.V6Contract
import com.superqr.android.vision.v6.detection.V6StaticDetector
import com.superqr.android.vision.v6.diagnostic.*
import com.superqr.android.vision.v6.model.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class LastValidEvaluation(
    val timestamp: String,
    val pattern: String,
    val colorCorrect: Int,
    val colorUncertain: Int,
    val decodedCrc32: Long?,
    val expectedCrc32: Long?,
    val reprojectionError: Double
)

data class PersistentErrorState(
    val signature: String,
    val category: String,
    val timestamp: String,
    val message: String,
    val exactReason: String,
    var count: Int = 1,
    val firstSeen: String,
    var lastSeen: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun V6StaticScreen(
    analysisExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() },
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    diagnosticLogger: V6DiagnosticLogger = remember { V6DiagnosticLogger() }
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val previewView = remember { PreviewView(context) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (!isGranted) {
            diagnosticLogger.log("WARN", "PERMISSION", "CAMERA_DENIED", "Camera permission denied by user")
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var staticResult by remember { mutableStateOf<V6StaticResult?>(null) }
    var lastValidEvaluation by remember { mutableStateOf<LastValidEvaluation?>(null) }
    var frozenSnapshot by remember { mutableStateOf<V6StaticResult?>(null) }

    val activeErrors = remember { mutableStateMapOf<String, PersistentErrorState>() }

    var canonicalHash by remember { mutableStateOf("") }
    var rawHash by remember { mutableStateOf("") }
    var assetByteLength by remember { mutableStateOf(0) }
    var contractMatch by remember { mutableStateOf<Boolean?>(null) }

    var selectedTestMode by remember { mutableStateOf("deterministic_random") }
    val selectedTestModeState = rememberUpdatedState(selectedTestMode)

    var showLabPanel by remember { mutableStateOf(false) }
    var labTab by remember { mutableIntStateOf(0) } // 0: TEST, 1: LIVE, 2: TOOLS

    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val freezeRequested = remember { AtomicBoolean(false) }

    val fullDiagnosticArmed = remember { AtomicBoolean(false) }
    val armedTimestamp = remember { AtomicLong(0L) }
    var isDiagnosticArmed by remember { mutableStateOf(false) }
    var captureStatusMessage by remember { mutableStateOf<String?>(null) }
    var capturedZipFile by remember { mutableStateOf<File?>(null) }

    var lastLoggedBorder by remember { mutableStateOf<Boolean?>(null) }
    var lastLoggedOrientation by remember { mutableStateOf<Boolean?>(null) }
    var lastLoggedTrackingState by remember { mutableStateOf<String?>(null) }
    var lastLoggedAccuracy by remember { mutableStateOf<Double?>(null) }
    var lastLoggedEvalTimeMs by remember { mutableLongStateOf(0L) }

    fun reportError(category: String, message: String, reason: String) {
        val sig = "$category|$message"
        val now = dateFormat.format(Date())
        val existing = activeErrors[sig]
        if (existing != null) {
            existing.count++
            existing.lastSeen = now
            activeErrors[sig] = existing.copy()
        } else {
            activeErrors[sig] = PersistentErrorState(
                signature = sig,
                category = category,
                timestamp = now,
                message = message,
                exactReason = reason,
                firstSeen = now,
                lastSeen = now
            )
        }
    }

    DisposableEffect(hasCameraPermission, lifecycleOwner, context, previewView) {
        if (!hasCameraPermission) {
            return@DisposableEffect onDispose {}
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var detector: V6StaticDetector? = null

        val listener = Runnable {
            try {
                V6Contract.loadAndVerify(context)
                canonicalHash = V6Contract.canonicalHash
                rawHash = V6Contract.rawFileHash
                assetByteLength = V6Contract.assetByteLength
                val matches = (V6Contract.canonicalHash == V6Contract.EXPECTED_HASH)
                contractMatch = matches

                if (matches) {
                    diagnosticLogger.log("INFO", "CONTRACT", "CONTRACT_MATCH", "V6 Visual Contract canonical hash verified match")
                } else {
                    diagnosticLogger.log("ERROR", "CONTRACT", "CONTRACT_MISMATCH", "Canonical hash mismatch! Canonical: $canonicalHash")
                    reportError("CONTRACT", "Contract Hash Mismatch", "Canonical: ${V6Contract.canonicalHash}\nRaw: ${V6Contract.rawFileHash}\nExpected: ${V6Contract.EXPECTED_HASH}")
                }

                val provider = cameraProviderFuture.get()
                provider.unbindAll()

                val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
                val preview = Preview.Builder()
                    .setTargetRotation(targetRotation)
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }

                detector = V6StaticDetector()
                val lumaBuffer = LumaFrameBuffer()
                val chromaSampleBuffers = ChromaSampleBuffers()

                diagnosticLogger.log("INFO", "SYSTEM", "CAMERA_INIT", "Camera pipeline initialized and bound")

                val analysis = ImageAnalysis.Builder()
                    .setTargetRotation(targetRotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    try {
                        val activeDetector = detector
                        if (activeDetector != null) {
                            val lumaValid = lumaBuffer.packFrom(imageProxy)
                            val chromaReader = ImageProxyChromaSampler(imageProxy, chromaSampleBuffers)

                            if (lumaValid) {
                                val shouldFreeze = freezeRequested.getAndSet(false)
                                val currentMode = selectedTestModeState.value
                                val result = activeDetector.detect(
                                    luma = lumaBuffer.bytes,
                                    width = lumaBuffer.width,
                                    height = lumaBuffer.height,
                                    mode = currentMode,
                                    chromaReader = chromaReader,
                                    exportDebugImage = shouldFreeze,
                                    cacheDir = if (shouldFreeze) context.cacheDir.absolutePath else null
                                )

                                val payload = result.diagnosticPayload

                                // Check Full Diagnostic Arming
                                if (fullDiagnosticArmed.get()) {
                                    val now = System.currentTimeMillis()
                                    if (now - armedTimestamp.get() > 5000L) {
                                        fullDiagnosticArmed.set(false)
                                        diagnosticLogger.log("WARN", "DIAGNOSTIC", "TIMEOUT", "Diagnostic capture timed out (no valid LOCKED/TRACKING frame in 5s)")
                                        ContextCompat.getMainExecutor(context).execute {
                                            isDiagnosticArmed = false
                                            captureStatusMessage = "Diagnostic capture timed out (no valid frame in 5s)"
                                        }
                                    } else if (result.borderFound && result.orientationResolved && (result.trackingState == "LOCKED" || result.trackingState == "TRACKING") && payload != null) {
                                        fullDiagnosticArmed.set(false)

                                        val yPlane = imageProxy.planes[0]
                                        val uPlane = imageProxy.planes[1]
                                        val vPlane = imageProxy.planes[2]

                                        fun copyBuf(buf: java.nio.ByteBuffer): ByteArray {
                                            val dup = buf.duplicate()
                                            val bytes = ByteArray(dup.remaining())
                                            dup.get(bytes)
                                            return bytes
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
                                            yPlaneBytes = copyBuf(yPlane.buffer),
                                            uPlaneBytes = copyBuf(uPlane.buffer),
                                            vPlaneBytes = copyBuf(vPlane.buffer),
                                            trackingState = result.trackingState,
                                            ransacInliers = result.ransacInliers,
                                            correctCount = result.colorCorrect,
                                            incorrectCount = result.colorTotal - result.colorCorrect - result.colorUncertain,
                                            uncertainCount = result.colorUncertain,
                                            payload = payload,
                                            warpedLumaBytes = result.warpedLumaBytes,
                                            frameTrace = activeDetector.getFrameTraceSnapshot()
                                        )

                                        val cacheDir = context.cacheDir
                                        analysisExecutor.execute {
                                            try {
                                                val zipFile = V6FullDiagnosticExporter.exportToZip(bundle, cacheDir)
                                                if (zipFile != null) {
                                                    ContextCompat.getMainExecutor(context).execute {
                                                        isDiagnosticArmed = false
                                                        capturedZipFile = zipFile
                                                        captureStatusMessage = "Diagnostic captured: ${zipFile.name}"
                                                        diagnosticLogger.log("INFO", "DIAGNOSTIC", "CAPTURED", "Diagnostic captured: ${zipFile.name}")
                                                    }
                                                }
                                            } catch (e: Throwable) {
                                                Log.e("V6StaticScreen", "Failed to export diagnostic ZIP", e)
                                                ContextCompat.getMainExecutor(context).execute {
                                                    isDiagnosticArmed = false
                                                    captureStatusMessage = "Failed to export diagnostic: ${e.message}"
                                                }
                                            }
                                        }
                                    }
                                }

                                ContextCompat.getMainExecutor(context).execute {
                                    staticResult = result

                                    if (shouldFreeze) {
                                        frozenSnapshot = result
                                        diagnosticLogger.log("INFO", "UI", "SNAPSHOT", "Froze frame snapshot")
                                    }

                                    // Distinct tracking state transition logs
                                    if (lastLoggedTrackingState != result.trackingState) {
                                        val oldState = lastLoggedTrackingState ?: "SEARCHING"
                                        val newState = result.trackingState
                                        lastLoggedTrackingState = newState
                                        if (oldState != newState) {
                                            diagnosticLogger.log(
                                                "INFO",
                                                "TRACKER",
                                                "TRANSITION_${oldState}_TO_${newState}",
                                                "Tracking state: $oldState->$newState"
                                            )
                                        }
                                    }

                                    // Deduplicated transitions
                                    if (lastLoggedBorder != result.borderFound) {
                                        lastLoggedBorder = result.borderFound
                                        if (result.borderFound) {
                                            diagnosticLogger.log("INFO", "DETECTOR", "BORDER_FOUND", "Outer border detected. Area: ${result.contourArea}")
                                        } else {
                                            diagnosticLogger.log("WARN", "DETECTOR", "BORDER_LOST", "Outer border lost. Reason: ${result.failureReason}")
                                        }
                                    }

                                    if (result.borderFound && lastLoggedOrientation != result.orientationResolved) {
                                        lastLoggedOrientation = result.orientationResolved
                                        if (result.orientationResolved) {
                                            diagnosticLogger.log("INFO", "ORIENTATION", "ORIENT_VALID", "Orientation resolved")
                                        } else {
                                            diagnosticLogger.log("WARN", "ORIENTATION", "ORIENT_INVALID", "Orientation invalid: ${result.failureReason}")
                                        }
                                    }

                                    // DO NOT count TRACKED_HOMOGRAPHY frames as fresh classification measurements
                                    val isFreshEval = (result.borderFound && result.orientationResolved && result.diagnosticPayload?.classificationSource == "FULL_DETECTION")
                                    if (isFreshEval) {
                                        val nowMs = System.currentTimeMillis()
                                        val accuracyChanged = lastLoggedAccuracy == null || kotlin.math.abs(result.cellAccuracy - (lastLoggedAccuracy ?: 0.0)) >= 1.0
                                        val timeElapsed = (nowMs - lastLoggedEvalTimeMs) >= 2000L

                                        if (accuracyChanged || timeElapsed) {
                                            lastLoggedAccuracy = result.cellAccuracy
                                            lastLoggedEvalTimeMs = nowMs
                                            lastValidEvaluation = LastValidEvaluation(
                                                timestamp = dateFormat.format(Date()),
                                                pattern = currentMode,
                                                colorCorrect = result.colorCorrect,
                                                colorUncertain = result.colorUncertain,
                                                decodedCrc32 = result.decodedCrc32,
                                                expectedCrc32 = result.expectedCrc32,
                                                reprojectionError = result.reprojectionError
                                            )
                                            val hexDec = result.decodedCrc32?.let { String.format("%08X", it) } ?: "N/A"
                                            val hexExp = result.expectedCrc32?.let { String.format("%08X", it) } ?: "N/A"
                                            diagnosticLogger.log("INFO", "EVALUATION", "VALID_EVAL", "Correct: ${result.colorCorrect}/400, CRC: $hexDec (Expected $hexExp)")
                                        }
                                    }

                                    val failure = result.failureReason
                                    if (failure != null && (failure.contains("OpenCV initialization") || failure.contains("failed"))) {
                                        diagnosticLogger.log("ERROR", "DETECTOR", "DETECT_FAIL", "Detector error: $failure")
                                        reportError("DETECTOR", "Detection Failed", failure)
                                    }
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        Log.e("V6StaticScreen", "Frame analysis error", e)
                        ContextCompat.getMainExecutor(context).execute {
                            diagnosticLogger.log("ERROR", "DETECTOR", "FRAME_EXCEPTION", "Exception: ${e.message}")
                            reportError("DETECTOR", "Frame Analysis Exception", e.message ?: e.toString())
                        }
                    } finally {
                        imageProxy.close()
                    }
                }

                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Throwable) {
                Log.e("V6StaticScreen", "Failed to bind camera", e)
                diagnosticLogger.log("ERROR", "SYSTEM", "INIT_FAIL", "Init failed: ${e.message}")
                reportError("SYSTEM", "Initialization Failed", e.message ?: e.toString())
            }
        }
        cameraProviderFuture.addListener(listener, ContextCompat.getMainExecutor(context))

        onDispose {
            try {
                detector?.close()
                cameraProviderFuture.get().unbindAll()
            } catch (e: Exception) {}
        }
    }

    if (!hasCameraPermission) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Camera Permission Required",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "SuperQR V6 Scanner needs camera access to detect and track visual contracts in real-time.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }
                    ) {
                        Text("Grant Camera Permission")
                    }
                }
            }
        }
        return
    }

    val currentResult = staticResult
    val rawTrackingState = currentResult?.trackingState ?: "SEARCHING"
    val classSource = currentResult?.diagnosticPayload?.classificationSource
    val isLive = (classSource == "FULL_DETECTION")
    val isHeld = (classSource == "TRACKED_HOMOGRAPHY")

    val mainStatusText = when {
        isHeld -> "HELD"
        else -> rawTrackingState
    }

    val mainStatusBgColor = when {
        isHeld -> Color(0xFFF57C00) // Amber/Orange
        rawTrackingState == "ACQUIRING" || rawTrackingState == "REACQUIRING" -> Color(0xFFF57C00)
        rawTrackingState == "LOCKED" || rawTrackingState == "TRACKING" -> Color(0xFF2E7D32) // Green
        else -> Color(0xFF616161) // Gray
    }

    val reticleColor = when {
        isLive && (rawTrackingState == "LOCKED" || rawTrackingState == "TRACKING") -> Color(0xFF4CAF50) // Green
        isHeld -> Color(0xFFF57C00) // Amber/Orange
        else -> Color.White.copy(alpha = 0.6f)
    }

    val hasValidEval = (currentResult != null && currentResult.borderFound && currentResult.orientationResolved)

    val decodedCrc = currentResult?.decodedCrc32
    val expectedCrc = currentResult?.expectedCrc32
    val crcMatches = (decodedCrc != null && expectedCrc != null && decodedCrc == expectedCrc)
    val isCrcPass = hasValidEval && crcMatches && (currentResult?.colorUncertain == 0)

    val sourceLabel = when {
        isLive -> "LIVE"
        isHeld -> "HELD"
        else -> "-"
    }
    val sourceColor = when {
        isLive -> Color(0xFF2E7D32)
        isHeld -> Color(0xFFF57C00)
        else -> Color.Gray
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        // 1. Dominant edge-to-edge camera preview
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // 2. Framing Guide Overlay
        FramingGuideOverlay(
            modifier = Modifier.fillMaxSize(),
            reticleColor = reticleColor
        )

        // 3. Top Control Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Text("<", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
                Text(
                    text = "V6 Scanner",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = mainStatusBgColor
                ) {
                    Text(
                        text = mainStatusText,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                Button(
                    onClick = { showLabPanel = !showLabPanel },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.6f))
                ) {
                    Text("Lab", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }

        // 4. Compact Live Lab Panel or Bottom Floating Summary Bar
        if (showLabPanel) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.88f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(12.dp)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TabRow(
                            selectedTabIndex = labTab,
                            containerColor = Color.Transparent,
                            contentColor = Color.White,
                            modifier = Modifier.weight(1f)
                        ) {
                            Tab(
                                selected = labTab == 0,
                                onClick = { labTab = 0 },
                                text = { Text("TEST", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                            )
                            Tab(
                                selected = labTab == 1,
                                onClick = { labTab = 1 },
                                text = { Text("LIVE", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                            )
                            Tab(
                                selected = labTab == 2,
                                onClick = { labTab = 2 },
                                text = { Text("TOOLS", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                            )
                        }

                        IconButton(onClick = { showLabPanel = false }) {
                            Text("X", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.2f))

                    when (labTab) {
                        0 -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Pattern:", fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (contractMatch == true) Color(0xFF2E7D32) else Color(0xFFD32F2F)
                                    ) {
                                        Text(
                                            text = if (contractMatch == true) "VERIFIED" else "MISMATCH",
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                val modes = listOf(
                                    "Random" to "deterministic_random",
                                    "Checkerboard" to "checkerboard",
                                    "Black" to "black",
                                    "White" to "white"
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    for ((label, value) in modes) {
                                        FilterChip(
                                            selected = (selectedTestMode == value),
                                            onClick = { selectedTestMode = value },
                                            label = { Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                        1 -> {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("State: $mainStatusText", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                    Text("Source: $sourceLabel", fontSize = 11.sp, color = sourceColor, fontWeight = FontWeight.Bold)
                                    Text("Time: ${currentResult?.processingTimeMs ?: 0}ms", fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CompactMetricItem(label = "Inliers", value = "${currentResult?.ransacInliers ?: 0}")
                                    CompactMetricItem(
                                        label = "Correct",
                                        value = if (hasValidEval) "${currentResult?.colorCorrect}/400" else "—",
                                        isGood = if (hasValidEval) (currentResult?.colorCorrect ?: 0) >= 390 else null,
                                        isHeld = isHeld
                                    )
                                    CompactMetricItem(
                                        label = "Wrong",
                                        value = if (hasValidEval) "${currentResult!!.colorTotal - currentResult.colorCorrect - currentResult.colorUncertain}" else "—",
                                        isGood = if (hasValidEval) (currentResult!!.colorTotal - currentResult.colorCorrect - currentResult.colorUncertain) == 0 else null,
                                        isHeld = isHeld
                                    )
                                    CompactMetricItem(
                                        label = "Uncertain",
                                        value = if (hasValidEval) "${currentResult?.colorUncertain}" else "—",
                                        isGood = if (hasValidEval) (currentResult?.colorUncertain ?: 0) == 0 else null,
                                        isHeld = isHeld
                                    )
                                    CompactMetricBadge(
                                        label = "CRC",
                                        value = if (hasValidEval) (if (isCrcPass) "PASS" else "FAIL") else "—",
                                        isGood = hasValidEval && isCrcPass,
                                        isHeld = isHeld
                                    )
                                }
                            }
                        }
                        2 -> {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Button(
                                        onClick = { freezeRequested.set(true) },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(vertical = 4.dp)
                                    ) { Text("Freeze", fontSize = 10.sp) }

                                    if (V6FullDiagnosticExporter.IS_SUPPORTED) {
                                        Button(
                                            onClick = {
                                                if (capturedZipFile != null) {
                                                    shareZipFile(context, capturedZipFile!!)
                                                } else {
                                                    fullDiagnosticArmed.set(true)
                                                    armedTimestamp.set(System.currentTimeMillis())
                                                    isDiagnosticArmed = true
                                                    captureStatusMessage = "Armed..."
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            contentPadding = PaddingValues(vertical = 4.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = if (isDiagnosticArmed) Color(0xFFE65100) else MaterialTheme.colorScheme.primary)
                                        ) { Text(if (capturedZipFile != null) "Share ZIP" else if (isDiagnosticArmed) "Armed..." else "Capture ZIP", fontSize = 10.sp) }
                                    }

                                    Button(
                                        onClick = {
                                            val report = generateDiagnosticReport(context, currentResult, contractMatch, canonicalHash, rawHash, assetByteLength, lastValidEvaluation, activeErrors.values.toList(), diagnosticLogger, frozenSnapshot)
                                            clipboardManager.setText(AnnotatedString(report))
                                        },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(vertical = 4.dp)
                                    ) { Text("Copy Report", fontSize = 10.sp) }

                                    Button(
                                        onClick = {
                                            val report = generateDiagnosticReport(context, currentResult, contractMatch, canonicalHash, rawHash, assetByteLength, lastValidEvaluation, activeErrors.values.toList(), diagnosticLogger, frozenSnapshot)
                                            shareReportFile(context, report)
                                        },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(vertical = 4.dp)
                                    ) { Text("Share Report", fontSize = 10.sp) }
                                }

                                val lastError = activeErrors.values.maxByOrNull { it.lastSeen }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (lastError != null) {
                                        Text(
                                            text = "Last Error: [${lastError.category}] ${lastError.message}",
                                            fontSize = 10.sp,
                                            color = Color(0xFFF44336),
                                            maxLines = 1,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            onClick = { activeErrors.clear() },
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                        ) {
                                            Text("Clear", fontSize = 10.sp, color = Color.Gray)
                                        }
                                    } else {
                                        Text(
                                            text = "No active errors",
                                            fontSize = 10.sp,
                                            color = Color.Gray
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.78f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val guidance = when {
                            currentResult == null || !currentResult.borderFound -> "Center marker inside reticle"
                            !currentResult.orientationResolved -> "Hold steady..."
                            isHeld -> "Revalidating marker..."
                            rawTrackingState == "LOCKED" || rawTrackingState == "TRACKING" -> "Marker locked & tracking"
                            else -> "Acquiring marker..."
                        }
                        Text(
                            text = guidance,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.9f)
                        )

                        if (hasValidEval) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = sourceColor.copy(alpha = 0.25f)
                            ) {
                                Text(
                                    text = sourceLabel,
                                    color = sourceColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.2f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CompactMetricItem(label = "Inliers", value = "${currentResult?.ransacInliers ?: 0}")
                        CompactMetricItem(
                            label = "Cells",
                            value = if (hasValidEval) "${currentResult?.colorCorrect}/400" else "—",
                            isGood = if (hasValidEval) (currentResult?.colorCorrect ?: 0) >= 390 else null,
                            isHeld = isHeld
                        )
                        CompactMetricItem(
                            label = "Uncertain",
                            value = if (hasValidEval) "${currentResult?.colorUncertain}" else "—",
                            isGood = if (hasValidEval) (currentResult?.colorUncertain ?: 0) == 0 else null,
                            isHeld = isHeld
                        )
                        CompactMetricBadge(
                            label = "CRC",
                            value = if (hasValidEval) (if (isCrcPass) "PASS" else "FAIL") else "—",
                            isGood = hasValidEval && isCrcPass,
                            isHeld = isHeld
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FramingGuideOverlay(
    modifier: Modifier = Modifier,
    reticleColor: Color = Color.White.copy(alpha = 0.6f)
) {
    Canvas(modifier = modifier) {
        val side = size.minDimension * 0.65f
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        val cornerLen = side * 0.15f
        val strokeWidth = 3.dp.toPx()

        // Top-Left
        drawLine(reticleColor, Offset(left, top), Offset(left + cornerLen, top), strokeWidth)
        drawLine(reticleColor, Offset(left, top), Offset(left, top + cornerLen), strokeWidth)

        // Top-Right
        drawLine(reticleColor, Offset(left + side, top), Offset(left + side - cornerLen, top), strokeWidth)
        drawLine(reticleColor, Offset(left + side, top), Offset(left + side, top + cornerLen), strokeWidth)

        // Bottom-Right
        drawLine(reticleColor, Offset(left + side, top + side), Offset(left + side - cornerLen, top + side), strokeWidth)
        drawLine(reticleColor, Offset(left + side, top + side), Offset(left + side, top + side - cornerLen), strokeWidth)

        // Bottom-Left
        drawLine(reticleColor, Offset(left, top + side), Offset(left + cornerLen, top + side), strokeWidth)
        drawLine(reticleColor, Offset(left, top + side), Offset(left, top + side - cornerLen), strokeWidth)
    }
}

@Composable
private fun CompactMetricItem(
    label: String,
    value: String,
    isGood: Boolean? = null,
    isHeld: Boolean = false
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = if (isHeld && label != "Inliers") "$label (last)" else label,
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.6f)
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = when {
                isHeld -> Color(0xFFFFB74D) // De-emphasized amber when held
                isGood == true -> Color(0xFF4CAF50)
                isGood == false -> Color(0xFFF44336)
                else -> Color.White
            }
        )
    }
}

@Composable
private fun CompactMetricBadge(
    label: String,
    value: String,
    isGood: Boolean,
    isHeld: Boolean = false
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = if (isHeld) "$label (last)" else label,
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.6f)
        )
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = when {
                isHeld -> Color(0xFFE65100).copy(alpha = 0.85f) // De-emphasized amber/orange when held
                isGood -> Color(0xFF2E7D32)
                else -> Color(0xFF616161)
            }
        ) {
            Text(
                text = if (isHeld && value != "—") "$value (held)" else value,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

private fun shareReportFile(context: Context, text: String) {
    try {
        val file = File(context.cacheDir, "v6_diagnostic_report.txt")
        file.writeText(text)
        
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, text)
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, "Share V6 Diagnostic Report")
        context.startActivity(shareIntent)
    } catch (e: Exception) {
        Log.e("V6StaticScreen", "Share error", e)
    }
}

private fun shareZipFile(context: Context, zipFile: File) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            zipFile
        )
        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_STREAM, uri)
            type = "application/zip"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "Share V6 Full Diagnostic ZIP")
        context.startActivity(chooser)
    } catch (e: Exception) {
        Log.e("V6StaticScreen", "Share ZIP error", e)
    }
}

private fun generateDiagnosticReport(
    context: Context,
    currentResult: V6StaticResult?,
    contractMatch: Boolean?,
    canonicalHash: String,
    rawHash: String,
    assetByteLength: Int,
    lastValid: LastValidEvaluation?,
    errors: List<PersistentErrorState>,
    logger: V6DiagnosticLogger,
    frozenSnapshot: V6StaticResult?
): String {
    val pm = context.packageManager
    val pkgName = context.packageName
    val pInfo = pm.getPackageInfo(pkgName, 0)
    val appVersion = pInfo.versionName
    val androidVersion = android.os.Build.VERSION.RELEASE
    val deviceModel = android.os.Build.MODEL

    return buildString {
        appendLine("=== V6 DIAGNOSTIC REPORT ===")
        appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        appendLine("App Version: $appVersion")
        appendLine("Android OS: $androidVersion")
        appendLine("Device: $deviceModel")
        appendLine()
        appendLine("--- CONTRACT ---")
        appendLine("Match: $contractMatch")
        appendLine("Canonical Hash: $canonicalHash")
        appendLine("Raw Hash: $rawHash")
        appendLine("Size: $assetByteLength bytes")
        appendLine()
        appendLine("--- DETECTOR & TRACKING STATE ---")
        if (currentResult != null) {
            appendLine("Tracking State: ${currentResult.trackingState}")
            appendLine("Classification Source: ${currentResult.diagnosticPayload?.classificationSource ?: "N/A"}")
            appendLine("RANSAC Inliers: ${currentResult.ransacInliers}")
            appendLine("Missed Frames: ${currentResult.missedFrameCount}")
            appendLine("Border: ${currentResult.borderFound}")
            appendLine("Orient: ${currentResult.orientationResolved}")
            appendLine("Time: ${currentResult.processingTimeMs}ms")
            appendLine("Quad: ${currentResult.detectedQuad?.joinToString(" ") { "(${it[0]},${it[1]})" }}")
            appendLine("Area: ${currentResult.contourArea}")
            appendLine("Warp Min/Max/Mean: ${if (currentResult.warpCoverage > 0.0) "${currentResult.warpMinLuma}/${currentResult.warpMaxLuma}/${currentResult.warpMeanLuma}" else "N/A"}")
            appendLine("Warp Coverage: ${if (currentResult.warpCoverage > 0.0) String.format(Locale.US, "%.2f", currentResult.warpCoverage) else "N/A"}")
            appendLine("Corner IDs: ${currentResult.decodedCornerIds}")
            appendLine("Corner Bits: ${currentResult.decodedCornerBits}")
            appendLine("Corner Distances: ${currentResult.decodedCornerDistances}")
            appendLine("Corner Margins: ${currentResult.decodedCornerMargins}")
            currentResult.cornerMatches.forEach { (pos, match) ->
                appendLine("  [$pos] expected=${match.expectedPattern} decoded=${match.decodedPattern} bestMatch=${match.bestMatchId} dist=${match.hammingDistance} margin=${match.secondBestMargin}")
            }
            currentResult.bitSamples.forEach { (corner, samples) ->
                appendLine("  Bits $corner:")
                samples.forEach { sample ->
                    appendLine("    [${sample.bitName}] coords=(${String.format(Locale.US, "%.1f", sample.x)},${String.format(Locale.US, "%.1f", sample.y)}) med=${sample.medianLuma} black=${sample.localBlackRef} white=${sample.localWhiteRef} thresh=${sample.threshold} -> ${sample.decodedBit}")
                }
            }
            appendLine()
            appendLine("--- DECODING & CLASSIFICATION ---")
            appendLine("Correct: ${currentResult.colorCorrect}/400")
            appendLine("Uncertain: ${currentResult.colorUncertain}/400")
            val hexDec = currentResult.decodedCrc32?.let { String.format("%08X", it) } ?: "N/A"
            val hexExp = currentResult.expectedCrc32?.let { String.format("%08X", it) } ?: "N/A"
            appendLine("Decoded CRC32: $hexDec")
            appendLine("Expected CRC32: $hexExp")
            
            val matrix = currentResult.confusionMatrix
            if (matrix != null) {
                appendLine("Confusion Matrix (Expected -> Decoded):")
                matrix.forEach { (exp, decMap) ->
                    val decStr = decMap.entries.joinToString(", ") { "${it.key}:${it.value}" }
                    appendLine("  $exp -> $decStr")
                }
            }
        } else {
            appendLine("None")
        }
        
        if (frozenSnapshot != null) {
            appendLine()
            appendLine("--- FROZEN SNAPSHOT ---")
            appendLine("Border: ${frozenSnapshot.borderFound}")
            appendLine("Orient: ${frozenSnapshot.orientationResolved}")
            appendLine("Correct: ${frozenSnapshot.colorCorrect}/400")
            val hexDec = frozenSnapshot.decodedCrc32?.let { String.format("%08X", it) } ?: "N/A"
            appendLine("Decoded CRC32: $hexDec")
            appendLine("Debug Image Path: ${frozenSnapshot.debugImagePath ?: "N/A"}")
        }
        
        appendLine()
        appendLine("--- ERRORS (${errors.size}) ---")
        errors.forEach { e ->
            appendLine("[${e.category}] ${e.message} (x${e.count})")
            appendLine("Reason: ${e.exactReason}")
            appendLine("Seen: ${e.firstSeen} to ${e.lastSeen}")
            appendLine()
        }
        appendLine("--- LOGS ---")
        logger.getLogs().forEach { l ->
            appendLine("[${l.timestamp}] ${l.level}/${l.category} (x${l.count}): ${l.message}")
        }
    }
}
