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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.superqr.android.colorgrid8.ColorGrid8FrameContinuity
import com.superqr.android.colorgrid8.ColorGrid8LabCameraManager
import com.superqr.android.colorgrid8.ColorGrid8RunEstimator
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8AnalysisResult
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8Profile
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8Spec
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

    var gridIndex by remember { mutableIntStateOf(3) } // 168x144
    var fpsIndex by remember { mutableIntStateOf(3) } // 30 FPS
    val dims = ColorGrid8Spec.grids[gridIndex]
    val profile = ColorGrid8Profile(dims.first, dims.second, ColorGrid8Spec.fpsSweep[fpsIndex])

    var uniqueFrames by remember { mutableIntStateOf(0) }
    var observations by remember { mutableIntStateOf(0) }
    var sumSer by remember { mutableDoubleStateOf(0.0) }
    var sumBer by remember { mutableDoubleStateOf(0.0) }
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
        sumSer = 0.0
        sumBer = 0.0
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
        resetStats()
    }

    DisposableEffect(manager) {
        manager.onSample = { sample ->
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
                sumSer += analysis.symbolErrorRate
                sumBer += analysis.bitErrorRate
                sumErasure += analysis.erasureRate
                sumFecLoad += analysis.fecLoad
                lastResult = analysis
            }
        }
        onDispose {
            manager.onSample = null
            manager.destroy()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) manager.stop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (!permission) launcher.launch(Manifest.permission.CAMERA)
    }

    val running = status == ColorGrid8LabCameraManager.Status.RUNNING ||
        status == ColorGrid8LabCameraManager.Status.STARTING
    val avgSer = if (observations > 0) sumSer / observations else 0.0
    val avgBer = if (observations > 0) sumBer / observations else 0.0
    val avgErasure = if (observations > 0) sumErasure / observations else 0.0
    val avgFecLoad = if (observations > 0) sumFecLoad / observations else 0.0
    val runEstimate = ColorGrid8RunEstimator.estimate(
        postFecBudgetKibS = profile.postFecKibS(),
        channelFecLoad = avgFecLoad,
        frameDeliveryRatio = frameDeliveryRatio,
    )
    val estimatedGoodput = if (observations > 0) runEstimate.estimatedPostFecKibS else 0.0
    // Do not declare victory from a handful of easy frames. Require at least
    // two sender-seconds of logical continuity in addition to the latency target.
    val enoughRun = observations >= 60 && sentTransitions >= (profile.fps * 2L)
    val targetPass = enoughRun && estimatedGoodput >= 200.0 && p95Ms in 0.01..24.999

    Column(
        modifier.fillMaxSize().background(Color(0xFF090B10)).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("ColorGrid8 PHY Lab", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("8 symbols = Y:1 bit + UV:2 bits • YUV420 direct", color = Color(0xFF7CB7FF), fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { gridIndex = (gridIndex + 1) % ColorGrid8Spec.grids.size },
                enabled = !running,
                modifier = Modifier.weight(1f),
            ) { Text("GRID ${profile.cols}×${profile.rows}", fontSize = 11.sp) }
            OutlinedButton(
                onClick = { fpsIndex = (fpsIndex + 1) % ColorGrid8Spec.fpsSweep.size },
                enabled = !running,
                modifier = Modifier.weight(1f),
            ) { Text("SENDER ${profile.fps} FPS", fontSize = 11.sp) }
        }
        Text(
            "Budget: ${"%.1f".format(profile.rawKibS)} raw • ${"%.1f".format(profile.payloadKibS)} after header/pilots • ${"%.1f".format(profile.postFecKibS())} KiB/s after 20% FEC",
            color = if (profile.postFecKibS() >= 200.0) Color(0xFF7EE787) else Color.White.copy(alpha = 0.55f),
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
            Grid8Metric("Camera", "%.1f fps".format(cameraFps))
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
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.06f)),
            ) {
                Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        if (targetPass) "TARGET PASS ≥200 KiB/s / p95<25ms" else "LIVE CHANNEL",
                        color = if (targetPass) Color(0xFF7EE787) else Color(0xFFF2CC60),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    )
                    Text(
                        "SER ${"%.3f".format(avgSer * 100)}% • BER ${"%.3f".format(avgBer * 100)}% • erasures ${"%.2f".format(avgErasure * 100)}%",
                        color = Color.White,
                        fontSize = 12.sp,
                    )
                    Text(
                        "Cell FEC ${"%.2f".format(avgFecLoad * 100)}% • frame loss ${"%.2f".format(runEstimate.frameLossRate * 100)}% • total ${"%.2f".format(runEstimate.totalFecLoad * 100)}% / 20%",
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 11.sp,
                    )
                    Text(
                        "Estimated recoverable goodput ${"%.1f".format(estimatedGoodput)} KiB/s • unique $uniqueFrames",
                        color = if (estimatedGoodput >= 200.0) Color(0xFF7EE787) else Color.White.copy(alpha = 0.78f),
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
                        Spacer(Modifier.height(4.dp))
                        Text("CONFUSION MATRIX rows=expected cols=observed", color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp)
                        Text(
                            confusionText(result.confusionMatrix),
                            color = Color.White.copy(alpha = 0.62f),
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { if (running) manager.stop() else manager.start(lifecycleOwner) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) Color(0xFFE53935) else Color(0xFF43A047)
                ),
            ) { Text(if (running) "STOP" else "START GRID8 LAB", fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = {
                    val text = buildString {
                        appendLine("SuperQR ColorGrid8 PHY Lab")
                        appendLine("profile=${profile.cols}x${profile.rows}@${profile.fps}")
                        appendLine("observations=$observations uniqueFrames=$uniqueFrames sentTransitions=$sentTransitions")
                        appendLine("frameDelivery=${"%.3f".format(frameDeliveryRatio * 100)}% frameLoss=${"%.3f".format(runEstimate.frameLossRate * 100)}%")
                        appendLine("SER=${"%.4f".format(avgSer * 100)}% BER=${"%.4f".format(avgBer * 100)}% erasures=${"%.3f".format(avgErasure * 100)}%")
                        appendLine("cellFecLoad=${"%.3f".format(avgFecLoad * 100)}% totalFecLoad=${"%.3f".format(runEstimate.totalFecLoad * 100)}%")
                        appendLine("estimated recoverable goodput=${"%.2f".format(estimatedGoodput)} KiB/s")
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
    }
}

private fun confusionText(matrix: IntArray): String {
    if (matrix.size < 64) return "—"
    return (0 until 8).joinToString("\n") { row ->
        (0 until 8).joinToString(" ") { col -> "%4d".format(matrix[row * 8 + col]) }
    }
}

@Composable
private fun Grid8Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color.White.copy(alpha = 0.45f), fontSize = 9.sp)
    }
}
