package com.superqr.android.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.transform.OutputTransform
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.superqr.android.camera.CameraManager
import com.superqr.android.camera.CameraStatus
import com.superqr.android.camera.CoordinateMapper
import com.superqr.android.diagnostics.SessionExporter
import com.superqr.android.phase1.Phase1FramePoint
import com.superqr.android.phase1.Phase1FramingGeometry
import com.superqr.android.phase1.Phase1RunSnapshot
import com.superqr.android.session.DiagnosticSession
import com.superqr.android.session.SessionPhase
import com.superqr.android.session.SessionState
import java.util.concurrent.ExecutorService

@Composable
fun MainScreen(
    analysisExecutor: ExecutorService,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var permission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permission = it }

    val cameraManager = remember { CameraManager(context, analysisExecutor) }
    val session = remember { DiagnosticSession(context) }
    val sessionState by session.sessionState.collectAsState()
    val cameraStatus by cameraManager.status.collectAsState()
    var showDiagnostics by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!permission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && cameraStatus == CameraStatus.RUNNING) {
                cameraManager.stop()
                session.stopSession()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            cameraManager.destroy()
            session.close()
        }
    }

    DisposableEffect(cameraManager) {
        cameraManager.onFrame = { frame -> session.processFrame(frame) }
        onDispose { cameraManager.onFrame = null }
    }

    Box(modifier.fillMaxSize().background(Color(0xFF090B10))) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("SuperQR", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("V7 receiver", color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))

            if (!permission) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant Camera Permission")
                    }
                }
                return@Column
            }

            val framing = sessionState.framing
            val targetTransform = cameraManager.previewView.outputTransform
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                Card(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black),
                ) {
                    AndroidView(
                        factory = { cameraManager.previewView },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (framing.carrierQuad != null || framing.candidateQuad != null) {
                    PreviewOverlay(
                        framing = framing,
                        sourceTransform = sessionState.sourceTransform,
                        targetTransform = targetTransform,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            StatusBar(sessionState.phase, sessionState.profileName)
            Spacer(Modifier.height(8.dp))
            MetricsRow(
                sessionState.cameraFps,
                sessionState.analysisFps,
                sessionState.pipelineMs,
                sessionState.profileName,
            )
            Spacer(Modifier.height(12.dp))

            val isRunning = cameraStatus == CameraStatus.RUNNING || cameraStatus == CameraStatus.STARTING
            if (!isRunning && sessionState.phase == SessionPhase.COMPLETE) {
                Column(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "SESSION COMPLETE",
                        color = Color(0xFF7EE787),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${sessionState.analyzedFrames} frames • ${sessionState.campaignId.take(8)}",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { SessionExporter.shareSession(context, session) }) {
                            Text("SHARE SESSION", fontWeight = FontWeight.Bold)
                        }
                        Button(onClick = {
                            session.startSession()
                            cameraManager.start(lifecycleOwner)
                        }) {
                            Text("New Session")
                        }
                    }
                }
            } else if (isRunning) {
                Button(
                    onClick = {
                        cameraManager.stop()
                        session.stopSession()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                ) {
                    Text("STOP CAMERA", fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = {
                        session.startSession()
                        cameraManager.start(lifecycleOwner)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF43A047)),
                ) {
                    Text("START CAMERA", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
                Text(
                    if (showDiagnostics) "Hide Diagnostics" else "Show Diagnostics",
                    color = Color.White.copy(alpha = 0.55f),
                )
            }
            if (showDiagnostics) {
                DiagnosticsPanel(sessionState, session.snapshot(), Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PreviewOverlay(
    framing: Phase1FramingGeometry,
    sourceTransform: OutputTransform?,
    targetTransform: OutputTransform?,
    modifier: Modifier = Modifier,
) {
    val quad = framing.carrierQuad ?: framing.candidateQuad
    if (quad == null || quad.size != 4 || sourceTransform == null || targetTransform == null) return
    val mappedQuad = mapFramePoints(quad, sourceTransform, targetTransform) ?: return
    val mappedFinders = framing.finderCenters.take(4).mapNotNull { point ->
        CoordinateMapper.mapPoint(sourceTransform, targetTransform, point.x, point.y)?.let {
            Offset(it.first, it.second)
        }
    }

    Canvas(modifier = modifier) {
        val path = Path().apply {
            moveTo(mappedQuad[0].x, mappedQuad[0].y)
            for (index in 1 until mappedQuad.size) lineTo(mappedQuad[index].x, mappedQuad[index].y)
            close()
        }
        drawPath(path, Color(0xFF7CB7FF), style = Stroke(width = 2.5f))
        mappedQuad.forEach { drawCircle(Color.White, 4f, it) }
        mappedFinders.forEach { drawCircle(Color(0xFFF2CC60), 3f, it) }
    }
}

private fun mapFramePoints(
    points: List<Phase1FramePoint>,
    source: OutputTransform,
    target: OutputTransform,
): List<Offset>? {
    val raw = FloatArray(points.size * 2)
    points.forEachIndexed { index, point ->
        raw[index * 2] = point.x
        raw[index * 2 + 1] = point.y
    }
    if (!CoordinateMapper.mapPoints(source, target, raw)) return null
    return List(points.size) { index -> Offset(raw[index * 2], raw[index * 2 + 1]) }
}

@Composable
private fun StatusBar(phase: SessionPhase, profileName: String) {
    val phaseColor = when (phase) {
        SessionPhase.SEARCHING -> Color(0xFFF2CC60)
        SessionPhase.QR_DETECTED, SessionPhase.QR_LOCKED, SessionPhase.GRID_DETECTED -> Color(0xFF7CB7FF)
        SessionPhase.GRID_LOCKED, SessionPhase.RECEIVING, SessionPhase.COMPLETE -> Color(0xFF7EE787)
        SessionPhase.LOST, SessionPhase.REACQUIRING -> Color(0xFFFF7B72)
        else -> Color.White.copy(alpha = 0.55f)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(phase.label, color = phaseColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        if (profileName.isNotEmpty()) Text(profileName, color = Color(0xFF7CB7FF), fontSize = 11.sp)
    }
}

@Composable
private fun MetricsRow(cameraFps: Double, analysisFps: Double, pipelineMs: Double, profileName: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        MetricChip("Camera", "%.1f fps".format(cameraFps))
        MetricChip("Analysis", "%.1f fps".format(analysisFps))
        MetricChip("Pipeline", "%.1f ms".format(pipelineMs))
        MetricChip("Profile", profileName.ifEmpty { "—" })
    }
}

@Composable
private fun MetricChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp)
    }
}

@Composable
private fun DiagnosticsPanel(state: SessionState, snapshot: Phase1RunSnapshot, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionHeader("Session")
        DiagnosticText("Campaign: ${state.campaignId.take(8)} · Frames: ${state.analyzedFrames}")
        SectionHeader("Tracking")
        DiagnosticText("State: ${state.trackingState.label} · Profile: ${state.profileName}")
        SectionHeader("Performance")
        DiagnosticText(
            "Pipeline: %.1f ms · Camera: %.1f fps · Analysis: %.1f fps".format(
                state.pipelineMs, state.cameraFps, state.analysisFps,
            )
        )
        if (snapshot.observations > 0) {
            SectionHeader("Observations")
            DiagnosticText(
                "Observed: ${snapshot.observations} · Valid: ${snapshot.validFrames} · " +
                    "Unique: ${snapshot.uniqueFrames} · FEC valid: ${snapshot.innerFecFrames}"
            )
            DiagnosticText(
                "BER: %.4f · Erasures: %.4f · Goodput: %.2f KiB/s".format(
                    snapshot.bitErrorRate, snapshot.erasureRate, snapshot.goodputKibS,
                )
            )
        }
        if (snapshot.failureSummary.isNotEmpty()) {
            SectionHeader("Failures")
            Text(snapshot.failureSummary, color = Color(0xFFFF7B72), fontSize = 12.sp)
        }
        state.error?.let {
            SectionHeader("Last Error")
            Text(it, color = Color(0xFFFF7B72), fontSize = 12.sp)
        }
    }
}

@Composable
private fun DiagnosticText(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
}

@Composable
private fun SectionHeader(title: String) {
    Text(title.uppercase(), color = Color(0xFF7CB7FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
}
