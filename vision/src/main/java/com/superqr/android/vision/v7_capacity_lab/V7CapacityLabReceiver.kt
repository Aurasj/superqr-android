package com.superqr.android.vision.v7_capacity_lab

import com.superqr.android.vision.v6.classification.ChromaPixelReader
import com.superqr.android.vision.v6.model.V6StaticResult

/**
 * Top-level V7 Capacity Lab receiver orchestrator.
 *
 * Receives carrier geometry from the proven V6 detector (V6StaticResult) and
 * runs only the V7 payload pipeline: sampling → calibrated classification → metrics.
 *
 * Does NOT own a camera pipeline, geometry engine, or V6 detector.
 * Does NOT implement: V6 transport, file transfer, FEC, compression, encryption.
 */
class V7CapacityLabReceiver(
    private val manifest: V7LabManifest
) {

    // ---- Components ----
    val sampler = V7HighDensitySampler()
    val calibrator: V7Calibrator
    val classifier = V7SoftClassifier()
    val metrics = V7ChannelMetrics()

    // ---- Current profile ----
    var profileName: String = manifest.referenceProfiles.keys.first()
        private set

    var gridSize: Int = 40
        private set

    var paletteName: String = "v6_reference_4"
        private set

    var bitsPerCell: Int = 2
        private set

    var paletteSize: Int = 4
        private set

    // ---- Expected frame index (MANUAL_EXPECTED_FRAME mode) ----
    var expectedDataFrameIndex: Int = 0

    // ---- Sampler mode ----
    var probeMode: V7HighDensitySampler.ProbeMode = V7HighDensitySampler.ProbeMode.CENTER_1

    // ---- Last result ----
    data class LabResult(
        /** V6 tracker state: SEARCHING/TRACKING/etc. */
        val trackingState: String,
        /** V6 classification source: FULL_DETECTION/TRACKED_RESAMPLED/TRACKED_HOMOGRAPHY. */
        val classificationSource: String,
        val metrics: V7ChannelMetrics.FrameMetrics?,
        val expectedFrameIndex: Int,
        val calibrationStatus: String
    ) {
        val isFresh: Boolean get() =
            classificationSource == "FULL_DETECTION" || classificationSource == "TRACKED_RESAMPLED"
    }

    var lastResult: LabResult? = null
        private set

    // ---- Timing ----
    data class StageTiming(
        var carrierGeometryUs: Long = 0,
        var samplingUs: Long = 0,
        var classificationUs: Long = 0,
        var metricsUs: Long = 0,
        var totalAnalysisUs: Long = 0
    )
    val timing = StageTiming()

    init {
        val initialProfile = manifest.referenceProfiles[profileName]!!
        gridSize = initialProfile.gridSize
        paletteName = initialProfile.paletteName
        val palette = manifest.palettes[paletteName]!!
        bitsPerCell = palette.bitsPerCell
        paletteSize = palette.symbolCount
        calibrator = V7Calibrator(paletteSize)
        configureForProfile()
    }

    // ---- Profile selection ----

    fun selectProfile(name: String): Boolean {
        val profile = manifest.referenceProfiles[name] ?: return false
        profileName = name
        gridSize = profile.gridSize
        paletteName = profile.paletteName
        val palette = manifest.palettes[paletteName]!!
        bitsPerCell = palette.bitsPerCell
        paletteSize = palette.symbolCount
        expectedDataFrameIndex = 0
        configureForProfile()
        return true
    }

    private fun configureForProfile() {
        calibrator.reset()
        sampler.setGridSize(gridSize, manifest.payloadBbox)
        classifier.setCellCount(gridSize * gridSize)
        metrics.setGridSize(gridSize)
    }

    // ---- Expected frame management ----

    fun advanceExpectedFrame(): Boolean {
        val numData = manifest.numDataFrames(profileName)
        if (expectedDataFrameIndex + 1 < numData) {
            expectedDataFrameIndex++
            return true
        }
        return false
    }

    fun retreatExpectedFrame(): Boolean {
        if (expectedDataFrameIndex > 0) {
            expectedDataFrameIndex--
            return true
        }
        return false
    }

    fun resetExpectedFrame() {
        expectedDataFrameIndex = 0
    }

    fun getExpectedSymbols(): ByteArray? {
        return manifest.generateExpectedDataFrame(profileName, expectedDataFrameIndex)
    }

    fun totalDataFrames(): Int = manifest.numDataFrames(profileName)
    fun totalCalibrationFrames(): Int = manifest.numCalibrationFrames(profileName)

    fun absoluteFrameIndex(): Int {
        val sv = manifest.sequenceVectors[profileName] ?: return -1
        return if (expectedDataFrameIndex < sv.dataFrames.size)
            sv.dataFrames[expectedDataFrameIndex].frameIndex else -1
    }

    fun frameTypeLabel(): String {
        val sv = manifest.sequenceVectors[profileName] ?: return "?"
        return if (expectedDataFrameIndex < sv.dataFrames.size)
            sv.dataFrames[expectedDataFrameIndex].frameType else "?"
    }

    // ---- Per-frame analysis ----

    /**
     * Run V7 payload pipeline using carrier geometry from the V6 detector.
     *
     * @param v6Result carrier geometry + tracking state from V6StaticDetector.detect()
     * @param lumaBytes rotation-normalized luma buffer (same as V6 detector used)
     * @param width luma buffer width
     * @param height luma buffer height
     * @param chromaReader chroma pixel reader (null = Y-only)
     */
    fun analyze(
        v6Result: V6StaticResult,
        lumaBytes: ByteArray,
        width: Int,
        height: Int,
        chromaReader: ChromaPixelReader?
    ): LabResult {
        val t0 = System.nanoTime()

        val hInv = v6Result.finalInvHomography
        val source = v6Result.diagnosticPayload?.classificationSource
            ?: if (v6Result.borderFound) "FULL_DETECTION" else "NONE"

        if (!v6Result.borderFound || !v6Result.orientationResolved || hInv == null) {
            val result = LabResult(
                v6Result.trackingState, source, null,
                expectedDataFrameIndex, calibrationLabel()
            )
            lastResult = result
            timing.totalAnalysisUs = (System.nanoTime() - t0) / 1000
            return result
        }

        // 1. Calibration from V6 carrier pilots (opportunistic)
        if (v6Result.pilotYUVs.isNotEmpty()) {
            val pilotMap = mapOf("BLACK" to 0, "WHITE" to 1, "RED" to 2, "BLUE" to 3)
            for ((pilotName, symbolIdx) in pilotMap) {
                if (symbolIdx < paletteSize) {
                    val yuv = v6Result.pilotYUVs[pilotName]
                    if (yuv != null && !calibrator.isCalibrated(symbolIdx)) {
                        calibrator.setPilotCenter(symbolIdx, yuv[0], yuv[1], yuv[2])
                    }
                }
            }
        }

        // 2. Sampling (carrier geometry time from V6 detector — recorded from v6Result)
        val tSamp0 = System.nanoTime()
        val validSamples = when (probeMode) {
            V7HighDensitySampler.ProbeMode.CENTER_1 ->
                sampler.sampleCenter1(hInv, lumaBytes, width, height, chromaReader)
            V7HighDensitySampler.ProbeMode.CROSS_5 ->
                sampler.sampleCross5(hInv, lumaBytes, width, height, chromaReader)
            V7HighDensitySampler.ProbeMode.LUMA_PATCH_9 ->
                sampler.sampleCross5(hInv, lumaBytes, width, height, chromaReader)
        }
        val tSamp1 = System.nanoTime()
        timing.samplingUs = (tSamp1 - tSamp0) / 1000

        // 3. Classification
        val tCls0 = System.nanoTime()
        val yArr = when (probeMode) {
            V7HighDensitySampler.ProbeMode.CENTER_1 -> sampler.getYCenters()
            V7HighDensitySampler.ProbeMode.CROSS_5 -> sampler.getYCross5()
            V7HighDensitySampler.ProbeMode.LUMA_PATCH_9 -> sampler.getYCross5()
        }
        val uArr = when (probeMode) {
            V7HighDensitySampler.ProbeMode.CENTER_1 -> sampler.getUCenters()
            V7HighDensitySampler.ProbeMode.CROSS_5 -> sampler.getUCross5()
            V7HighDensitySampler.ProbeMode.LUMA_PATCH_9 -> sampler.getUCross5()
        }
        val vArr = when (probeMode) {
            V7HighDensitySampler.ProbeMode.CENTER_1 -> sampler.getVCenters()
            V7HighDensitySampler.ProbeMode.CROSS_5 -> sampler.getVCross5()
            V7HighDensitySampler.ProbeMode.LUMA_PATCH_9 -> sampler.getVCross5()
        }

        classifier.setCenters(calibrator.getCentersSnapshot())
        classifier.classify(yArr, uArr, vArr, gridSize * gridSize)
        val tCls1 = System.nanoTime()
        timing.classificationUs = (tCls1 - tCls0) / 1000

        // 4. Metrics
        val tMet0 = System.nanoTime()
        val expected = getExpectedSymbols()
        val frameMetrics = if (expected != null) {
            metrics.computeFrame(
                expectedSymbols = expected,
                decodedSymbols = classifier.bestSymbols,
                bitsPerCell = bitsPerCell,
                paletteSize = paletteSize,
                totalCells = gridSize * gridSize,
                accumulateSpatial = false,
                carrierGeometryUs = v6Result.processingTimeMs * 1000, // V6 processing
                homographyUpdateUs = 0,
                samplingUs = timing.samplingUs,
                classificationUs = timing.classificationUs,
                metricsUs = 0,
                totalAnalysisUs = 0,
                gridSize = gridSize
            )
        } else null
        val tMet1 = System.nanoTime()
        timing.metricsUs = (tMet1 - tMet0) / 1000
        timing.totalAnalysisUs = (System.nanoTime() - t0) / 1000

        val finalMetrics = frameMetrics?.copy(
            metricsUs = timing.metricsUs,
            totalAnalysisUs = timing.totalAnalysisUs
        )

        val result = LabResult(
            v6Result.trackingState, source, finalMetrics,
            expectedDataFrameIndex, calibrationLabel()
        )
        lastResult = result
        return result
    }

    /** Run solid-frame calibration from a known solid-color frame. */
    fun calibrateFromSolidFrame(symbolIdx: Int) {
        val yArr = when (probeMode) {
            V7HighDensitySampler.ProbeMode.CENTER_1 -> sampler.getYCenters()
            V7HighDensitySampler.ProbeMode.CROSS_5 -> sampler.getYCross5()
            V7HighDensitySampler.ProbeMode.LUMA_PATCH_9 -> sampler.getYCross5()
        }
        val uArr = when (probeMode) {
            V7HighDensitySampler.ProbeMode.CENTER_1 -> sampler.getUCenters()
            V7HighDensitySampler.ProbeMode.CROSS_5 -> sampler.getUCross5()
            V7HighDensitySampler.ProbeMode.LUMA_PATCH_9 -> sampler.getUCross5()
        }
        val vArr = when (probeMode) {
            V7HighDensitySampler.ProbeMode.CENTER_1 -> sampler.getVCenters()
            V7HighDensitySampler.ProbeMode.CROSS_5 -> sampler.getVCross5()
            V7HighDensitySampler.ProbeMode.LUMA_PATCH_9 -> sampler.getVCross5()
        }
        calibrator.calibrateFromSolidFrame(symbolIdx, yArr, uArr, vArr, gridSize * gridSize)
    }

    fun resetCalibration() {
        calibrator.reset()
    }

    /** No-op close for API compatibility (does not own heavyweight resources). */
    fun close() {}

    private fun calibrationLabel(): String {
        val count = calibrator.calibratedCount()
        return if (count == paletteSize) "FULL" else "$count/$paletteSize"
    }
}
