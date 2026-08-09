package com.superqr.android.ui.phase1

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Debug
import android.util.Size
import android.view.Surface
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import com.superqr.android.camera.ChromaSampleBuffers
import com.superqr.android.camera.ImageProxyChromaSampler
import com.superqr.android.camera.LumaFrameBuffer
import com.superqr.android.vision.v6.contract.V6Contract
import com.superqr.android.vision.v6.detection.V6StaticDetector
import com.superqr.android.vision.opencv.OpenCvRuntime
import com.superqr.android.vision.v7_capacity_lab.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger

private val Good = Color(0xFF7EE787)
private val Warn = Color(0xFFF2CC60)
private val Bad = Color(0xFFFF7B72)
private val Accent = Color(0xFF7DD3FC)

@Composable
fun Phase1LabScreen(analysisExecutor: ExecutorService, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val manifest = remember { Phase1Manifest.load(context) }
    val recorder = remember { Phase1ObservationRecorder() }
    val generation = remember { AtomicInteger(0) }
    val qrExpected = remember(manifest) {
        manifest.profiles.filterIsInstance<Phase1Profile.Qr>().associate { it.version to it.frameBytes }
    }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var permission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    LaunchedEffect(Unit) { if (!permission) launcher.launch(Manifest.permission.CAMERA) }

    var running by remember { mutableStateOf(false) }
    var cameraState by remember { mutableStateOf("STOPPED") }
    var status by remember { mutableStateOf(recorder.snapshot()) }

    DisposableEffect(permission, running) {
        val myGeneration = generation.incrementAndGet()
        var provider: ProcessCameraProvider? = null
        var detector: V6StaticDetector? = null
        var qrDecoder: V7Phase1QrDecoder? = null
        var analysis: ImageAnalysis? = null
        if (permission && running) {
            cameraState = "CAMERA STARTING"
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (generation.get() != myGeneration) return@addListener
                try {
                    provider = future.get()
                    V6Contract.loadAndVerify(context)
                    previewView.doOnLayout {
                        if (generation.get() != myGeneration) return@doOnLayout
                        try {
                            provider?.unbindAll()
                            val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
                            val preview = Preview.Builder().setTargetRotation(rotation).build().also { it.surfaceProvider = previewView.surfaceProvider }
                            @Suppress("DEPRECATION")
                            analysis = ImageAnalysis.Builder()
                                .setTargetRotation(rotation)
                                .setTargetResolution(Size(1920, 1080))
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                            val openCv = OpenCvRuntime.ensureLoaded()
                            detector = V6StaticDetector()
                            qrDecoder = V7Phase1QrDecoder()
                            val syncDecoder = V7Phase1SyncDecoder()
                            val luma = LumaFrameBuffer()
                            val chroma = ChromaSampleBuffers()
                            val chromaReader = ImageProxyChromaSampler(chroma)
                            var activeGridId = -1
                            var gridReceiver: V7Phase1Receiver? = null
                            var deliveredFrames = 0
                            var warmupRemaining = 45
                            var cameraLabel = "CAMERA • OpenCV ${openCv.version}"

                            analysis!!.setAnalyzer(analysisExecutor) { image ->
                                val arrivalNs = System.nanoTime()
                                val gcStart = gcCount()
                                @Suppress("DEPRECATION") val allocStart = Debug.getThreadAllocSize().toLong()
                                try {
                                    if (!luma.packFrom(image)) return@setAnalyzer
                                    if (warmupRemaining > 0) {
                                        warmupRemaining--
                                        if (warmupRemaining % 5 == 0) mainExecutor.execute {
                                            if (generation.get() == myGeneration) cameraState = if (warmupRemaining == 0) "$cameraLabel • ANALYZING" else "$cameraLabel • WARMUP $warmupRemaining"
                                        }
                                        return@setAnalyzer
                                    }
                                    val geometry = detector!!.detectGeometry(luma.bytes, luma.width, luma.height)
                                    val h = geometry.finalInvHomography
                                    var handled = false
                                    var pendingFailure: String? = null
                                    var pendingSync = "SEARCHING"
                                    if (h != null) {
                                        val sync = syncDecoder.analyze(h, luma.bytes, luma.width, luma.height)
                                        val envelope = sync.envelope
                                        val profile = envelope?.let { manifest.profile(it.profileId) }
                                        if (envelope != null && profile != null) {
                                            recorder.observeSender(profile, envelope, sync.status, geometry.geometrySource)
                                            handled = true
                                            if (envelope.state == V7LabRunState.RUNNING) {
                                                if (profile is Phase1Profile.Grid) {
                                                    if (activeGridId != profile.id) {
                                                        gridReceiver = V7Phase1Receiver(profile.receiverProfile, manifest.seed)
                                                        activeGridId = profile.id
                                                    }
                                                    val reader = if (profile.receiverProfile.bitsPerCell == 2) chromaReader.bind(image) else null
                                                    val result = gridReceiver!!.analyze(
                                                        h, luma.bytes, luma.width, luma.height, reader,
                                                        synchronizedFrameIndex = envelope.frameIndex,
                                                    )
                                                    val completedNs = System.nanoTime()
                                                    @Suppress("DEPRECATION") val allocation = (Debug.getThreadAllocSize().toLong() - allocStart).coerceAtLeast(0)
                                                    val reason = when {
                                                        result.postFecValid -> null
                                                        result.erasedBits > 0 -> "INNER_FEC_FAILED_WITH_ERASURES"
                                                        else -> "INNER_FEC_FAILED_WITH_ERRORS"
                                                    }
                                                    recorder.record(
                                                        profile, envelope.dwellEpochs, completedNs, result.frameIndex?.toLong(),
                                                        result.observedBits, result.bitErrors, result.erasedBits,
                                                        result.frameValid, result.postFecValid,
                                                        (completedNs - arrivalNs) / 1_000_000.0, allocation,
                                                        (gcCount() - gcStart).coerceAtLeast(0), envelope,
                                                        sync.status, geometry.geometrySource, reason,
                                                        result.errorCellIndexes, result.errorCellCount,
                                                        result.erasureCellIndexes, result.erasureCellCount,
                                                        mapOf(
                                                            "sensor_timestamp_ns" to image.imageInfo.timestamp,
                                                            "capture_width" to luma.width, "capture_height" to luma.height,
                                                            "byte_errors" to result.byteErrors, "byte_erasures" to result.byteErasures,
                                                            "valid_samples" to result.validSamples, "black_y" to result.blackY, "white_y" to result.whiteY,
                                                        ),
                                                    )
                                                } else {
                                                    recorder.recordFailure(
                                                        "SYNC_PROFILE_CARRIER_MISMATCH", System.nanoTime(),
                                                        (System.nanoTime() - arrivalNs) / 1_000_000.0,
                                                        geometry.geometrySource, sync.status,
                                                    )
                                                }
                                            } else {
                                                recorder.snapshot()
                                            }
                                        } else {
                                            val reason = if (envelope == null) sync.status else "SYNC_UNKNOWN_PROFILE_${envelope.profileId}"
                                            if (envelope == null) {
                                                // A campaign can switch from the V6 lab carrier to a native QR.
                                                // Try QR before committing the geometry/sync failure.
                                                pendingFailure = reason; pendingSync = sync.status
                                            } else {
                                                recorder.recordFailure(
                                                    reason, System.nanoTime(), (System.nanoTime() - arrivalNs) / 1_000_000.0,
                                                    geometry.geometrySource, sync.status,
                                                    mapOf("capture_width" to luma.width, "capture_height" to luma.height),
                                                )
                                                handled = true
                                            }
                                        }
                                    }
                                    if (!handled) {
                                        val qr = qrDecoder!!.analyzeAuto(luma.bytes, luma.width, luma.height, qrExpected)
                                        val envelope = qr.envelope
                                        val profile = envelope?.let { manifest.profile(it.profileId) }
                                        val completedNs = System.nanoTime()
                                        if (envelope != null && profile is Phase1Profile.Qr) {
                                            recorder.observeSender(profile, envelope, "QR_LOCKED", "QR_NATIVE")
                                            if (envelope.state == V7LabRunState.RUNNING && qr.valid) {
                                                recorder.record(
                                                    profile, envelope.dwellEpochs, completedNs, qr.frameIndex,
                                                    profile.frameBytes * 8, 0, 0, true, true,
                                                    (completedNs - arrivalNs) / 1_000_000.0,
                                                    (Debug.getThreadAllocSize().toLong() - allocStart).coerceAtLeast(0),
                                                    (gcCount() - gcStart).coerceAtLeast(0), envelope,
                                                    "QR_LOCKED", "QR_NATIVE", extra = mapOf(
                                                        "sensor_timestamp_ns" to image.imageInfo.timestamp,
                                                        "capture_width" to luma.width, "capture_height" to luma.height,
                                                    ),
                                                )
                                            } else recorder.snapshot()
                                        } else {
                                            recorder.recordFailure(
                                                pendingFailure ?: qr.failure ?: "NO_GEOMETRY_OR_QR", completedNs,
                                                (completedNs - arrivalNs) / 1_000_000.0,
                                                if (h == null) "NO_V6_GEOMETRY" else geometry.geometrySource,
                                                if (pendingFailure != null) pendingSync else qr.failure ?: "SEARCHING",
                                                mapOf("capture_width" to luma.width, "capture_height" to luma.height),
                                            )
                                        }
                                    }
                                    deliveredFrames++
                                    if (deliveredFrames % 3 == 0) mainExecutor.execute {
                                        if (generation.get() == myGeneration) status = recorder.snapshot()
                                    }
                                } catch (t: Throwable) {
                                    mainExecutor.execute { cameraState = "ERROR • ${t.message ?: t.javaClass.simpleName}" }
                                } finally {
                                    image.close()
                                }
                            }
                            val viewPort = previewView.viewPort
                            val base = SessionConfig.Builder(listOf(preview, analysis!!)).apply { if (viewPort != null) setViewPort(viewPort) }
                            val info = provider!!.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA)
                            val ranges = try { info.getSupportedFrameRateRanges(base.build()) } catch (_: Throwable) { emptySet() }
                            val chosen = ranges.firstOrNull { it.lower == 60 && it.upper == 60 }
                                ?: ranges.filter { it.upper == 60 }.maxByOrNull { it.lower }
                                ?: ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
                            val config = SessionConfig.Builder(listOf(preview, analysis!!)).apply {
                                if (viewPort != null) setViewPort(viewPort)
                                if (chosen != null) setFrameRateRange(chosen)
                            }.build()
                            provider!!.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, config)
                            cameraLabel = if (chosen == null) "CAMERA AUTO" else "CAMERA ${chosen.lower}-${chosen.upper} FPS"
                            cameraState = "$cameraLabel • WARMUP $warmupRemaining"
                        } catch (t: Throwable) { cameraState = "ERROR • ${t.message ?: "camera"}" }
                    }
                } catch (t: Throwable) { cameraState = "ERROR • ${t.message ?: "provider"}" }
            }, mainExecutor)
        }
        onDispose {
            generation.incrementAndGet(); analysis?.clearAnalyzer()
            try { provider?.unbindAll() } catch (_: Throwable) {}
            detector?.close(); qrDecoder?.close()
        }
    }

    DisposableEffect(running) {
        if (running) activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val stateColor = when {
        status.senderState == "RUNNING" && status.syncStatus.contains("LOCKED") -> Good
        status.senderState == "READY" || status.senderState == "DONE" -> Warn
        status.lastFailure != null -> Bad
        else -> Color.LightGray
    }
    Column(modifier.background(Color(0xFF090B10))) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().weight(0.48f))
        Column(
            Modifier.fillMaxWidth().weight(0.52f).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text("V7 Physical PHY Lab", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text("${status.senderState} • ${status.syncStatus}", style = MaterialTheme.typography.titleMedium, color = stateColor)
            Text("${status.profileName} • run ${status.runId} • expected ${status.expectedFrames}", color = Accent)
            Text("Geometry ${status.geometryState} • $cameraState", color = Color.LightGray)
            LinearProgressIndicator(progress = { status.progress.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text("Analyzed ${status.analyzedFrames} • scored ${status.observations} • unique ${status.uniqueFrames}/${status.expectedFrames}", color = Color.White)
            Text("BER (non-erased) %.3f%% • erasures %.3f%%".format(status.bitErrorRate * 100.0, status.erasureRate * 100.0), color = Color.White)
            Text("Raw valid %.1f%% • inner-FEC valid %.1f%%".format(status.rawValidYield * 100.0, status.innerFecYield * 100.0), color = Color.White)
            Text("Pipeline %.2f / %.2f ms mean/p95 • %.1f fps • %dx%d".format(status.meanPipelineMs, status.p95PipelineMs, status.cameraFps, status.captureWidth, status.captureHeight), color = Color.White)
            Text("Goodput %.2f KiB/s • campaign %s".format(status.goodputKibS, status.campaignId.take(8)), color = Color.White)
            if (status.lastFailure != null) Text("Last failure: ${status.lastFailure}", color = Bad)
            if (status.failureSummary.isNotBlank()) Text("Failures: ${status.failureSummary}", color = Color.LightGray)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { running = !running }) { Text(if (running) "Stop camera" else "Start camera") }
                OutlinedButton(enabled = !running, onClick = { recorder.reset(); status = recorder.snapshot() }) { Text("New campaign") }
                OutlinedButton(enabled = status.analyzedFrames > 0, onClick = {
                    try { recorder.exportAndShare(context) }
                    catch (t: Throwable) { Toast.makeText(context, "Export failed: ${t.message}", Toast.LENGTH_LONG).show() }
                }) { Text("Share results") }
            }
            Text("AUTO follows optical run/profile state. No terminal or manual profile switching required.", color = Color.Gray)
        }
    }
}

private fun gcCount(): Long = try { Debug.getRuntimeStat("art.gc.gc-count").toLongOrNull() ?: 0L } catch (_: Throwable) { 0L }
