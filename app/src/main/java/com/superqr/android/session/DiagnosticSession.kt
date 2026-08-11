package com.superqr.android.session

import android.content.Context
import com.superqr.android.camera.AnalysisRateAccumulator
import com.superqr.android.camera.CameraFrame
import com.superqr.android.phase1.Phase1AnalysisPath
import com.superqr.android.phase1.Phase1AnalysisPolicy
import com.superqr.android.phase1.Phase1Manifest
import com.superqr.android.phase1.Phase1ObservationRecorder
import com.superqr.android.phase1.Phase1Profile
import com.superqr.android.phase1.Phase1RunSnapshot
import com.superqr.android.phase1.Phase1TrackingState
import com.superqr.android.phase1.VisionEngine
import com.superqr.android.phase1.VisionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
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
    private val recorder = Phase1ObservationRecorder()
    private val analysisRate = AnalysisRateAccumulator(64)
    private val frameGate = SessionFrameGate()
    private val capturedQrFrames = ArrayList<CapturedQrFrame>(MAX_CAPTURED_QR_FRAMES)
    private var lastQrCaptureSequence = Int.MIN_VALUE / 2
    private var wasTracking = false
    private var wasLost = false
    private var observedFrames = 0
    private var sessionActive = false

    fun startSession() {
        resetForNewSession()
        sessionActive = true
        frameGate.start()
        _sessionState.value = SessionState(phase = SessionPhase.SEARCHING, campaignId = recorder.campaignId)
    }

    fun stopSession() {
        if (!sessionActive) return

        // stop() synchronizes with any result commit currently in progress and bumps
        // the epoch so analyses that started before STOP can no longer commit afterward.
        frameGate.stop()
        sessionActive = false

        // Every explicit Start/Stop cycle must leave a shareable diagnostic artifact,
        // even when no QR/carrier was ever confirmed. COMPLETE remains terminal until
        // the operator explicitly starts a new session.
        if (!recorder.hasLines) {
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
            hasObservations = recorder.hasLines,
        )
    }

    fun reset() {
        frameGate.stop()
        sessionActive = false
        resetForNewSession()
    }

    private fun resetForNewSession() {
        engine.reset()
        recorder.reset()
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

        val result = try {
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

        // All mutable session/recorder state is committed under the same gate token.
        // If STOP happened while vision was analyzing this frame, this block is skipped.
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

            // READY/DONE GRID frames are valid synchronization evidence just like
            // READY/DONE QR frames. They update sender/run state but remain unscored.
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

            // READY/DONE QR frames are valid lock/synchronization evidence, but the
            // Phase 1 contract scores payload only while the sender is RUNNING.
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
                val progress = if (envelope != null) {
                    CampaignProgress(
                        runToken = envelope.runToken,
                        state = envelope.state.name,
                        frameIndex = envelope.frameIndex,
                        frameCount = envelope.frameCount,
                    )
                } else {
                    CampaignProgress()
                }
                it.copy(
                    phase = derivePhase(result),
                    trackingState = result.trackingState,
                    framing = result.framing,
                    sourceTransform = frame.sourceTransform,
                    profileName = result.profileName,
                    cameraFps = frame.cameraFps,
                    analysisFps = analysisFps,
                    pipelineMs = result.pipelineMs,
                    analyzedFrames = observedFrames,
                    hasObservations = recorder.hasLines,
                    campaignId = recorder.campaignId,
                    error = result.qrResult?.failure,
                    campaignProgress = progress,
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
            // Event-selected exact normalized analyzer luma. No normal-frame copy.
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
        return if (result.envelope.state == com.superqr.android.vision.v7_capacity_lab.V7LabRunState.RUNNING) {
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
            return if (result.envelope?.state == com.superqr.android.vision.v7_capacity_lab.V7LabRunState.RUNNING) {
                SessionPhase.RECEIVING
            } else SessionPhase.QR_LOCKED
        }
        if (qr.decoded || qr.quad != null) {
            // qr.quad is emitted only by native QR detectors, not by the generic
            // carrier contour path. It is therefore real QR detection even when
            // payload decode fails on this exposure.
            wasTracking = true
            wasLost = false
            return SessionPhase.QR_DETECTED
        }

        wasTracking = false
        wasLost = false
        return SessionPhase.SEARCHING
    }

    fun snapshot(): Phase1RunSnapshot = recorder.snapshot()

    fun exportSession(): File? {
        if (!recorder.hasLines && capturedQrFrames.isEmpty()) return null
        val file = File(context.cacheDir, "superqr-phase1-${recorder.campaignId.take(8)}.zip")
        val state = _sessionState.value
        val snapshot = recorder.snapshot()
        val observationLines = recorder.currentObservationLines()
        val captures = capturedQrFrames.toList()

        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            val sessionJson = JSONObject()
                .put("format", "superqr_phase0_diagnostic_bundle")
                .put("schema_version", 1)
                .put("campaign_id", recorder.campaignId)
                .put("phase", state.phase.name)
                .put("profile", state.profileName)
                .put("analyzed_frames", state.analyzedFrames)
                .put("camera_fps", state.cameraFps)
                .put("analysis_fps", state.analysisFps)
                .put("pipeline_ms", state.pipelineMs)
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

            val observations = if (observationLines.isEmpty()) "" else observationLines.joinToString("\n", postfix = "\n")
            zip.writeTextEntry("observations.jsonl", observations)

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
        engine.close()
    }

    companion object {
        private const val MAX_CAPTURED_QR_FRAMES = 3
        private const val QR_CAPTURE_MIN_GAP_FRAMES = 8
    }
}
