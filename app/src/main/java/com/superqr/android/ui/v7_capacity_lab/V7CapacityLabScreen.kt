package com.superqr.android.ui.v7_capacity_lab

import android.Manifest
import android.content.pm.PackageManager
import android.view.Surface
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.lifecycle.LifecycleOwner
import com.superqr.android.camera.ChromaSampleBuffers
import com.superqr.android.camera.ImageProxyChromaSampler
import com.superqr.android.camera.LumaFrameBuffer
import com.superqr.android.vision.v7_capacity_lab.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun V7CapacityLabScreen(
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }

    // Camera permission
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    // Lab state
    var receiver by remember { mutableStateOf<V7CapacityLabReceiver?>(null) }
    var manifest by remember { mutableStateOf<V7LabManifest?>(null) }
    var manifestErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    var manifestLoaded by remember { mutableStateOf(false) }

    var scannerRunning by remember { mutableStateOf(false) }
    var lastMetrics by remember { mutableStateOf<V7ChannelMetrics.FrameMetrics?>(null) }
    var lastCarrier by remember { mutableStateOf<V7CarrierGeometryEngine.CarrierResult?>(null) }
    var cameraFps by remember { mutableStateOf(0.0) }
    var analysisFps by remember { mutableStateOf(0.0) }
    var frameCount by remember { mutableIntStateOf(0) }
    var totalAnalysisMs by remember { mutableDoubleStateOf(0.0) }
    var confidenceSummary by remember { mutableStateOf("—") }

    // Profile state
    var selectedProfile by remember { mutableStateOf("ref_40x40_v6_reference_4_seed42") }
    var expectedFrameIndex by remember { mutableIntStateOf(0) }
    var samplerMode by remember { mutableStateOf(V7HighDensitySampler.ProbeMode.CENTER_1) }
    var calibrationLabel by remember { mutableStateOf("—") }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // Load manifest
    LaunchedEffect(Unit) {
        try {
            val bytes = context.assets.open("v7_capacity_lab/lab_manifest.json")
                .use { it.readBytes() }
            val m = V7LabManifest.loadFromBytes(bytes)
            val errors = m.validateAll()
            manifest = m
            manifestErrors = errors
            manifestLoaded = true
            selectedProfile = m.referenceProfiles.keys.first()
        } catch (e: Exception) {
            manifestErrors = listOf("Manifest load failed: ${e.message}")
            manifestLoaded = false
        }
    }

    // Permission launcher
    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    fun startScan() {
        val m = manifest ?: return
        val rec = V7CapacityLabReceiver(m)
        rec.selectProfile(selectedProfile)
        rec.probeMode = samplerMode
        receiver = rec
        scannerRunning = true
        frameCount = 0

        val cameraProvider = try {
            ProcessCameraProvider.getInstance(context).get()
        } catch (e: Exception) { null } ?: return

        val lumaBuffer = LumaFrameBuffer()
        val chromaBuffers = ChromaSampleBuffers()
        val analysisExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

        val preview = Preview.Builder()
            .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
            .build().also { it.surfaceProvider = previewView.surfaceProvider }

        val analysis = ImageAnalysis.Builder()
            .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        val sessionConfig = SessionConfig.Builder(listOf(preview, analysis)).build()

        var lastTimestamp = 0L
        var fpsAccum = 0.0

        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
            try {
                val activeReceiver = receiver ?: return@setAnalyzer
                if (!lumaBuffer.packFrom(imageProxy)) return@setAnalyzer

                val sensorTs = imageProxy.imageInfo.timestamp
                if (lastTimestamp > 0) {
                    val delta = (sensorTs - lastTimestamp) / 1_000_000_000.0
                    fpsAccum = 1.0 / delta
                }
                lastTimestamp = sensorTs

                val chromaReader = ImageProxyChromaSampler(imageProxy, chromaBuffers)
                val result = activeReceiver.analyze(
                    lumaBuffer.bytes, lumaBuffer.width, lumaBuffer.height, chromaReader
                )

                mainExecutor.execute {
                    receiver?.let { r ->
                        lastCarrier = result.carrier
                        lastMetrics = result.metrics
                        expectedFrameIndex = result.expectedFrameIndex
                        calibrationLabel = result.calibrationStatus
                        confidenceSummary = if (r.classifier.bestSymbols.isNotEmpty()) {
                            val margins = (0 until minOf(100, r.gridSize * r.gridSize)).map {
                                r.classifier.confidenceMargin(it)
                            }.filter { it > 0.0 }
                            if (margins.isNotEmpty())
                                "μ=%.3f".format(margins.average()) else "—"
                        } else "—"
                    }
                    cameraFps = fpsAccum
                    val analysisMs = result.metrics?.totalAnalysisUs?.div(1000.0) ?: 0.0
                    totalAnalysisMs = analysisMs
                    analysisFps = if (analysisMs > 0) 1000.0 / analysisMs else 0.0
                    frameCount++
                }
            } catch (_: Exception) {
            } finally {
                imageProxy.close()
            }
        }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, sessionConfig
            )
        } catch (e: Exception) {
            scannerRunning = false
        }
    }

    fun stopScan() {
        scannerRunning = false
        receiver?.close()
        receiver = null
        try {
            ProcessCameraProvider.getInstance(context).get().unbindAll()
        } catch (_: Exception) {}
    }

    // Lifecycle cleanup
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, evt ->
            if (evt == Lifecycle.Event.ON_STOP && scannerRunning) stopScan()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            if (scannerRunning) stopScan()
        }
    }

    // UI
    if (!hasCameraPermission) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("Grant Camera Permission")
            }
        }
        return
    }

    if (!manifestLoaded) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text("Loading manifest...", color = Color.White)
                manifestErrors.forEach { err ->
                    Text(err, color = Color(0xFFFF6B6B), fontSize = 10.sp)
                }
            }
        }
        return
    }

    if (manifestErrors.isNotEmpty()) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("MANIFEST VALIDATION FAILED", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                manifestErrors.take(5).forEach { err ->
                    Text(err, color = Color(0xFFFF6B6B), fontSize = 9.sp)
                }
                if (manifestErrors.size > 5) {
                    Text("...and ${manifestErrors.size - 5} more errors", color = Color(0xFFFF6B6B), fontSize = 9.sp)
                }
            }
        }
        return
    }

    if (scannerRunning) {
        ScannerActiveUI(
            previewView = previewView,
            receiver = receiver,
            lastCarrier = lastCarrier,
            lastMetrics = lastMetrics,
            selectedProfile = selectedProfile,
            expectedFrameIndex = expectedFrameIndex,
            calibrationLabel = calibrationLabel,
            cameraFps = cameraFps,
            analysisFps = analysisFps,
            totalAnalysisMs = totalAnalysisMs,
            confidenceSummary = confidenceSummary,
            frameCount = frameCount,
            samplerMode = samplerMode,
            onStop = { stopScan() },
            manifest = manifest,
        )
    } else {
        ScannerIdleUI(
            manifest = manifest!!,
            selectedProfile = selectedProfile,
            onProfileChanged = { selectedProfile = it },
            samplerMode = samplerMode,
            onSamplerModeChanged = { samplerMode = it },
            onStart = { startScan() },
            onBack = onBack,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScannerIdleUI(
    manifest: V7LabManifest,
    selectedProfile: String,
    onProfileChanged: (String) -> Unit,
    samplerMode: V7HighDensitySampler.ProbeMode,
    onSamplerModeChanged: (V7HighDensitySampler.ProbeMode) -> Unit,
    onStart: () -> Unit,
    onBack: (() -> Unit)?,
) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState())
        ) {
            Text("V7 Capacity Lab", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("LAB ONLY — Not a production receiver", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)

            // Profile selector
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = selectedProfile.takeLast(30),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Profile") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedLabelColor = Color(0xFF89B4FA),
                        unfocusedLabelColor = Color.White.copy(alpha = 0.6f),
                        focusedBorderColor = Color(0xFF89B4FA),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                    ),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    manifest.referenceProfiles.keys.forEach { name ->
                        DropdownMenuItem(
                            text = {
                                val p = manifest.referenceProfiles[name]!!
                                Text("${p.gridSize}x${p.gridSize} ${p.paletteName}", fontSize = 11.sp)
                            },
                            onClick = { onProfileChanged(name); expanded = false },
                        )
                    }
                }
            }

            // Selected profile info
            val profile = manifest.referenceProfiles[selectedProfile]
            if (profile != null) {
                Text(
                    "Grid: ${profile.gridSize}x${profile.gridSize} | Palette: ${profile.paletteName} | Seed: ${profile.seed}",
                    color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp
                )
            }

            // Sampler mode
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = samplerMode == V7HighDensitySampler.ProbeMode.CENTER_1,
                    onClick = { onSamplerModeChanged(V7HighDensitySampler.ProbeMode.CENTER_1) },
                    label = { Text("CENTER_1", fontSize = 10.sp) },
                )
                FilterChip(
                    selected = samplerMode == V7HighDensitySampler.ProbeMode.CROSS_5,
                    onClick = { onSamplerModeChanged(V7HighDensitySampler.ProbeMode.CROSS_5) },
                    label = { Text("CROSS_5", fontSize = 10.sp) },
                )
            }

            Spacer(Modifier.height(8.dp))
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text("START SCAN", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
            }
            if (onBack != null) {
                TextButton(onClick = onBack) { Text("Back", color = Color.White) }
            }
        }
    }
}

@Composable
private fun ScannerActiveUI(
    previewView: PreviewView,
    receiver: V7CapacityLabReceiver?,
    lastCarrier: V7CarrierGeometryEngine.CarrierResult?,
    lastMetrics: V7ChannelMetrics.FrameMetrics?,
    selectedProfile: String,
    expectedFrameIndex: Int,
    calibrationLabel: String,
    cameraFps: Double,
    analysisFps: Double,
    totalAnalysisMs: Double,
    confidenceSummary: String,
    frameCount: Int,
    samplerMode: V7HighDensitySampler.ProbeMode,
    onStop: () -> Unit,
    manifest: V7LabManifest?,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Top bar
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("V7 Capacity Lab", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Button(onClick = onStop, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                Text("STOP", fontSize = 11.sp)
            }
        }

        // Metrics card
        Card(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(10.dp).fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.85f)),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Row 1: profile + state
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val prof = manifest?.referenceProfiles?.get(selectedProfile)
                    Text(
                        if (prof != null) "${prof.gridSize}x${prof.gridSize} ${prof.paletteName}" else "—",
                        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                    val state = lastCarrier?.trackingState ?: "—"
                    val stateColor = when (state) {
                        "TRACKING", "LOCKED" -> Color(0xFF4CAF50)
                        "ACQUIRING", "REACQUIRING" -> Color(0xFFFFB74D)
                        else -> Color.White.copy(alpha = 0.6f)
                    }
                    Text(state, color = stateColor, fontSize = 11.sp)
                }

                // Row 2: calibration + sampler
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Cal: $calibrationLabel", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
                    Text("Sampler: $samplerMode", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
                }

                // Row 3: FPS + timing
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Cam: %.1f fps".format(cameraFps), color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
                    Text("Analysis: %.1f ms".format(totalAnalysisMs), color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
                }

                // Row 4: Expected frame
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Expected frame: $expectedFrameIndex", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
                    Text("Frames: $frameCount", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
                }

                // Row 5: Error metrics (only when metrics available)
                val m = lastMetrics
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "SER: ${if (m != null) "%.1f%%".format(m.serAll * 100) else "—"}",
                        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        "C-SER: ${if (m != null && !m.conditionalSer.isNaN()) "%.1f%%".format(m.conditionalSer * 100) else "—"}",
                        color = Color.White, fontSize = 11.sp
                    )
                    Text(
                        "BER: ${if (m != null && !m.berAccepted.isNaN()) "%.3f".format(m.berAccepted) else "—"}",
                        color = Color.White, fontSize = 11.sp
                    )
                }

                // Row 6: Erasure + confidence
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "Erasure: ${if (m != null) "%.1f%%".format(m.erasureRate * 100) else "—"}",
                        color = if (m != null && m.erasureRate > 0.3) Color(0xFFFFB74D) else Color.White,
                        fontSize = 10.sp
                    )
                    Text("Conf: $confidenceSummary", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
                    Text(
                        "Acc: ${if (m != null) "${m.correctSymbols}/${m.totalCells}" else "—"}",
                        color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp
                    )
                }

                // Buttons row
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Expected frame navigation
                    OutlinedButton(
                        onClick = { receiver?.retreatExpectedFrame() },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                    ) { Text("◀", fontSize = 10.sp) }

                    OutlinedButton(
                        onClick = { receiver?.advanceExpectedFrame() },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                    ) { Text("▶", fontSize = 10.sp) }

                    OutlinedButton(
                        onClick = { receiver?.resetExpectedFrame() },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                    ) { Text("RstIdx", fontSize = 8.sp) }

                    OutlinedButton(
                        onClick = { receiver?.resetCalibration() },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                    ) { Text("RstCal", fontSize = 8.sp) }
                }
            }
        }
    }
}
