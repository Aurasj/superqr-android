package com.superqr.android.ui.phase1

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Debug
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
import androidx.compose.ui.Alignment
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
import com.superqr.android.vision.v7_capacity_lab.V7Phase1QrDecoder
import com.superqr.android.vision.v7_capacity_lab.V7Phase1Receiver
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger

@Composable
fun Phase1LabScreen(
    analysisExecutor: ExecutorService,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val manifest = remember { Phase1Manifest.load(context) }
    val recorder = remember { Phase1ObservationRecorder() }
    val generation = remember { AtomicInteger(0) }
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

    var profileIndex by remember { mutableIntStateOf(0) }
    var dwellIndex by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var cameraState by remember { mutableStateOf("IDLE") }
    var status by remember { mutableStateOf(recorder.snapshot()) }
    val profile = manifest.profiles[profileIndex]
    val dwell = manifest.dwellEpochs[dwellIndex]

    fun resetRun() {
        recorder.reset(); status = recorder.snapshot()
    }

    DisposableEffect(permission, running, profileIndex, dwellIndex) {
        val myGeneration = generation.incrementAndGet()
        var provider: ProcessCameraProvider? = null
        var detector: V6StaticDetector? = null
        var qrDecoder: V7Phase1QrDecoder? = null
        var analysis: ImageAnalysis? = null
        if (permission && running) {
            cameraState = "STARTING"
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (generation.get() != myGeneration) return@addListener
                try {
                    provider = future.get()
                    try { V6Contract.loadAndVerify(context) } catch (_: Throwable) {}
                    previewView.doOnLayout {
                        if (generation.get() != myGeneration) return@doOnLayout
                        try {
                            provider?.unbindAll()
                            val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
                            val preview = Preview.Builder().setTargetRotation(rotation).build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }
                            analysis = ImageAnalysis.Builder()
                                .setTargetRotation(rotation)
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                            val gridReceiver = (profile as? Phase1Profile.Grid)?.let { V7Phase1Receiver(it.receiverProfile, manifest.seed) }
                            detector = if (gridReceiver != null) V6StaticDetector() else null
                            qrDecoder = if (profile is Phase1Profile.Qr) V7Phase1QrDecoder() else null
                            val luma = LumaFrameBuffer()
                            val chroma = ChromaSampleBuffers()
                            val chromaReader = ImageProxyChromaSampler(chroma)
                            var deliveredFrames = 0
                            var warmupRemaining = 60
                            var cameraLabel = "CAMERA"
                            analysis!!.setAnalyzer(analysisExecutor) { image ->
                                val arrivalNs = System.nanoTime()
                                val gcStart = gcCount()
                                @Suppress("DEPRECATION") val allocStart = Debug.getThreadAllocSize().toLong()
                                try {
                                    if (!luma.packFrom(image)) return@setAnalyzer
                                    val grid = profile as? Phase1Profile.Grid
                                    val qr = profile as? Phase1Profile.Qr
                                    var frameIndex: Long? = null
                                    var observedBits = 0
                                    var bitErrors = 0
                                    var erasedBits = 0
                                    var frameValid = false
                                    var postFecValid = false
                                    var byteErrors = 0
                                    var byteErasures = 0
                                    var validSamples = 0
                                    var errorCells: IntArray? = null
                                    var errorCellCount = 0
                                    var erasureCells: IntArray? = null
                                    var erasureCellCount = 0
                                    var geometrySource = "QR_NATIVE"
                                    if (grid != null && gridReceiver != null) {
                                        observedBits = grid.receiverProfile.rawBytesPerFrame * 8
                                        val geometry = detector!!.detectGeometry(luma.bytes, luma.width, luma.height)
                                        geometrySource = geometry.geometrySource
                                        val h = geometry.finalInvHomography
                                        if (h == null) {
                                            erasedBits = observedBits
                                        } else {
                                            val reader = if (grid.receiverProfile.bitsPerCell == 2) chromaReader.bind(image) else null
                                            val result = gridReceiver.analyze(h, luma.bytes, luma.width, luma.height, reader)
                                            frameIndex = result.frameIndex?.toLong()
                                            bitErrors = result.bitErrors; erasedBits = result.erasedBits
                                            frameValid = result.frameValid; postFecValid = result.postFecValid
                                            byteErrors = result.byteErrors; byteErasures = result.byteErasures
                                            validSamples = result.validSamples
                                            errorCells = result.errorCellIndexes; errorCellCount = result.errorCellCount
                                            erasureCells = result.erasureCellIndexes; erasureCellCount = result.erasureCellCount
                                        }
                                    } else if (qr != null) {
                                        observedBits = qr.frameBytes * 8
                                        val result = qrDecoder!!.analyze(luma.bytes, luma.width, luma.height, qr.version, qr.frameBytes)
                                        frameIndex = result.frameIndex
                                        frameValid = result.valid; postFecValid = result.valid
                                        if (!result.valid) erasedBits = observedBits
                                    }
                                    val completedNs = System.nanoTime()
                                    @Suppress("DEPRECATION") val allocEnd = Debug.getThreadAllocSize().toLong()
                                    val allocationBytes = (allocEnd - allocStart).coerceAtLeast(0)
                                    val gcEvents = (gcCount() - gcStart).coerceAtLeast(0)
                                    if (warmupRemaining > 0) {
                                        warmupRemaining--
                                        val remaining = warmupRemaining
                                        if (remaining % 10 == 0) mainExecutor.execute {
                                            if (generation.get() == myGeneration) {
                                                cameraState = if (remaining == 0) "$cameraLabel • READY" else "$cameraLabel • WARMUP $remaining"
                                            }
                                        }
                                        return@setAnalyzer
                                    }
                                    val next = recorder.record(
                                        profile = profile, dwellEpochs = dwell, completedNs = completedNs,
                                        frameIndex = frameIndex, observedBits = observedBits,
                                        bitErrors = bitErrors, erasedBits = erasedBits,
                                        frameValid = frameValid, postFecValid = postFecValid,
                                        pipelineMs = (completedNs - arrivalNs) / 1_000_000.0,
                                        allocationBytes = allocationBytes, gcEvents = gcEvents,
                                        errorCellIndexes = errorCells, errorCellCount = errorCellCount,
                                        erasureCellIndexes = erasureCells, erasureCellCount = erasureCellCount,
                                        extra = mapOf(
                                            "sensor_timestamp_ns" to image.imageInfo.timestamp,
                                            "geometry_source" to geometrySource,
                                            "byte_errors" to byteErrors,
                                            "byte_erasures" to byteErasures,
                                            "valid_samples" to validSamples,
                                        ),
                                    )
                                    deliveredFrames++
                                    if (deliveredFrames % 5 == 0) mainExecutor.execute {
                                        if (generation.get() == myGeneration) status = next
                                    }
                                } catch (t: Throwable) {
                                    mainExecutor.execute { cameraState = "ERROR: ${t.message ?: t.javaClass.simpleName}" }
                                } finally {
                                    image.close()
                                }
                            }
                            val viewPort = previewView.viewPort
                            val base = SessionConfig.Builder(listOf(preview, analysis!!)).apply {
                                if (viewPort != null) setViewPort(viewPort)
                            }
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
                            cameraState = "$cameraLabel • WARMUP 60"
                        } catch (t: Throwable) {
                            cameraState = "ERROR: ${t.message ?: "camera"}"
                        }
                    }
                } catch (t: Throwable) {
                    cameraState = "ERROR: ${t.message ?: "provider"}"
                }
            }, mainExecutor)
        }
        onDispose {
            generation.incrementAndGet()
            analysis?.clearAnalyzer()
            try { provider?.unbindAll() } catch (_: Throwable) {}
            detector?.close(); qrDecoder?.close()
        }
    }

    DisposableEffect(running) {
        if (running) activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    Column(modifier.background(Color(0xFF090B10))) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().weight(1f))
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("V7 Phase 1 • PHY Selection", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(profile.name, color = Color(0xFF7DD3FC))
            Text("Dwell $dwell refreshes • $cameraState", color = Color.LightGray)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !running, onClick = { profileIndex = (profileIndex + manifest.profiles.size - 1) % manifest.profiles.size; resetRun() }) { Text("Previous PHY") }
                Button(enabled = !running, onClick = { profileIndex = (profileIndex + 1) % manifest.profiles.size; resetRun() }) { Text("Next PHY") }
                Button(enabled = !running, onClick = { dwellIndex = (dwellIndex + 1) % manifest.dwellEpochs.size; resetRun() }) { Text("Dwell") }
            }
            Text("Frames ${status.observations} • unique ${status.uniqueFrames}/256 • valid ${status.validFrames} • RS15 ${status.postFecFrames}", color = Color.White)
            Text("Goodput %.2f KiB/s • pipeline %.2f ms • run %s".format(status.goodputKibS, status.meanPipelineMs, status.runId.take(8)), color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (!running) resetRun(); running = !running }) { Text(if (running) "Stop" else "Start") }
                OutlinedButton(enabled = !running, onClick = { resetRun() }) { Text("Reset") }
                OutlinedButton(enabled = status.observations > 0, onClick = {
                    try { recorder.exportAndShare(context, profile.name) }
                    catch (t: Throwable) { Toast.makeText(context, "Export failed: ${t.message}", Toast.LENGTH_LONG).show() }
                }) { Text("Export JSONL") }
            }
            Text("Lab-only: no production V7 wire-format changes.", color = Color.Gray)
        }
    }
}

private fun gcCount(): Long = try {
    Debug.getRuntimeStat("art.gc.gc-count").toLongOrNull() ?: 0L
} catch (_: Throwable) { 0L }
