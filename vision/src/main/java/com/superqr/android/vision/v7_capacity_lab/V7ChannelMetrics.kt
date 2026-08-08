package com.superqr.android.vision.v7_capacity_lab

/**
 * Channel quality metrics for the V7 Capacity Lab.
 *
 * Compares decoded symbols against locally regenerated expected symbols.
 * All calculations operate on primitive arrays.
 *
 * Metric semantics:
 * - SER_ALL: (wrong + erasures) / total — probability a transmitted symbol was not recovered
 * - CONDITIONAL_SER: wrong / (total - erasures) — how often an accepted decision is wrong
 * - erasure_rate: erasures / total
 * - BER_ACCEPTED: Hamming bit errors over non-erased cells only
 *
 * ERASURE_MARKER = -1
 */
class V7ChannelMetrics {

    companion object {
        const val ERASURE_MARKER: Byte = -1
    }

    data class FrameMetrics(
        val gridSize: Int,
        val totalCells: Int,
        val correctSymbols: Int,
        val wrongSymbols: Int,
        val erasures: Int,
        val serAll: Double,         // (wrong + erasures) / total
        val conditionalSer: Double, // wrong / (total - erasures), NaN if all erased
        val erasureRate: Double,     // erasures / total
        val berAccepted: Double,     // Hamming errors / ((total - erasures) * bitsPerCell), NaN if all erased
        val bitsPerCell: Int,
        // Timing (microseconds)
        val carrierGeometryUs: Long,
        val homographyUpdateUs: Long,
        val samplingUs: Long,
        val classificationUs: Long,
        val metricsUs: Long,
        val totalAnalysisUs: Long,
        // Confusion: confusionMatrix[expected * paletteSize + decoded]
        val confusionMatrix: IntArray,
        val paletteSize: Int
    ) {
        /** Number of bits considered in BER_ACCEPTED denominator. */
        val acceptedBits: Int get() = (totalCells - erasures) * bitsPerCell

        /** Number of Hamming bit errors in accepted decisions. */
        fun hammingBitErrors(): Int {
            var errors = 0
            for (s in 0 until paletteSize) {
                for (d in 0 until paletteSize) {
                    val count = confusionMatrix[s * paletteSize + d]
                    if (count > 0 && s != d) {
                        errors += Integer.bitCount(s xor d) * count
                    }
                }
            }
            return errors
        }
    }

    // ---- Spatial accumulation (diagnostic mode) ----
    private var perCellErrorCount: IntArray = IntArray(0)
    private var perCellErasureCount: IntArray = IntArray(0)
    private var frameCount: Int = 0

    fun setGridSize(gridSize: Int) {
        val total = gridSize * gridSize
        perCellErrorCount = IntArray(total)
        perCellErasureCount = IntArray(total)
        frameCount = 0
    }

    /**
     * Compute frame metrics from expected and decoded symbol arrays.
     *
     * @param expectedSymbols row-major expected symbols (positive only)
     * @param decodedSymbols row-major decoded symbols (may contain ERASURE_MARKER)
     * @param bitsPerCell bits per palette symbol
     * @param accumulateSpatial if true, accumulate per-cell error/erasure counters
     */
    fun computeFrame(
        expectedSymbols: ByteArray,
        decodedSymbols: ByteArray,
        bitsPerCell: Int,
        paletteSize: Int,
        totalCells: Int,
        accumulateSpatial: Boolean = false,
        carrierGeometryUs: Long = 0,
        homographyUpdateUs: Long = 0,
        samplingUs: Long = 0,
        classificationUs: Long = 0,
        metricsUs: Long = 0,
        totalAnalysisUs: Long = 0,
        gridSize: Int = 0
    ): FrameMetrics {
        var correct = 0
        var wrong = 0
        var erasures = 0

        val confusion = IntArray(paletteSize * paletteSize)

        for (i in 0 until totalCells) {
            val decoded = decodedSymbols[i]
            val expected = expectedSymbols[i]

            if (decoded == ERASURE_MARKER) {
                erasures++
                if (accumulateSpatial && i < perCellErasureCount.size) {
                    perCellErasureCount[i]++
                }
            } else {
                val expIdx = expected.toInt() and 0xFF
                val decIdx = decoded.toInt() and 0xFF
                confusion[expIdx * paletteSize + decIdx]++
                if (decIdx == expIdx) {
                    correct++
                } else {
                    wrong++
                    if (accumulateSpatial && i < perCellErrorCount.size) {
                        perCellErrorCount[i]++
                    }
                }
            }
        }

        val nonErasures = totalCells - erasures

        // SER_ALL: (wrong + erasures) / total
        val serAll = (wrong + erasures).toDouble() / totalCells.toDouble()

        // CONDITIONAL_SER: wrong / nonErasures
        val conditionalSer = if (nonErasures > 0) wrong.toDouble() / nonErasures.toDouble() else Double.NaN

        // Erasure rate
        val erasureRate = erasures.toDouble() / totalCells.toDouble()

        // BER_ACCEPTED: Hamming errors over non-erased cells
        val berAccepted: Double
        if (nonErasures > 0) {
            var hammingErrors = 0
            for (s in 0 until paletteSize) {
                for (d in 0 until paletteSize) {
                    val count = confusion[s * paletteSize + d]
                    if (count > 0 && s != d) {
                        hammingErrors += Integer.bitCount(s xor d) * count
                    }
                }
            }
            berAccepted = hammingErrors.toDouble() / (nonErasures.toDouble() * bitsPerCell.toDouble())
        } else {
            berAccepted = Double.NaN
        }

        if (accumulateSpatial) frameCount++

        return FrameMetrics(
            gridSize = gridSize,
            totalCells = totalCells,
            correctSymbols = correct,
            wrongSymbols = wrong,
            erasures = erasures,
            serAll = serAll,
            conditionalSer = conditionalSer,
            erasureRate = erasureRate,
            berAccepted = berAccepted,
            bitsPerCell = bitsPerCell,
            carrierGeometryUs = carrierGeometryUs,
            homographyUpdateUs = homographyUpdateUs,
            samplingUs = samplingUs,
            classificationUs = classificationUs,
            metricsUs = metricsUs,
            totalAnalysisUs = totalAnalysisUs,
            confusionMatrix = confusion,
            paletteSize = paletteSize
        )
    }

    /** Get per-cell error accumulation array (diagnostic). */
    fun getPerCellErrorCount(): IntArray = perCellErrorCount

    /** Get per-cell erasure accumulation array (diagnostic). */
    fun getPerCellErasureCount(): IntArray = perCellErasureCount

    /** Get number of frames accumulated. */
    fun getFrameCount(): Int = frameCount

    /** Reset spatial accumulation. */
    fun resetSpatial() {
        perCellErrorCount.fill(0)
        perCellErasureCount.fill(0)
        frameCount = 0
    }
}
