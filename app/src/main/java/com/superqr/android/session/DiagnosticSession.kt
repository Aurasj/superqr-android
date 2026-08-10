package com.superqr.android.session

import com.superqr.android.camera.CameraFrame
import com.superqr.android.camera.V7AnalysisRateAccumulator
import com.superqr.android.camera.V7MeasurementTracker
import com.superqr.android.vision.v6.contract.V6Contract
import com.superqr.android.vision.v6.detection.V6StaticDetector
import com.superqr.android.vision.v6.model.V6StaticResult
import com.superqr.android.vision.v6.transport.V6ReceiveUtils
import com.superqr.android.vision.v7.transport.V7OpticalProfiles
import com.superqr.android.vision.v7.transport.V7SessionAccumulator
import com.superqr.android.vision.v7.transport.V7TransferPackage
import com.superqr.android.vision.v7.transport.V7TransferReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class DiagnosticSession(private val cacheDir: File) {

    private val _sessionState = MutableStateFlow(SessionState())
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val detector = V6StaticDetector()
    private val receiver = V7TransferReceiver()
    private val accumulator = V7SessionAccumulator()
    private val measurement = V7MeasurementTracker()
    private val analysisRate = V7AnalysisRateAccumulator(64)

    private var wasTracking = false
    private var wasLost = false
    private var generation = 0

    fun start() {
        reset()
        generation++
        _sessionState.value = SessionState(phase = SessionPhase.SEARCHING)
    }

    fun stop() {
        val current = _sessionState.value
        _sessionState.value = current.copy(
            phase = if (current.completedTransfer != null) SessionPhase.COMPLETE
                    else SessionPhase.IDLE,
        )
    }

    fun reset() {
        detector.close()
        receiver.reset()
        accumulator.reset()
        measurement.reset()
        analysisRate.reset()
        wasTracking = false
        wasLost = false
        _sessionState.value = SessionState()
    }

    fun processFrame(frame: CameraFrame) {
        if (frame.generation != generation) return

        val detected = try {
            detector.detectGeometry(frame.lumaBytes, frame.width, frame.height)
                .copy(analyzerArrivalNs = frame.arrivalNs)
        } catch (t: Throwable) {
            measurement.recordException(t)
            updateState { it.copy(error = "${t::class.java.simpleName}: ${t.message}") }
            return
        }

        val decoded = receiver.analyze(
            detected, frame.lumaBytes, frame.width, frame.height,
            frame.chromaReader,
        )

        val accepted = decoded.acceptedFrame
        if (accepted != null) {
            measurement.recordAccepted(
                accepted.sessionId, accepted.frameId, accepted.payload.size,
            )
        }

        val completedNs = System.nanoTime()
        analysisRate.recordCompletion(completedNs)
        measurement.recordAnalysis(
            completedNs = completedNs,
            pipelineMs = (completedNs - frame.arrivalNs) / 1_000_000.0,
            detectorMs = detected.processingTimeMs.toDouble(),
            v7TotalMs = decoded.timing.totalUs / 1000.0,
            v7ProfileMs = decoded.timing.profileUs / 1000.0,
            v7SamplingMs = decoded.timing.samplingUs / 1000.0,
            v7ClassificationMs = decoded.timing.classificationUs / 1000.0,
            v7TransportMs = decoded.timing.transportUs / 1000.0,
        )
        val ms = measurement.snapshot(completedNs)

        var completedTransfer: CompletedTransfer? = null
        if (accepted != null) {
            val pkg = try {
                accumulator.addFrame(accepted)
            } catch (t: Throwable) {
                measurement.recordException(t)
                null
            }
            if (pkg != null) {
                completedTransfer = savePackage(pkg, accepted.sessionId, accepted.totalFrames)
            }
        }

        val phase = derivePhase(detected, decoded)
        val guidance = deriveGuidance(detected, frame.width, frame.height)

        updateState {
            it.copy(
                phase = phase,
                guidance = guidance,
                profile = decoded.profile,
                cameraFps = frame.cameraFps,
                analysisFps = ms.analysisFps,
                pipelineMs = ms.pipelineMs,
                calibratedCount = decoded.calibratedCount,
                colorCount = decoded.profile.colorCount,
                acceptedFrames = accumulator.getUniqueFrames(),
                totalFrames = accumulator.getTotalFrames(),
                codedErasures = decoded.erasureCount,
                error = decoded.transportError,
                completedTransfer = completedTransfer ?: it.completedTransfer,
            )
        }

        if (completedTransfer != null) {
            updateState { it.copy(phase = SessionPhase.COMPLETE) }
        }
    }

    private fun derivePhase(
        detected: V6StaticResult,
        decoded: V7TransferReceiver.DecodeResult,
    ): SessionPhase {
        val phase = computePhase(
            borderFound = detected.borderFound,
            orientationResolved = detected.orientationResolved,
            calibratedCount = decoded.calibratedCount,
            colorCount = decoded.profile.colorCount,
            hasSession = accumulator.getCurrentSessionId() != -1,
            wasTracking = wasTracking,
            wasLost = wasLost,
        )

        // Update tracking state based on current detection
        wasTracking = detected.borderFound
        wasLost = !detected.borderFound

        return phase
    }

    companion object {
        fun computePhase(
            borderFound: Boolean,
            orientationResolved: Boolean,
            calibratedCount: Int,
            colorCount: Int,
            hasSession: Boolean,
            wasTracking: Boolean,
            wasLost: Boolean,
        ): SessionPhase {
            if (!borderFound) {
                return if (wasTracking || wasLost) SessionPhase.LOST
                else SessionPhase.SEARCHING
            }

            if (wasLost && borderFound && !orientationResolved) {
                return SessionPhase.REACQUIRING
            }

            if (!orientationResolved) return SessionPhase.QR_DETECTED

            if (calibratedCount <= 0) return SessionPhase.QR_LOCKED

            if (calibratedCount < colorCount) return SessionPhase.GRID_DETECTED

            if (hasSession) return SessionPhase.RECEIVING

            return SessionPhase.GRID_LOCKED
        }
    }

    private fun deriveGuidance(
        detected: V6StaticResult,
        frameWidth: Int,
        frameHeight: Int,
    ): Set<SessionGuidance> {
        val guidance = mutableSetOf<SessionGuidance>()
        if (!detected.borderFound) return guidance

        val frameArea = (frameWidth * frameHeight).toDouble()
        val contourArea = detected.contourArea
        if (frameArea > 0) {
            val ratio = contourArea / frameArea
            if (ratio < 0.05) guidance.add(SessionGuidance.MOVE_CLOSER)
            if (ratio > 0.80) guidance.add(SessionGuidance.MOVE_BACK)
        }

        val quad = detected.detectedQuad
        if (quad != null && quad.size == 4) {
            val edges = listOf(
                edgeLength(quad[0], quad[1]),
                edgeLength(quad[1], quad[2]),
                edgeLength(quad[2], quad[3]),
                edgeLength(quad[3], quad[0]),
            )
            val minEdge = edges.min()
            val maxEdge = edges.max()
            if (minEdge > 0 && maxEdge / minEdge > 1.6) {
                guidance.add(SessionGuidance.HOLD_PHONE_PARALLEL)
            }
        }

        return guidance
    }

    private fun edgeLength(a: DoubleArray, b: DoubleArray): Double {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun savePackage(
        pkg: V7TransferPackage,
        sessionId: Int,
        totalFrames: Int,
    ): CompletedTransfer? {
        return try {
            val safe = V6ReceiveUtils.sanitizeFilename(pkg.filename)
            val dir = File(cacheDir, "superqr_received").apply { mkdirs() }
            val out = File(dir, safe)
            out.writeBytes(pkg.fileData)
            CompletedTransfer(
                filename = pkg.filename,
                file = out,
                sessionId = sessionId,
                frameCount = totalFrames,
                fileSize = pkg.fileSize,
            )
        } catch (_: Throwable) { null }
    }

    private inline fun updateState(transform: (SessionState) -> SessionState) {
        _sessionState.value = transform(_sessionState.value)
    }

    fun close() {
        try { detector.close() } catch (_: Throwable) {}
    }
}
