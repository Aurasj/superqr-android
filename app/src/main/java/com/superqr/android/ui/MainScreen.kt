package com.superqr.android.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.superqr.android.diagnostics.SessionExporter
import com.superqr.android.session.DiagnosticSession
import com.superqr.android.session.SessionPhase
import com.superqr.android.ui.phase1.Phase1FramingGeometry
import java.io.File
import java.util.concurrent.ExecutorService

@Composable
fun MainScreen(
    analysisExecutor: ExecutorService,
    cacheDir: File,
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
    val session = remember { DiagnosticSession(context, cacheDir) }
    val sessionState by session.sessionState.collectAsState()
    val cameraStatus by cameraManager.status.collectAsState()

    var showDiagnostics by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!permission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP &&
                cameraStatus == CameraStatus.RUNNING
            ) {
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
            Text(
                "SuperQR",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "V7 receiver",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(12.dp))

            if (!permission) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant Camera Permission")
                    }
                }
                return@Column
            }

            // Camera preview card (16:9) with overlay
            val framing = sessionState.framing
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            ) {
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
                // Preview geometry overlay: draw detected quad on top
                if (framing.carrierQuad != null || framing.candidateQuad != null) {
                    PreviewOverlay(
                        framing = framing,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Status bar
            StatusBar(phase = sessionState.phase, profileName = sessionState.profileName)

            Spacer(Modifier.height(8.dp))

            // Live metrics
            MetricsRow(
                cameraFps = sessionState.cameraFps,
                analysisFps = sessionState.analysisFps,
                pipelineMs = sessionState.pipelineMs,
                profileName = sessionState.profileName,
            )

            Spacer(Modifier.height(12.dp))

            // Control buttons
            val isRunning = cameraStatus == CameraStatus.RUNNING ||
                cameraStatus == CameraStatus.STARTING

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
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White.copy(alpha = 0.07f)
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Campaign: ${sessionState.campaignId.take(8)}", color = Color.White, fontWeight = FontWeight.Bold)
                            Text(
                                "${sessionState.analyzedFrames} frames analyzed",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 12.sp,
                            )
                        }
                    }
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
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (isRunning) {
                        Button(
                            onClick = {
                                cameraManager.stop()
                                session.stopSession()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFE53935)
                            ),
                        ) {
                            Text("STOP CAMERA", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = {
                                session.startSession()
                                cameraManager.start(lifecycleOwner)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF43A047)
                            ),
                        ) {
                            Text("START CAMERA", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Collapsible diagnostics
            TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
                Text(
                    if (showDiagnostics) "Hide Diagnostics" else "Show Diagnostics",
                    color = Color.White.copy(alpha = 0.55f),
                )
            }

            if (showDiagnostics) {
                DiagnosticsPanel(
                    sessionState = sessionState,
                    snapshot = session.snapshot(),
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PreviewOverlay(
    framing: Phase1FramingGeometry,
    modifier: Modifier = Modifier,
) {
    val quad = framing.carrierQuad ?: framing.candidateQuad
    if (quad == null || quad.size != 4) return

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Map analysis-space points to preview view space.
        // The framing geometry points are in analysis (luma) coordinates.
        // PreviewView uses FIT_CENTER, so we need to apply the same transform
        // that Phase1FrameFit would compute.
        if (framing.frameWidth <= 0 || framing.frameHeight <= 0) return@Canvas

        val scale = minOf(w / framing.frameWidth, h / framing.frameHeight)
        val renderedW = framing.frameWidth * scale
        val renderedH = framing.frameHeight * scale
        val offsetX = (w - renderedW) * 0.5f
        val offsetY = (h - renderedH) * 0.5f

        val mappedPoints = quad.map { pt ->
            Offset(
                offsetX + pt.x * scale,
                offsetY + pt.y * scale,
            )
        }

        // Draw quad outline
        val path = Path().apply {
            moveTo(mappedPoints[0].x, mappedPoints[0].y)
            for (i in 1 until mappedPoints.size) {
                lineTo(mappedPoints[i].x, mappedPoints[i].y)
            }
            close()
        }
        drawPath(path, color = Color(0xFF7CB7FF), style = Stroke(width = 2.5f))

        // Draw corner dots
        for (pt in mappedPoints) {
            drawCircle(Color.White, radius = 4f, center = pt)
        }

        // Draw finder centers
        for (center in framing.finderCenters.take(4)) {
            val cx = offsetX + center.x * scale
            val cy = offsetY + center.y * scale
            drawCircle(Color(0xFFF2CC60), radius = 3f, center = Offset(cx, cy))
        }
    }
}

@Composable
private fun StatusBar(
    phase: SessionPhase,
    profileName: String,
) {
    val phaseColor = when (phase) {
        SessionPhase.SEARCHING -> Color(0xFFF2CC60)
        SessionPhase.QR_DETECTED -> Color(0xFF7CB7FF)
        SessionPhase.QR_LOCKED -> Color(0xFF7CB7FF)
        SessionPhase.GRID_DETECTED -> Color(0xFF7CB7FF)
        SessionPhase.GRID_LOCKED -> Color(0xFF7EE787)
        SessionPhase.RECEIVING -> Color(0xFF7EE787)
        SessionPhase.LOST, SessionPhase.REACQUIRING -> Color(0xFFFF7B72)
        SessionPhase.COMPLETE -> Color(0xFF7EE787)
        else -> Color.White.copy(alpha = 0.55f)
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            phase.label,
            color = phaseColor,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
        if (profileName.isNotEmpty()) {
            Text(
                profileName,
                color = Color(0xFF7CB7FF),
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun MetricsRow(
    cameraFps: Double,
    analysisFps: Double,
    pipelineMs: Double,
    profileName: String,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        MetricChip("Camera", "%.1f fps".format(cameraFps))
        MetricChip("Analysis", "%.1f fps".format(analysisFps))
        MetricChip("Pipeline", "%.1f ms".format(pipelineMs))
        MetricChip("Profile", profileName.ifEmpty { "—" })
    }
}

@Composable
private fun MetricChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            label,
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun DiagnosticsPanel(
    sessionState: com.superqr.android.session.SessionState,
    snapshot: com.superqr.android.ui.phase1.Phase1RunSnapshot,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionHeader("Session")
        Text(
            "Campaign: ${sessionState.campaignId.take(8)} · " +
                "Frames: ${sessionState.analyzedFrames}",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
        )

        SectionHeader("Tracking")
        Text(
            "State: ${sessionState.trackingState.label} · " +
                "Path: ${sessionState.profileName}",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
        )

        SectionHeader("Performance")
        Text(
            "Pipeline: %.1f ms · Camera: %.1f fps · Analysis: %.1f fps".format(
                sessionState.pipelineMs, sessionState.cameraFps, sessionState.analysisFps,
            ),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
        )

        if (snapshot.observations > 0) {
            SectionHeader("Observations")
            Text(
                "Observed: ${snapshot.observations} · " +
                    "Valid: ${snapshot.validFrames} · " +
                    "Unique: ${snapshot.uniqueFrames} · " +
                    "FEC valid: ${snapshot.innerFecFrames}",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
            )
            Text(
                "BER: %.4f · Erasure rate: %.4f · Goodput: %.2f KiB/s".format(
                    snapshot.bitErrorRate,
                    snapshot.erasureRate,
                    snapshot.goodputKibS,
                ),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
            )
        }

        if (!snapshot.failureSummary.isNullOrEmpty()) {
            SectionHeader("Failures")
            Text(
                snapshot.failureSummary,
                color = Color(0xFFFF7B72),
                fontSize = 12.sp,
            )
        }

        if (sessionState.error != null) {
            SectionHeader("Last Error")
            Text(
                sessionState.error,
                color = Color(0xFFFF7B72),
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        color = Color(0xFF7CB7FF),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
    )
}
