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
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import com.superqr.android.camera.ChromaSampleBuffers
import com.superqr.android.camera.ImageProxyChromaSampler
import com.superqr.android.camera.LumaFrameBuffer
import com.superqr.android.vision.opencv.OpenCvRuntime
import com.superqr.android.vision.v7_capacity_lab.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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

    // A real CameraX Preview gives a smooth, full-color, full-frame-rate live
    // view. It is bound with a ViewPort shared with ImageAnalysis so both use
    // cases see the identical field of view -- Preview never crops anything
    // ImageAnalysis (and therefore the decoder) does not also see.
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val diagnosticsRef = remember { AtomicReference(Phase1LiveDiagnostics()) }

    var permission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    LaunchedEffect(Unit) { if (!permission) launcher.launch(Manifest.permission.CAMERA) }

    var running by remember { mutableStateOf(false) }
    var debugMode by remember { mutableStateOf(false) }
    // Read on the analyzer thread; toggling this must never rebind the camera
    // or reset acquisition/tracking state, so it is a plain flag, not a
    // DisposableEffect key.
    val debugModeFlag = remember { AtomicBoolean(false) }
    LaunchedEffect(debugMode) {
        debugModeFlag.set(debugMode)
        if (!debugMode) previewPublisher.setMeasuring(false)
    }

    var cameraState by remember { mutableStateOf("STOPPED") }
    var status by remember { mutableStateOf(recorder.snapshot()) }
    var alignmentPreview by remember { mutableStateOf(Phase1AnalysisPreview()) }
    var liveGuidance by remember { mutableStateOf(Phase1FramingGeometry.empty()) }

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
                    previewView.doOnLayout {
                        if (generation.get() != myGeneration) return@doOnLayout
                        try {
                            val boundProvider = provider ?: return@doOnLayout
                            boundProvider.unbindAll()
                            val rotation = previewView.display?.rotation
                                ?: activity?.window?.decorView?.display?.rotation
                                ?: Surface.ROTATION_0
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
                            val preview = Preview.Builder().setTargetRotation(rotation).build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }
                            analysis = ImageAnalysis.Builder()
                                .setTargetRotation(rotation)
                                .setResolutionSelector(resolutionSelector)
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                            // A missing ViewPort (rare timing edge case) falls back to
                            // analysis-only binding rather than risking a Preview/Analysis
                            // field-of-view mismatch that would make the live view lie
                            // about what the decoder sees.
                            val viewPort = previewView.viewPort
                            val openCv = OpenCvRuntime.ensureLoaded()
                            carrierAcquirer = V7CarrierAcquirer(manifest.carrierSpec)
                            qrDecoder = V7Phase1QrDecoder()
                            val luma = LumaFrameBuffer()
                            val chroma = ChromaSampleBuffers()
                            val chromaReader = ImageProxyChromaSampler(chroma)
                            val transformFactory = ImageProxyTransformFactory().apply {
                                setUsingCropRect(true)
                                setUsingRotationDegrees(true)
                            }
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

                            // Exact decoder-input debug view: renders the same normalized
                            // luma buffer the acquirer analyzed, with a technical overlay.
                            // Only paid for while debug mode is on.
                            fun publishDebugPreview(framing: Phase1FramingGeometry, arrivalNs: Long) {
                                if (previewSuppressed) return
                                previewPublisher.offer(
                                    luma.bytes, luma.width, luma.height, framing, arrivalNs,
                                ) { preview ->
                                    mainExecutor.execute {
                                        if (generation.get() == myGeneration && !previewSuppressed) alignmentPreview = preview
                                        else preview.bitmap?.recycle()
                                    }
                                }
                            }

                            // Live guidance overlay: maps the same geometry directly onto
                            // the real camera Preview via CameraX's coordinate transforms,
                            // instead of rendering a bitmap. Cheap: a handful of point
                            // transforms per frame, no pixel copy.
                            fun publishLiveGuidance(framing: Phase1FramingGeometry, sourceTx: OutputTransform?) {
                                if (previewSuppressed) return
                                val targetTx = try { previewView.outputTransform } catch (_: Throwable) { null }
                                val mapped = if (sourceTx != null && targetTx != null) {
                                    mapGeometryToView(framing, sourceTx, targetTx)
                                } else {
                                    framing
                                }
                                mainExecutor.execute {
                                    if (generation.get() == myGeneration && !previewSuppressed) liveGuidance = mapped
                                }
                            }

                            analysis!!.setAnalyzer(analysisExecutor) { image ->
                                val arrivalNs = System.nanoTime()
                                val gcStart = gcCount()
                                @Suppress("DEPRECATION") val allocStart = Debug.getThreadAllocSize().toLong()
                                val sourceTx = try { transformFactory.getOutputTransform(image) } catch (_: Throwable) { null }
                                val isDebug = debugModeFlag.get()
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
                                            scheduler.missed(Phase1AnalysisPath.GRID, carrierCandidate = acquisition.carrierLike)
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
                                                    diagnosticsRef.set(
                                                        Phase1LiveDiagnostics(
                                                            frameWidth = luma.width, frameHeight = luma.height,
                                                            cropLeft = crop.left, cropTop = crop.top, cropRight = crop.right, cropBottom = crop.bottom,
                                                            rotationDegrees = image.imageInfo.rotationDegrees,
                                                            sensorTimestampNs = image.imageInfo.timestamp, arrivalNs = arrivalNs,
                                                            mode = Phase1FramingMode.GRID, activeProfileName = profile.name,
                                                            acquisitionSource = acquisition.source,
                                                            trackingState = Phase1TrackingState.fromSource(acquisition.source),
                                                            homography = h, detectedQuad = acquisition.detectedQuad,
                                                            runToken = envelope.runToken, frameIndex = result.frameIndex?.toLong(),
                                                            acquisitionMs = (acquisitionDoneNs - packedNs) / 1_000_000.0,
                                                            syncMs = 0.0, payloadMs = (completedNs - syncDoneNs) / 1_000_000.0,
                                                            pipelineMs = (completedNs - arrivalNs) / 1_000_000.0,
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
                                                diagnosticsRef.set(
                                                    Phase1LiveDiagnostics(
                                                        frameWidth = luma.width, frameHeight = luma.height,
                                                        cropLeft = crop.left, cropTop = crop.top, cropRight = crop.right, cropBottom = crop.bottom,
                                                        rotationDegrees = image.imageInfo.rotationDegrees,
                                                        sensorTimestampNs = image.imageInfo.timestamp, arrivalNs = arrivalNs,
                                                        mode = Phase1FramingMode.GRID, activeProfileName = "AUTO • searching",
                                                        acquisitionSource = acquisition.source,
                                                        trackingState = Phase1TrackingState.fromSource(acquisition.source),
                                                        homography = h, detectedQuad = acquisition.detectedQuad,
                                                        acquisitionMs = (acquisitionDoneNs - packedNs) / 1_000_000.0,
                                                        syncMs = 0.0, pipelineMs = (syncDoneNs - arrivalNs) / 1_000_000.0,
                                                    ),
                                                )
                                            }
                                        }

                                        if (!previewSuppressed) {
                                            val framing = Phase1FramingEvaluator.evaluate(
                                                luma.width, luma.height, acquisition, manifest.carrierSpec,
                                            )
                                            if (isDebug) publishDebugPreview(framing, arrivalNs) else publishLiveGuidance(framing, sourceTx)
                                            if (h == null) {
                                                diagnosticsRef.set(
                                                    Phase1LiveDiagnostics(
                                                        frameWidth = luma.width, frameHeight = luma.height,
                                                        cropLeft = crop.left, cropTop = crop.top, cropRight = crop.right, cropBottom = crop.bottom,
                                                        rotationDegrees = image.imageInfo.rotationDegrees,
                                                        sensorTimestampNs = image.imageInfo.timestamp, arrivalNs = arrivalNs,
                                                        mode = Phase1FramingMode.GRID, activeProfileName = "AUTO • searching",
                                                        acquisitionSource = acquisition.source,
                                                        trackingState = Phase1TrackingState.fromSource(acquisition.source),
                                                        detectedQuad = acquisition.detectedQuad,
                                                        acquisitionMs = (acquisitionDoneNs - packedNs) / 1_000_000.0,
                                                        pipelineMs = (acquisitionDoneNs - arrivalNs) / 1_000_000.0,
                                                    ),
                                                )
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
                                            recorder.observeSender(profile, envelope, "QR_LOCKED", "QR_NATIVE_FULL_FRAME")
                                            if (envelope.state == V7LabRunState.RUNNING && qr.valid) {
                                                recorder.record(
                                                    profile, envelope.dwellEpochs, completedNs, qr.frameIndex,
                                                    profile.frameBytes * 8, 0, 0, true, true,
                                                    (completedNs - arrivalNs) / 1_000_000.0,
                                                    (Debug.getThreadAllocSize().toLong() - allocStart).coerceAtLeast(0),
                                                    (gcCount() - gcStart).coerceAtLeast(0), envelope,
                                                    "QR_LOCKED", "QR_NATIVE_FULL_FRAME", extra = mapOf(
                                                        "sensor_timestamp_ns" to image.imageInfo.timestamp,
                                                        "capture_width" to luma.width, "capture_height" to luma.height,
                                                        "analysis_path" to "QR", "acquisition_state" to scheduler.state,
                                                        "qr_scan_scope" to "FULL_ANALYSIS_FRAME",
                                                        "luma_pack_ms" to (packedNs - arrivalNs) / 1_000_000.0,
                                                        "qr_ms" to (completedNs - packedNs) / 1_000_000.0,
                                                    ),
                                                )
                                                diagnosticsRef.set(
                                                    Phase1LiveDiagnostics(
                                                        frameWidth = luma.width, frameHeight = luma.height,
                                                        cropLeft = crop.left, cropTop = crop.top, cropRight = crop.right, cropBottom = crop.bottom,
                                                        rotationDegrees = image.imageInfo.rotationDegrees,
                                                        sensorTimestampNs = image.imageInfo.timestamp, arrivalNs = arrivalNs,
                                                        mode = Phase1FramingMode.QR, activeProfileName = profile.name,
                                                        acquisitionSource = "QR_NATIVE_LOCKED",
                                                        trackingState = Phase1TrackingState.TRACKING,
                                                        detectedQuad = qr.quad, runToken = envelope.runToken, frameIndex = qr.frameIndex,
                                                        payloadMs = (completedNs - packedNs) / 1_000_000.0,
                                                        pipelineMs = (completedNs - arrivalNs) / 1_000_000.0,
                                                    ),
                                                )
                                            } else if (envelope.state == V7LabRunState.RUNNING) {
                                                recorder.recordFailure(
                                                    qr.failure ?: "QR_PAYLOAD_INVALID", completedNs,
                                                    (completedNs - arrivalNs) / 1_000_000.0, "QR_NATIVE_FULL_FRAME", "QR_LOCKED",
                                                    mapOf(
                                                        "capture_width" to luma.width, "capture_height" to luma.height,
                                                        "analysis_path" to "QR", "qr_scan_scope" to "FULL_ANALYSIS_FRAME",
                                                        "qr_ms" to (completedNs - packedNs) / 1_000_000.0,
                                                    ),
                                                )
                                            }
                                        } else {
                                            scheduler.missed(Phase1AnalysisPath.QR)
                                            recorder.recordFailure(
                                                qr.failure ?: "QR_NOT_DECODED", completedNs,
                                                (completedNs - arrivalNs) / 1_000_000.0, "QR_NATIVE_FULL_FRAME", scheduler.state,
                                                mapOf(
                                                    "sensor_timestamp_ns" to image.imageInfo.timestamp,
                                                    "capture_width" to luma.width, "capture_height" to luma.height,
                                                    "analysis_path" to "QR", "acquisition_state" to scheduler.state,
                                                    "qr_scan_scope" to "FULL_ANALYSIS_FRAME",
                                                    "luma_pack_ms" to (packedNs - arrivalNs) / 1_000_000.0,
                                                    "qr_ms" to (completedNs - packedNs) / 1_000_000.0,
                                                ),
                                            )
                                            diagnosticsRef.set(
                                                Phase1LiveDiagnostics(
                                                    frameWidth = luma.width, frameHeight = luma.height,
                                                    cropLeft = crop.left, cropTop = crop.top, cropRight = crop.right, cropBottom = crop.bottom,
                                                    rotationDegrees = image.imageInfo.rotationDegrees,
                                                    sensorTimestampNs = image.imageInfo.timestamp, arrivalNs = arrivalNs,
                                                    mode = Phase1FramingMode.QR, activeProfileName = "AUTO • searching",
                                                    acquisitionSource = "QR_NATIVE_SEARCH",
                                                    trackingState = Phase1TrackingState.SEARCHING,
                                                    detectedQuad = qr.quad,
                                                    pipelineMs = (completedNs - arrivalNs) / 1_000_000.0,
                                                ),
                                            )
                                        }

                                        // QR search/READY uses the exact same normalized full luma frame
                                        // as OpenCV. There is no independent Preview use case and no hidden
                                        // center ROI. Once synchronized RUNNING begins this path freezes too.
                                        if (!previewSuppressed) {
                                            val framing = Phase1FramingEvaluator.evaluateQr(luma.width, luma.height, qr)
                                            if (isDebug) publishDebugPreview(framing, arrivalNs) else publishLiveGuidance(framing, sourceTx)
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

                            val useCases = if (viewPort != null) listOf(preview, analysis!!) else listOf(analysis!!)
                            val base = SessionConfig.Builder(useCases).apply { if (viewPort != null) setViewPort(viewPort) }
                            val info = boundProvider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA)
                            val ranges = try { info.getSupportedFrameRateRanges(base.build()) } catch (_: Throwable) { emptySet() }
                            val chosen = ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
                                ?: ranges.filter { it.lower <= 30 && it.upper >= 30 }.minByOrNull { it.upper - it.lower }
                            val config = SessionConfig.Builder(useCases).apply {
                                if (viewPort != null) setViewPort(viewPort)
                                if (chosen != null) setFrameRateRange(chosen)
                            }.build()
                            boundProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, config)
                            val fpsLabel = if (chosen == null) "AUTO FPS" else "${chosen.lower}-${chosen.upper} FPS"
                            val previewNote = if (viewPort == null) " • NO LIVE PREVIEW (viewport unavailable)" else ""
                            cameraLabel = "CAMERA $fpsLabel • 1280×720 target • OpenCV ${openCv.version}$previewNote"
                            cameraState = "$cameraLabel • WARMUP"
                        } catch (t: Throwable) {
                            cameraState = "ERROR • ${t.message ?: "camera"}"
                        }
                    }
                } catch (t: Throwable) {
                    cameraState = "ERROR • ${t.message ?: "provider"}"
                }
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
        previewView = previewView,
        debugMode = debugMode,
        onToggleDebugMode = { debugMode = !debugMode },
        alignmentPreview = alignmentPreview,
        liveGuidance = liveGuidance,
        status = status,
        cameraState = cameraState,
        running = running,
        cameraPermission = permission,
        onToggleCamera = {
            running = !running
            if (running) {
                alignmentPreview = Phase1AnalysisPreview()
                liveGuidance = Phase1FramingGeometry.empty()
            }
        },
        onNewCampaign = {
            recorder.reset(); status = recorder.snapshot()
            alignmentPreview = Phase1AnalysisPreview(); liveGuidance = Phase1FramingGeometry.empty()
        },
        onShare = {
            try { recorder.exportAndShare(context) }
            catch (t: Throwable) { Toast.makeText(context, "Export failed: ${t.message}", Toast.LENGTH_LONG).show() }
        },
        onSaveFrame = {
            val bitmap = alignmentPreview.bitmap
            if (bitmap == null) {
                Toast.makeText(
                    context,
                    "Turn on \"Decoder input\" to capture the exact frame the decoder sees",
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                try {
                    val file = Phase1DebugExporter.exportBundle(
                        context, bitmap, diagnosticsRef.get(), recorder.currentObservationLines(),
                    )
                    Phase1DebugExporter.shareBundle(context, file)
                } catch (t: Throwable) {
                    Toast.makeText(context, "Save frame failed: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        },
        modifier = modifier,
    )
}

/** Maps analysis-space geometry into the PreviewView's own pixel coordinate space. */
private fun mapGeometryToView(
    geometry: Phase1FramingGeometry,
    source: OutputTransform,
    target: OutputTransform,
): Phase1FramingGeometry {
    val transform = try { CoordinateTransform(source, target) } catch (_: Throwable) { return geometry }

    fun mapAll(points: List<Phase1FramePoint>): List<Phase1FramePoint> {
        if (points.isEmpty()) return points
        val array = FloatArray(points.size * 2)
        points.forEachIndexed { index, point -> array[index * 2] = point.x; array[index * 2 + 1] = point.y }
        return try {
            transform.mapPoints(array)
            List(points.size) { index -> Phase1FramePoint(array[index * 2], array[index * 2 + 1]) }
        } catch (_: Throwable) {
            points
        }
    }

    // frameWidth/frameHeight intentionally stay in analysis-space; the mapped
    // point lists are already in PreviewView pixel space and are drawn
    // directly onto a Canvas overlaying that view, with no further fit scale.
    return geometry.copy(
        finderCenters = mapAll(geometry.finderCenters),
        finderQuads = geometry.finderQuads.map(::mapAll),
        carrierQuad = geometry.carrierQuad?.let(::mapAll),
        candidateQuad = geometry.candidateQuad?.let(::mapAll),
    )
}

private fun gcCount(): Long = try {
    Debug.getRuntimeStat("art.gc.gc-count").toLongOrNull() ?: 0L
} catch (_: Throwable) {
    0L
}
