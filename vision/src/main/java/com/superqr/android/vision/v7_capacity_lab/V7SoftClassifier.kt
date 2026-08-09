package com.superqr.android.vision.v7_capacity_lab

/**
 * Allocation-light soft YUV classifier shared by the V7 lab and production receiver.
 *
 * Lab callers keep the original calibrated nearest-center behavior. Production
 * four-color mode uses a frame-local payload estimator because CameraX YUV_420,
 * display moire and view angle can make the tiny pilots differ from the large
 * payload area. The known BLACK/WHITE/RED/BLUE topology gives us a particularly
 * robust estimator:
 *
 * - WHITE is the high-luma cluster;
 * - RED has V-U strongly positive;
 * - BLUE has U-V strongly positive;
 * - the remaining low-luma neutral cluster is BLACK.
 *
 * We estimate medians from those broad groups every optical frame and classify
 * against the resulting payload-space centers. Crucially, these medians are
 * frame-local: they do not feed back into pilot calibration, so a bad frame cannot
 * permanently collapse RED and BLUE for all following frames.
 */
class V7SoftClassifier {

    companion object {
        const val ERASURE_MARKER: Byte = -1

        // The two supplied 40x40 captures show four very well separated payload
        // clusters. 0.88 keeps genuinely ambiguous boundary samples as erasures
        // without turning hundreds of otherwise clean cells into erasures.
        private const val DENSE4_FINAL_MARGIN = 0.88
        private const val DENSE4_MIN_CLUSTER_SAMPLES = 16
        private const val DENSE4_CHROMA_AXIS_MIN = 24
    }

    var paletteSize: Int = 4
        private set

    private var centers: Array<IntArray> = emptyArray()
    private var calibrated: BooleanArray = BooleanArray(0)

    var bestSymbols: ByteArray = ByteArray(0)
        private set
    var secondBestSymbols: ByteArray = ByteArray(0)
        private set
    var bestDistances: IntArray = IntArray(0)
        private set
    var secondBestDistances: IntArray = IntArray(0)
        private set

    /** Same semantics/scale as before; weighted distances are normalized to 3 channels. */
    var maxDistanceThreshold: Int = 40000
    var marginThreshold: Double = 0.9

    var yWeight: Int = 1
        private set
    var uWeight: Int = 1
        private set
    var vWeight: Int = 1
        private set

    // Non-uniform production weighting opts into production tuning. Lab callers
    // that keep 1:1:1 retain the original behavior.
    private var productionWeightedMode = false

    private val histY = Array(4) { IntArray(256) }
    private val histU = Array(4) { IntArray(256) }
    private val histV = Array(4) { IntArray(256) }
    private val denseCounts = IntArray(4)
    private val denseCenters = Array(4) { IntArray(3) }

    /** Last centers actually used for the final pass, useful for diagnostics/tests. */
    private var effectiveCenters: Array<IntArray> = emptyArray()

    fun setChannelWeights(y: Int, u: Int, v: Int) {
        require(y > 0 && u > 0 && v > 0) { "channel weights must be positive" }
        yWeight = y
        uWeight = u
        vWeight = v
        productionWeightedMode = y != 1 || u != 1 || v != 1
    }

    fun setCenters(newCenters: Array<IntArray>) {
        paletteSize = newCenters.size
        centers = newCenters.map { it.copyOf() }.toTypedArray()
        copyCentersToEffective()
        calibrated = BooleanArray(paletteSize) { true }
        ensureOutputArrays()
    }

    fun setCenter(symbolIdx: Int, y: Int, u: Int, v: Int) {
        require(symbolIdx in 0 until paletteSize)
        centers[symbolIdx][0] = y
        centers[symbolIdx][1] = u
        centers[symbolIdx][2] = v
        if (effectiveCenters.size != paletteSize) copyCentersToEffective()
        effectiveCenters[symbolIdx][0] = y
        effectiveCenters[symbolIdx][1] = u
        effectiveCenters[symbolIdx][2] = v
        calibrated[symbolIdx] = true
    }

    fun isCalibrated(symbolIdx: Int): Boolean =
        symbolIdx in 0 until paletteSize && calibrated[symbolIdx]

    fun isFullyCalibrated(): Boolean = calibrated.all { it }

    fun resetCalibration() {
        calibrated.fill(false)
        centers = Array(paletteSize) { intArrayOf(128, 128, 128) }
        copyCentersToEffective()
    }

    private fun ensureOutputArrays() = Unit

    fun setCellCount(count: Int) {
        if (bestSymbols.size != count) {
            bestSymbols = ByteArray(count)
            secondBestSymbols = ByteArray(count)
            bestDistances = IntArray(count)
            secondBestDistances = IntArray(count)
        }
    }

    fun getEffectiveCentersSnapshot(): Array<IntArray> =
        effectiveCenters.map { it.copyOf() }.toTypedArray()

    fun classify(yArr: IntArray, uArr: IntArray, vArr: IntArray, totalCells: Int) {
        setCellCount(totalCells)

        val denseFourColor = productionWeightedMode && paletteSize == 4 && totalCells >= 64
        if (!denseFourColor) {
            copyCentersToEffective()
            classifyPass(
                yArr, uArr, vArr, totalCells,
                effectiveCenters,
                yWeight, uWeight, vWeight,
                marginThreshold,
                applyErasures = true,
            )
            return
        }

        val refined = buildDenseFourColorPayloadCenters(yArr, uArr, vArr, totalCells)
        if (refined == null) copyCentersToEffective() else copyCenters(refined, effectiveCenters)

        // Keep luma important, but retain enough chroma authority to cleanly
        // separate RED and BLUE. The previous 10:1:1 seed could merge them.
        classifyPass(
            yArr, uArr, vArr, totalCells,
            effectiveCenters,
            6, 2, 2,
            minOf(marginThreshold, DENSE4_FINAL_MARGIN),
            applyErasures = true,
        )
    }

    private fun classifyPass(
        yArr: IntArray,
        uArr: IntArray,
        vArr: IntArray,
        totalCells: Int,
        passCenters: Array<IntArray>,
        wy: Int,
        wu: Int,
        wv: Int,
        passMargin: Double,
        applyErasures: Boolean,
    ) {
        val weightSum = wy + wu + wv

        for (i in 0 until totalCells) {
            val y = yArr[i]
            val u = uArr[i]
            val v = vArr[i]

            var bestIdx = 0
            var bestDist = Int.MAX_VALUE
            var secondIdx = 0
            var secondDist = Int.MAX_VALUE

            for (s in 0 until paletteSize) {
                if (!calibrated[s]) continue
                val c = passCenters[s]
                val dy = y - c[0]
                val du = u - c[1]
                val dv = v - c[2]
                val weighted =
                    wy.toLong() * dy * dy +
                    wu.toLong() * du * du +
                    wv.toLong() * dv * dv
                val dist = ((weighted * 3L) / weightSum.toLong())
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

                if (dist < bestDist) {
                    secondIdx = bestIdx
                    secondDist = bestDist
                    bestIdx = s
                    bestDist = dist
                } else if (dist < secondDist) {
                    secondIdx = s
                    secondDist = dist
                }
            }

            bestDistances[i] = bestDist
            secondBestDistances[i] = secondDist
            secondBestSymbols[i] = secondIdx.toByte()

            bestSymbols[i] = if (!applyErasures) {
                bestIdx.toByte()
            } else {
                when {
                    bestDist > maxDistanceThreshold -> ERASURE_MARKER
                    secondDist > 0 && secondDist < Int.MAX_VALUE &&
                        bestDist.toDouble() / secondDist.toDouble() > passMargin -> ERASURE_MARKER
                    else -> bestIdx.toByte()
                }
            }
        }
    }

    /**
     * Estimate the four production centers directly from the current payload.
     *
     * This deliberately does not depend on the *current* RED/BLUE pilot centers
     * for identity assignment. In a bad capture the old EMA could make those two
     * centers collapse; once collapsed, nearest-center k-means could never recover.
     * YUV's chroma axes give us stable semantic labels for this particular palette.
     */
    private fun buildDenseFourColorPayloadCenters(
        yArr: IntArray,
        uArr: IntArray,
        vArr: IntArray,
        totalCells: Int,
    ): Array<IntArray>? {
        for (s in 0 until 4) {
            histY[s].fill(0)
            histU[s].fill(0)
            histV[s].fill(0)
        }
        denseCounts.fill(0)

        // WHITE is always substantially brighter than the three non-white colors.
        // Use pilot luma only for this coarse cut; RED/BLUE identity is assigned by
        // the signed chroma axis below and therefore cannot collapse together.
        val brightestNonWhite = maxOf(centers[0][0], centers[2][0], centers[3][0])
        val whiteCut = ((centers[1][0] + brightestNonWhite) / 2).coerceIn(145, 220)

        for (i in 0 until totalCells) {
            val y = yArr[i].coerceIn(0, 255)
            val u = uArr[i].coerceIn(0, 255)
            val v = vArr[i].coerceIn(0, 255)
            if (y == 128 && u == 128 && v == 128) continue

            val chromaAxis = v - u
            val symbol = when {
                y >= whiteCut -> 1
                chromaAxis >= DENSE4_CHROMA_AXIS_MIN -> 2       // RED
                chromaAxis <= -DENSE4_CHROMA_AXIS_MIN -> 3      // BLUE
                else -> 0                                        // BLACK
            }

            histY[symbol][y]++
            histU[symbol][u]++
            histV[symbol][v]++
            denseCounts[symbol]++
        }

        if (denseCounts.any { it < DENSE4_MIN_CLUSTER_SAMPLES }) return null

        val out = denseCenters
        for (s in 0 until 4) {
            out[s][0] = histogramMedian(histY[s], denseCounts[s])
            out[s][1] = histogramMedian(histU[s], denseCounts[s])
            out[s][2] = histogramMedian(histV[s], denseCounts[s])
        }

        // Reject a pathological estimate instead of poisoning this optical frame.
        if (out[1][0] - out[0][0] < 60) return null
        if (out[2][2] - out[2][1] < DENSE4_CHROMA_AXIS_MIN) return null
        if (out[3][1] - out[3][2] < DENSE4_CHROMA_AXIS_MIN) return null
        return out
    }

    private fun histogramMedian(hist: IntArray, total: Int): Int {
        val target = (total - 1) / 2
        var seen = 0
        for (value in hist.indices) {
            seen += hist[value]
            if (seen > target) return value
        }
        return 128
    }

    fun confidenceMargin(cellIdx: Int): Double {
        val best = bestDistances[cellIdx]
        val second = secondBestDistances[cellIdx]
        if (second <= 0 || second >= Int.MAX_VALUE) return 0.0
        return best.toDouble() / second.toDouble()
    }

    fun isErasure(cellIdx: Int): Boolean = bestSymbols[cellIdx] == ERASURE_MARKER

    private fun copyCentersToEffective() {
        if (effectiveCenters.size != paletteSize) {
            effectiveCenters = Array(paletteSize) { IntArray(3) }
        }
        copyCenters(centers, effectiveCenters)
    }

    private fun copyCenters(source: Array<IntArray>, destination: Array<IntArray>) {
        for (symbol in 0 until paletteSize) {
            destination[symbol][0] = source[symbol][0]
            destination[symbol][1] = source[symbol][1]
            destination[symbol][2] = source[symbol][2]
        }
    }
}
