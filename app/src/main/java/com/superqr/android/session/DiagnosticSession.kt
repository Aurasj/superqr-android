package com.superqr.android.session

import android.content.Context
import com.superqr.android.camera.AnalysisRateAccumulator
import com.superqr.android.camera.CameraFrame
import com.superqr.android.phase1.Phase1AnalysisPath
import com.superqr.android.phase1.Phase1AnalysisPolicy
import com.superqr.android.phase1.Phase1Manifest
import com.superqr.android.phase1.Phase1ObservationRecorder
import com.superqr.android.phase1.Phase1RunSnapshot
import com.superqr.android.phase1.Phase1TrackingState
import com.superqr.android.phase1.VisionEngine
import com.superqr.android.phase1.VisionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

internal class SessionFrameGate {
    private var accepting = false
    @Synchronized fun start() { accepting = true }
    @Synchronized fun stop() { accepting = false }
    @Synchronized fun acceptsFrames(): Boolean = accepting
}

class DiagnosticSession(private val context: Context) {
    private val _sessionState = MutableStateFlow(SessionState())
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val manifest by lazy { Phase1Manifest.load(context) }
    private val engine by lazy { VisionEngine(manifest) }
    private val recorder = Phase1ObservationRecorder()
    private val analysisRate = AnalysisRateAccumulator(64)
    private val frameGate = SessionFrameGate()
    private var wasTracking = false
    private var wasLost = false
    private var observedFrames = 0

    fun startSession() {
        resetForNewSession()
        frameGate.start()
        _sessionState.value = SessionState(phase = SessionPhase.SEARCHING, campaignId = recorder.campaignId)
    }

    fun stopSession() {
        frameGate.stop()
        val hasObservations = recorder.hasLines
        _sessionState.value = _sessionState.value.copy(
            phase = if (hasObservations) SessionPhase.COMPLETE else SessionPhase.IDLE,
            hasObservations = hasObservations,
        )
    }

    fun reset() {
        frameGate.stop()
        resetForNewSession()
    }

    private fun resetForNewSession() {
        engine.reset()
        recorder.reset()
        analysisRate.reset()
        wasTracking = false
        wasLost = false
        observedFrames = 0
        _sessionState.value = SessionState(campaignId = recorder.campaignId)
    }

    fun processFrame(frame: CameraFrame) {
        if (!frameGate.acceptsFrames()) return
        if (!Phase1AnalysisPolicy.accepts(frame.width, frame.height)) {
            updateState { it.copy(error = "Resolution rejected: ${frame.width}x${frame.height}", sourceTransform = frame.sourceTransform) }
            return
        }

        val result = try {
            engine.analyze(frame.lumaBytes, frame.width, frame.height, frame.chromaReader, frame.arrivalNs)
        } catch (t: Throwable) {
            updateState { it.copy(error = "${t::class.java.simpleName}: ${t.message}", sourceTransform = frame.sourceTransform) }
            return
        }

        val completedNs = System.nanoTime()
        analysisRate.recordCompletion(completedNs)
        val analysisFps = analysisRate.computeFps(completedNs)
        observedFrames++

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

        result.qrObservation?.let { observation ->
            val envelope = requireNotNull(result.envelope) { "QR observation must carry its validated run envelope" }
            recorder.observeSender(observation.profile, envelope, "QR_LOCKED", observation.geometrySource)
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

        if (result.gridObservation == null && result.qrObservation == null && observedFrames % 15 == 0) {
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
            )
        }
    }

    private fun frameExtras(frame: CameraFrame, result: VisionResult, path: String): Map<String, Any?> = mapOf(
        "capture_width" to frame.width,
        "capture_height" to frame.height,
        "sensor_timestamp_ns" to frame.sensorTimestamp,
        "analysis_path" to path,
        "acquisition_state" to result.schedulerState,
    )

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
            return SessionPhase.QR_LOCKED
        }
        if (qr.decoded) {
            wasTracking = true
            wasLost = false
            return SessionPhase.QR_DETECTED
        }
        wasTracking = result.framing.trackingState == Phase1TrackingState.TRACKING
        wasLost = !wasTracking
        return if (qr.quad?.size == 4) SessionPhase.QR_DETECTED
        else if (wasTracking || wasLost) SessionPhase.LOST
        else SessionPhase.SEARCHING
    }

    fun snapshot(): Phase1RunSnapshot = recorder.snapshot()
    fun exportSession(): File? = if (recorder.hasLines) recorder.export(context.cacheDir) else null

    private inline fun updateState(transform: (SessionState) -> SessionState) {
        _sessionState.value = transform(_sessionState.value)
    }

    fun close() {
        frameGate.stop()
        engine.close()
    }
}
