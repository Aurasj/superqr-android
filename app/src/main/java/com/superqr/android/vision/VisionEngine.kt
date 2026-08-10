package com.superqr.android.vision

import com.superqr.android.ui.phase1.Phase1AcquisitionScheduler
import com.superqr.android.ui.phase1.Phase1AnalysisPath
import com.superqr.android.ui.phase1.Phase1AnalysisPolicy
import com.superqr.android.ui.phase1.Phase1FramingEvaluator
import com.superqr.android.ui.phase1.Phase1FramingGeometry
import com.superqr.android.ui.phase1.Phase1FramingMode
import com.superqr.android.ui.phase1.Phase1Manifest
import com.superqr.android.ui.phase1.Phase1Profile
import com.superqr.android.ui.phase1.Phase1TrackingState
import com.superqr.android.vision.v7_capacity_lab.V7CarrierAcquirer
import com.superqr.android.vision.v7_capacity_lab.V7CarrierAcquisitionResult
import com.superqr.android.vision.v7_capacity_lab.V7LabRunState
import com.superqr.android.vision.v7_capacity_lab.V7Phase1GridProfile
import com.superqr.android.vision.v7_capacity_lab.V7Phase1QrDecoder
import com.superqr.android.vision.v7_capacity_lab.V7Phase1QrResult
import com.superqr.android.vision.v7_capacity_lab.V7Phase1Receiver
import com.superqr.android.vision.v6.classification.ChromaPixelReader

data class VisionResult(
    val path: Phase1AnalysisPath,
    val trackingState: Phase1TrackingState,
    val framing: Phase1FramingGeometry,
    val carrierAcquisition: V7CarrierAcquisitionResult?,
    val qrResult: V7Phase1QrResult?,
    val schedulerState: String,
    val profileName: String,
    val pipelineMs: Double,
    /** Non-null when the GRID path has an active run envelope for a grid profile. */
    val activeGridProfile: V7Phase1GridProfile?,
    /** Non-null when the QR path has a valid decoded control QR. */
    val qrProfile: Phase1Profile.Qr?,
    /** Non-null when a GRID frame observation is available for the session recorder. */
    val gridObservation: GridObservationData?,
    /** Non-null when a QR frame observation is available for the session recorder. */
    val qrObservation: QrObservationData?,
    /** The current run envelope, if any path has locked. */
    val envelope: com.superqr.android.vision.v7_capacity_lab.V7LabRunEnvelope?,
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

class VisionEngine(
    private val manifest: Phase1Manifest,
) {
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
    private var resolutionRejected = false

    val warmupComplete: Boolean
        get() = Phase1AnalysisPolicy.warmupComplete(warmupStartedNs, System.nanoTime(), warmupFrames)

    val resolutionAccepted: Boolean
        get() = !resolutionRejected

    fun analyze(
        luma: ByteArray,
        width: Int,
        height: Int,
        chromaReader: ChromaPixelReader?,
        arrivalNs: Long,
    ): VisionResult {
        val packedNs = System.nanoTime()

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

        if (scheduler.path == Phase1AnalysisPath.GRID) {
            return analyzeGrid(luma, width, height, chromaReader, arrivalNs, packedNs)
        } else {
            return analyzeQr(luma, width, height, arrivalNs, packedNs)
        }
    }

    private fun analyzeGrid(
        luma: ByteArray,
        width: Int,
        height: Int,
        chromaReader: ChromaPixelReader?,
        arrivalNs: Long,
        packedNs: Long,
    ): VisionResult {
        val acquisition = carrierAcquirer.analyze(luma, width, height, diagnostics = true)
        val acquisitionDoneNs = System.nanoTime()
        val h = acquisition.canonicalToImageHomography

        if (h == null) {
            scheduler.missed(Phase1AnalysisPath.GRID, carrierCandidate = acquisition.carrierLike)
            val pipelineMs = (acquisitionDoneNs - arrivalNs) / 1_000_000.0
            val framing = Phase1FramingEvaluator.evaluate(width, height, acquisition, manifest.carrierSpec)
            return VisionResult(
                path = Phase1AnalysisPath.GRID,
                trackingState = Phase1TrackingState.fromSource(acquisition.source),
                framing = framing,
                carrierAcquisition = acquisition,
                qrResult = null,
                schedulerState = scheduler.state,
                profileName = "AUTO",
                pipelineMs = pipelineMs,
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
                    h, luma, width, height, reader,
                    synchronizedFrameIndex = envelope.frameIndex,
                )
                val completedNs = System.nanoTime()
                val failureReason = when {
                    result.postFecValid -> null
                    result.erasedBits > 0 -> "INNER_FEC_FAILED_WITH_ERASURES"
                    else -> "INNER_FEC_FAILED_WITH_ERRORS"
                }
                val framing = Phase1FramingEvaluator.evaluate(width, height, acquisition, manifest.carrierSpec)
                return VisionResult(
                    path = Phase1AnalysisPath.GRID,
                    trackingState = Phase1TrackingState.fromSource(acquisition.source),
                    framing = framing,
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

            // GRID locked but not yet RUNNING (READY or DONE)
            val framing = Phase1FramingEvaluator.evaluate(width, height, acquisition, manifest.carrierSpec)
            return VisionResult(
                path = Phase1AnalysisPath.GRID,
                trackingState = Phase1TrackingState.fromSource(acquisition.source),
                framing = framing,
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

        // Sync decoded but profile mismatch
        scheduler.missed(Phase1AnalysisPath.GRID, carrierCandidate = acquisition.carrierLike)
        val framing = Phase1FramingEvaluator.evaluate(width, height, acquisition, manifest.carrierSpec)
        return VisionResult(
            path = Phase1AnalysisPath.GRID,
            trackingState = Phase1TrackingState.fromSource(acquisition.source),
            framing = framing,
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
        packedNs: Long,
    ): VisionResult {
        val qr = qrDecoder.analyzeAuto(luma, width, height, qrExpectedBytes)
        val completedNs = System.nanoTime()
        val envelope = qr.envelope
        val profile = envelope?.let { manifest.profile(it.profileId) }

        if (envelope != null && profile is Phase1Profile.Qr) {
            scheduler.locked(Phase1AnalysisPath.QR)
            val framing = Phase1FramingEvaluator.evaluateQr(width, height, qr)
            val observation = if (qr.valid) {
                QrObservationData(
                    profile = profile,
                    frameIndex = qr.frameIndex,
                    valid = true,
                    failure = null,
                    geometrySource = "QR_NATIVE_LOCKED",
                )
            } else null
            return VisionResult(
                path = Phase1AnalysisPath.QR,
                trackingState = Phase1TrackingState.TRACKING,
                framing = framing,
                carrierAcquisition = null,
                qrResult = qr,
                schedulerState = scheduler.state,
                profileName = profile.name,
                pipelineMs = (completedNs - arrivalNs) / 1_000_000.0,
                activeGridProfile = null,
                qrProfile = profile,
                gridObservation = null,
                qrObservation = observation,
                envelope = envelope,
            )
        }

        scheduler.missed(Phase1AnalysisPath.QR)
        val framing = Phase1FramingEvaluator.evaluateQr(width, height, qr)
        return VisionResult(
            path = Phase1AnalysisPath.QR,
            trackingState = Phase1TrackingState.SEARCHING,
            framing = framing,
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

    fun acquireParams(): Map<String, Any?> = mapOf(
        "acquisition_state" to scheduler.state,
        "warmup_frames" to warmupFrames,
    )

    fun reset() {
        scheduler.reset()
        gridReceiver = null
        activeGridId = -1
        warmupFrames = 0
        warmupStartedNs = 0L
        resolutionRejected = false
    }

    fun close() {
        carrierAcquirer.close()
        qrDecoder.close()
    }
}
