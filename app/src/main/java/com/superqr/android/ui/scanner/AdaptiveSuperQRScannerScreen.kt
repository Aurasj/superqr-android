package com.superqr.android.ui.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.util.Range
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.*
import com.superqr.android.camera.*
import com.superqr.android.ui.v6.V6PreviewOverlayGeometry
import com.superqr.android.ui.v6.V6PreviewOverlayMapper
import com.superqr.android.vision.v6.contract.V6Contract
import com.superqr.android.vision.v6.detection.V6StaticDetector
import com.superqr.android.vision.v6.model.V6StaticResult
import com.superqr.android.vision.v6.transport.V6ReceiveUtils
import com.superqr.android.vision.v7.transport.*
import com.superqr.android.vision.v7_capacity_lab.V7HighDensitySampler
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private enum class AdaptiveScannerState { IDLE, STARTING, SCANNING, COMPLETE, ERROR }
private data class AdaptiveCompleted(val pkg: V7TransferPackage, val file: File, val session: Int, val frames: Int)

/** One production scanner: V6-proven geometry + adaptive V7 payload. */
@Composable
fun AdaptiveSuperQRScannerScreen(
    analysisExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() },
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    LaunchedEffect(Unit) { if (!permission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    var state by remember { mutableStateOf(AdaptiveScannerState.IDLE) }
    var cameraState by remember { mutableStateOf("IDLE") }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var boundConfig by remember { mutableStateOf<SessionConfig?>(null) }
    var detectorRef by remember { mutableStateOf<V6StaticDetector?>(null) }
    var receiverRef by remember { mutableStateOf<V7TransferReceiver?>(null) }
    val generation = remember { AtomicInteger(0) }
    val accumulator = remember { V7SessionAccumulator() }

    var result by remember { mutableStateOf<V6StaticResult?>(null) }
    var overlay by remember { mutableStateOf<V6PreviewOverlayGeometry?>(null) }
    var completed by remember { mutableStateOf<AdaptiveCompleted?>(null) }

    var activeProfile by remember { mutableStateOf(V7OpticalProfiles.all.first()) }
    var calibrated by remember { mutableIntStateOf(0) }
    var validSamples by remember { mutableIntStateOf(0) }
    var confidentCells by remember { mutableIntStateOf(0) }
    var erasures by remember { mutableIntStateOf(0) }
    var lastFrame by remember { mutableIntStateOf(-1) }
    var lastError by remember { mutableStateOf<String?>(null) }
    var analyzedFrames by remember { mutableIntStateOf(0) }
    var crcPass by remember { mutableIntStateOf(0) }
    var crcFail by remember { mutableIntStateOf(0) }
    var cameraFps by remember { mutableDoubleStateOf(0.0) }
    var analysisFps by remember { mutableDoubleStateOf(0.0) }
    var analysisMs by remember { mutableDoubleStateOf(0.0) }
    var lastSensorTs by remember { mutableLongStateOf(0L) }
    val rate = remember { V7AnalysisRateAccumulator(64) }

    var showDebug by remember { mutableStateOf(false) }
    var focusState by remember { mutableStateOf("AUTO") }
    var forcedProfile by remember { mutableIntStateOf(-1) } // -1 = AUTO
    var samplerMode by remember { mutableStateOf(V7HighDensitySampler.ProbeMode.CROSS_5) }

    fun resetLive() {
        accumulator.reset(); result = null; overlay = null; completed = null
        activeProfile = V7OpticalProfiles.all.first(); calibrated = 0; validSamples = 0
        confidentCells = 0; erasures = 0; lastFrame = -1; lastError = null
        analyzedFrames = 0; crcPass = 0; crcFail = 0; cameraFps = 0.0; analysisFps = 0.0; analysisMs = 0.0
        lastSensorTs = 0L; rate.reset()
    }

    fun releaseCamera() {
        val detector = detectorRef
        detectorRef = null; receiverRef = null; generation.incrementAndGet(); result = null; overlay = null
        try {
            val provider = ProcessCameraProvider.getInstance(context).get()
            boundConfig?.let { provider.unbind(it) }
        } catch (_: Throwable) {}
        boundConfig = null; boundCamera = null
        if (detector != null) analysisExecutor.execute { try { detector.close() } catch (_: Throwable) {} }
    }

    fun startScan() {
        if (!permission) { permissionLauncher.launch(Manifest.permission.CAMERA); return }
        releaseCamera(); resetLive(); generation.incrementAndGet(); state = AdaptiveScannerState.STARTING; cameraState = "STARTING"
    }

    fun stopScan() { releaseCamera(); state = AdaptiveScannerState.IDLE; cameraState = "STOPPED" }

    fun lockFocus() {
        val camera = boundCamera ?: return
        if (previewView.width <= 0 || previewView.height <= 0) return
        focusState = "LOCKING"
        try {
            val p = previewView.meteringPointFactory.createPoint(previewView.width / 2f, previewView.height / 2f)
            val action = FocusMeteringAction.Builder(p, FocusMeteringAction.FLAG_AF).disableAutoCancel().build()
            val future = camera.cameraControl.startFocusAndMetering(action)
            future.addListener({ focusState = try { if (future.get().isFocusSuccessful) "LOCKED" else "FAILED" } catch (_: Throwable) { "FAILED" } }, mainExecutor)
        } catch (_: Throwable) { focusState = "FAILED" }
    }
    fun unlockFocus() { try { boundCamera?.cameraControl?.cancelFocusAndMetering() } catch (_: Throwable) {}; focusState = "AUTO" }

    fun bindCamera(provider: ProcessCameraProvider, pv: PreviewView) {
        try {
            try { provider.unbindAll() } catch (_: Throwable) {}
            val rotation = pv.display?.rotation ?: Surface.ROTATION_0
            val preview = Preview.Builder().setTargetRotation(rotation).build().also { it.surfaceProvider = pv.surfaceProvider }
            val analysis = ImageAnalysis.Builder().setTargetRotation(rotation).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            val viewPort = pv.viewPort ?: run { state = AdaptiveScannerState.ERROR; cameraState = "ERROR • VIEWPORT"; return }

            val info = try { provider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA) } catch (_: Throwable) { null }
            var ranges = emptySet<Range<Int>>()
            if (info != null) try {
                val probe = SessionConfig.Builder(listOf(preview, analysis)).apply { setViewPort(viewPort) }.build()
                ranges = info.getSupportedFrameRateRanges(probe)
            } catch (_: Throwable) {}
            val chosen = when {
                ranges.any { it.lower == 60 && it.upper == 60 } -> Range(60, 60)
                ranges.any { it.upper == 60 } -> ranges.filter { it.upper == 60 }.maxByOrNull { it.lower }
                ranges.any { it.lower == 30 && it.upper == 30 } -> Range(30, 30)
                else -> null
            }
            val config = SessionConfig.Builder(listOf(preview, analysis)).apply { setViewPort(viewPort); if (chosen != null) setFrameRateRange(chosen) }.build()

            val detector = V6StaticDetector()
            val receiver = V7TransferReceiver().also {
                it.forcedProfileId = forcedProfile.takeIf { id -> id >= 0 }
                it.probeMode = samplerMode
            }
            detectorRef = detector; receiverRef = receiver
            val luma = LumaFrameBuffer(); val chroma = ChromaSampleBuffers()
            val transforms = ImageProxyTransformFactory().apply { setUsingCropRect(true); setUsingRotationDegrees(true) }

            analysis.setAnalyzer(analysisExecutor) { image ->
                val gen = generation.get(); val sensorTs = image.imageInfo.timestamp; val arrival = System.nanoTime()
                try {
                    val d = detectorRef ?: return@setAnalyzer
                    val r = receiverRef ?: return@setAnalyzer
                    r.forcedProfileId = forcedProfile.takeIf { it >= 0 }; r.probeMode = samplerMode
                    if (!luma.packFrom(image)) return@setAnalyzer
                    val chromaReader = ImageProxyChromaSampler(image, chroma)
                    val detected = d.detect(luma.bytes, luma.width, luma.height, "deterministic_random", chromaReader).copy(analyzerArrivalNs = arrival)
                    val decoded = r.analyze(detected, luma.bytes, luma.width, luma.height, chromaReader)
                    val sourceTx = try { transforms.getOutputTransform(image) } catch (_: Throwable) { null }
                    val accepted = decoded.acceptedFrame
                    var pkg: V7TransferPackage? = null
                    if (accepted != null) pkg = try { accumulator.addFrame(accepted) } catch (_: Throwable) { null }

                    mainExecutor.execute {
                        if (gen != generation.get()) return@execute
                        result = detected
                        overlay = if (sourceTx != null) pv.outputTransform?.let { V6PreviewOverlayMapper.map(detected, sourceTx, it) } else null
                        activeProfile = decoded.profile; calibrated = decoded.calibratedCount; validSamples = decoded.validSamples
                        confidentCells = decoded.confidentCells; erasures = decoded.erasureCount; analysisMs = decoded.timing.totalUs / 1000.0
                        analyzedFrames++
                        if (accepted != null) { crcPass++; lastFrame = accepted.frameId; lastError = null }
                        else if (decoded.transportError != null) { crcFail++; lastError = decoded.transportError }
                        if (lastSensorTs > 0L) {
                            val delta = (sensorTs - lastSensorTs) / 1_000_000_000.0
                            if (delta > 0) cameraFps = 1.0 / delta
                        }
                        lastSensorTs = sensorTs; rate.recordCompletion(System.nanoTime()); analysisFps = rate.computeFps()

                        if (pkg != null && accepted != null) {
                            val safe = V6ReceiveUtils.sanitizeFilename(pkg.filename)
                            val dir = File(context.cacheDir, "superqr_received").apply { mkdirs() }
                            val out = File(dir, safe); out.writeBytes(pkg.fileData)
                            completed = AdaptiveCompleted(pkg, out, accepted.sessionId, accepted.totalFrames)
                            releaseCamera(); state = AdaptiveScannerState.COMPLETE; cameraState = "STOPPED"
                        }
                    }
                } catch (_: Throwable) {
                    // Bad optical frames are expected; live counters expose quality.
                } finally { image.close() }
            }

            try {
                boundCamera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, config)
                boundConfig = config; cameraState = if (chosen != null) "CAMERA ${chosen.lower}-${chosen.upper} FPS" else "CAMERA AUTO"; state = AdaptiveScannerState.SCANNING
            } catch (_: Throwable) {
                val fallback = SessionConfig.Builder(listOf(preview, analysis)).apply { setViewPort(viewPort) }.build()
                boundCamera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, fallback)
                boundConfig = fallback; cameraState = "CAMERA FALLBACK"; state = AdaptiveScannerState.SCANNING
            }
        } catch (e: Throwable) { state = AdaptiveScannerState.ERROR; cameraState = "ERROR • ${e.message ?: "camera"}" }
    }

    if (state == AdaptiveScannerState.STARTING) LaunchedEffect(Unit) {
        mainExecutor.execute {
            val provider = try { ProcessCameraProvider.getInstance(context).get() } catch (_: Throwable) { state = AdaptiveScannerState.ERROR; return@execute }
            try { V6Contract.loadAndVerify(context) } catch (_: Throwable) {}
            previewView.doOnLayout { bindCamera(provider, previewView) }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP && state in listOf(AdaptiveScannerState.STARTING, AdaptiveScannerState.SCANNING)) stopScan() }
        lifecycleOwner.lifecycle.addObserver(obs); onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    DisposableEffect(Unit) { onDispose { releaseCamera() } }
    DisposableEffect(state) {
        try { if (state == AdaptiveScannerState.SCANNING) (context as? android.app.Activity)?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (_: Throwable) {}
        onDispose { try { (context as? android.app.Activity)?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (_: Throwable) {} }
    }

    val done = completed
    if (state == AdaptiveScannerState.COMPLETE && done != null) {
        AdaptiveCompleteScreen(done, { startScan() }, modifier); return
    }

    if (state == AdaptiveScannerState.IDLE || state == AdaptiveScannerState.ERROR) {
        Box(modifier.fillMaxSize().background(Color(0xFF090B10)), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("SuperQR", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text("Adaptive V7 optical receiver", color = Color.White.copy(alpha = .62f))
                Text("Profile: AUTO • carrier: proven geometry", color = Color(0xFF7CB7FF), fontSize = 11.sp)
                if (state == AdaptiveScannerState.ERROR) Text(cameraState, color = Color(0xFFFF7B72), fontSize = 11.sp)
                Button(onClick = { startScan() }, modifier = Modifier.fillMaxWidth()) { Text("SCAN SUPERQR", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 7.dp)) }
                if (onBack != null) TextButton(onClick = onBack) { Text("Back") }
            }
        }; return
    }

    if (!permission) { Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Grant camera permission") } }; return }

    val src = result?.diagnosticPayload?.classificationSource ?: "SEARCH"
    val fresh = src == "FULL_DETECTION" || src == "TRACKED_RESAMPLED"
    val hasSession = accumulator.getCurrentSessionId() != -1
    val guidance = when {
        hasSession -> "RECEIVING"
        result == null || result?.borderFound != true -> "CENTER SUPERQR"
        result?.orientationResolved != true -> "SHOW ALL 4 CORNERS"
        calibrated < activeProfile.colorCount -> "CALIBRATING ${activeProfile.colorCount} COLORS"
        fresh && erasures == 0 -> "LOCKED"
        fresh -> "HOLD STEADY"
        else -> "ALIGN SUPERQR"
    }
    val guideColor = if (guidance == "RECEIVING" || guidance == "LOCKED") Color(0xFF7EE787) else Color(0xFFF2CC60)

    Box(modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        AdaptiveOverlay(overlay, guidance, guideColor, Modifier.fillMaxSize())
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 7.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("SuperQR", color = Color.White, fontWeight = FontWeight.Bold); Text(activeProfile.label, color = Color(0xFF7CB7FF), fontSize = 9.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { showDebug = !showDebug }, label = { Text(if (showDebug) "Live" else "Debug", fontSize = 10.sp) })
                TextButton(onClick = { stopScan() }) { Text("Stop", color = Color.White) }
            }
        }

        if (showDebug) {
            AdaptiveDebugPanel(
                profile = activeProfile, cameraState = cameraState, result = result, calibrated = calibrated,
                validSamples = validSamples, confident = confidentCells, erasures = erasures, analyzed = analyzedFrames,
                crcPass = crcPass, crcFail = crcFail, cameraFps = cameraFps, analysisFps = analysisFps, analysisMs = analysisMs,
                accumulator = accumulator, focusState = focusState, forcedProfile = forcedProfile, samplerMode = samplerMode,
                onForceProfile = { forcedProfile = it; receiverRef?.forcedProfileId = it.takeIf { id -> id >= 0 } },
                onSampler = { samplerMode = it; receiverRef?.probeMode = it }, onLock = ::lockFocus, onUnlock = ::unlockFocus,
                onClose = { showDebug = false }, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(10.dp)
            )
        } else {
            AdaptiveReceiveCard(activeProfile, accumulator, calibrated, confidentCells, erasures, lastFrame, lastError, crcPass, crcFail, cameraFps, analysisFps,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp))
        }
    }
}

@Composable private fun AdaptiveOverlay(g: V6PreviewOverlayGeometry?, guidance: String, color: Color, modifier: Modifier) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val side = size.minDimension * .76f; val left = (size.width - side) / 2; val top = (size.height - side) / 2; val c = side * .10f; val sw = 2.dp.toPx(); val guide = Color.White.copy(alpha = .28f)
            listOf(
                Offset(left, top) to Offset(left+c, top), Offset(left, top) to Offset(left, top+c),
                Offset(left+side, top) to Offset(left+side-c, top), Offset(left+side, top) to Offset(left+side, top+c),
                Offset(left, top+side) to Offset(left+c, top+side), Offset(left, top+side) to Offset(left, top+side-c),
                Offset(left+side, top+side) to Offset(left+side-c, top+side), Offset(left+side, top+side) to Offset(left+side, top+side-c)
            ).forEach { drawLine(guide, it.first, it.second, sw) }
            if (g != null && g.outerQuad.size == 4) {
                val exact = if (g.isFresh) Color(0xFF7EE787) else Color(0xFFF2CC60)
                for (i in 0..3) drawLine(exact, g.outerQuad[i], g.outerQuad[(i+1)%4], 3.dp.toPx())
            }
        }
        Surface(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top=58.dp), shape=RoundedCornerShape(20.dp), color=Color.Black.copy(alpha=.72f)) {
            Text(guidance, color=color, fontWeight=FontWeight.Bold, fontSize=12.sp, modifier=Modifier.padding(horizontal=14.dp, vertical=6.dp))
        }
    }
}

@Composable private fun AdaptiveReceiveCard(profile: V7OpticalProfile, acc: V7SessionAccumulator, calibrated: Int, confident: Int, erasures: Int, lastFrame: Int, error: String?, crcPass: Int, crcFail: Int, cameraFps: Double, analysisFps: Double, modifier: Modifier) {
    Card(modifier.fillMaxWidth(), colors=CardDefaults.cardColors(containerColor=Color(0xE6141821))) {
        Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
                Text(profile.label, color=Color(0xFF7CB7FF), fontSize=11.sp, fontWeight=FontWeight.Bold)
                Text("CAL $calibrated/${profile.colorCount}", color=if(calibrated==profile.colorCount) Color(0xFF7EE787) else Color(0xFFF2CC60), fontSize=10.sp)
            }
            val total=acc.getTotalFrames(); val unique=acc.getUniqueFrames()
            Text(if(total>0) "$unique / $total frames • ${acc.getMissingFramesCount()} missing" else "Waiting for CRC-valid frame", color=Color.White, fontSize=14.sp, fontWeight=FontWeight.SemiBold)
            if(total>0) LinearProgressIndicator(progress={acc.getProgress().toFloat()}, modifier=Modifier.fillMaxWidth())
            Text("Cells $confident/${profile.cellCount} confident • $erasures erased", color=Color.White.copy(alpha=.74f), fontSize=10.sp)
            Text("CRC pass $crcPass • fail $crcFail • last ${if(lastFrame>=0) lastFrame else "—"}${error?.let { " • $it" } ?: ""}", color=if(error==null) Color.White.copy(alpha=.66f) else Color(0xFFFF7B72), fontSize=10.sp)
            Text("Camera ${"%.1f".format(cameraFps)} fps • analysis ${"%.1f".format(analysisFps)} fps", color=Color.White.copy(alpha=.55f), fontSize=10.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AdaptiveDebugPanel(profile: V7OpticalProfile, cameraState: String, result: V6StaticResult?, calibrated: Int, validSamples: Int, confident: Int, erasures: Int, analyzed: Int, crcPass: Int, crcFail: Int, cameraFps: Double, analysisFps: Double, analysisMs: Double, accumulator: V7SessionAccumulator, focusState: String, forcedProfile: Int, samplerMode: V7HighDensitySampler.ProbeMode, onForceProfile:(Int)->Unit, onSampler:(V7HighDensitySampler.ProbeMode)->Unit, onLock:()->Unit, onUnlock:()->Unit, onClose:()->Unit, modifier: Modifier) {
    Card(modifier.fillMaxWidth(), colors=CardDefaults.cardColors(containerColor=Color(0xF0141821))) {
        Column(Modifier.padding(12.dp).heightIn(max=420.dp).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) { Text("LIVE DEBUG", color=Color(0xFF7CB7FF), fontWeight=FontWeight.Bold); TextButton(onClick=onClose){Text("Close")} }
            Text("Profile: ${profile.key} • id ${profile.id} • ${profile.frameSize} B/frame • ${profile.payloadSize} payload", color=Color.White, fontSize=10.sp)
            Text("Camera: $cameraState • tracking ${result?.trackingState ?: "—"} • ${result?.diagnosticPayload?.classificationSource ?: "—"}", color=Color.White, fontSize=10.sp)
            Text("Geometry: border ${result?.borderFound ?: false} • orientation ${result?.orientationResolved ?: false} • detector ${result?.processingTimeMs ?: 0} ms", color=Color.White.copy(alpha=.78f), fontSize=10.sp)
            Text("Cells: sampled $validSamples/${profile.cellCount} • confident $confident • erased $erasures • CAL $calibrated/${profile.colorCount}", color=Color.White, fontSize=10.sp)
            Text("Frames analyzed $analyzed • CRC pass $crcPass • fail $crcFail • unique ${accumulator.getUniqueFrames()} • dup ${accumulator.getDuplicateCount()} • conflicts ${accumulator.getConflictCount()}", color=Color.White, fontSize=10.sp)
            Text("Camera ${"%.1f".format(cameraFps)} fps • analysis ${"%.1f".format(analysisFps)} fps • V7 ${"%.2f".format(analysisMs)} ms", color=Color.White, fontSize=10.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                FilterChip(selected=samplerMode==V7HighDensitySampler.ProbeMode.CENTER_1, onClick={onSampler(V7HighDensitySampler.ProbeMode.CENTER_1)}, label={Text("CENTER_1", fontSize=9.sp)})
                FilterChip(selected=samplerMode==V7HighDensitySampler.ProbeMode.CROSS_5, onClick={onSampler(V7HighDensitySampler.ProbeMode.CROSS_5)}, label={Text("CROSS_5", fontSize=9.sp)})
            }
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded=expanded, onExpandedChange={expanded=it}) {
                OutlinedTextField(value=if(forcedProfile<0) "AUTO profile" else V7OpticalProfiles.byId(forcedProfile)?.label ?: "AUTO", onValueChange={}, readOnly=true, label={Text("Profile detection")}, modifier=Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(expanded=expanded, onDismissRequest={expanded=false}) {
                    DropdownMenuItem(text={Text("AUTO from marker")}, onClick={onForceProfile(-1); expanded=false})
                    V7OpticalProfiles.all.forEach { p -> DropdownMenuItem(text={Text(p.label)}, onClick={onForceProfile(p.id); expanded=false}) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(6.dp)) { Button(onClick=onLock, modifier=Modifier.weight(1f)){Text("Focus & lock", fontSize=10.sp)}; Button(onClick=onUnlock, modifier=Modifier.weight(1f)){Text("Unlock", fontSize=10.sp)} }
            Text("Focus: $focusState", color=Color.White.copy(alpha=.65f), fontSize=10.sp)
        }
    }
}

@Composable private fun AdaptiveCompleteScreen(done: AdaptiveCompleted, again:()->Unit, modifier: Modifier) {
    Box(modifier.fillMaxSize().background(Color(0xFF090B10)).padding(18.dp)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("TRANSFER COMPLETE", color=Color(0xFF7EE787), fontSize=23.sp, fontWeight=FontWeight.Bold)
            Card(colors=CardDefaults.cardColors(containerColor=Color.White.copy(alpha=.07f))) { Column(Modifier.padding(15.dp), verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text(done.pkg.filename, color=Color.White, fontSize=18.sp, fontWeight=FontWeight.Bold); Text(done.pkg.mimeType, color=Color(0xFF7CB7FF), fontSize=11.sp)
                Text("${done.pkg.fileSize} B • ${done.frames} frames • session ${done.session}", color=Color.White.copy(alpha=.78f)); Text("CRC32 PASS • 0x${"%08X".format(done.pkg.fileCrc32)}", color=Color(0xFF7EE787))
            } }
            Text("Preview", color=Color.White, fontWeight=FontWeight.Bold)
            Surface(color=Color.White.copy(alpha=.05f), shape=RoundedCornerShape(10.dp)) { Text(V6ReceiveUtils.generatePreview(done.pkg.fileData), color=Color.White.copy(alpha=.8f), fontSize=12.sp, modifier=Modifier.padding(12.dp)) }
            Button(onClick=again, modifier=Modifier.fillMaxWidth()){Text("Scan another")}
        }
    }
}
