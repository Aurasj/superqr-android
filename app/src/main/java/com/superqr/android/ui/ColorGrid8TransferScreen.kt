package com.superqr.android.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.superqr.android.colorgrid8.ColorGrid8Camera2Manager
import com.superqr.android.colorgrid8.ColorGrid8FrameContinuity
import com.superqr.android.colorgrid8.ColorGrid8GlPipeline
import com.superqr.android.colorgrid8.ColorGrid8TransferReceiverSession
import com.superqr.android.transfer.TransferReceiveStatus
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8AnalysisResult
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8Profile
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8Spec
import com.superqr.android.vision.lab.colorgrid8.gl.ColorGrid8GlSampler
import com.superqr.android.vision.lab.colorgrid8.gl.ColorGrid8GlThread
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ExecutorService
import kotlin.math.min
import kotlinx.coroutines.launch

/**
 * Dedicated ColorGrid8 High-Speed File Transfer Window.
 *
 * Isolated from both the standard QR V40 production receiver and the experimental diagnostic LAB.
 * Pre-configured for ColorGrid8 v2 (336x288 @ 30 FPS).
 */
@Composable
fun ColorGrid8TransferScreen(
    analysisExecutor: ExecutorService,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var permission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission = it
    }

    // Pre-configured baseline profile: 336x288 @ 30 FPS, Transport v2
    val profile = remember {
        ColorGrid8Profile(
            cols = 336,
            rows = 288,
            fps = 30,
            version = ColorGrid8Spec.TRANSFER_HEADER_VERSION,
        )
    }

    val glThread = remember { ColorGrid8GlThread() }
    val camera2Manager = remember { ColorGrid8Camera2Manager(context, glThread) }
    val glPipeline = remember { ColorGrid8GlPipeline(glThread, analysisExecutor) }
    val transferReceiver = remember { ColorGrid8TransferReceiverSession(context) }

    val cameraStatus by camera2Manager.status.collectAsState()
    val cameraFps by camera2Manager.cameraFps.collectAsState()
    val cameraResolution by camera2Manager.resolution.collectAsState()
    val transferState by transferReceiver.state.collectAsState()
    val transferDiagnostics by transferReceiver.diagnostics.collectAsState()

    var uniqueFrames by remember { mutableIntStateOf(0) }
    var observations by remember { mutableIntStateOf(0) }
    var p50Ms by remember { mutableDoubleStateOf(0.0) }
    var p95Ms by remember { mutableDoubleStateOf(0.0) }
    var lastResult by remember { mutableStateOf<ColorGrid8AnalysisResult?>(null) }
    var seen by remember { mutableStateOf(BooleanArray(65536)) }
    val latencyWindow = remember { DoubleArray(120) }
    var latencyCount by remember { mutableIntStateOf(0) }
    val continuity = remember { ColorGrid8FrameContinuity() }
    var frameDeliveryRatio by remember { mutableDoubleStateOf(1.0) }
    var sentTransitions by remember { mutableLongStateOf(0L) }
    var dataPhaseStarted by remember { mutableStateOf(false) }
    var isLocked by remember { mutableStateOf(false) }
    var opticalStatus by remember { mutableStateOf("Waiting for camera") }

    val previewBitmap = remember {
        Bitmap.createBitmap(
            ColorGrid8GlSampler.FINDER_WIDTH,
            ColorGrid8GlSampler.FINDER_HEIGHT,
            Bitmap.Config.ARGB_8888
        )
    }
    val pixelBuffer = remember {
        ByteBuffer.allocateDirect(
            ColorGrid8GlSampler.FINDER_WIDTH * ColorGrid8GlSampler.FINDER_HEIGHT * 4
        ).order(ByteOrder.nativeOrder())
    }
    var previewRevision by remember { mutableLongStateOf(0L) }

    val isRunning = cameraStatus == ColorGrid8Camera2Manager.Status.RUNNING ||
            cameraStatus == ColorGrid8Camera2Manager.Status.STARTING

    fun resetStats() {
        uniqueFrames = 0
        observations = 0
        p50Ms = 0.0
        p95Ms = 0.0
        lastResult = null
        seen = BooleanArray(65536)
        latencyWindow.fill(0.0)
        latencyCount = 0
        continuity.reset()
        frameDeliveryRatio = 1.0
        sentTransitions = 0L
        dataPhaseStarted = false
        isLocked = false
    }

    fun recordLatency(value: Double) {
        latencyWindow[latencyCount % latencyWindow.size] = value
        latencyCount++
        val n = min(latencyCount, latencyWindow.size)
        val ordered = latencyWindow.copyOf(n)
        ordered.sort()
        p50Ms = ordered[(n - 1) / 2]
        p95Ms = ordered[((n - 1) * 95) / 100]
    }

    // Pipeline binding
    DisposableEffect(glPipeline, profile, camera2Manager) {
        glPipeline.profile = profile
        glPipeline.sampleMode = 1 // 2x2 GPU centroid sampling for 336x288
        camera2Manager.onResolutionChanged = { w, h ->
            glPipeline.setResolution(w, h)
        }
        glPipeline.onResult = { glResult ->
            val analysis = glResult.processResult?.analysis
            analysis?.let {
                transferReceiver.onAnalysis(profile, it, glResult.fullPipelineMs)
            }
            scope.launch {
                val processResult = glResult.processResult
                isLocked = processResult?.hasVerifiedTransport == true
                opticalStatus = when {
                    isLocked -> "CRC-valid file data"
                    processResult?.failure != null -> "${processResult.stage}: ${processResult.failure}"
                    processResult?.headerStatus == "VALID" -> "Header OK • waiting for CRC-valid payload"
                    else -> "Searching for finder geometry"
                }
                recordLatency(glResult.fullPipelineMs)
                if (!dataPhaseStarted) {
                    if (analysis == null) return@launch
                    dataPhaseStarted = true
                }
                if (analysis == null) return@launch
                val frameIndex = analysis.header.frameIndex and 0xFFFF
                continuity.observe(frameIndex)
                frameDeliveryRatio = continuity.deliveryRatio
                sentTransitions = continuity.sentTransitions
                if (!seen[frameIndex]) {
                    seen[frameIndex] = true
                    uniqueFrames++
                }
                observations++
                lastResult = analysis
            }
        }
        glPipeline.onPreviewFrame = { bytes, _, _ ->
            pixelBuffer.clear()
            pixelBuffer.put(bytes)
            pixelBuffer.position(0)
            previewBitmap.copyPixelsFromBuffer(pixelBuffer)
            previewRevision++
        }
        onDispose {
            glPipeline.onResult = null
            glPipeline.onPreviewFrame = null
        }
    }

    // Auto-start camera when permission is granted and idle
    LaunchedEffect(permission) {
        if (permission && !isRunning) {
            camera2Manager.start()
        }
    }

    // Lifecycle cleanup
    DisposableEffect(camera2Manager, glThread, glPipeline, transferReceiver) {
        onDispose {
            camera2Manager.stop()
            camera2Manager.destroy()
            glPipeline.release()
            glThread.release()
            transferReceiver.close()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                camera2Manager.stop()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Auto-stop camera when transfer is verified / completed
    LaunchedEffect(transferState.status) {
        if (transferState.status in setOf(
                TransferReceiveStatus.VERIFYING,
                TransferReceiveStatus.PREVIEW,
                TransferReceiveStatus.SAVED,
            )
        ) {
            camera2Manager.stop()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF090B10))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Header
        Text(
            "ColorGrid8 Transfer",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "336×288 • 30 FPS • measured reception speed",
            color = Color(0xFF7CB7FF),
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(8.dp))

        if (!permission) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("ALLOW CAMERA")
                }
            }
            return@Column
        }

        // Throttled 8 FPS Viewfinder Card (Zero Transfer Performance Drop)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (previewRevision > 0L) {
                    key(previewRevision) {
                        Image(
                            bitmap = previewBitmap.asImageBitmap(),
                            contentDescription = "ColorGrid8 Viewfinder",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            if (isRunning) "Initializing Camera2 4K Stream…" else "Receiver Idle",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Aim camera at the monitor carrier",
                            color = Color(0xFF7CB7FF),
                            fontSize = 11.sp
                        )
                    }
                }

                // Overlay badges
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            "PREVIEW • 8 FPS OPTIMIZED",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .background(
                                if (isLocked) Color(0xDD00C853) else if (isRunning) Color(0xDDF57F17) else Color(0x88333333),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            if (isLocked) "LINK LOCKED ✓" else if (isRunning) "SEARCHING…" else "STANDBY",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Live Optical Pipeline Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF11151D)),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (isRunning) "CAMERA2 4K GL ACTIVE" else "RECEIVER IDLE",
                        color = if (isRunning) Color(0xFF7EE787) else Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${String.format(Locale.US, "%.1f", cameraFps)} FPS",
                        color = Color(0xFF8AB4F8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Optical Link: ${if (isLocked) "LOCKED ✓" else if (isRunning) "SEARCHING…" else "STANDBY"}",
                        color = if (isLocked) Color(0xFF7EE787) else if (isRunning) Color(0xFFF2CC60) else Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                    )
                    Text(
                        "p50/p95: ${if (p50Ms > 0) String.format(Locale.US, "%.1f/%.1f ms", p50Ms, p95Ms) else "—"}",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                    )
                }

                if (cameraResolution.isNotBlank()) {
                    Text(
                        "Sensor: $cameraResolution • GPU sampling 2x2",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 10.sp,
                    )
                }
                Text(opticalStatus, color = Color(0xFFF2CC60), fontSize = 10.sp)
            }
        }

        Spacer(Modifier.height(8.dp))

        // Main Transfer Progress & Verification Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val transferTitle = when (transferState.status) {
                    TransferReceiveStatus.WAITING -> "Waiting for ColorGrid8 Stream…"
                    TransferReceiveStatus.RECEIVING -> "Receiving ${transferState.filename.ifBlank { "file" }}"
                    TransferReceiveStatus.VERIFYING -> "Verifying Checksum (CRC32 + SHA-256)…"
                    TransferReceiveStatus.PREVIEW -> "Verified 100% Bit-for-Bit ✓"
                    TransferReceiveStatus.SAVING -> "Saving to device storage…"
                    TransferReceiveStatus.SAVED -> "File Saved Successfully ✓"
                    TransferReceiveStatus.ERROR -> "Transfer Error"
                }
                Text(
                    transferTitle,
                    color = when (transferState.status) {
                        TransferReceiveStatus.PREVIEW, TransferReceiveStatus.SAVED -> Color(0xFF7EE787)
                        TransferReceiveStatus.ERROR -> Color(0xFFFF8A80)
                        TransferReceiveStatus.RECEIVING -> Color(0xFF8AB4F8)
                        else -> Color.White
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )

                // Progress Bar
                if (transferState.totalFrames > 0) {
                    val progress = transferState.progress.coerceIn(0f, 1f)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(5.dp))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(progress)
                                .height(10.dp)
                                .background(Color(0xFF1976D2), RoundedCornerShape(5.dp))
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "${transferState.uniqueFrames} / ${transferState.totalFrames} data frames",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                        )
                        Text(
                            String.format(Locale.US, "%.1f%%", progress * 100),
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                // File metadata
                if (transferState.filename.isNotEmpty() || transferState.fileSize > 0) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            transferState.filename.ifBlank { "incoming_payload.bin" },
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            formatTransferBytes(transferState.fileSize),
                            color = Color(0xFF7CB7FF),
                            fontSize = 12.sp,
                        )
                    }
                }

                // Speed indicator
                if (transferState.status == TransferReceiveStatus.RECEIVING) {
                    val kibs = transferState.usefulKibPerSecond
                    val mbs = (kibs * 1024.0) / 1_000_000.0
                    Text(
                        String.format(Locale.US, "Speed: %.1f KiB/s (%.2f MB/s)", kibs, mbs),
                        color = if (kibs >= 800.0) Color(0xFF7EE787) else Color(0xFF8AB4F8),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "XOR parity ${transferDiagnostics.parityFrames} • recovered ${transferDiagnostics.recoveredFrames} • duplicates ${transferState.duplicates}",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 10.sp,
                    )
                }

                // Preview & Actions upon verification
                if (transferState.status == TransferReceiveStatus.PREVIEW) {
                    Text(
                        "SHA-256: ${transferState.sha256Hex}",
                        color = Color(0xFF7EE787),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(4.dp))
                    ReceivedContentPreview(transferState)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { transferReceiver.save() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                        ) {
                            Text("SAVE TO DOWNLOADS")
                        }
                        OutlinedButton(onClick = {
                            val uri = transferState.previewUri ?: return@OutlinedButton
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = transferState.mimeType.ifBlank { "application/octet-stream" }
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share verified file"))
                        }) {
                            Text("SHARE")
                        }
                        OutlinedButton(onClick = { transferReceiver.discard() }) {
                            Text("DISCARD")
                        }
                    }
                }

                if (transferState.status == TransferReceiveStatus.SAVED) {
                    Text(
                        "Saved to ${transferState.savedLocation}",
                        color = Color(0xFF7EE787),
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        transferState.savedUri?.let { uri ->
                            Button(onClick = {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, transferState.mimeType.ifBlank { "application/octet-stream" })
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                try {
                                    context.startActivity(intent)
                                } catch (_: Throwable) {
                                    Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Text("OPEN FILE")
                            }
                        }
                        OutlinedButton(onClick = {
                            transferReceiver.reset()
                            resetStats()
                        }) {
                            Text("RECEIVE ANOTHER")
                        }
                    }
                }

                if (transferState.status == TransferReceiveStatus.ERROR) {
                    Text(
                        transferState.error.orEmpty(),
                        color = Color(0xFFFF8A80),
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = {
                        transferReceiver.reset()
                        resetStats()
                    }) {
                        Text("TRY AGAIN")
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Start / Stop Controls
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    if (isRunning) {
                        camera2Manager.stop()
                    } else {
                        if (transferState.status in setOf(TransferReceiveStatus.SAVED, TransferReceiveStatus.ERROR)) {
                            transferReceiver.reset()
                            resetStats()
                        }
                        camera2Manager.start()
                    }
                },
                enabled = transferState.status !in setOf(
                    TransferReceiveStatus.VERIFYING,
                    TransferReceiveStatus.PREVIEW,
                    TransferReceiveStatus.SAVING,
                ),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) Color(0xFFD32F2F) else Color(0xFF1976D2)
                ),
            ) {
                Text(
                    if (isRunning) "STOP RECEIVER" else "START COLORGRID RECEIVER",
                    fontWeight = FontWeight.Bold,
                )
            }

            if (transferState.status == TransferReceiveStatus.RECEIVING || isRunning) {
                OutlinedButton(
                    onClick = {
                        transferReceiver.reset()
                        resetStats()
                    }
                ) {
                    Text("RESET")
                }
            }
        }
    }
}

private fun formatTransferBytes(value: Long): String = when {
    value >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GiB", value / (1024.0 * 1024.0 * 1024.0))
    value >= 1024L * 1024L -> String.format(Locale.US, "%.2f MiB", value / (1024.0 * 1024.0))
    value >= 1024L -> String.format(Locale.US, "%.1f KiB", value / 1024.0)
    else -> "$value B"
}
