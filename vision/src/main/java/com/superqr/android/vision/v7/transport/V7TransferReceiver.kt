package com.superqr.android.vision.v7.transport

import com.superqr.android.vision.v6.classification.ChromaPixelReader
import com.superqr.android.vision.v6.model.V6StaticResult
import com.superqr.android.vision.v7_capacity_lab.V7Calibrator
import com.superqr.android.vision.v7_capacity_lab.V7HighDensitySampler
import com.superqr.android.vision.v7_capacity_lab.V7SoftClassifier
import kotlin.math.abs
import kotlin.math.roundToInt

/** Per-analysis transport instrumentation exposed to the production UI/debugger. */
data class V7TransportDiagnostics(
    val rawErasures: Int = 0,
    val headerAttempted: Boolean = false,
    val headerValid: Boolean = false,
    val header: V7FrameHeader? = null,
    val headerError: String? = null,
    val temporalObservations: Int = 0,
    val temporalFilledCells: Int = 0,
    val temporalOverriddenCells: Int = 0,
    val temporalConsensusCells: Int = 0,
    val remainingErasures: Int = 0,
    val packAttempted: Boolean = false,
    val crcAttempted: Boolean = false,
    val crcPassed: Boolean = false,
    val rejectionReason: String? = null,
)

/** Throttled deep snapshot used only while Live Debug is open. */
data class V7DebugSnapshot(
    val profileId: Int,
    val grid: Int,
    val probeMode: V7HighDensitySampler.ProbeMode,
    val symbols: ByteArray,
    val stabilizedSymbols: ByteArray?,
    val secondBestSymbols: ByteArray,
    val bestDistances: IntArray,
    val secondBestDistances: IntArray,
    val validMask: ByteArray,
    val probeValidityMask: ByteArray,
    val sampleY: IntArray,
    val sampleU: IntArray,
    val sampleV: IntArray,
    val transport: V7TransportDiagnostics,
)

/**
 * Production adaptive V7 payload decoder.
 *
 * Carrier acquisition/tracking remains the proven V6 detector. After geometry
 * lock, four monochrome header cells announce the active V7 optical profile.
 * The payload decoder then performs rectangular-grid sampling, soft decisions,
 * explicit erasures, header-first inspection and CRC-gated temporal recovery.
 */
class V7TransferReceiver {
    val sampler = V7HighDensitySampler()
    var calibrator = V7Calibrator(4)
        private set
    var classifier = V7SoftClassifier()
        private set

    var probeMode: V7HighDensitySampler.ProbeMode = V7HighDensitySampler.ProbeMode.CROSS_5
    var forcedProfileId: Int? = null
    var debugSnapshotEnabled: Boolean = false
    var debugSnapshotIntervalNs: Long = 250_000_000L

    var activeProfile: V7OpticalProfile = V7OpticalProfiles.all.first()
        private set

    private val temporal = V7TemporalFrameStabilizer()
    private var lastDebugSnapshotNs = 0L

    data class Timing(
        val profileUs: Long = 0,
        val samplingUs: Long = 0,
        val classificationUs: Long = 0,
        val transportUs: Long = 0,
        val totalUs: Long = 0,
    )

    data class DecodeResult(
        val trackingState: String,
        val profile: V7OpticalProfile,
        val profileAuto: Boolean,
        val calibratedCount: Int,
        val validSamples: Int,
        val confidentCells: Int,
        val erasureCount: Int,
        val acceptedFrame: V7TransportFrame?,
        val transportError: String?,
        val transport: V7TransportDiagnostics,
        val debugSnapshot: V7DebugSnapshot?,
        val timing: Timing,
    )

    init { applyProfile(activeProfile) }

    fun reset() {
        temporal.reset()
        calibrator.reset()
        activeProfile = V7OpticalProfiles.all.first()
        applyProfile(activeProfile)
        lastDebugSnapshotNs = 0L
    }

    private fun applyProfile(profile: V7OpticalProfile) {
        activeProfile = profile
        sampler.setGridSize(profile.grid, V7OpticalProfiles.payloadBbox)
        calibrator = V7Calibrator(profile.colorCount)
        classifier = V7SoftClassifier().also { it.setCellCount(profile.cellCount) }
        temporal.reset()
        lastDebugSnapshotNs = 0L
    }

    fun analyze(
        geometry: V6StaticResult,
        lumaBytes: ByteArray,
        width: Int,
        height: Int,
        chromaReader: ChromaPixelReader?,
    ): DecodeResult {
        val t0 = System.nanoTime()
        val hInv = geometry.finalInvHomography
        if (!geometry.borderFound || !geometry.orientationResolved || hInv == null) {
            val transport = V7TransportDiagnostics(remainingErasures = activeProfile.cellCount)
            return DecodeResult(
                geometry.trackingState, activeProfile, forcedProfileId == null,
                calibrator.calibratedCount(), 0, 0, activeProfile.cellCount,
                null, null, transport, null,
                Timing(totalUs = (System.nanoTime() - t0) / 1000)
            )
        }

        val tp0 = System.nanoTime()
        val selected = forcedProfileId?.let { V7OpticalProfiles.byId(it) }
            ?: decodeProfile(hInv, lumaBytes, width, height, geometry)
        if (selected != null && selected.id != activeProfile.id) applyProfile(selected)
        val profileUs = (System.nanoTime() - tp0) / 1000

        updatePilotCalibration(geometry, hInv, lumaBytes, width, height, chromaReader)

        val ts0 = System.nanoTime()
        val validSamples = when (probeMode) {
            V7HighDensitySampler.ProbeMode.CENTER_1 -> sampler.sampleCenter1(hInv, lumaBytes, width, height, chromaReader)
            V7HighDensitySampler.ProbeMode.CROSS_5 -> sampler.sampleCross5(hInv, lumaBytes, width, height, chromaReader)
        }
        val samplingUs = (System.nanoTime() - ts0) / 1000

        if (!calibrator.isFullyCalibrated()) {
            val transport = V7TransportDiagnostics(remainingErasures = activeProfile.cellCount)
            return DecodeResult(
                geometry.trackingState, activeProfile, forcedProfileId == null,
                calibrator.calibratedCount(), validSamples, 0, activeProfile.cellCount,
                null, null, transport, null,
                Timing(profileUs, samplingUs, totalUs = (System.nanoTime() - t0) / 1000)
            )
        }

        val tc0 = System.nanoTime()
        classifier.setCenters(calibrator.getCentersSnapshot())
        val y = if (probeMode == V7HighDensitySampler.ProbeMode.CENTER_1) sampler.getYCenters() else sampler.getYCross5()
        val u = if (probeMode == V7HighDensitySampler.ProbeMode.CENTER_1) sampler.getUCenters() else sampler.getUCross5()
        val v = if (probeMode == V7HighDensitySampler.ProbeMode.CENTER_1) sampler.getVCenters() else sampler.getVCross5()
        classifier.classify(y, u, v, activeProfile.cellCount)

        // A projected cell with insufficient real YUV probes is an explicit
        // erasure even if neutral fallback values happen to be near a palette.
        val validMask = sampler.getValidMask()
        for (i in 0 until activeProfile.cellCount) {
            if (validMask[i].toInt() == 0) classifier.bestSymbols[i] = V7SoftClassifier.ERASURE_MARKER
        }
        val classificationUs = (System.nanoTime() - tc0) / 1000

        var rawErasures = 0
        for (symbol in classifier.bestSymbols) if (symbol == V7SoftClassifier.ERASURE_MARKER) rawErasures++
        val confident = activeProfile.cellCount - rawErasures

        val tt0 = System.nanoTime()
        var headerAttempted = false
        var headerValid = false
        var header: V7FrameHeader? = null
        var headerError: String? = null
        var merged: V7TemporalFrameStabilizer.MergeResult? = null
        var packAttempted = false
        var crcAttempted = false
        var crcPassed = false
        var accepted: V7TransportFrame? = null
        var rejection: String? = null

        val headerBytes = V7Transport.symbolsToPrefixBytes(classifier.bestSymbols, activeProfile, V7Transport.HEADER_SIZE)
        headerAttempted = true
        if (headerBytes == null) {
            headerError = "header contains erasures"
            rejection = headerError
        } else {
            try {
                header = V7Transport.inspectHeader(headerBytes, activeProfile)
                headerValid = true
            } catch (e: V7TransportError) {
                headerError = e.message
                rejection = e.message
            }
        }

        var candidateSymbols: ByteArray = classifier.bestSymbols
        if (header != null) {
            merged = temporal.merge(header, classifier.bestSymbols)
            candidateSymbols = merged.symbols
        }
        val remainingErasures = merged?.remainingErasures ?: rawErasures

        if (headerValid && remainingErasures == 0) {
            packAttempted = true
            val bytes = V7Transport.symbolsToBytes(candidateSymbols, activeProfile)
            if (bytes == null) {
                rejection = "symbol packing failed"
            } else {
                crcAttempted = true
                try {
                    accepted = V7Transport.parseFrame(bytes, activeProfile)
                    crcPassed = true
                    rejection = null
                } catch (e: V7TransportError) {
                    rejection = e.message
                }
            }
        } else if (headerValid && remainingErasures > 0) {
            rejection = "$remainingErasures erasures remain after temporal recovery"
        }

        val transport = V7TransportDiagnostics(
            rawErasures = rawErasures,
            headerAttempted = headerAttempted,
            headerValid = headerValid,
            header = header,
            headerError = headerError,
            temporalObservations = merged?.observations ?: 0,
            temporalFilledCells = merged?.filledErasures ?: 0,
            temporalOverriddenCells = merged?.overriddenConflicts ?: 0,
            temporalConsensusCells = merged?.consensusCells ?: 0,
            remainingErasures = remainingErasures,
            packAttempted = packAttempted,
            crcAttempted = crcAttempted,
            crcPassed = crcPassed,
            rejectionReason = rejection,
        )

        val nowNs = System.nanoTime()
        val debugSnapshot = if (
            debugSnapshotEnabled &&
            (lastDebugSnapshotNs == 0L || nowNs - lastDebugSnapshotNs >= debugSnapshotIntervalNs)
        ) {
            lastDebugSnapshotNs = nowNs
            V7DebugSnapshot(
                profileId = activeProfile.id,
                grid = activeProfile.grid,
                probeMode = probeMode,
                symbols = classifier.bestSymbols.copyOf(),
                stabilizedSymbols = merged?.symbols?.copyOf(),
                secondBestSymbols = classifier.secondBestSymbols.copyOf(),
                bestDistances = classifier.bestDistances.copyOf(),
                secondBestDistances = classifier.secondBestDistances.copyOf(),
                validMask = validMask.copyOf(),
                probeValidityMask = sampler.getProbeValidityMask().copyOf(),
                sampleY = y.copyOf(),
                sampleU = u.copyOf(),
                sampleV = v.copyOf(),
                transport = transport,
            )
        } else null

        val transportUs = (System.nanoTime() - tt0) / 1000
        return DecodeResult(
            geometry.trackingState, activeProfile, forcedProfileId == null,
            calibrator.calibratedCount(), validSamples, confident, rawErasures,
            accepted, rejection, transport, debugSnapshot,
            Timing(profileUs, samplingUs, classificationUs, transportUs, (System.nanoTime() - t0) / 1000)
        )
    }

    private fun decodeProfile(
        h: DoubleArray,
        luma: ByteArray,
        width: Int,
        height: Int,
        geometry: V6StaticResult,
    ): V7OpticalProfile? {
        val black = geometry.pilotYUVs["BLACK"]?.getOrNull(0) ?: return null
        val white = geometry.pilotYUVs["WHITE"]?.getOrNull(0) ?: return null
        if (abs(white - black) < 24) return null
        val threshold = (black + white) / 2.0
        var id = 0
        for (center in V7OpticalProfiles.profileCodeCenters) {
            val y = sampleLuma(h, center[0], center[1], luma, width, height) ?: return null
            id = (id shl 1) or if (y >= threshold) 1 else 0
        }
        return V7OpticalProfiles.byId(id)
    }

    private fun updatePilotCalibration(
        result: V6StaticResult,
        h: DoubleArray,
        luma: ByteArray,
        width: Int,
        height: Int,
        chromaReader: ChromaPixelReader?,
    ) {
        val baseMap = if (activeProfile.colorCount == 4) {
            mapOf("BLACK" to 0, "WHITE" to 1, "RED" to 2, "BLUE" to 3)
        } else {
            mapOf("BLACK" to 0, "WHITE" to 1, "RED" to 2, "BLUE" to 4)
        }
        for ((name, idx) in baseMap) {
            val yuv = result.pilotYUVs[name] ?: continue
            if (!calibrator.isCalibrated(idx)) calibrator.setPilotCenter(idx, yuv[0], yuv[1], yuv[2])
            else calibrator.updateCenterEMA(idx, yuv[0], yuv[1], yuv[2], alpha = 0.04)
        }
        if (activeProfile.colorCount != 8) return
        val extraMap = mapOf("GREEN" to 3, "YELLOW" to 5, "CYAN" to 6, "MAGENTA" to 7)
        for ((name, idx) in extraMap) {
            val center = V7OpticalProfiles.extraPilotCenters[name] ?: continue
            val sample = sampleYuv(h, center[0], center[1], luma, width, height, chromaReader) ?: continue
            if (!calibrator.isCalibrated(idx)) calibrator.setPilotCenter(idx, sample[0], sample[1], sample[2])
            else calibrator.updateCenterEMA(idx, sample[0], sample[1], sample[2], alpha = 0.04)
        }
    }

    private fun sampleLuma(h: DoubleArray, cx: Double, cy: Double, luma: ByteArray, width: Int, height: Int): Int? {
        val den = h[6] * cx + h[7] * cy + h[8]
        if (!den.isFinite() || abs(den) < 1e-9) return null
        val x = ((h[0] * cx + h[1] * cy + h[2]) / den).roundToInt()
        val y = ((h[3] * cx + h[4] * cy + h[5]) / den).roundToInt()
        if (x !in 0 until width || y !in 0 until height) return null
        return luma[y * width + x].toInt() and 0xFF
    }

    private fun sampleYuv(
        h: DoubleArray, cx: Double, cy: Double, luma: ByteArray, width: Int, height: Int,
        chromaReader: ChromaPixelReader?,
    ): IntArray? {
        val den = h[6] * cx + h[7] * cy + h[8]
        if (!den.isFinite() || abs(den) < 1e-9) return null
        val x = (h[0] * cx + h[1] * cy + h[2]) / den
        val y = (h[3] * cx + h[4] * cy + h[5]) / den
        val ix = x.roundToInt(); val iy = y.roundToInt()
        if (ix !in 0 until width || iy !in 0 until height) return null
        val yy = luma[iy * width + ix].toInt() and 0xFF
        val uv = IntArray(2)
        if (chromaReader == null || !chromaReader.read(x, y, uv)) return null
        return intArrayOf(yy, uv[0], uv[1])
    }
}
