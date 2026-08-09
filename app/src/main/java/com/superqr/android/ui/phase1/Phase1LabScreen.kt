package com.superqr.android.ui.phase1

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Debug
import android.util.Log
import android.util.Size
import android.view.Surface
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.SessionConfig
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.superqr.android.camera.ChromaSampleBuffers
import com.superqr.android.camera.ImageProxyChromaSampler
import com.superqr.android.camera.LumaFrameBuffer
import com.superqr.android.vision.opencv.OpenCvRuntime
import com.superqr.android.vision.v7_capacity_lab.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

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
    val previewExecutor = remember {
        Executors.newSingleThreadExecutor { task -> Thread(task, "phy-lab-analysis-preview").apply { isDaemon = true } }
    }
    val previewPublisher = remember(previewExecutor) { Phase1AnalysisPreviewPublisher(previewExecutor) }
    DisposableEffect(previewExecutor) { onDispose { previewExecutor.shutdownNow() } }
    var permission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    LaunchedEffect(Unit) { if (!permission) launcher.launch(Manifest.permission.CAMERA) }

    var running by remember { mutableStateOf(false) }
    var cameraState by remember { mutableStateOf("STOPPED") }
    var status by remember { mutableStateOf(recorder.snapshot()) }
    var alignmentPreview by remember { mutableStateOf(Phase1AnalysisPreview()) }

    DisposableEffect(permission, running) {
        val myGeneration = generation.incrementAndGet()
        var provider: ProcessCameraProvider? = null
        var carrierAcquirer: V7CarrierAcquirer? = null
        var qrDecoder: V7Phase1QrDecoder? = null
        var analysis: ImageAnalysis? = null
        if (permission && running) {
            cameraState = "CAMERA STARTING"
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (generation.get() != myGeneration) return@addListener
                try {
                    provider = future.get()
                    try {
                            provider?.unbindAll()
                            val rotation = activity?.window?.decorView?.display?.rotation ?: Surface.ROTATION_0
                            val resolutionSelector = ResolutionSelector.Builder()
                                .setAllowedResolutionMode(ResolutionSelector.PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION)
                                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        Size(Phase1AnalysisPolicy.TARGET_WIDTH, Phase1AnalysisPolicy.TARGET_HEIGHT),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                    ),
                                )
                                .build()
                            analysis = ImageAnalysis.Builder()
                                .setTargetRotation(rotation)
                                .setResolutionSelector(resolutionSelector)
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                            val openCv = OpenCvRuntime.ensureLoaded()
                            carrierAcquirer = V7CarrierAcquirer(manifest.carrierSpec)
                            qrDecoder = V7Phase1QrDecoder()
                            val luma = LumaFrameBuffer()
                            val chroma = ChromaSampleBuffers()
                            val chromaReader = ImageProxyChromaSampler(chroma)
                            var activeGridId = -1
                            var gridReceiver: V7Phase1Receiver? = null
                            val scheduler = Phase1AcquisitionScheduler()
                            var deliveredFrames = 0
                            var warmupFrames = 0
                            var warmupStartedNs = 0L
                            var resolutionRejected = false
                            var cameraLabel = "CAMERA • 1280×720 target • OpenCV ${openCv.version}"
                            var previewSuppressed = false

                            fun updatePreviewSuppression(value: Boolean) {
                                if (value == previewSuppressed) return
                                previewSuppressed = value
                                previewPublisher.setMeasuring(value)
                                mainExecutor.execute {
                                    if (generation.get() == myGeneration) {
                                        alignmentPreview = alignmentPreview.copy(measuring = value)
                                    }
                                }
                            }

                            analysis!!.setAnalyzer(analysisExecutor) { image ->
                                val arrivalNs = System.nanoTime()
                                val gcStart = gcCount()
                                @Suppress("DEPRECATION") val allocStart = Debug.getThreadAllocSize().toLong()
                                try {
                                    val crop = image.cropRect
                                    if (!Phase1AnalysisPolicy.accepts(crop.width(), crop.height())) {
                                        if (!resolutionRejected) mainExecutor.execute {
                                            cameraState = "ERROR • Camera delivered ${crop.width()}×${crop.height()}; analysis is capped at ${Phase1AnalysisPolicy.MAX_ANALYSIS_PIXELS} pixels"
                                        }
                                        resolutionRejected = true
                                        return@setAnalyzer
                                    }
                                    if (!luma.packFrom(image)) return@setAnalyzer
                                    if (warmupStartedNs == 0L) warmupStartedNs = arrivalNs
                                    warmupFrames++
                                    if (!Phase1AnalysisPolicy.warmupComplete(warmupStartedNs, arrivalNs, warmupFrames)) {
                                        if (warmupFrames == 1 || warmupFrames % 4 == 0) mainExecutor.execute {
                                            if (generation.get() == myGeneration) cameraState = "$cameraLabel • ${luma.width}×${luma.height} • WARMUP"
                                        }
                                        return@setAnalyzer
                                    }
                                    if (warmupFrames == Phase1AnalysisPolicy.MIN_WARMUP_FRAMES || deliveredFrames == 0) mainExecutor.execute {
                                        if (generation.get() == myGeneration) cameraState = "$cameraLabel • ${luma.width}×${luma.height} • ANALYZING"
                                    }
                                    val packedNs = System.nanoTime()
                                    if (scheduler.path == Phase1AnalysisPath.GRID) {
                                        val acquisition = carrierAcquirer!!.analyze(
                                            luma.bytes, luma.width, luma.height,
                                            diagnostics = !previewSuppressed,
                                        )
                                        val acquisitionDoneNs = System.nanoTime()
                                        val h = acquisition.canonicalToImageHomography
                                        if (h == null) {
                                            if (deliveredFrames % 30 == 0) {
                                                Log.i(
                                                    "SuperQR-V7Carrier",
                                                    "source=${acquisition.source} candidates=${acquisition.candidateCount} " +
                                                        "contours=${acquisition.contourCount} attempts=${acquisition.syncAttempts} " +
                                                        "finderHypotheses=${acquisition.finderHypothesisCount} " +
                                                        "best=${acquisition.bestSyncStatus} topContrast=${acquisition.sync.topContrast} " +
                                                        "bottomContrast=${acquisition.sync.bottomContrast} " +
                                                        "margin=${acquisition.sync.minimumCellMargin} " +
                                                        "quad=${acquisition.detectedQuad?.joinToString { it.joinToString(prefix = "[", postfix = "]") }} " +
                                                        "candidateSummary=${acquisition.candidateSummary}",
                                                )
                                            }
                                            scheduler.missed(
                                                Phase1AnalysisPath.GRID,
                                                carrierCandidate = acquisition.carrierLike,
                                            )
                                            recorder.recordFailure(
                                                acquisition.bestSyncStatus, acquisitionDoneNs,
                                                (acquisitionDoneNs - arrivalNs) / 1_000_000.0,
                                                acquisition.source, acquisition.sync.status,
                                                mapOf(
                                                    "sensor_timestamp_ns" to image.imageInfo.timestamp,
                                                    "capture_width" to luma.width, "capture_height" to luma.height,
                                                    "analysis_path" to "GRID", "acquisition_state" to scheduler.state,
                                                    "luma_pack_ms" to (packedNs - arrivalNs) / 1_000_000.0,
                                                    "acquisition_ms" to (acquisitionDoneNs - packedNs) / 1_000_000.0,
                                                    "contours_considered" to acquisition.contourCount,
                                                    "quad_candidates" to acquisition.candidateCount,
                                                    "sync_hypotheses_tested" to acquisition.syncAttempts,
                                                    "best_sync_status" to acquisition.bestSyncStatus,
                                                    "top_sync_contrast" to acquisition.sync.topContrast,
                                                    "bottom_sync_contrast" to acquisition.sync.bottomContrast,
                                                    "minimum_sync_cell_margin" to acquisition.sync.minimumCellMargin,
                                                    "detected_quad" to acquisition.detectedQuad,
                                                ),
                                            )
                                        } else {
                                            val sync = acquisition.sync
                                            val syncDoneNs = acquisitionDoneNs
                                            val envelope = sync.envelope
                                            val profile = envelope?.let { manifest.profile(it.profileId) }
                                            if (envelope != null && profile is Phase1Profile.Grid) {
                                                // READY is the operator's alignment guard. Keep the exact
                                                // analysis preview live there; suppress it only when measured
                                                // RUNNING data actually begins.
                                                updatePreviewSuppression(envelope.state == V7LabRunState.RUNNING)
                                                scheduler.locked(Phase1AnalysisPath.GRID)
                                                recorder.observeSender(profile, envelope, sync.status, acquisition.source)
                                                if (envelope.state == V7LabRunState.RUNNING) {
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
                                                        sync.status, acquisition.source, reason,
                                                        result.errorCellIndexes, result.errorCellCount,
                                                        result.erasureCellIndexes, result.erasureCellCount,
                                                        mapOf(
                                                            "sensor_timestamp_ns" to image.imageInfo.timestamp,
                                                            "capture_width" to luma.width, "capture_height" to luma.height,
                                                            "analysis_path" to "GRID", "acquisition_state" to scheduler.state,
                                                            "luma_pack_ms" to (packedNs - arrivalNs) / 1_000_000.0,
                                                            "acquisition_ms" to (acquisitionDoneNs - packedNs) / 1_000_000.0,
                                                            "sync_ms" to 0.0,
                                                            "payload_ms" to (completedNs - syncDoneNs) / 1_000_000.0,
                                                            "byte_errors" to result.byteErrors, "byte_erasures" to result.byteErasures,
                                                            "valid_samples" to result.validSamples, "black_y" to result.blackY, "white_y" to result.whiteY,
                                                            "contours_considered" to acquisition.contourCount,
                                                            "quad_candidates" to acquisition.candidateCount,
                                                            "sync_hypotheses_tested" to acquisition.syncAttempts,
                                                            "top_sync_contrast" to sync.topContrast,
                                                            "bottom_sync_contrast" to sync.bottomContrast,
                                                            "minimum_sync_cell_margin" to sync.minimumCellMargin,
                                                        ),
                                                    )
                                                }
                                            } else {
                                                scheduler.missed(Phase1AnalysisPath.GRID, carrierCandidate = acquisition.carrierLike)
                                                val reason = when {
                                                    envelope == null -> sync.status
                                                    profile == null -> "SYNC_UNKNOWN_PROFILE_${envelope.profileId}"
                                                    else -> "SYNC_PROFILE_CARRIER_MISMATCH"
                                                }
                                                recorder.recordFailure(
                                                    reason, syncDoneNs, (syncDoneNs - arrivalNs) / 1_000_000.0,
                                                    acquisition.source, sync.status,
                                                    mapOf(
                                                        "sensor_timestamp_ns" to image.imageInfo.timestamp,
                                                        "capture_width" to luma.width, "capture_height" to luma.height,
                                                        "analysis_path" to "GRID", "acquisition_state" to scheduler.state,
                                                        "luma_pack_ms" to (packedNs - arrivalNs) / 1_000_000.0,
                                                        "acquisition_ms" to (acquisitionDoneNs - packedNs) / 1_000_000.0,
                                                        "sync_ms" to 0.0,
                                                        "contours_considered" to acquisition.contourCount,
                                                        "quad_candidates" to acquisition.candidateCount,
                                                        "sync_hypotheses_tested" to acquisition.syncAttempts,
                                                        "top_sync_contrast" to sync.topContrast,
                                                        "bottom_sync_contrast" to sync.bottomContrast,
                                                        "minimum_sync_cell_margin" to sync.minimumCellMargin,
                                                    ),
                                                )
                                            }
                                        }

                                        // The preview and overlay are produced only from this exact
                                        // GRID analysis frame and its own acquisition result. Never
                                        // overlay geometry from a previous GRID frame onto a later QR
                                        // analysis frame while the search scheduler alternates paths.
                                        if (!previewSuppressed) {
                                            val framing = Phase1FramingEvaluator.evaluate(
                                                luma.width, luma.height, acquisition, manifest.carrierSpec,
                                            )
                                            previewPublisher.offer(
                                                luma.bytes, luma.width, luma.height, framing, arrivalNs,
                                            ) { preview ->
                                                mainExecutor.execute {
                                                    if (generation.get() == myGeneration && !previewSuppressed) alignmentPreview = preview
                                                    else preview.bitmap?.recycle()
                                                }
                                            }
                                        }
                                    } else {
                                        val qr = qrDecoder!!.analyzeAuto(luma.bytes, luma.width, luma.height, qrExpected)
                                        val completedNs = System.nanoTime()
                                        val envelope = qr.envelope
                                        val profile = envelope?.let { manifest.profile(it.profileId) }
                                        if (envelope != null && profile is Phase1Profile.Qr) {
                                            updatePreviewSuppression(envelope.state == V7LabRunState.RUNNING)
                                            scheduler.locked(Phase1AnalysisPath.QR)
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
                                                        "analysis_path" to "QR", "acquisition_state" to scheduler.state,
                                                        "luma_pack_ms" to (packedNs - arrivalNs) / 1_000_000.0,
                                                        "qr_ms" to (completedNs - packedNs) / 1_000_000.0,
                                                    ),
                                                )
                                            } else if (envelope.state == V7LabRunState.RUNNING) {
                                                recorder.recordFailure(
                                                    qr.failure ?: "QR_PAYLOAD_INVALID", completedNs,
                                                    (completedNs - arrivalNs) / 1_000_000.0, "QR_NATIVE", "QR_LOCKED",
                                                    mapOf("capture_width" to luma.width, "capture_height" to luma.height,
                                                        "analysis_path" to "QR", "qr_ms" to (completedNs - packedNs) / 1_000_000.0),
                                                )
                                            }
                                        } else {
                                            scheduler.missed(Phase1AnalysisPath.QR)
                                            recorder.recordFailure(
                                                qr.failure ?: "QR_NOT_DECODED", completedNs,
                                                (completedNs - arrivalNs) / 1_000_000.0, "QR_NATIVE", scheduler.state,
                                                mapOf(
                                                    "sensor_timestamp_ns" to image.imageInfo.timestamp,
                                                    "capture_width" to luma.width, "capture_height" to luma.height,
                                                    "analysis_path" to "QR", "acquisition_state" to scheduler.state,
                                                    "luma_pack_ms" to (packedNs - arrivalNs) / 1_000_000.0,
                                                    "qr_ms" to (completedNs - packedNs) / 1_000_000.0,
                                                ),
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
                            // ImageAnalysis is the only camera output. The UI renders a
                            // throttled copy of the same normalized luma buffer and never
                            // attaches an independent Preview viewport that can crop or
                            // transform the operator's view differently from the receiver.
                            val base = SessionConfig.Builder(listOf(analysis!!))
                            val info = provider!!.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA)
                            val ranges = try { info.getSupportedFrameRateRanges(base.build()) } catch (_: Throwable) { emptySet() }
                            val chosen = ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
                                ?: ranges.filter { it.lower <= 30 && it.upper >= 30 }.minByOrNull { it.upper - it.lower }
                            val config = SessionConfig.Builder(listOf(analysis!!)).apply {
                                if (chosen != null) setFrameRateRange(chosen)
                            }.build()
                            provider!!.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, config)
                            val fpsLabel = if (chosen == null) "AUTO FPS" else "${chosen.lower}-${chosen.upper} FPS"
                            cameraLabel = "CAMERA $fpsLabel • 1280×720 target • OpenCV ${openCv.version}"
                            cameraState = "$cameraLabel • WARMUP"
                    } catch (t: Throwable) { cameraState = "ERROR • ${t.message ?: "camera"}" }
                } catch (t: Throwable) { cameraState = "ERROR • ${t.message ?: "provider"}" }
            }, mainExecutor)
        }
        onDispose {
            generation.incrementAndGet(); analysis?.clearAnalyzer()
            previewPublisher.setMeasuring(false)
            try { provider?.unbindAll() } catch (_: Throwable) {}
            carrierAcquirer?.close(); qrDecoder?.close()
        }
    }

    DisposableEffect(running) {
        if (running) activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    Phase1LabDashboard(
        alignmentPreview = alignmentPreview,
        status = status,
        cameraState = cameraState,
        running = running,
        cameraPermission = permission,
        onToggleCamera = {
            running = !running
            if (running) alignmentPreview = Phase1AnalysisPreview()
        },
        onNewCampaign = {
            recorder.reset(); status = recorder.snapshot(); alignmentPreview = Phase1AnalysisPreview()
        },
        onShare = {
            try { recorder.exportAndShare(context) }
            catch (t: Throwable) { Toast.makeText(context, "Export failed: ${t.message}", Toast.LENGTH_LONG).show() }
        },
        modifier = modifier,
    )
}

private fun gcCount(): Long = try { Debug.getRuntimeStat("art.gc.gc-count").toLongOrNull() ?: 0L } catch (_: Throwable) { 0L }
