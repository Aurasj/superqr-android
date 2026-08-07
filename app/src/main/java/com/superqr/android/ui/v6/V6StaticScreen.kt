package com.superqr.android.ui.v6

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
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

    var staticResult by remember { mutableStateOf<V6StaticResult?>(null) }
    var lastValidEvaluation by remember { mutableStateOf<LastValidEvaluation?>(null) }
    var frozenSnapshot by remember { mutableStateOf<V6StaticResult?>(null) }

    val activeErrors = remember { mutableStateMapOf<String, PersistentErrorState>() }

    var canonicalHash by remember { mutableStateOf("") }
    var rawHash by remember { mutableStateOf("") }
    var assetByteLength by remember { mutableStateOf(0) }
    var contractMatch by remember { mutableStateOf<Boolean?>(null) }

    var showErrorsSheet by remember { mutableStateOf(false) }
    var showDebugSheet by remember { mutableStateOf(false) }

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

    DisposableEffect(lifecycleOwner, context, previewView) {
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
                                val result = activeDetector.detect(
                                    luma = lumaBuffer.bytes,
                                    width = lumaBuffer.width,
                                    height = lumaBuffer.height,
                                    mode = "deterministic random",
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

                                    if (result.borderFound && result.orientationResolved) {
                                        val nowMs = System.currentTimeMillis()
                                        val accuracyChanged = lastLoggedAccuracy == null || kotlin.math.abs(result.cellAccuracy - (lastLoggedAccuracy ?: 0.0)) >= 1.0
                                        val timeElapsed = (nowMs - lastLoggedEvalTimeMs) >= 2000L

                                        if (accuracyChanged || timeElapsed) {
                                            lastLoggedAccuracy = result.cellAccuracy
                                            lastLoggedEvalTimeMs = nowMs
                                            lastValidEvaluation = LastValidEvaluation(
                                                timestamp = dateFormat.format(Date()),
                                                pattern = "V6_DATA",
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

    val currentResult = staticResult
    val statusText: String
    val statusBgColor: Color

    when {
        activeErrors.isNotEmpty() -> {
            statusText = "ERROR"
            statusBgColor = Color(0xFFD32F2F)
        }
        currentResult == null -> {
            statusText = "SEARCHING"
            statusBgColor = Color(0xFF616161)
        }
        else -> {
            statusText = currentResult.trackingState
            statusBgColor = when (currentResult.trackingState) {
                "SEARCHING" -> Color(0xFF616161)
                "ACQUIRING" -> Color(0xFFF57C00)
                "LOCKED" -> Color(0xFF388E3C)
                "TRACKING" -> Color(0xFF388E3C)
                "REACQUIRING" -> Color(0xFFF57C00)
                else -> Color(0xFF616161)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth().padding(end = 8.dp)
                    ) {
                        Text("V6 Static", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Surface(shape = CircleShape, color = statusBgColor) {
                            Text(
                                text = statusText,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Text("<", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Button(
                        onClick = { showErrorsSheet = true },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (activeErrors.isNotEmpty()) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(
                            text = if (activeErrors.isEmpty()) "Errors" else "Errors (${activeErrors.size})",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                    Button(
                        onClick = { showDebugSheet = true },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)
                    ) {
                        Text(text = "Debug", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    Button(
                        onClick = {
                            val report = generateDiagnosticReport(context, currentResult, contractMatch, canonicalHash, rawHash, assetByteLength, lastValidEvaluation, activeErrors.values.toList(), diagnosticLogger, frozenSnapshot)
                            clipboardManager.setText(AnnotatedString(report))
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)
                    ) {
                        Text(text = "Copy Report", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    Button(
                        onClick = {
                            val report = generateDiagnosticReport(context, currentResult, contractMatch, canonicalHash, rawHash, assetByteLength, lastValidEvaluation, activeErrors.values.toList(), diagnosticLogger, frozenSnapshot)
                            shareReportFile(context, report)
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)
                    ) {
                        Text(text = "Share Report", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // FIXED Camera Preview Card (Large, unobstructed)
            Card(
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .weight(1f)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                }
            }

            // Compact Status Card (Below camera preview)
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatusPair(label = "Contract", value = if (contractMatch == true) "MATCH" else "MISMATCH", isGood = contractMatch == true)
                        StatusPair(label = "Border", value = if (currentResult?.borderFound == true) "FOUND" else "NOT FOUND", isGood = currentResult?.borderFound == true)
                        StatusPair(label = "Orientation", value = if (currentResult?.orientationResolved == true) "VALID" else "INVALID", isGood = currentResult?.orientationResolved == true)
                        StatusPair(label = "Time", value = "${currentResult?.processingTimeMs ?: 0} ms", isGood = true)
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))

                    if (currentResult?.borderFound == true && currentResult.orientationResolved) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(horizontalAlignment = Alignment.Start) {
                                val hexDec = currentResult.decodedCrc32?.let { String.format("%08X", it) } ?: "N/A"
                                val hexExp = currentResult.expectedCrc32?.let { String.format("%08X", it) } ?: "N/A"
                                val match = hexDec == hexExp && hexExp != "N/A"
                                Text(
                                    text = "Cells: ${currentResult.colorCorrect}/400 Correct",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (currentResult.colorCorrect > 390) Color(0xFF2E7D32) else Color(0xFFE65100)
                                )
                                Text(
                                    text = "CRC32: $hexDec ${if(match) "✓" else "✗"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (match) Color(0xFF2E7D32) else Color(0xFFD32F2F)
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "Uncertain: ${currentResult.colorUncertain}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    } else {
                        val guidance = when {
                            currentResult == null || !currentResult.borderFound -> "Center the full marker"
                            !currentResult.orientationResolved -> "Hold steady"
                            else -> "Ready"
                        }
                        Text(
                            text = guidance,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // ERRORS SCREEN (Modal Sheet)
    if (showErrorsSheet) {
        ModalBottomSheet(onDismissRequest = { showErrorsSheet = false }) {
            Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Errors & Warnings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Button(onClick = { activeErrors.clear() }) {
                        Text("Clear All")
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                if (activeErrors.isEmpty()) {
                    Text("No active errors.", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                } else {
                    for (error in activeErrors.values.sortedByDescending { it.lastSeen }) {
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("[${error.category}] ${error.message}", fontWeight = FontWeight.Bold, color = Color(0xFFD32F2F))
                                Text("Reason: ${error.exactReason}", style = MaterialTheme.typography.bodySmall)
                                Text("Occurrence count: ${error.count}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                Text("First seen: ${error.firstSeen} | Last seen: ${error.lastSeen}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(onClick = {
                                        val errText = "[${error.category}] ${error.message}\nReason: ${error.exactReason}\nCount: ${error.count}\nFirst: ${error.firstSeen}\nLast: ${error.lastSeen}"
                                        clipboardManager.setText(AnnotatedString(errText))
                                    }) { Text("Copy") }
                                    TextButton(onClick = {
                                        val errText = "[${error.category}] ${error.message}\nReason: ${error.exactReason}\nCount: ${error.count}\nFirst: ${error.firstSeen}\nLast: ${error.lastSeen}"
                                        shareReportFile(context, errText)
                                    }) { Text("Share") }
                                    TextButton(onClick = { activeErrors.remove(error.signature) }) { Text("Clear") }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }

    // DEBUG SCREEN (Modal Sheet)
    if (showDebugSheet) {
        ModalBottomSheet(onDismissRequest = { showDebugSheet = false }) {
            Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Text("Debug Details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                
                Text("Contract Hashes", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text("Match: $contractMatch")
                Text("Canonical Hash: $canonicalHash")
                Text("Raw Hash: $rawHash")
                Text("Asset Length: $assetByteLength bytes")
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text("Detector & Tracking State", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text("Tracking State: ${currentResult?.trackingState}")
                Text("RANSAC Inliers: ${currentResult?.ransacInliers ?: 0}")
                Text("Missed Frames: ${currentResult?.missedFrameCount ?: 0}")
                Text("Border Found: ${currentResult?.borderFound}")
                Text("Orientation Resolved: ${currentResult?.orientationResolved}")
                Text("Processing Time: ${currentResult?.processingTimeMs ?: 0} ms")
                Text("Contour Area: ${currentResult?.contourArea ?: 0.0}")
                Text("Reprojection Error: ${if (currentResult?.orientationResolved == true) currentResult?.reprojectionError else "N/A"}")
                Text("Warp Min/Max/Mean: ${if (currentResult != null && currentResult.warpCoverage > 0.0) "${currentResult.warpMinLuma} / ${currentResult.warpMaxLuma} / ${currentResult.warpMeanLuma}" else "N/A"}")
                Text("Warp Coverage: ${if (currentResult != null && currentResult.warpCoverage > 0.0) String.format(Locale.US, "%.2f%%", currentResult.warpCoverage * 100) else "N/A"}")
                Text("Tracking Points Count: ${currentResult?.trackingPoints?.size ?: 0}")
                Text("Failure Reason: ${currentResult?.failureReason ?: "None"}")
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text("Corner Decoding", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text("Decoded IDs: ${currentResult?.decodedCornerIds}")
                Text("Decoded Bits: ${currentResult?.decodedCornerBits}")
                Text("Hamming Distances: ${currentResult?.decodedCornerDistances}")
                Text("Margins: ${currentResult?.decodedCornerMargins}")
                currentResult?.cornerMatches?.forEach { (pos, match) ->
                    Text("  [$pos] expected=${match.expectedPattern} decoded=${match.decodedPattern} bestId=${match.bestMatchId} dist=${match.hammingDistance} margin=${match.secondBestMargin}", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                currentResult?.bitSamples?.forEach { (corner, samples) ->
                    Text("  Bits for $corner:", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    samples.forEach { sample ->
                        Text("    [${sample.bitName}] (${String.format(Locale.US, "%.1f", sample.x)}, ${String.format(Locale.US, "%.1f", sample.y)}) med=${sample.medianLuma} black=${sample.localBlackRef} white=${sample.localWhiteRef} thresh=${sample.threshold} -> ${sample.decodedBit}", fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text("Pilots (YUV)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text("Pilots: ${currentResult?.pilotYUVs?.mapValues { it.value.joinToString(",") }}")
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // FULL DIAGNOSTIC CAPTURE IN DEBUG SHEET
                Text("Full Diagnostic Capture", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (V6FullDiagnosticExporter.IS_SUPPORTED) {
                        Button(
                            onClick = {
                                fullDiagnosticArmed.set(true)
                                armedTimestamp.set(System.currentTimeMillis())
                                isDiagnosticArmed = true
                                captureStatusMessage = "Armed: Waiting for LOCKED/TRACKING frame (5s window)..."
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDiagnosticArmed) Color(0xFFE65100) else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(if (isDiagnosticArmed) "Armed (Waiting...)" else "Full Diagnostic")
                        }

                        if (capturedZipFile != null) {
                            Button(onClick = {
                                shareZipFile(context, capturedZipFile!!)
                            }) {
                                Text("Share ZIP")
                            }
                        }
                    }
                }
                if (captureStatusMessage != null) {
                    Text(text = captureStatusMessage!!, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = if (isDiagnosticArmed) Color(0xFFE65100) else Color(0xFF2E7D32))
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // FROZEN SNAPSHOT IN DEBUG
                Text("Frozen Snapshot", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = { freezeRequested.set(true) }) { Text("Freeze") }
                    if (frozenSnapshot != null) {
                        Button(onClick = {
                            val hexDec = frozenSnapshot?.decodedCrc32?.let { String.format("%08X", it) } ?: "N/A"
                            val snapText = "Snapshot Correct: ${frozenSnapshot?.colorCorrect}/400 CRC: $hexDec\nImage Path: ${frozenSnapshot?.debugImagePath}"
                            clipboardManager.setText(AnnotatedString(snapText))
                        }) { Text("Copy") }
                        Button(onClick = {
                            val hexDec = frozenSnapshot?.decodedCrc32?.let { String.format("%08X", it) } ?: "N/A"
                            val snapText = "Snapshot Correct: ${frozenSnapshot?.colorCorrect}/400 CRC: $hexDec\nImage Path: ${frozenSnapshot?.debugImagePath}"
                            shareReportFile(context, snapText)
                        }) { Text("Share") }
                        Button(onClick = { frozenSnapshot = null }) { Text("Clear") }
                    }
                }
                if (frozenSnapshot != null) {
                    val hexDec = frozenSnapshot?.decodedCrc32?.let { String.format("%08X", it) } ?: "N/A"
                    Text("Snapshot Correct: ${frozenSnapshot?.colorCorrect}/400 CRC: $hexDec")
                    Text("Snapshot Image: ${frozenSnapshot?.debugImagePath ?: "None"}")
                } else {
                    Text("No frozen snapshot captured.", color = Color.Gray)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text("Deduplicated Logs (${diagnosticLogger.getLogs().size})", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                for (log in diagnosticLogger.getLogs().reversed()) {
                    Text("[${log.timestamp}] ${log.level}/${log.category} (x${log.count}): ${log.message}", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }
}

@Composable
private fun StatusPair(label: String, value: String, isGood: Boolean) {
    Column {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = if (isGood) Color(0xFF2E7D32) else Color(0xFFD32F2F)
        )
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
