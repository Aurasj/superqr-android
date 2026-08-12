package com.superqr.android.session

import android.content.Context
import com.superqr.android.camera.AnalysisRateAccumulator
import com.superqr.android.camera.CameraFrame
import com.superqr.android.phase1.AdvancedObservationRecorder
import com.superqr.android.phase1.AdvancedPhyEngine
import com.superqr.android.phase1.AdvancedPhyManifest
import com.superqr.android.phase1.AdvancedPhyResult
import com.superqr.android.phase1.Phase1AnalysisPath
import com.superqr.android.phase1.Phase1AnalysisPolicy
import com.superqr.android.phase1.Phase1Manifest
import com.superqr.android.phase1.Phase1ObservationRecorder
import com.superqr.android.phase1.Phase1Profile
import com.superqr.android.phase1.Phase1RunSnapshot
import com.superqr.android.phase1.Phase1TrackingState
import com.superqr.android.phase1.ShapeGridEngine
import com.superqr.android.phase1.ShapeGridManifest
import com.superqr.android.phase1.ShapeGridResult
import com.superqr.android.phase1.ShapeGridObservationRecorder
import com.superqr.android.phase1.VisionEngine
import com.superqr.android.phase1.VisionResult
import com.superqr.android.vision.v7_capacity_lab.V7LabRunState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Session-local frame gate. The token never crosses into CameraManager; it exists only
 * to prevent an analysis that started before STOP from committing state afterward.
 */
internal class SessionFrameGate {
    private var accepting = false
    private var epoch = 0L

    @Synchronized
    fun start() {
        epoch++
        accepting = true
    }

    @Synchronized
    fun stop() {
        accepting = false
        epoch++
    }

    @Synchronized
    fun acceptsFrames(): Boolean = accepting

    @Synchronized
    fun enter(): Long? = if (accepting) epoch else null

    @Synchronized
    fun commitIfCurrent(token: Long, block: () -> Unit): Boolean {
        if (!accepting || token != epoch) return false
        block()
        return true
    }
}

private data class CapturedQrFrame(
    val sequence: Int,
    val width: Int,
    val height: Int,
    val sensorTimestampNs: Long,
    val arrivalNs: Long,
    val pipelineMs: Double,
    val failure: String?,
    val schedulerState: String,
    val quad: List<DoubleArray>?,
    val diagnostics: Map<String, Any?>,
    val luma: ByteArray,
)

class DiagnosticSession(private val context: Context) {
    private val _sessionState = MutableStateFlow(SessionState())
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val manifest by lazy { Phase1Manifest.load(context) }
    private val engine by lazy { VisionEngine(manifest) }
    val receiveSession: com.superqr.android.receive.ReceiveSession by lazy {
        com.superqr.android.receive.ReceiveSession(context.cacheDir)
    }
    var transferMode: Boolean = false

    fun setAppMode(mode: AppMode) {
        transferMode = mode == AppMode.RECEIVE
        receiveSession.reset()
        if (mode == AppMode.RECEIVE) {
            setReceiverMode(ReceiverMode.QR_ONLY)
        }
        _sessionState.value = _sessionState.value.copy(appMode = mode)
    }
    private val advancedManifest by lazy { AdvancedPhyManifest.load(context) }
    private val shapeGridManifest by lazy { ShapeGridManifest.load(context) }
    private var advancedEngine: AdvancedPhyEngine? = null
    private var shapeGridEngine: ShapeGridEngine? = null
    private val recorder = Phase1ObservationRecorder()
    private val advancedRecorder = AdvancedObservationRecorder()
    private val shapeGridRecorder = ShapeGridObservationRecorder()
    private val analysisRate = AnalysisRateAccumulator(64)
    private val frameGate = SessionFrameGate()
    private val capturedQrFrames = ArrayList<CapturedQrFrame>(MAX_CAPTURED_QR_FRAMES)
    private var lastQrCaptureSequence = Int.MIN_VALUE / 2
    private var wasTracking = false
    private var wasLost = false
    private var observedFrames = 0
    private var sessionActive = false
    private var gapThrottle = 0

    // Stateful decoder routing — only one expensive PHY decoder runs per frame.
    private var receiverMode = ReceiverMode.AUTO
    private var lockedPath: String? = null // "qr", "shapegrid", "advanced", or null
    private var lockMisses = 0

    fun setReceiverMode(mode: ReceiverMode) {
        receiverMode = mode
        lockedPath = null
        lockMisses = 0
        _sessionState.value = _sessionState.value.copy(receiverMode = mode)
    }

    private fun advancedEngine(): AdvancedPhyEngine {
        val existing = advancedEngine
        if (existing != null) return existing
        return AdvancedPhyEngine(advancedManifest, manifest.carrierSpec).also { advancedEngine = it }
    }

    private fun shapeGridEngine(): ShapeGridEngine {
        val existing = shapeGridEngine
        if (existing != null) return existing
        return ShapeGridEngine(shapeGridManifest, manifest.carrierSpec).also { shapeGridEngine = it }
    }

    fun startSession() {
        resetForNewSession()
        sessionActive = true
        frameGate.start()
        _sessionState.value = SessionState(phase = SessionPhase.SEARCHING, campaignId = recorder.campaignId)
    }

    fun stopSession() {
        if (!sessionActive) return
        frameGate.stop()
        sessionActive = false

        if (!recorder.hasLines && !advancedRecorder.hasLines && !shapeGridRecorder.hasLines) {
            recorder.recordFailure(
                reason = "SESSION_STOP_NO_DETECTION",
                completedNs = System.nanoTime(),
                pipelineMs = _sessionState.value.pipelineMs,
                geometry = _sessionState.value.trackingState.name,
                sync = "STOPPED",
                extra = mapOf(
                    "session_event" to "STOP",
                    "analyzed_frame_count" to observedFrames,
                    "final_phase" to _sessionState.value.phase.name,
                    "final_profile" to _sessionState.value.profileName,
                    "forensic_qr_frames" to capturedQrFrames.size,
                ),
            )
        }

        _sessionState.value = _sessionState.value.copy(
            phase = SessionPhase.COMPLETE,
            hasObservations = recorder.hasLines || advancedRecorder.hasLines || shapeGridRecorder.hasLines,
        )
    }

    fun reset() {
        frameGate.stop()
        sessionActive = false
        resetForNewSession()
    }

    private fun resetForNewSession() {
        engine.reset()
        receiveSession.reset()
        advancedEngine?.close()
        advancedEngine = null
        shapeGridEngine?.close()
        shapeGridEngine = null
        recorder.reset()
        advancedRecorder.reset()
        shapeGridRecorder.reset()
        analysisRate.reset()
        capturedQrFrames.clear()
        lastQrCaptureSequence = Int.MIN_VALUE / 2
        wasTracking = false
        wasLost = false
        observedFrames = 0
        _sessionState.value = SessionState(campaignId = recorder.campaignId)
    }

    fun processFrame(frame: CameraFrame) {
        val frameToken = frameGate.enter() ?: return

        if (!Phase1AnalysisPolicy.accepts(frame.width, frame.height)) {
            frameGate.commitIfCurrent(frameToken) {
                updateState {
                    it.copy(
                        error = "Resolution rejected: ${frame.width}x${frame.height}",
                        sourceTransform = frame.sourceTransform,
                    )
                }
            }
            return
        }

        val runShapeGrid = receiverMode == ReceiverMode.SHAPEGRID_ONLY ||
            (receiverMode == ReceiverMode.AUTO && lockedPath != "qr" && lockedPath != "advanced")
        val runAdvanced = receiverMode != ReceiverMode.SHAPEGRID_ONLY && receiverMode != ReceiverMode.QR_ONLY &&
            (receiverMode == ReceiverMode.AUTO && lockedPath != "qr")
        val runVision = receiverMode != ReceiverMode.SHAPEGRID_ONLY

        // ShapeGrid — only when not locked to QR/Advanced and mode permits.
        if (runShapeGrid) {
            val shapeGrid = try {
                shapeGridEngine().analyze(
                    frame.lumaBytes, frame.width, frame.height, frame.chromaReader,
                )
            } catch (t: Throwable) {
                frameGate.commitIfCurrent(frameToken) {
                    updateState { it.copy(error = "ShapeGrid ${t::class.java.simpleName}: ${t.message}") }
                }
                return
            }
            if (shapeGrid != null) {
                handleShapeGrid(frame, shapeGrid, frameToken)
                lockIfReceiving("shapegrid", shapeGrid.envelope.state == V7LabRunState.RUNNING)
                return
            }
            if (lockedPath == "shapegrid") {
                lockMisses++
                if (lockMisses >= MAX_LOCK_MISSES) { lockedPath = null; lockMisses = 0 }
                return
            }
        }

        // Advanced — only when mode permits and not locked to QR.
        if (runAdvanced) {
            val advanced = try {
                advancedEngine().analyze(
                    frame.lumaBytes, frame.width, frame.height, frame.chromaReader, frame.arrivalNs,
                )
            } catch (t: Throwable) {
                frameGate.commitIfCurrent(frameToken) {
                    updateState { it.copy(error = "AdvancedPhy ${t::class.java.simpleName}: ${t.message}") }
                }
                return
            }
            if (advanced != null) {
                handleAdvanced(frame, advanced, frameToken)
                lockIfReceiving("advanced", advanced.envelope.state == V7LabRunState.RUNNING)
                return
            }
            if (lockedPath == "advanced") {
                lockMisses++
                if (lockMisses >= MAX_LOCK_MISSES) { lockedPath = null; lockMisses = 0 }
                return
            }
        }

        // Canonical VisionEngine (QR + grid).
        if (!runVision) return

        // Transfer mode: feed raw QR bytes directly into receive session.
        if (transferMode) {
            val external = (frame.chromaReader as? com.superqr.android.vision.v7_capacity_lab.ExternalQrFrameDecoder)?.decodeQr()
            if (external != null && external.payload.isNotEmpty()) {
                receiveSession.processQrBytes(external.payload)
            }
        }

        val result = try {
            if (receiverMode == ReceiverMode.QR_ONLY)
                engine.analyzeQrDirect(frame.lumaBytes, frame.width, frame.height, frame.chromaReader, frame.arrivalNs)
            else
                engine.analyze(frame.lumaBytes, frame.width, frame.height, frame.chromaReader, frame.arrivalNs)
        } catch (t: Throwable) {
            frameGate.commitIfCurrent(frameToken) {
                updateState {
                    it.copy(
                        error = "${t::class.java.simpleName}: ${t.message}",
                        sourceTransform = frame.sourceTransform,
                    )
                }
            }
            return
        }

        val completedNs = System.nanoTime()
        frameGate.commitIfCurrent(frameToken) {
            analysisRate.recordCompletion(completedNs)
            val analysisFps = analysisRate.computeFps(completedNs)
            observedFrames++
            captureQrFailureIfSelected(frame, result, observedFrames)

            result.gridObservation?.let { observation ->
                recorder.record(
                    profile = observation.profile,
                    dwellEpochs = result.envelope?.dwellEpochs ?: 3,
                    completedNs = completedNs,
                    frameIndex = observation.frameIndex,
                    observedBits = observation.observedBits,
                    bitErrors = observation.bitErrors,
                    erasedBits = observation.erasedBits,
                    frameValid = observation.frameValid,
                    postFecValid = observation.postFecValid,
                    pipelineMs = result.pipelineMs,
                    allocationBytes = 0,
                    gcEvents = 0,
                    envelope = result.envelope,
                    sync = observation.syncStatus,
                    geometry = observation.geometrySource,
                    failureReason = observation.failureReason,
                    errorCellIndexes = observation.errorCellIndexes,
                    errorCellCount = observation.errorCellCount,
                    erasureCellIndexes = observation.erasureCellIndexes,
                    erasureCellCount = observation.erasureCellCount,
                    extra = frameExtras(frame, result, "GRID"),
                )
            }

            var validNonScoredGrid = false
            if (result.path == Phase1AnalysisPath.GRID && result.gridObservation == null) {
                val envelope = result.envelope
                val profile = envelope?.let { manifest.profile(it.profileId) as? Phase1Profile.Grid }
                if (profile != null && envelope != null) {
                    val acquisition = result.carrierAcquisition
                    recorder.observeSender(
                        profile,
                        envelope,
                        acquisition?.sync?.status ?: "GRID_LOCKED",
                        acquisition?.source ?: "GRID_LOCKED",
                        completedNs,
                    )
                    validNonScoredGrid = true
                }
            }

            if (result.path == Phase1AnalysisPath.QR && result.qrObservation == null && result.qrResult?.valid == true) {
                val profile = result.qrProfile
                val envelope = result.envelope
                if (profile != null && envelope != null) {
                    recorder.observeSender(profile, envelope, "QR_LOCKED", "QR_NATIVE_LOCKED", completedNs)
                }
            }

            result.qrObservation?.let { observation ->
                val envelope = requireNotNull(result.envelope) { "QR observation must carry its validated run envelope" }
                recorder.record(
                    profile = observation.profile,
                    dwellEpochs = envelope.dwellEpochs,
                    completedNs = completedNs,
                    frameIndex = observation.frameIndex,
                    observedBits = observation.profile.frameBytes * 8,
                    bitErrors = 0,
                    erasedBits = 0,
                    frameValid = true,
                    postFecValid = true,
                    pipelineMs = result.pipelineMs,
                    allocationBytes = 0,
                    gcEvents = 0,
                    envelope = envelope,
                    sync = "QR_LOCKED",
                    geometry = observation.geometrySource,
                    extra = frameExtras(frame, result, "QR"),
                )
            }

            val validNonScoredQr = result.path == Phase1AnalysisPath.QR &&
                result.qrObservation == null && result.qrResult?.valid == true && result.envelope != null
            if (result.gridObservation == null && result.qrObservation == null &&
                !validNonScoredGrid && !validNonScoredQr && observedFrames % 15 == 0
            ) {
                val failureReason = when (result.path) {
                    Phase1AnalysisPath.GRID -> result.carrierAcquisition?.bestSyncStatus ?: "GRID_NO_CANDIDATE"
                    Phase1AnalysisPath.QR -> result.qrResult?.failure ?: "QR_NOT_DECODED"
                }
                recorder.recordFailure(
                    failureReason,
                    completedNs,
                    result.pipelineMs,
                    result.trackingState.name,
                    result.schedulerState,
                    frameExtras(frame, result, result.path.name),
                )
            }

            updateState {
                val envelope = result.envelope
                val observedProgress = envelope?.let {
                    CampaignProgress(
                        runToken = it.runToken,
                        state = it.state.name,
                    )
                }
                val progress = it.campaignProgress.stabilizedWith(observedProgress)
                gapThrottle++
                val gaps = if (gapThrottle >= 16 || progress.complete) {
                    gapThrottle = 0
                    recorder.missedGaps()
                } else null
                it.copy(
                    phase = derivePhase(result),
                    trackingState = result.trackingState,
                    framing = result.framing,
                    sourceTransform = frame.sourceTransform,
                    profileName = result.profileName.takeIf { it != "AUTO" } ?: it.profileName,
                    cameraFps = frame.cameraFps,
                    analysisFps = analysisFps,
                    pipelineMs = result.pipelineMs,
                    analyzedFrames = observedFrames,
                    hasObservations = recorder.hasLines || advancedRecorder.hasLines || shapeGridRecorder.hasLines,
                    campaignId = recorder.campaignId,
                    error = result.qrResult?.failure,
                    campaignProgress = progress,
                    receiverUniqueFrames = recorder.currentUniqueFrames,
                    receiverExpectedFrames = recorder.currentExpectedFrames,
                ).let { next -> if (gaps != null) next.copy(missedGaps = gaps) else next }
            }

            lockIfReceiving("qr", result.envelope?.state == V7LabRunState.RUNNING && (result.qrObservation != null || result.gridObservation != null))
        }
    }

    private fun lockIfReceiving(path: String, receiving: Boolean) {
        if (receiving) {
            lockedPath = path
            lockMisses = 0
        } else {
            lockMisses++
            if (lockMisses >= MAX_LOCK_MISSES) { lockedPath = null; lockMisses = 0 }
        }
    }

    private fun handleShapeGrid(frame: CameraFrame, shapeGrid: ShapeGridResult, frameToken: Long) {
        val completedNs = System.nanoTime()
        frameGate.commitIfCurrent(frameToken) {
            analysisRate.recordCompletion(completedNs)
            val analysisFps = analysisRate.computeFps(completedNs)
            observedFrames++
            shapeGridRecorder.record(
                campaignId = recorder.campaignId, result = shapeGrid,
                completedNs = completedNs, cameraFps = frame.cameraFps,
                captureWidth = frame.width, captureHeight = frame.height,
            )
            val observedProgress = CampaignProgress(shapeGrid.envelope.runToken, shapeGrid.envelope.state.name)
            updateState {
                it.copy(
                    phase = if (shapeGrid.envelope.state == V7LabRunState.RUNNING) SessionPhase.RECEIVING else SessionPhase.GRID_LOCKED,
                    trackingState = Phase1TrackingState.TRACKING, sourceTransform = frame.sourceTransform,
                    profileName = shapeGrid.profile.name, cameraFps = frame.cameraFps, analysisFps = analysisFps,
                    pipelineMs = shapeGrid.shapegridTotalMs, analyzedFrames = observedFrames,
                    hasObservations = shapeGridRecorder.hasLines || advancedRecorder.hasLines || recorder.hasLines,
                    campaignId = recorder.campaignId, error = shapeGrid.failure,
                    campaignProgress = it.campaignProgress.stabilizedWith(observedProgress),
                )
            }
        }
    }

    private fun handleAdvanced(frame: CameraFrame, advanced: AdvancedPhyResult, frameToken: Long) {
        val completedNs = System.nanoTime()
        frameGate.commitIfCurrent(frameToken) {
            analysisRate.recordCompletion(completedNs)
            val analysisFps = analysisRate.computeFps(completedNs)
            observedFrames++
            advancedRecorder.record(
                campaignId = recorder.campaignId, result = advanced,
                completedNs = completedNs, cameraFps = frame.cameraFps,
                captureWidth = frame.width, captureHeight = frame.height,
            )
            val observedProgress = CampaignProgress(advanced.envelope.runToken, advanced.envelope.state.name)
            updateState {
                it.copy(
                    phase = if (advanced.envelope.state == V7LabRunState.RUNNING) SessionPhase.RECEIVING else SessionPhase.QR_LOCKED,
                    trackingState = Phase1TrackingState.TRACKING, sourceTransform = frame.sourceTransform,
                    profileName = advanced.profile.name, cameraFps = frame.cameraFps, analysisFps = analysisFps,
                    pipelineMs = advanced.pipelineMs, analyzedFrames = observedFrames,
                    hasObservations = advancedRecorder.hasLines || recorder.hasLines || shapeGridRecorder.hasLines,
                    campaignId = recorder.campaignId, error = advanced.failure,
                    campaignProgress = it.campaignProgress.stabilizedWith(observedProgress),
                )
            }
        }
    }

    private fun captureQrFailureIfSelected(frame: CameraFrame, result: VisionResult, sequence: Int) {
        if (capturedQrFrames.size >= MAX_CAPTURED_QR_FRAMES) return
        if (sequence - lastQrCaptureSequence < QR_CAPTURE_MIN_GAP_FRAMES) return
        if (result.path != Phase1AnalysisPath.QR) return
        val qr = result.qrResult ?: return
        val quad = qr.quad ?: return
        if (qr.valid) return
        if (qr.diagnostics["zxing_attempted"] != true && qr.diagnostics["external_qr_attempted"] != true) return

        capturedQrFrames += CapturedQrFrame(
            sequence = sequence,
            width = frame.width,
            height = frame.height,
            sensorTimestampNs = frame.sensorTimestamp,
            arrivalNs = frame.arrivalNs,
            pipelineMs = result.pipelineMs,
            failure = qr.failure,
            schedulerState = result.schedulerState,
            quad = quad.map { it.copyOf() },
            diagnostics = LinkedHashMap(qr.diagnostics),
            luma = frame.lumaBytes.copyOf(),
        )
        lastQrCaptureSequence = sequence
    }

    private fun frameExtras(frame: CameraFrame, result: VisionResult, path: String): Map<String, Any?> = buildMap {
        put("capture_width", frame.width)
        put("capture_height", frame.height)
        put("sensor_timestamp_ns", frame.sensorTimestamp)
        put("analysis_path", path)
        put("acquisition_state", result.schedulerState)
        result.qrResult?.let { qr ->
            put("qr_failure", qr.failure)
            put("qr_decoded", qr.decoded)
            put("qr_valid", qr.valid)
            put("qr_payload_bytes", qr.bytes)
            if (qr.diagnostics.isNotEmpty()) put("qr_decoder", qr.diagnostics)
        }
    }

    private fun derivePhase(result: VisionResult): SessionPhase = when (result.path) {
        Phase1AnalysisPath.GRID -> deriveGridPhase(result)
        Phase1AnalysisPath.QR -> deriveQrPhase(result)
    }

    private fun deriveGridPhase(result: VisionResult): SessionPhase {
        val acquisition = result.carrierAcquisition
        if (acquisition == null) {
            val phase = if (wasTracking || wasLost) SessionPhase.LOST else SessionPhase.SEARCHING
            wasTracking = false
            wasLost = true
            return phase
        }
        if (wasLost && !acquisition.acquired) {
            wasTracking = false
            return SessionPhase.REACQUIRING
        }
        wasTracking = true
        wasLost = false
        if (!acquisition.acquired) return SessionPhase.SEARCHING
        if (result.envelope == null) return SessionPhase.GRID_DETECTED
        return if (result.envelope.state == V7LabRunState.RUNNING) {
            SessionPhase.RECEIVING
        } else SessionPhase.GRID_LOCKED
    }

    private fun deriveQrPhase(result: VisionResult): SessionPhase {
        val qr = result.qrResult ?: run {
            wasTracking = false
            wasLost = true
            return SessionPhase.SEARCHING
        }
        if (qr.valid) {
            wasTracking = true
            wasLost = false
            return if (result.envelope?.state == V7LabRunState.RUNNING) {
                SessionPhase.RECEIVING
            } else SessionPhase.QR_LOCKED
        }
        if (qr.decoded || qr.quad != null) {
            wasTracking = true
            wasLost = false
            return SessionPhase.QR_DETECTED
        }
        wasTracking = false
        wasLost = false
        return SessionPhase.SEARCHING
    }

    fun snapshot(): Phase1RunSnapshot {
        val s = recorder.snapshot()
        _sessionState.value = _sessionState.value.copy(
            missedGaps = recorder.missedGaps(),
        )
        return s
    }

    fun exportSession(): File? {
        if (!recorder.hasLines && !advancedRecorder.hasLines && !shapeGridRecorder.hasLines && capturedQrFrames.isEmpty()) return null
        val file = File(context.cacheDir, "superqr-phase1-${recorder.campaignId.take(8)}.zip")
        val state = _sessionState.value
        val snapshot = recorder.snapshot()
        val advancedSnapshot = advancedRecorder.snapshot()
        val shapeGridSnapshot = shapeGridRecorder.snapshot()
        val observationLines = recorder.currentObservationLines()
        val advancedLines = advancedRecorder.currentLines()
        val shapeGridLines = shapeGridRecorder.currentLines()
        val captures = capturedQrFrames.toList()

        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            val sessionJson = JSONObject()
                .put("format", "superqr_phase0_diagnostic_bundle")
                .put("schema_version", 3)
                .put("campaign_id", recorder.campaignId)
                .put("phase", state.phase.name)
                .put("profile", state.profileName)
                .put("analyzed_frames", state.analyzedFrames)
                .put("camera_fps", state.cameraFps)
                .put("analysis_fps", state.analysisFps)
                .put("pipeline_ms", state.pipelineMs)
                .put("advanced_phy", advancedRecorder.hasLines)
                .put("shapegrid_phy", shapeGridRecorder.hasLines)
                .put("receiver_max_fps", 30.0)
                .put("shapegrid_design_target_fps", 20.0)
                .put("qr_forensic_frame_count", captures.size)
            zip.writeTextEntry("session.json", sessionJson.toString(2))

            val summaryJson = JSONObject()
                .put("campaign_id", snapshot.campaignId)
                .put("run_id", snapshot.runId)
                .put("profile", snapshot.profileName)
                .put("sender_state", snapshot.senderState)
                .put("sync_status", snapshot.syncStatus)
                .put("geometry_state", snapshot.geometryState)
                .put("analyzed_frames", snapshot.analyzedFrames)
                .put("observations", snapshot.observations)
                .put("valid_frames", snapshot.validFrames)
                .put("unique_frames", snapshot.uniqueFrames)
                .put("mean_pipeline_ms", snapshot.meanPipelineMs)
                .put("p95_pipeline_ms", snapshot.p95PipelineMs)
                .put("camera_fps", snapshot.cameraFps)
                .put("capture_width", snapshot.captureWidth)
                .put("capture_height", snapshot.captureHeight)
                .put("last_failure", snapshot.lastFailure ?: JSONObject.NULL)
                .put("failure_summary", snapshot.failureSummary)
            zip.writeTextEntry("summary.json", summaryJson.toString(2))

            // Per-run aggregation from observations
            val runMap = LinkedHashMap<Int, JSONObject>()
            val runSeen = LinkedHashMap<Int, BooleanArray>()
            val runPipes = LinkedHashMap<Int, ArrayList<Double>>()
            val runProfiles = LinkedHashMap<Int, String>()
            val runFailures = LinkedHashMap<Int, LinkedHashMap<String, Int>>()
            for (line in observationLines) {
                if (line.isBlank()) continue
                val obs = JSONObject(line)
                val rt = obs.optInt("run_token", -1)
                if (rt < 0) continue
                runProfiles.putIfAbsent(rt, obs.optString("profile", "?"))
                runSeen.putIfAbsent(rt, BooleanArray(256))
                runPipes.putIfAbsent(rt, ArrayList())
                runFailures.putIfAbsent(rt, LinkedHashMap())
                val pm = obs.optDouble("pipeline_ms", 0.0)
                if (pm > 0) runPipes[rt]!!.add(pm)
                val fr = obs.optString("failure_reason", null)
                if (fr != null) runFailures[rt]!!.merge(fr, 1, Int::plus)
                if (obs.optBoolean("qr_valid", false)) {
                    val fi = obs.optInt("frame_index", -1)
                    if (fi in 0..255) runSeen[rt]!![fi] = true
                }
            }
            val runsJson = JSONArray()
            for ((rt, seen) in runSeen) {
                val unique = seen.count { it }
                val missed = (0 until 256).filter { !seen[it] }
                val pipes = runPipes[rt]!!.sorted()
                val entry = JSONObject()
                entry.put("run_token", rt)
                entry.put("profile", runProfiles[rt] ?: "?")
                entry.put("unique", unique)
                entry.put("missed", 256 - unique)
                entry.put("coverage_pct", unique / 256.0 * 100.0)
                entry.put("missed_indexes", JSONArray(missed))
                if (pipes.isNotEmpty()) {
                    entry.put("pipeline_avg", pipes.average())
                    entry.put("pipeline_p50", pipes[pipes.size / 2])
                    entry.put("pipeline_p95", pipes[(pipes.size * 0.95).toInt().coerceAtMost(pipes.lastIndex)])
                    entry.put("pipeline_n", pipes.size)
                }
                val fails = runFailures[rt]!!
                if (fails.isNotEmpty()) {
                    val fj = JSONObject()
                    fails.forEach { (k, v) -> fj.put(k, v) }
                    entry.put("failures", fj)
                }
                runsJson.put(entry)
            }
            zip.writeTextEntry("runs.json", runsJson.toString(2))

            val debugJson = JSONObject()
            val missedIdx = recorder.missedFrameIndexes()
            debugJson.put("missed_frame_indexes", JSONArray(missedIdx))
            debugJson.put("missed_count", missedIdx.size)
            debugJson.put("first_missed", if (missedIdx.isNotEmpty()) missedIdx.first() else JSONObject.NULL)
            debugJson.put("last_missed", if (missedIdx.isNotEmpty()) missedIdx.last() else JSONObject.NULL)
            debugJson.put("expected_frames", snapshot.expectedFrames)
            debugJson.put("unique_frames", snapshot.uniqueFrames)
            debugJson.put("coverage_pct", if (snapshot.expectedFrames > 0)
                snapshot.uniqueFrames.toDouble() / snapshot.expectedFrames * 100.0 else 0.0)
            val pStats = recorder.pipelineStats()
            if (pStats.isNotEmpty()) {
                val timingJson = JSONObject()
                pStats.forEach { (k, v) -> timingJson.put(k, v) }
                debugJson.put("pipeline_ms", timingJson)
            }
            debugJson.put("receiver_mode", state.receiverMode.name)
            debugJson.put("session_phase", state.phase.name)
            debugJson.put("camera_fps", state.cameraFps)
            debugJson.put("analysis_fps", state.analysisFps)
            debugJson.put("failure_summary", snapshot.failureSummary)
            zip.writeTextEntry("debug_analysis.json", debugJson.toString(2))

            val observations = if (observationLines.isEmpty()) "" else observationLines.joinToString("\n", postfix = "\n")
            zip.writeTextEntry("observations.jsonl", observations)

            if (shapeGridLines.isNotEmpty()) {
                val shapeGridSummary = JSONObject()
                    .put("profile", shapeGridSnapshot.profileName)
                    .put("run_token", shapeGridSnapshot.runToken ?: JSONObject.NULL)
                    .put("sender_state", shapeGridSnapshot.senderState)
                    .put("frame_count", shapeGridSnapshot.frameCount)
                    .put("observations", shapeGridSnapshot.observations)
                    .put("valid_block_observations", shapeGridSnapshot.validBlockObservations)
                    .put("unique_blocks", shapeGridSnapshot.uniqueBlocks)
                    .put("innovative_bytes", shapeGridSnapshot.innovativeBytes)
                    .put("elapsed_seconds", shapeGridSnapshot.elapsedSeconds)
                    .put("goodput_kib_s", shapeGridSnapshot.goodputKibS)
                    .put("goodput_mbps", shapeGridSnapshot.goodputMbps)
                    .put("block_yield", shapeGridSnapshot.blockYield)
                    .put("symbol_erasures", shapeGridSnapshot.symbolErasures)
                    .put("shape_symbol_errors", shapeGridSnapshot.shapeSymbolErrors)
                    .put("color_symbol_errors", shapeGridSnapshot.colorSymbolErrors)
                    .put("rs_errors", shapeGridSnapshot.rsErrors)
                    .put("rs_erasures", shapeGridSnapshot.rsErasures)
                    .put("projected_tile_pitch_px", shapeGridSnapshot.lastProjectedTilePitchPx)
                    .put("shapegrid_total_ms", shapeGridSnapshot.lastPipelineMs)
                    .put("design_target_fps", 20.0)
                    .put("receiver_max_fps", 30.0)
                    .put("last_failure", shapeGridSnapshot.lastFailure ?: JSONObject.NULL)
                zip.writeTextEntry("shapegrid_summary.json", shapeGridSummary.toString(2))
                zip.writeTextEntry(
                    "shapegrid_observations.jsonl",
                    shapeGridLines.joinToString("\n", postfix = "\n"),
                )
            }

            if (advancedLines.isNotEmpty()) {
                val advancedSummary = JSONObject()
                    .put("profile", advancedSnapshot.profileName)
                    .put("run_token", advancedSnapshot.runToken ?: JSONObject.NULL)
                    .put("sender_state", advancedSnapshot.senderState)
                    .put("lane_count", advancedSnapshot.laneCount)
                    .put("frame_count", advancedSnapshot.frameCount)
                    .put("observations", advancedSnapshot.observations)
                    .put("unique_lane_frames", advancedSnapshot.uniqueLaneFrames)
                    .put("innovative_bytes", advancedSnapshot.innovativeBytes)
                    .put("elapsed_seconds", advancedSnapshot.elapsedSeconds)
                    .put("goodput_kib_s", advancedSnapshot.goodputKibS)
                    .put("goodput_mbps", advancedSnapshot.goodputKibS * 1024.0 * 8.0 / 1_000_000.0)
                    .put("bit_error_rate", advancedSnapshot.bitErrorRate)
                    .put("erasure_rate", advancedSnapshot.erasureRate)
                    .put("decoded_qr_lanes_last_frame", advancedSnapshot.decodedQrLanesLastFrame)
                    .put("receiver_max_fps", 30.0)
                    .put("last_failure", advancedSnapshot.lastFailure ?: JSONObject.NULL)
                zip.writeTextEntry("advanced_summary.json", advancedSummary.toString(2))
                zip.writeTextEntry(
                    "advanced_observations.jsonl",
                    advancedLines.joinToString("\n", postfix = "\n"),
                )
            }

            captures.forEachIndexed { index, capture ->
                val stem = "frames/qr_fail_%03d".format(index + 1)
                zip.writePgmEntry("$stem.pgm", capture)
                val metadata = JSONObject()
                    .put("sequence", capture.sequence)
                    .put("width", capture.width)
                    .put("height", capture.height)
                    .put("sensor_timestamp_ns", capture.sensorTimestampNs)
                    .put("arrival_ns", capture.arrivalNs)
                    .put("pipeline_ms", capture.pipelineMs)
                    .put("failure", capture.failure ?: JSONObject.NULL)
                    .put("scheduler_state", capture.schedulerState)
                    .put("luma_sha256", sha256(capture.luma))
                    .put("quad", quadJson(capture.quad))
                    .put("qr_decoder", mapJson(capture.diagnostics))
                zip.writeTextEntry("$stem.json", metadata.toString(2))
            }
        }
        return file
    }

    private fun ZipOutputStream.writeTextEntry(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipOutputStream.writePgmEntry(name: String, capture: CapturedQrFrame) {
        putNextEntry(ZipEntry(name))
        write("P5\n${capture.width} ${capture.height}\n255\n".toByteArray(Charsets.US_ASCII))
        write(capture.luma)
        closeEntry()
    }

    private fun mapJson(values: Map<String, Any?>): JSONObject = JSONObject().apply {
        values.forEach { (key, value) -> put(key, jsonValue(value)) }
    }

    private fun quadJson(quad: List<DoubleArray>?): Any {
        if (quad == null) return JSONObject.NULL
        return JSONArray().apply {
            quad.forEach { point ->
                put(JSONArray().apply {
                    put(point[0])
                    put(point[1])
                })
            }
        }
    }

    private fun jsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is JSONObject, is JSONArray, is String, is Number, is Boolean -> value
        is Map<*, *> -> JSONObject().apply {
            value.forEach { (key, nested) -> if (key != null) put(key.toString(), jsonValue(nested)) }
        }
        is Iterable<*> -> JSONArray().apply { value.forEach { put(jsonValue(it)) } }
        is DoubleArray -> JSONArray().apply { value.forEach { put(it) } }
        is FloatArray -> JSONArray().apply { value.forEach { put(it.toDouble()) } }
        is IntArray -> JSONArray().apply { value.forEach { put(it) } }
        is LongArray -> JSONArray().apply { value.forEach { put(it) } }
        else -> value.toString()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private inline fun updateState(transform: (SessionState) -> SessionState) {
        _sessionState.value = transform(_sessionState.value)
    }

    fun close() {
        frameGate.stop()
        sessionActive = false
        capturedQrFrames.clear()
        shapeGridEngine?.close()
        shapeGridEngine = null
        advancedEngine?.close()
        advancedEngine = null
        engine.close()
    }

    companion object {
        private const val MAX_LOCK_MISSES = 15
        private const val MAX_CAPTURED_QR_FRAMES = 3
        private const val QR_CAPTURE_MIN_GAP_FRAMES = 8
    }
}
