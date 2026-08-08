package com.superqr.android.vision.v7_capacity_lab

import com.superqr.android.vision.v6.classification.ChromaPixelReader

/**
 * Top-level V7 Capacity Lab receiver orchestrator.
 *
 * Pipeline per camera frame:
 *   carrier geometry → sampling → calibrated classification → metrics
 *
 * Supports configurable profile, grid, palette, sampler mode, expected frame.
 * Does NOT implement: V6 transport, file transfer, FEC, compression, encryption.
 */
class V7CapacityLabReceiver(
    private val manifest: V7LabManifest
) : AutoCloseable {

    // ---- Components ----
    val geometryEngine = V7CarrierGeometryEngine()
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
        val carrier: V7CarrierGeometryEngine.CarrierResult,
        val metrics: V7ChannelMetrics.FrameMetrics?,
        val expectedFrameIndex: Int,
        val calibrationStatus: String
    )

    var lastResult: LabResult? = null
        private set

    // ---- Timing ----
    data class StageTiming(
        var carrierGeometryUs: Long = 0,
        var homographyUpdateUs: Long = 0,
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
        // Reset with the new palette size
        val newCal = V7Calibrator(paletteSize)
        // Copy centers from old calibrator if applicable
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

    /** Get expected symbols for the current expected data frame index. */
    fun getExpectedSymbols(): ByteArray? {
        return manifest.generateExpectedDataFrame(profileName, expectedDataFrameIndex)
    }

    /** Total number of data frames in the profile sequence. */
    fun totalDataFrames(): Int = manifest.numDataFrames(profileName)

    /** Total number of calibration frames in the profile sequence. */
    fun totalCalibrationFrames(): Int = manifest.numCalibrationFrames(profileName)

    /** Get the absolute frame index for the current expected data frame. */
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
     * Analyze one camera frame.
     *
     * @param lumaBytes rotation-normalized luma buffer
     * @param width luma buffer width
     * @param height luma buffer height
     * @param chromaReader chroma pixel reader (null = Y-only)
     * @return LabResult with carrier state and metrics (null if no carrier found)
     */
    fun analyze(
        lumaBytes: ByteArray,
        width: Int,
        height: Int,
        chromaReader: ChromaPixelReader?
    ): LabResult {
        val t0 = System.nanoTime()

        // 1. Carrier geometry
        val tGeo0 = System.nanoTime()
        val carrier = geometryEngine.acquire(lumaBytes, width, height, chromaReader)
        val tGeo1 = System.nanoTime()
        timing.carrierGeometryUs = (tGeo1 - tGeo0) / 1000

        if (!carrier.quadFound || carrier.canonicalToImageH == null) {
            val result = LabResult(carrier, null, expectedDataFrameIndex, calibrationLabel())
            lastResult = result
            timing.totalAnalysisUs = (System.nanoTime() - t0) / 1000
            return result
        }

        // 2. Calibration from V6 pilots (opportunistic)
        if (carrier.pilotsRead) {
            val pilotMap = mapOf("BLACK" to 0, "WHITE" to 1, "RED" to 2, "BLUE" to 3)
            for ((pilotName, symbolIdx) in pilotMap) {
                if (symbolIdx < paletteSize) {
                    val yuv = carrier.pilotYUVs[pilotName]
                    if (yuv != null) {
                        if (!calibrator.isCalibrated(symbolIdx)) {
                            calibrator.setPilotCenter(symbolIdx, yuv[0], yuv[1], yuv[2])
                        }
                    }
                }
            }
        }

        // For candidate_8_a, pilots only cover 4 of 8 colors.
        // Remaining colors need solid calibration frames — handled externally.

        // 3. Sampling
        val tSamp0 = System.nanoTime()
        val validSamples = when (probeMode) {
            V7HighDensitySampler.ProbeMode.CENTER_1 ->
                sampler.sampleCenter1(carrier.canonicalToImageH, lumaBytes, width, height, chromaReader)
            V7HighDensitySampler.ProbeMode.CROSS_5 ->
                sampler.sampleCross5(carrier.canonicalToImageH, lumaBytes, width, height, chromaReader)
        }
        val tSamp1 = System.nanoTime()
        timing.samplingUs = (tSamp1 - tSamp0) / 1000

        // 4. Classification
        val tCls0 = System.nanoTime()
        val yArr = when (probeMode) {
            V7HighDensitySampler.ProbeMode.CENTER_1 -> sampler.getYCenters()
            V7HighDensitySampler.ProbeMode.CROSS_5 -> sampler.getYCross5()
        }
        val uArr = when (probeMode) {
            V7HighDensitySampler.ProbeMode.CENTER_1 -> sampler.getUCenters()
            V7HighDensitySampler.ProbeMode.CROSS_5 -> sampler.getUCross5()
        }
        val vArr = when (probeMode) {
            V7HighDensitySampler.ProbeMode.CENTER_1 -> sampler.getVCenters()
            V7HighDensitySampler.ProbeMode.CROSS_5 -> sampler.getVCross5()
        }

        // Update classifier with latest calibration centers
        classifier.setCenters(calibrator.getCentersSnapshot())
        classifier.classify(yArr, uArr, vArr, gridSize * gridSize)
        val tCls1 = System.nanoTime()
        timing.classificationUs = (tCls1 - tCls0) / 1000

        // 5. Metrics
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
                carrierGeometryUs = timing.carrierGeometryUs,
                homographyUpdateUs = timing.homographyUpdateUs,
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

        // Fill in timing on metrics
        val finalMetrics = frameMetrics?.copy(
            metricsUs = timing.metricsUs,
            totalAnalysisUs = timing.totalAnalysisUs
        )

        val result = LabResult(carrier, finalMetrics, expectedDataFrameIndex, calibrationLabel())
        lastResult = result
        return result
    }

    /** Run solid-frame calibration from a known solid-color frame. */
    fun calibrateFromSolidFrame(symbolIdx: Int) {
        val yArr = when (probeMode) {
            V7HighDensitySampler.ProbeMode.CENTER_1 -> sampler.getYCenters()
            V7HighDensitySampler.ProbeMode.CROSS_5 -> sampler.getYCross5()
        }
        val uArr = when (probeMode) {
            V7HighDensitySampler.ProbeMode.CENTER_1 -> sampler.getUCenters()
            V7HighDensitySampler.ProbeMode.CROSS_5 -> sampler.getUCross5()
        }
        val vArr = when (probeMode) {
            V7HighDensitySampler.ProbeMode.CENTER_1 -> sampler.getVCenters()
            V7HighDensitySampler.ProbeMode.CROSS_5 -> sampler.getVCross5()
        }
        calibrator.calibrateFromSolidFrame(symbolIdx, yArr, uArr, vArr, gridSize * gridSize)
    }

    fun resetCalibration() {
        calibrator.reset()
    }

    private fun calibrationLabel(): String {
        val count = calibrator.calibratedCount()
        return if (count == paletteSize) "FULL" else "$count/$paletteSize"
    }

    override fun close() {
        geometryEngine.close()
    }
}
