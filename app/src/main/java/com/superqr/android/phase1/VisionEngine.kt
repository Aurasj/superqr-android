package com.superqr.android.phase1

import com.superqr.android.vision.v6.classification.ChromaPixelReader
import com.superqr.android.vision.v7_capacity_lab.ExternalQrDecodeResult
import com.superqr.android.vision.v7_capacity_lab.ExternalQrFrameDecoder
import com.superqr.android.vision.v7_capacity_lab.V7CarrierAcquirer
import com.superqr.android.vision.v7_capacity_lab.V7CarrierAcquisitionResult
import com.superqr.android.vision.v7_capacity_lab.V7LabRunEnvelope
import com.superqr.android.vision.v7_capacity_lab.V7LabRunState
import com.superqr.android.vision.v7_capacity_lab.V7Phase1GridProfile
import com.superqr.android.vision.v7_capacity_lab.V7Phase1QrDecoder
import com.superqr.android.vision.v7_capacity_lab.V7Phase1QrResult
import com.superqr.android.vision.v7_capacity_lab.V7Phase1Receiver

data class VisionResult(
    val path: Phase1AnalysisPath,
    val trackingState: Phase1TrackingState,
    val framing: Phase1FramingGeometry,
    val carrierAcquisition: V7CarrierAcquisitionResult?,
    val qrResult: V7Phase1QrResult?,
    val schedulerState: String,
    val profileName: String,
    val pipelineMs: Double,
    val activeGridProfile: V7Phase1GridProfile?,
    val qrProfile: Phase1Profile.Qr?,
    val gridObservation: GridObservationData?,
    val qrObservation: QrObservationData?,
    val envelope: V7LabRunEnvelope?,
)

data class GridObservationData(
    val profile: Phase1Profile.Grid,
    val frameIndex: Long?,
    val observedBits: Int,
    val bitErrors: Int,
    val erasedBits: Int,
    val frameValid: Boolean,
    val postFecValid: Boolean,
    val errorCellIndexes: IntArray?,
    val errorCellCount: Int,
    val erasureCellIndexes: IntArray?,
    val erasureCellCount: Int,
    val validSamples: Int,
    val syncStatus: String,
    val geometrySource: String,
    val failureReason: String?,
)

data class QrObservationData(
    val profile: Phase1Profile.Qr,
    val frameIndex: Long?,
    val valid: Boolean,
    val failure: String?,
    val geometrySource: String,
)

/** App-level adapter over the dedicated V7 Phase 1 acquisition/decoder components. */
class VisionEngine(private val manifest: Phase1Manifest) {
    private val carrierAcquirer = V7CarrierAcquirer(manifest.carrierSpec)
    private val qrDecoder = V7Phase1QrDecoder()
    private val scheduler = Phase1AcquisitionScheduler()

    private val qrExpectedBytes: Map<Int, Int> = manifest.profiles
        .filterIsInstance<Phase1Profile.Qr>()
        .associate { it.version to it.frameBytes }

    private var gridReceiver: V7Phase1Receiver? = null
    private var activeGridId = -1
    private var warmupFrames = 0
    private var warmupStartedNs = 0L

    fun analyze(
        luma: ByteArray,
        width: Int,
        height: Int,
        chromaReader: ChromaPixelReader?,
        arrivalNs: Long,
    ): VisionResult {
        if (warmupStartedNs == 0L) warmupStartedNs = arrivalNs
        warmupFrames++

        if (!Phase1AnalysisPolicy.warmupComplete(warmupStartedNs, arrivalNs, warmupFrames)) {
            return VisionResult(
                path = scheduler.path,
                trackingState = Phase1TrackingState.SEARCHING,
                framing = Phase1FramingGeometry.empty(),
                carrierAcquisition = null,
                qrResult = null,
                schedulerState = scheduler.state,
                profileName = "WARMUP",
                pipelineMs = (System.nanoTime() - arrivalNs) / 1_000_000.0,
                activeGridProfile = null,
                qrProfile = null,
                gridObservation = null,
                qrObservation = null,
                envelope = null,
            )
        }

        return if (scheduler.path == Phase1AnalysisPath.GRID) {
            analyzeGrid(luma, width, height, chromaReader, arrivalNs)
        } else {
            analyzeQr(luma, width, height, chromaReader, arrivalNs)
        }
    }

    private fun analyzeGrid(
        luma: ByteArray,
        width: Int,
        height: Int,
        chromaReader: ChromaPixelReader?,
        arrivalNs: Long,
    ): VisionResult {
        val acquisition = carrierAcquirer.analyze(luma, width, height, diagnostics = true)
        val acquisitionDoneNs = System.nanoTime()
        val h = acquisition.canonicalToImageHomography

        if (h == null) {
            scheduler.missed(
                Phase1AnalysisPath.GRID,
                carrierCandidate = acquisition.carrierLike,
                // Cold carrier-like hypotheses are useful while searching but
                // must not keep a completed/old GRID lock alive indefinitely.
                // Only the acquirer's bounded TRACK_HOLD may retain that lock.
                retainLockedPath = acquisition.source == "V7_TRACK_HOLD",
            )
            return VisionResult(
                path = Phase1AnalysisPath.GRID,
                trackingState = Phase1TrackingState.fromSource(acquisition.source),
                framing = Phase1FramingEvaluator.evaluate(width, height, acquisition, manifest.carrierSpec),
                carrierAcquisition = acquisition,
                qrResult = null,
                schedulerState = scheduler.state,
                profileName = "AUTO",
                pipelineMs = (acquisitionDoneNs - arrivalNs) / 1_000_000.0,
                activeGridProfile = null,
                qrProfile = null,
                gridObservation = null,
                qrObservation = null,
                envelope = null,
            )
        }

        val sync = acquisition.sync
        val envelope = sync.envelope
        val profile = envelope?.let { manifest.profile(it.profileId) }

        if (envelope != null && profile is Phase1Profile.Grid) {
            if (envelope.state == V7LabRunState.DONE) {
                scheduler.completed(Phase1AnalysisPath.GRID)
            } else {
                scheduler.locked(Phase1AnalysisPath.GRID)
            }

            if (envelope.state == V7LabRunState.RUNNING) {
                if (activeGridId != profile.id) {
                    gridReceiver = V7Phase1Receiver(profile.receiverProfile, manifest.seed)
                    activeGridId = profile.id
                }
                val reader = if (profile.receiverProfile.bitsPerCell == 2) chromaReader else null
                val result = gridReceiver!!.analyze(
                    h,
                    luma,
                    width,
                    height,
                    reader,
                    synchronizedFrameIndex = envelope.frameIndex,
                )
                val completedNs = System.nanoTime()
                val failureReason = when {
                    result.postFecValid -> null
                    result.erasedBits > 0 -> "INNER_FEC_FAILED_WITH_ERASURES"
                    else -> "INNER_FEC_FAILED_WITH_ERRORS"
                }
                return VisionResult(
                    path = Phase1AnalysisPath.GRID,
                    trackingState = Phase1TrackingState.fromSource(acquisition.source),
                    framing = Phase1FramingEvaluator.evaluate(width, height, acquisition, manifest.carrierSpec),
                    carrierAcquisition = acquisition,
                    qrResult = null,
                    schedulerState = scheduler.state,
                    profileName = profile.name,
                    pipelineMs = (completedNs - arrivalNs) / 1_000_000.0,
                    activeGridProfile = profile.receiverProfile,
                    qrProfile = null,
                    gridObservation = GridObservationData(
                        profile = profile,
                        frameIndex = result.frameIndex?.toLong(),
                        observedBits = result.observedBits,
                        bitErrors = result.bitErrors,
                        erasedBits = result.erasedBits,
                        frameValid = result.frameValid,
                        postFecValid = result.postFecValid,
                        errorCellIndexes = result.errorCellIndexes,
                        errorCellCount = result.errorCellCount,
                        erasureCellIndexes = result.erasureCellIndexes,
                        erasureCellCount = result.erasureCellCount,
                        validSamples = result.validSamples,
                        syncStatus = sync.status,
                        geometrySource = acquisition.source,
                        failureReason = failureReason,
                    ),
                    qrObservation = null,
                    envelope = envelope,
                )
            }

            return VisionResult(
                path = Phase1AnalysisPath.GRID,
                trackingState = Phase1TrackingState.fromSource(acquisition.source),
                framing = Phase1FramingEvaluator.evaluate(width, height, acquisition, manifest.carrierSpec),
                carrierAcquisition = acquisition,
                qrResult = null,
                schedulerState = scheduler.state,
                profileName = profile.name,
                pipelineMs = (acquisitionDoneNs - arrivalNs) / 1_000_000.0,
                activeGridProfile = null,
                qrProfile = null,
                gridObservation = null,
                qrObservation = null,
                envelope = envelope,
            )
        }

        scheduler.missed(Phase1AnalysisPath.GRID, carrierCandidate = acquisition.carrierLike)
        return VisionResult(
            path = Phase1AnalysisPath.GRID,
            trackingState = Phase1TrackingState.fromSource(acquisition.source),
            framing = Phase1FramingEvaluator.evaluate(width, height, acquisition, manifest.carrierSpec),
            carrierAcquisition = acquisition,
            qrResult = null,
            schedulerState = scheduler.state,
            profileName = "AUTO",
            pipelineMs = (acquisitionDoneNs - arrivalNs) / 1_000_000.0,
            activeGridProfile = null,
            qrProfile = null,
            gridObservation = null,
            qrObservation = null,
            envelope = envelope,
        )
    }

    private fun analyzeQr(
        luma: ByteArray,
        width: Int,
        height: Int,
        chromaReader: ChromaPixelReader?,
        arrivalNs: Long,
    ): VisionResult {
        val external = (chromaReader as? ExternalQrFrameDecoder)?.decodeQr()
        val qr = if (external != null && (external.payload.isNotEmpty() || external.quad != null)) {
            analyzeExternalQr(external)
        } else {
            qrDecoder.analyzeAuto(luma, width, height, qrExpectedBytes, external)
        }
        val completedNs = System.nanoTime()
        val envelope = qr.envelope
        val profile = envelope?.let { manifest.profile(it.profileId) }

        if (envelope != null && profile is Phase1Profile.Qr) {
            if (envelope.state == V7LabRunState.DONE) {
                scheduler.completed(Phase1AnalysisPath.QR)
            } else {
                scheduler.locked(Phase1AnalysisPath.QR)
            }
            return VisionResult(
                path = Phase1AnalysisPath.QR,
                trackingState = Phase1TrackingState.TRACKING,
                framing = Phase1FramingEvaluator.evaluateQr(width, height, qr),
                carrierAcquisition = null,
                qrResult = qr,
                schedulerState = scheduler.state,
                profileName = profile.name,
                pipelineMs = (completedNs - arrivalNs) / 1_000_000.0,
                activeGridProfile = null,
                qrProfile = profile,
                gridObservation = null,
                qrObservation = if (qr.valid && envelope.state == V7LabRunState.RUNNING) {
                    QrObservationData(
                        profile = profile,
                        frameIndex = qr.frameIndex,
                        valid = true,
                        failure = null,
                        geometrySource = "QR_NATIVE_LOCKED",
                    )
                } else null,
                envelope = envelope,
            )
        }

        val qrCandidate = qr.quad != null
        scheduler.missed(Phase1AnalysisPath.QR, qrCandidate = qrCandidate)
        return VisionResult(
            path = Phase1AnalysisPath.QR,
            trackingState = if (qrCandidate) Phase1TrackingState.TRACKING else Phase1TrackingState.SEARCHING,
            framing = Phase1FramingEvaluator.evaluateQr(width, height, qr),
            carrierAcquisition = null,
            qrResult = qr,
            schedulerState = scheduler.state,
            profileName = "AUTO",
            pipelineMs = (completedNs - arrivalNs) / 1_000_000.0,
            activeGridProfile = null,
            qrProfile = null,
            gridObservation = null,
            qrObservation = null,
            envelope = null,
        )
    }

    private fun analyzeExternalQr(external: ExternalQrDecodeResult): V7Phase1QrResult {
        val quad = external.quad
            ?.takeIf { points ->
                points.size == 4 && points.all { point ->
                    point.size >= 2 && point[0].isFinite() && point[1].isFinite()
                }
            }
            ?.map { point -> doubleArrayOf(point[0], point[1]) }

        val diagnostics = linkedMapOf<String, Any?>(
            "decode_source" to if (external.payload.isNotEmpty()) external.source else "NONE",
            "external_qr_attempted" to true,
            "external_qr_source" to external.source,
            "external_qr_ms" to external.elapsedMs,
            "external_qr_result_count" to external.resultCount,
            "external_qr_payload_bytes" to external.payload.size,
            "external_qr_geometry" to (quad != null),
            "legacy_qr_fallback_attempted" to false,
            "opencv_geometry_only_attempted" to false,
        )
        external.errorType?.let { diagnostics["external_qr_error"] = it }
        external.errorMessage?.takeIf { it.isNotBlank() }?.let {
            diagnostics["external_qr_error_message"] = it
        }

        val payload = external.payload
        val result = when {
            payload.isEmpty() -> V7Phase1QrResult(
                decoded = false,
                valid = false,
                frameIndex = null,
                bytes = 0,
                failure = "QR_NOT_DECODED",
                quad = quad,
            )
            payload.size < 5 -> V7Phase1QrResult(
                decoded = true,
                valid = false,
                frameIndex = null,
                bytes = payload.size,
                failure = "QR_HEADER",
                quad = quad,
            )
            else -> {
                val version = payload[4].toInt() and 0xFF
                val expectedBytes = qrExpectedBytes[version]
                if (expectedBytes == null) {
                    V7Phase1QrResult(
                        decoded = true,
                        valid = false,
                        frameIndex = null,
                        bytes = payload.size,
                        failure = "QR_UNSUPPORTED_VERSION",
                        quad = quad,
                    )
                } else {
                    V7Phase1QrDecoder.validatePayload(payload, version, expectedBytes, quad)
                }
            }
        }
        return result.copy(diagnostics = diagnostics)
    }

    fun reset() {
        scheduler.reset()
        gridReceiver = null
        activeGridId = -1
        warmupFrames = 0
        warmupStartedNs = 0L
    }

    fun close() {
        carrierAcquirer.close()
        qrDecoder.close()
    }
}
