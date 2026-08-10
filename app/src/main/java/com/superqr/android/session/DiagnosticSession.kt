package com.superqr.android.session

import android.content.Context
import com.superqr.android.camera.CameraFrame
import com.superqr.android.camera.V7AnalysisRateAccumulator
import com.superqr.android.ui.phase1.Phase1AnalysisPath
import com.superqr.android.ui.phase1.Phase1AnalysisPolicy
import com.superqr.android.ui.phase1.Phase1Manifest
import com.superqr.android.ui.phase1.Phase1ObservationRecorder
import com.superqr.android.ui.phase1.Phase1RunSnapshot
import com.superqr.android.ui.phase1.Phase1TrackingState
import com.superqr.android.vision.VisionEngine
import com.superqr.android.vision.VisionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Session-owned frame gate. CameraX callback generations deliberately do not
 * cross this boundary: camera restart bookkeeping and campaign lifecycle are
 * independent concerns.
 */
internal class SessionFrameGate {
    private var accepting = false

    @Synchronized
    fun start() {
        accepting = true
    }

    @Synchronized
    fun stop() {
        accepting = false
    }

    @Synchronized
    fun acceptsFrames(): Boolean = accepting
}

class DiagnosticSession(
    private val context: Context,
    private val cacheDir: File,
) {
    private val _sessionState = MutableStateFlow(SessionState())
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val manifest by lazy { Phase1Manifest.load(context) }
    private val engine by lazy { VisionEngine(manifest) }
    private val recorder = Phase1ObservationRecorder()
    private val analysisRate = V7AnalysisRateAccumulator(64)
    private val frameGate = SessionFrameGate()

    private var wasTracking = false
    private var wasLost = false
    private var observedFrames = 0
    private var hasRecordedObservations = false

    fun startSession() {
        resetForNewSession()
        frameGate.start()
        _sessionState.value = SessionState(
            phase = SessionPhase.SEARCHING,
            campaignId = recorder.campaignId,
        )
    }

    fun stopSession() {
        frameGate.stop()
        val current = _sessionState.value
        _sessionState.value = current.copy(
            phase = if (current.hasObservations) SessionPhase.COMPLETE else SessionPhase.IDLE,
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
        hasRecordedObservations = false
        _sessionState.value = SessionState(campaignId = recorder.campaignId)
    }

    fun processFrame(frame: CameraFrame) {
        if (!frameGate.acceptsFrames()) return

        if (!Phase1AnalysisPolicy.accepts(frame.width, frame.height)) {
            updateState {
                it.copy(
                    error = "Resolution rejected: ${frame.width}x${frame.height}",
                    sourceTransform = frame.sourceTransform,
                )
            }
            return
        }

        val arrivalNs = frame.arrivalNs
        val result = try {
            engine.analyze(
                luma = frame.lumaBytes,
                width = frame.width,
                height = frame.height,
                chromaReader = frame.chromaReader,
                arrivalNs = arrivalNs,
            )
        } catch (t: Throwable) {
            updateState {
                it.copy(
                    error = "${t::class.java.simpleName}: ${t.message}",
                    sourceTransform = frame.sourceTransform,
                )
            }
            return
        }

        val completedNs = System.nanoTime()
        analysisRate.recordCompletion(completedNs)
        val analysisFps = analysisRate.computeFps(completedNs)

        observedFrames++

        val gridObs = result.gridObservation
        if (gridObs != null) {
            recorder.record(
                profile = gridObs.profile,
                dwellEpochs = result.envelope?.dwellEpochs ?: 3,
                completedNs = completedNs,
                frameIndex = gridObs.frameIndex,
                observedBits = gridObs.observedBits,
                bitErrors = gridObs.bitErrors,
                erasedBits = gridObs.erasedBits,
                frameValid = gridObs.frameValid,
                postFecValid = gridObs.postFecValid,
                pipelineMs = result.pipelineMs,
                allocationBytes = 0,
                gcEvents = 0,
                envelope = result.envelope,
                sync = gridObs.syncStatus,
                geometry = gridObs.geometrySource,
                failureReason = gridObs.failureReason,
                errorCellIndexes = gridObs.errorCellIndexes,
                errorCellCount = gridObs.errorCellCount,
                erasureCellIndexes = gridObs.erasureCellIndexes,
                erasureCellCount = gridObs.erasureCellCount,
                extra = mapOf(
                    "capture_width" to frame.width,
                    "capture_height" to frame.height,
                    "sensor_timestamp_ns" to frame.sensorTimestamp,
                    "analysis_path" to "GRID",
                    "acquisition_state" to result.schedulerState,
                    "pipeline_ms" to result.pipelineMs,
                ),
            )
            hasRecordedObservations = true
        }

        val qrObs = result.qrObservation
        if (qrObs != null) {
            recorder.observeSender(
                profile = qrObs.profile,
                envelope = result.envelope!!,
                sync = "QR_LOCKED",
                geometry = qrObs.geometrySource,
            )
            recorder.record(
                profile = qrObs.profile,
                dwellEpochs = result.envelope.dwellEpochs,
                completedNs = completedNs,
                frameIndex = qrObs.frameIndex,
                observedBits = qrObs.profile.frameBytes * 8,
                bitErrors = 0,
                erasedBits = 0,
                frameValid = true,
                postFecValid = true,
                pipelineMs = result.pipelineMs,
                allocationBytes = 0,
                gcEvents = 0,
                envelope = result.envelope,
                sync = "QR_LOCKED",
                geometry = qrObs.geometrySource,
                extra = mapOf(
                    "capture_width" to frame.width,
                    "capture_height" to frame.height,
                    "sensor_timestamp_ns" to frame.sensorTimestamp,
                    "analysis_path" to "QR",
                ),
            )
            hasRecordedObservations = true
        }

        // Keep search diagnostics bounded until the forensic recorder lands.
        if (gridObs == null && qrObs == null && observedFrames % 15 == 0) {
            val failureReason = when (result.path) {
                Phase1AnalysisPath.GRID -> result.carrierAcquisition?.bestSyncStatus ?: "GRID_NO_CANDIDATE"
                Phase1AnalysisPath.QR -> result.qrResult?.failure ?: "QR_NOT_DECODED"
            }
            recorder.recordFailure(
                reason = failureReason,
                completedNs = completedNs,
                pipelineMs = result.pipelineMs,
                geometry = result.trackingState.name,
                sync = result.schedulerState,
                extra = mapOf(
                    "capture_width" to frame.width,
                    "capture_height" to frame.height,
                    "analysis_path" to result.path.name,
                    "acquisition_state" to result.schedulerState,
                ),
            )
            hasRecordedObservations = true
        }

        val phase = derivePhase(result)

        updateState {
            it.copy(
                phase = phase,
                trackingState = result.trackingState,
                framing = result.framing,
                sourceTransform = frame.sourceTransform,
                profileName = result.profileName,
                cameraFps = frame.cameraFps,
                analysisFps = analysisFps,
                pipelineMs = result.pipelineMs,
                analyzedFrames = observedFrames,
                hasObservations = hasRecordedObservations,
                campaignId = recorder.campaignId,
                error = result.qrResult?.failure,
            )
        }
    }

    private fun derivePhase(result: VisionResult): SessionPhase {
        when (result.path) {
            Phase1AnalysisPath.GRID -> {
                if (result.carrierAcquisition == null) {
                    val next = if (wasTracking || wasLost) SessionPhase.LOST else SessionPhase.SEARCHING
                    wasTracking = false
                    wasLost = true
                    return next
                }

                if (wasLost && !result.carrierAcquisition.acquired) {
                    wasTracking = false
                    return SessionPhase.REACQUIRING
                }

                wasTracking = true
                wasLost = false

                if (!result.carrierAcquisition.acquired) return SessionPhase.SEARCHING
                if (result.envelope == null) return SessionPhase.GRID_DETECTED

                if (result.envelope.state == com.superqr.android.vision.v7_capacity_lab.V7LabRunState.RUNNING) {
                    return SessionPhase.RECEIVING
                }

                return SessionPhase.GRID_LOCKED
            }

            Phase1AnalysisPath.QR -> {
                val qr = result.qrResult
                if (qr == null) {
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

                val qrQuad = qr.quad
                return if (qrQuad != null && qrQuad.size == 4) {
                    SessionPhase.QR_DETECTED
                } else if (wasTracking || wasLost) {
                    SessionPhase.LOST
                } else {
                    SessionPhase.SEARCHING
                }
            }
        }
    }

    fun shareSession(context: Context): File = recorder.exportAndShare(context)

    fun snapshot(): Phase1RunSnapshot = recorder.snapshot()

    fun exportDebugBundle(context: Context): File? {
        if (!hasRecordedObservations) return null
        return recorder.exportAndShare(context)
    }

    private inline fun updateState(transform: (SessionState) -> SessionState) {
        _sessionState.value = transform(_sessionState.value)
    }

    fun close() {
        frameGate.stop()
        engine.close()
    }
}
