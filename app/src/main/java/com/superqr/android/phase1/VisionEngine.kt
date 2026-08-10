package com.superqr.android.phase1

import com.superqr.android.vision.v6.classification.ChromaPixelReader
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
            analyzeQr(luma, width, height, arrivalNs)
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
                envelope = null,
            )
        }

        val sync = acquisition.sync
        val envelope = sync.envelope
        val profile = envelope?.let { manifest.profile(it.profileId) }

        if (envelope != null && profile is Phase1Profile.Grid) {
            scheduler.locked(Phase1AnalysisPath.GRID)

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
                activeGridProfile = profile.receiverProfile,
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
        arrivalNs: Long,
    ): VisionResult {
        val qr = qrDecoder.analyzeAuto(luma, width, height, qrExpectedBytes)
        val completedNs = System.nanoTime()
        val envelope = qr.envelope
        val profile = envelope?.let { manifest.profile(it.profileId) }

        if (envelope != null && profile is Phase1Profile.Qr) {
            scheduler.locked(Phase1AnalysisPath.QR)
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
                qrObservation = if (qr.valid) {
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

        scheduler.missed(Phase1AnalysisPath.QR)
        return VisionResult(
            path = Phase1AnalysisPath.QR,
            trackingState = Phase1TrackingState.SEARCHING,
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
