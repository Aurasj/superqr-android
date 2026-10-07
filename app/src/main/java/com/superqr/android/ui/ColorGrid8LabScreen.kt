package com.superqr.android.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.superqr.android.colorgrid8.ColorGrid8Camera2Manager
import com.superqr.android.colorgrid8.ColorGrid8FrameContinuity
import com.superqr.android.colorgrid8.ColorGrid8GlPipeline
import com.superqr.android.colorgrid8.ColorGrid8LabCameraManager
import com.superqr.android.colorgrid8.ColorGrid8RunEstimator
import com.superqr.android.colorgrid8.ColorGrid8TransferReceiverSession
import com.superqr.android.transfer.TransferReceiveStatus
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8AnalysisResult
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8Profile
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8Spec
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8TransferCodec
import com.superqr.android.vision.lab.colorgrid8.gl.ColorGrid8GlThread
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.launch
import kotlin.math.min

@Composable
fun ColorGrid8LabScreen(
    analysisExecutor: ExecutorService,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var permission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    val manager = remember { ColorGrid8LabCameraManager(context, analysisExecutor) }
    val glThread = remember { ColorGrid8GlThread() }
    val camera2Manager = remember { ColorGrid8Camera2Manager(context, glThread) }
    val glPipeline = remember { ColorGrid8GlPipeline(glThread, analysisExecutor) }
    val transferReceiver = remember { ColorGrid8TransferReceiverSession(context) }

    var cameraPathMode by remember { mutableStateOf("CAMERA2_GL") } // "CAMERA2_GL" or "CAMERAX_NATIVE"
    var sampleModeIndex by remember { mutableIntStateOf(1) } // 0: 1x, 1: 2x2, 2: 3x3
    val sampleModes = listOf("1x", "2x2", "3x3")

    val status by manager.status.collectAsState()
    val cameraFps by manager.cameraFps.collectAsState()
    val pipelineMs by manager.pipelineMs.collectAsState()
    val geometryMs by manager.geometryMs.collectAsState()
    val warpMs by manager.warpMs.collectAsState()
    val resolution by manager.resolution.collectAsState()
    val acquisitionMode by manager.acquisitionMode.collectAsState()
    val stage by manager.stage.collectAsState()
    val failure by manager.failure.collectAsState()
    val finderCandidates by manager.finderCandidates.collectAsState()
    val geometryLocked by manager.geometryLocked.collectAsState()
    val headerStatus by manager.headerStatus.collectAsState()
    val expectedProfile by manager.expectedProfile.collectAsState()
    val detectedProfile by manager.detectedProfile.collectAsState()

    val camera2Status by camera2Manager.status.collectAsState()
    val camera2Fps by camera2Manager.cameraFps.collectAsState()
    val camera2Resolution by camera2Manager.resolution.collectAsState()
    val camera2Iso by camera2Manager.iso.collectAsState()
    val camera2Exp by camera2Manager.exposureTime.collectAsState()
    val camera2Awb by camera2Manager.awbState.collectAsState()
    val camera2Ae by camera2Manager.aeState.collectAsState()
    val camera2Focus by camera2Manager.focusState.collectAsState()

    val transferState by transferReceiver.state.collectAsState()
    val transferDiagnostics by transferReceiver.diagnostics.collectAsState()

    var gridIndex by remember { mutableIntStateOf(1) } // balanced 336x288
    var fpsIndex by remember { mutableIntStateOf(2) } // 60 FPS
    val dims = ColorGrid8Spec.transferGrids[gridIndex]
    val profile = ColorGrid8Profile(
        dims.first,
        dims.second,
        ColorGrid8Spec.transferFpsSweep[fpsIndex],
        version = ColorGrid8Spec.TRANSFER_HEADER_VERSION,
    )

    var uniqueFrames by remember { mutableIntStateOf(0) }
    var observations by remember { mutableIntStateOf(0) }
    var sumErasure by remember { mutableDoubleStateOf(0.0) }
    var sumFecLoad by remember { mutableDoubleStateOf(0.0) }
    var p50Ms by remember { mutableDoubleStateOf(0.0) }
    var p95Ms by remember { mutableDoubleStateOf(0.0) }
    var lastPackMs by remember { mutableDoubleStateOf(0.0) }
    var lastResult by remember { mutableStateOf<ColorGrid8AnalysisResult?>(null) }
    var seen by remember { mutableStateOf(BooleanArray(65536)) }
    val latencyWindow = remember { DoubleArray(120) }
    var latencyCount by remember { mutableIntStateOf(0) }
    val continuity = remember { ColorGrid8FrameContinuity() }
    var frameDeliveryRatio by remember { mutableDoubleStateOf(1.0) }
    var sentTransitions by remember { mutableLongStateOf(0L) }
    var dataPhaseStarted by remember { mutableStateOf(false) }

    fun resetStats() {
        uniqueFrames = 0
        observations = 0
        sumErasure = 0.0
        sumFecLoad = 0.0
        p50Ms = 0.0
        p95Ms = 0.0
        lastPackMs = 0.0
        lastResult = null
        seen = BooleanArray(65536)
        latencyWindow.fill(0.0)
        latencyCount = 0
        continuity.reset()
        frameDeliveryRatio = 1.0
        sentTransitions = 0L
        dataPhaseStarted = false
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

    LaunchedEffect(profile) {
        manager.setProfile(profile)
        glPipeline.profile = profile
        transferReceiver.reset()
        resetStats()
    }

    LaunchedEffect(sampleModeIndex) {
        glPipeline.sampleMode = sampleModeIndex
    }

    DisposableEffect(glPipeline, profile, sampleModeIndex, camera2Manager) {
        glPipeline.profile = profile
        glPipeline.sampleMode = sampleModeIndex
        camera2Manager.onResolutionChanged = { w, h ->
            glPipeline.setResolution(w, h)
        }
        glPipeline.onResult = { glResult ->
            glResult.processResult?.analysis?.let { analysis ->
                transferReceiver.onAnalysis(profile, analysis, glResult.fullPipelineMs)
            }
            scope.launch {
                val processResult = glResult.processResult
                if (!dataPhaseStarted) {
                    if (processResult?.analysis == null) return@launch
                    dataPhaseStarted = true
                }
                recordLatency(glResult.fullPipelineMs)
                val analysis = processResult?.analysis ?: return@launch
                val frameIndex = analysis.header.frameIndex and 0xFFFF
                continuity.observe(frameIndex)
                frameDeliveryRatio = continuity.deliveryRatio
                sentTransitions = continuity.sentTransitions
                if (!seen[frameIndex]) {
                    seen[frameIndex] = true
                    uniqueFrames++
                }
                observations++
                sumErasure += analysis.erasureRate
                sumFecLoad += analysis.fecLoad
                lastResult = analysis
            }
        }
        onDispose {
            glPipeline.onResult = null
        }
    }

    DisposableEffect(manager, profile) {
        manager.onSample = { sample ->
            sample.result?.analysis?.let { analysis ->
                transferReceiver.onAnalysis(profile, analysis, sample.totalWithPackMs)
            }
            scope.launch {
                val processResult = sample.result
                // Ignore the balanced sender warm-up until the first real data frame
                // decodes. After data begins, latency includes failed frames too.
                if (!dataPhaseStarted) {
                    if (processResult?.analysis == null) return@launch
                    dataPhaseStarted = true
                }
                lastPackMs = sample.packMs
                recordLatency(sample.totalWithPackMs)
                val analysis = processResult?.analysis ?: return@launch
                val frameIndex = analysis.header.frameIndex and 0xFFFF
                continuity.observe(frameIndex)
                frameDeliveryRatio = continuity.deliveryRatio
                sentTransitions = continuity.sentTransitions
                if (!seen[frameIndex]) {
                    seen[frameIndex] = true
                    uniqueFrames++
                }
                observations++
                sumErasure += analysis.erasureRate
                sumFecLoad += analysis.fecLoad
                lastResult = analysis
            }
        }
        onDispose {
            manager.onSample = null
        }
    }

    DisposableEffect(manager, camera2Manager, glThread, glPipeline) {
        onDispose {
            manager.destroy()
            camera2Manager.destroy()
            glPipeline.release()
            glThread.release()
            transferReceiver.close()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                manager.stop()
                camera2Manager.stop()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (!permission) launcher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(transferState.status) {
        if (transferState.status in setOf(
                TransferReceiveStatus.VERIFYING,
                TransferReceiveStatus.PREVIEW,
                TransferReceiveStatus.SAVING,
                TransferReceiveStatus.SAVED,
            )) {
            manager.stop()
            camera2Manager.stop()
        }
    }

    val running = if (cameraPathMode == "CAMERA2_GL") {
        camera2Status == ColorGrid8Camera2Manager.Status.RUNNING || camera2Status == ColorGrid8Camera2Manager.Status.STARTING
    } else {
        status == ColorGrid8LabCameraManager.Status.RUNNING || status == ColorGrid8LabCameraManager.Status.STARTING
    }
    val effectiveCameraFps = if (cameraPathMode == "CAMERA2_GL") camera2Fps else cameraFps
    val effectiveResolution = if (cameraPathMode == "CAMERA2_GL") camera2Resolution else resolution
    val avgErasure = if (observations > 0) sumErasure / observations else 0.0
    val avgFecLoad = if (observations > 0) sumFecLoad / observations else 0.0
    val protectedChannelBudget = ColorGrid8TransferCodec.logicalChunkCapacity(profile) *
        profile.fps * 8.0 / 9.0 / 1024.0
    val runEstimate = ColorGrid8RunEstimator.estimate(
        postFecBudgetKibS = protectedChannelBudget,
        channelFecLoad = avgFecLoad,
        frameDeliveryRatio = frameDeliveryRatio,
    )
    val estimatedGoodput = if (observations > 0) runEstimate.estimatedPostFecKibS else 0.0
    val fileVerified = transferState.status in setOf(
        TransferReceiveStatus.PREVIEW, TransferReceiveStatus.SAVING, TransferReceiveStatus.SAVED,
    )

    Column(
        modifier.fillMaxSize().background(Color(0xFF090B10)).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("ColorGrid8 High-Speed Lab", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Experimental file receiver • production V40 remains separate", color = Color(0xFF7CB7FF), fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { gridIndex = (gridIndex + 1) % ColorGrid8Spec.transferGrids.size },
                enabled = !running && transferState.status !in setOf(TransferReceiveStatus.VERIFYING, TransferReceiveStatus.PREVIEW, TransferReceiveStatus.SAVING),
                modifier = Modifier.weight(1f),
            ) { Text("GRID ${profile.cols}×${profile.rows}", fontSize = 11.sp) }
            OutlinedButton(
                onClick = { fpsIndex = (fpsIndex + 1) % ColorGrid8Spec.transferFpsSweep.size },
                enabled = !running && transferState.status !in setOf(TransferReceiveStatus.VERIFYING, TransferReceiveStatus.PREVIEW, TransferReceiveStatus.SAVING),
                modifier = Modifier.weight(1f),
            ) { Text("SENDER ${profile.fps} FPS", fontSize = 11.sp) }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    cameraPathMode = if (cameraPathMode == "CAMERA2_GL") "CAMERAX_NATIVE" else "CAMERA2_GL"
                },
                enabled = !running,
                modifier = Modifier.weight(1f),
            ) { Text(if (cameraPathMode == "CAMERA2_GL") "PATH: CAMERA2_GL" else "PATH: CAMERAX_NATIVE", fontSize = 11.sp) }
            OutlinedButton(
                onClick = { sampleModeIndex = (sampleModeIndex + 1) % sampleModes.size },
                enabled = !running,
                modifier = Modifier.weight(1f),
            ) { Text("SAMPLING: ${sampleModes[sampleModeIndex]}", fontSize = 11.sp) }
        }
        Text(
            "Channel budget: ${"%.1f".format(protectedChannelBudget)} KiB/s after 8+1 XOR • not a measured speed",
            color = if (protectedChannelBudget >= 1024.0) Color(0xFF7EE787) else Color.White.copy(alpha = 0.55f),
            fontSize = 10.sp,
        )
        Spacer(Modifier.height(6.dp))

        if (!permission) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("GRANT CAMERA") }
            }
            return@Column
        }

        Card(
            Modifier.fillMaxWidth().weight(0.52f),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black),
        ) {
            AndroidView(factory = { manager.previewView }, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(6.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Grid8Metric("Camera", "%.1f fps".format(effectiveCameraFps))
            Grid8Metric("p50", if (p50Ms > 0) "%.1f ms".format(p50Ms) else "—")
            Grid8Metric("p95", if (p95Ms > 0) "%.1f ms".format(p95Ms) else "—")
            Grid8Metric("Delivery", if (sentTransitions > 0) "%.1f%%".format(frameDeliveryRatio * 100) else "—")
        }
        Spacer(Modifier.height(5.dp))

        Column(
            Modifier.fillMaxWidth().weight(0.48f).verticalScroll(rememberScrollState()),
        ) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF11151D)),
            ) {
                Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    val transferTitle = when (transferState.status) {
                        TransferReceiveStatus.WAITING -> "WAITING FOR COLORGRID8 V2"
                        TransferReceiveStatus.RECEIVING -> "RECEIVING ${transferState.filename}"
                        TransferReceiveStatus.VERIFYING -> "VERIFYING CRC32 + SHA-256"
                        TransferReceiveStatus.PREVIEW -> "VERIFIED — REVIEW BEFORE SAVE"
                        TransferReceiveStatus.SAVING -> "SAVING"
                        TransferReceiveStatus.SAVED -> "FILE SAVED"
                        TransferReceiveStatus.ERROR -> "TRANSFER ERROR"
                    }
                    Text(
                        transferTitle,
                        color = if (transferState.status in setOf(TransferReceiveStatus.PREVIEW, TransferReceiveStatus.SAVED)) Color(0xFF7EE787) else Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    )
                    if (transferState.totalFrames > 0) {
                        val progress = transferState.progress.coerceIn(0f, 1f)
                        Box(Modifier.fillMaxWidth().height(7.dp).background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(4.dp))) {
                            Box(Modifier.fillMaxWidth(progress).height(7.dp).background(Color(0xFF1976D2), RoundedCornerShape(4.dp)))
                        }
                        Text(
                            "${transferState.uniqueFrames}/${transferState.totalFrames} data frames • ${"%.1f".format(progress * 100)}% • ${"%.1f".format(transferState.usefulKibPerSecond)} KiB/s measured",
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 11.sp,
                        )
                    }
                    if (transferState.filename.isNotEmpty()) {
                        Text(
                            "${transferState.filename} • ${formatGrid8Bytes(transferState.fileSize)} • ${transferState.mimeType}",
                            color = Color.White.copy(alpha = 0.58f),
                            fontSize = 10.sp,
                        )
                    }
                    Text(
                        "XOR parity ${transferDiagnostics.parityFrames} • recovered ${transferDiagnostics.recoveredFrames} • rejected ${transferDiagnostics.rejectedFrames} • duplicates ${transferState.duplicates}",
                        color = Color.White.copy(alpha = 0.58f),
                        fontSize = 10.sp,
                    )
                    if (transferState.status == TransferReceiveStatus.PREVIEW) {
                        ReceivedContentPreview(transferState)
                        Text("SHA-256 ${transferState.sha256Hex}", color = Color.White.copy(alpha = 0.48f), fontSize = 9.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { transferReceiver.save() }) { Text("SAVE") }
                            OutlinedButton(onClick = {
                                val uri = transferState.previewUri ?: return@OutlinedButton
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = transferState.mimeType.ifBlank { "application/octet-stream" }
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        },
                                        "Share verified ColorGrid8 file",
                                    )
                                )
                            }) { Text("SHARE") }
                            OutlinedButton(onClick = { transferReceiver.discard() }) { Text("DISCARD") }
                        }
                    }
                    if (transferState.status == TransferReceiveStatus.SAVED) {
                        Text("Saved to ${transferState.savedLocation}", color = Color.White.copy(alpha = 0.58f), fontSize = 10.sp)
                        OutlinedButton(onClick = { transferReceiver.reset() }) { Text("RECEIVE ANOTHER") }
                    }
                    if (transferState.status == TransferReceiveStatus.ERROR) {
                        Text(transferState.error.orEmpty(), color = Color(0xFFFF8A80), fontSize = 10.sp)
                        OutlinedButton(onClick = { transferReceiver.reset() }) { Text("TRY AGAIN") }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.06f)),
            ) {
                Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        if (fileVerified) "FILE VERIFIED" else "EXPERIMENTAL OPTICAL CHANNEL",
                        color = if (fileVerified) Color(0xFF7EE787) else Color(0xFFF2CC60),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    )
                    Text(
                        "Erasures ${"%.2f".format(avgErasure * 100)}% • valid transport frames require CRC32",
                        color = Color.White,
                        fontSize = 12.sp,
                    )
                    Text(
                        "Frame loss estimate ${"%.2f".format(runEstimate.frameLossRate * 100)}% • optical delivery ${"%.2f".format(frameDeliveryRatio * 100)}%",
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 11.sp,
                    )
                    Text(
                        "Estimated channel ${"%.1f".format(estimatedGoodput)} KiB/s • measured file ${"%.1f".format(transferState.usefulKibPerSecond)} KiB/s",
                        color = if (transferState.usefulKibPerSecond >= 1024.0) Color(0xFF7EE787) else Color.White.copy(alpha = 0.78f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "PIPELINE $stage${if (failure.isNotEmpty()) " • $failure" else " • frame decoded"}",
                        color = if (failure.isEmpty()) Color(0xFF7EE787) else Color(0xFFF2CC60),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Finders $finderCandidates • geometry ${if (geometryLocked) "LOCKED" else "UNLOCKED"} ($acquisitionMode) • header $headerStatus",
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 10.sp,
                    )
                    Text(
                        "Expected ${expectedProfile.ifEmpty { "pending" }} • detected ${detectedProfile.ifEmpty { "none" }}",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 10.sp,
                    )
                    Text(
                        "Pack ${"%.1f".format(lastPackMs)} ms • geometry ${"%.1f".format(geometryMs)} ms • warp/means ${"%.1f".format(warpMs)} ms • total ${"%.1f".format(pipelineMs)} ms",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 10.sp,
                    )
                    lastResult?.let { result ->
                        Text(
                            "Last frame ${result.header.frameIndex} • pilot min UV ${"%.1f".format(result.pilotMinUvDistance)} • Y threshold ${"%.1f".format(result.lumaThreshold)}",
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 10.sp,
                        )
                        Text(
                            "Capture ${resolution.ifEmpty { "pending" }} • $observations decoded observations",
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 10.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (running) {
                        manager.stop()
                        camera2Manager.stop()
                    } else {
                        if (transferState.status in setOf(TransferReceiveStatus.SAVED, TransferReceiveStatus.ERROR)) {
                            transferReceiver.reset()
                        }
                        if (cameraPathMode == "CAMERA2_GL") {
                            camera2Manager.start()
                        } else {
                            manager.start(lifecycleOwner)
                        }
                    }
                },
                enabled = transferState.status !in setOf(
                    TransferReceiveStatus.VERIFYING,
                    TransferReceiveStatus.PREVIEW,
                    TransferReceiveStatus.SAVING,
                ),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) Color(0xFFE53935) else Color(0xFF43A047)
                ),
            ) { Text(if (running) "STOP" else "START GRID8 RECEIVE", fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = {
                    val text = buildString {
                        appendLine("SuperQR ColorGrid8 high-speed LAB")
                        appendLine("profile=${profile.cols}x${profile.rows}@${profile.fps}")
                        appendLine("observations=$observations uniqueFrames=$uniqueFrames sentTransitions=$sentTransitions")
                        appendLine("frameDelivery=${"%.3f".format(frameDeliveryRatio * 100)}% frameLoss=${"%.3f".format(runEstimate.frameLossRate * 100)}%")
                        appendLine("erasures=${"%.3f".format(avgErasure * 100)}% rejected=${transferDiagnostics.rejectedFrames}")
                        appendLine("parity=${transferDiagnostics.parityFrames} recovered=${transferDiagnostics.recoveredFrames}")
                        appendLine("estimated channel=${"%.2f".format(estimatedGoodput)} KiB/s")
                        appendLine("measured file goodput=${"%.2f".format(transferState.usefulKibPerSecond)} KiB/s")
                        appendLine("pipeline p50=${"%.2f".format(p50Ms)} ms p95=${"%.2f".format(p95Ms)} ms")
                        appendLine("capture=$resolution acquisition=$acquisitionMode")
                        appendLine("pipelineStage=$stage failure=$failure finders=$finderCandidates geometryLocked=$geometryLocked header=$headerStatus")
                        appendLine("expected=$expectedProfile detected=$detectedProfile")
                    }
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            },
                            "Share ColorGrid8 results",
                        )
                    )
                },
                modifier = Modifier.weight(1f),
            ) { Text("SHARE RESULTS") }
        }

        Spacer(Modifier.height(8.dp))
        var benchSummary by remember { mutableStateOf<String?>(null) }
        Button(
            onClick = {
                analysisExecutor.execute {
                    val report = com.superqr.android.vision.lab.colorgrid8.MacrochromaNativeBenchRunner.runOnDeviceBenchmark()
                    benchSummary = "Native: ${if (report.realJniInvoked) "YES" else "NO"} (${report.framesExecutedNative} frames) | Clean: ${if (report.cleanFrameParity) "PASS" else "FAIL"} | RS: ${if (report.rs1ByteParity && report.rs2ByteParity && report.rs3ByteRejection) "PASS" else "FAIL"} | 10MB SHA: ${if (report.fullSha256Match) "PASS" else "FAIL"} | p50: ${"%.2f".format(report.nativeP50Ms)}ms"
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
        ) {
            Text("RUN NATIVE SYNTHETIC BENCHMARK", fontWeight = FontWeight.Bold)
        }
        benchSummary?.let { summary ->
            Text(summary, color = Color(0xFF4CAF50), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun formatGrid8Bytes(value: Long): String = when {
    value >= 1024L * 1024L * 1024L -> "%.2f GiB".format(value / (1024.0 * 1024.0 * 1024.0))
    value >= 1024L * 1024L -> "%.2f MiB".format(value / (1024.0 * 1024.0))
    value >= 1024L -> "%.1f KiB".format(value / 1024.0)
    else -> "$value B"
}

@Composable
private fun Grid8Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color.White.copy(alpha = 0.45f), fontSize = 9.sp)
    }
}
