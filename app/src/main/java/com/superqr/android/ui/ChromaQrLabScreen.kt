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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.superqr.android.chromaqr.ChromaQrLabCameraManager
import com.superqr.android.chromaqr.ChromaQrLabResult
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.launch

@Composable
fun ChromaQrLabScreen(
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
    val manager = remember { ChromaQrLabCameraManager(context, analysisExecutor) }
    val status by manager.status.collectAsState()
    val cameraFps by manager.cameraFps.collectAsState()
    val pipelineMs by manager.pipelineMs.collectAsState()
    val resolution by manager.resolution.collectAsState()

    var activeSenderFps by remember { mutableIntStateOf(0) }
    var uniqueFrames by remember { mutableIntStateOf(0) }
    var observations by remember { mutableIntStateOf(0) }
    var sumBer by remember { mutableDoubleStateOf(0.0) }
    var sumErasure by remember { mutableDoubleStateOf(0.0) }
    var sumQualityKib by remember { mutableDoubleStateOf(0.0) }
    var lastResult by remember { mutableStateOf<ChromaQrLabResult?>(null) }
    var seen by remember { mutableStateOf(BooleanArray(256)) }
    val completed = remember { mutableStateListOf<String>() }

    fun summaryForCurrent(prefix: String = ""): String? {
        if (activeSenderFps <= 0 || observations <= 0) return null
        val avgBer = sumBer / observations
        val avgErasure = sumErasure / observations
        val avgQuality = sumQualityKib / observations
        val coverage = uniqueFrames / 256.0
        val estimatedCombined = avgQuality * coverage
        return prefix +
            "${activeSenderFps} FPS: $uniqueFrames/256 (${"%.1f".format(coverage * 100)}%) QR, " +
            "BER ${"%.3f".format(avgBer * 100)}%, erasures ${"%.2f".format(avgErasure * 100)}%, " +
            "est combined ${"%.1f".format(estimatedCombined)} KiB/s"
    }

    fun finishCurrentRun() {
        summaryForCurrent()?.let(completed::add)
    }

    fun resetRun(fps: Int) {
        activeSenderFps = fps
        uniqueFrames = 0
        observations = 0
        sumBer = 0.0
        sumErasure = 0.0
        sumQualityKib = 0.0
        seen = BooleanArray(256)
        lastResult = null
    }

    DisposableEffect(manager) {
        manager.onSample = { sample ->
            scope.launch {
                val result = sample.result
                if (activeSenderFps != result.senderFps) {
                    finishCurrentRun()
                    resetRun(result.senderFps)
                }
                if (!seen[result.frameIndex]) {
                    seen[result.frameIndex] = true
                    uniqueFrames++
                }
                observations++
                sumBer += result.ber
                sumErasure += result.erasureRate
                sumQualityKib += result.qualityAdjustedKibS
                lastResult = result
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

    val running = status == ChromaQrLabCameraManager.Status.RUNNING || status == ChromaQrLabCameraManager.Status.STARTING
    val averageBer = if (observations > 0) sumBer / observations else 0.0
    val averageErasure = if (observations > 0) sumErasure / observations else 0.0
    val averageQuality = if (observations > 0) sumQualityKib / observations else 0.0

    Column(
        modifier.fillMaxSize().background(Color(0xFF090B10)).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("ChromaQR Lab", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text("V40-L luminance + 1 color bit/module", color = Color(0xFF7CB7FF), fontSize = 12.sp)
        Text(
            "Desktop LAB preset: Phase 0 ChromaQR V40 sweep",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(8.dp))

        if (!permission) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("GRANT CAMERA") }
            }
            return@Column
        }

        // The preview is never inside a scroll container. It receives a bounded share
        // of the remaining height so the action bar can never be pushed off-screen.
        Card(
            Modifier.fillMaxWidth().weight(0.58f),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black),
        ) {
            AndroidView(factory = { manager.previewView }, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(6.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            LabMetric("Camera", "%.1f fps".format(cameraFps))
            LabMetric("Pipeline", "%.1f ms".format(pipelineMs))
            LabMetric("Sender", if (activeSenderFps > 0) "$activeSenderFps fps" else "—")
            LabMetric("QR", "$uniqueFrames/256")
        }
        Spacer(Modifier.height(6.dp))

        // Only the diagnostics/results pane scrolls. Camera preview and actions stay fixed.
        Column(
            Modifier
                .fillMaxWidth()
                .weight(0.42f)
                .verticalScroll(rememberScrollState()),
        ) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.06f)),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("COLOR CHANNEL", color = Color(0xFFF2CC60), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(
                        "Avg BER: ${"%.3f".format(averageBer * 100)}%   •   Avg erasures: ${"%.2f".format(averageErasure * 100)}%",
                        color = Color.White,
                        fontSize = 13.sp,
                    )
                    lastResult?.let { result ->
                        Text(
                            "Last: ${result.goodBits}/${result.moduleCount * result.moduleCount} good color bits • U0 ${"%.1f".format(result.neutralU)} • threshold ${"%.1f".format(result.threshold)}",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                        )
                        Text(
                            "Raw combined ceiling: ${"%.1f".format(result.rawCombinedKibS)} KiB/s • color-quality ceiling: ${"%.1f".format(averageQuality)} KiB/s",
                            color = Color(0xFF7EE787),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Measured estimate is reported after each 256-frame run and includes QR coverage.",
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 10.sp,
                        )
                    }
                    Text(
                        "Capture: ${resolution.ifEmpty { "pending" }} • $observations decoded color observations",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 10.sp,
                    )
                }
            }

            if (completed.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.04f)),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("COMPLETED RUNS", color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp)
                        completed.takeLast(4).forEach {
                            Text(it, color = Color.White.copy(alpha = 0.75f), fontSize = 10.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(6.dp))

        // Sticky action bar: always visible regardless of preview/results height.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (running) manager.stop() else manager.start(lifecycleOwner)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = if (running) Color(0xFFE53935) else Color(0xFF43A047)),
            ) {
                Text(if (running) "STOP" else "START COLOR LAB", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = {
                    val text = buildString {
                        appendLine("SuperQR ChromaQR V40-L lab")
                        completed.forEach { appendLine(it) }
                        summaryForCurrent("CURRENT: ")?.let { appendLine(it) }
                    }
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            },
                            "Share ChromaQR results",
                        )
                    )
                },
                modifier = Modifier.weight(1f),
            ) { Text("SHARE RESULTS") }
        }
    }
}

@Composable
private fun LabMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color.White.copy(alpha = 0.45f), fontSize = 9.sp)
    }
}
