package com.superqr.android.vision.v7_capacity_lab

/**
 * Allocation-light soft YUV classifier shared by the V7 lab and production receiver.
 *
 * Default behavior remains the original calibrated nearest-center classifier.
 * When production requests strongly luma-weighted 4-color operation, the classifier
 * enables a robust dense-screen path:
 * - first pass assigns every sample to a provisional color;
 * - per-color payload medians are estimated from reusable 8-bit histograms;
 * - a second pass classifies against those payload-space centers;
 * - ambiguous cells become explicit erasures instead of confident wrong symbols.
 *
 * This matters for CameraX YUV_420: the pilots and payload can have slightly different
 * observed centers because chroma is subsampled and display-camera moire is spatially
 * varying. The robust median step is frame-local and never changes protocol semantics.
 */
class V7SoftClassifier {

    companion object {
        const val ERASURE_MARKER: Byte = -1
        private const val DENSE4_FINAL_MARGIN = 0.40
        private const val DENSE4_MIN_CLUSTER_SAMPLES = 16
        private const val DENSE4_MAX_CENTER_SHIFT_Y = 48
        private const val DENSE4_MAX_CENTER_SHIFT_UV = 72
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

    // Non-uniform production weighting opts into the dense four-color path. Lab
    // callers that keep the default 1:1:1 classifier retain the old behavior.
    private var productionWeightedMode = false

    private var nearestSymbols = ByteArray(0)
    private val histY = Array(4) { IntArray(256) }
    private val histU = Array(4) { IntArray(256) }
    private val histV = Array(4) { IntArray(256) }

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
        effectiveCenters = centers.map { it.copyOf() }.toTypedArray()
        calibrated = BooleanArray(paletteSize) { true }
        ensureOutputArrays()
    }

    fun setCenter(symbolIdx: Int, y: Int, u: Int, v: Int) {
        require(symbolIdx in 0 until paletteSize)
        centers[symbolIdx] = intArrayOf(y, u, v)
        effectiveCenters = centers.map { it.copyOf() }.toTypedArray()
        calibrated[symbolIdx] = true
    }

    fun isCalibrated(symbolIdx: Int): Boolean =
        symbolIdx in 0 until paletteSize && calibrated[symbolIdx]

    fun isFullyCalibrated(): Boolean = calibrated.all { it }

    fun resetCalibration() {
        calibrated.fill(false)
        centers = Array(paletteSize) { intArrayOf(128, 128, 128) }
        effectiveCenters = centers.map { it.copyOf() }.toTypedArray()
    }

    private fun ensureOutputArrays() = Unit

    fun setCellCount(count: Int) {
        if (bestSymbols.size != count) {
            bestSymbols = ByteArray(count)
            secondBestSymbols = ByteArray(count)
            bestDistances = IntArray(count)
            secondBestDistances = IntArray(count)
            nearestSymbols = ByteArray(count)
        }
    }

    fun getEffectiveCentersSnapshot(): Array<IntArray> =
        effectiveCenters.map { it.copyOf() }.toTypedArray()

    fun classify(yArr: IntArray, uArr: IntArray, vArr: IntArray, totalCells: Int) {
        setCellCount(totalCells)

        val denseFourColor = productionWeightedMode && paletteSize == 4 && totalCells >= 64
        if (!denseFourColor) {
            effectiveCenters = centers.map { it.copyOf() }.toTypedArray()
            classifyPass(
                yArr, uArr, vArr, totalCells,
                effectiveCenters,
                yWeight, uWeight, vWeight,
                marginThreshold,
                applyErasures = true,
            )
            return
        }

        // Broad first pass: keep raw nearest assignments even when the final soft
        // decision would be erased. Median payload centers are robust to a modest
        // number of misassignments and converge far better than a slow EMA here.
        classifyPass(
            yArr, uArr, vArr, totalCells,
            centers,
            yWeight, uWeight, vWeight,
            1.0,
            applyErasures = false,
        )

        val refined = buildDenseFourColorMedianCenters(yArr, uArr, vArr, totalCells)
        effectiveCenters = refined ?: centers.map { it.copyOf() }.toTypedArray()

        // Empirically, dense four-color camera samples need more U discrimination
        // than the original 10:1:1 preset to stop neutral BLACK from aliasing BLUE.
        // Keep Y dominant, but use 10:2:1 for the final decision.
        classifyPass(
            yArr, uArr, vArr, totalCells,
            effectiveCenters,
            10, 2, 1,
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

            nearestSymbols[i] = bestIdx.toByte()
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

    private fun buildDenseFourColorMedianCenters(
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
        val counts = IntArray(4)

        for (i in 0 until totalCells) {
            val y = yArr[i].coerceIn(0, 255)
            val u = uArr[i].coerceIn(0, 255)
            val v = vArr[i].coerceIn(0, 255)

            // 128/128/128 is the sampler's invalid/fallback value. A real optical
            // sample can theoretically equal it, but skipping this exact triplet
            // is much safer than letting out-of-bounds cells bias BLACK/RED medians.
            if (y == 128 && u == 128 && v == 128) continue

            val s = nearestSymbols[i].toInt()
            if (s !in 0..3) continue
            histY[s][y]++
            histU[s][u]++
            histV[s][v]++
            counts[s]++
        }

        if (counts.any { it < DENSE4_MIN_CLUSTER_SAMPLES }) return null

        val out = Array(4) { IntArray(3) }
        for (s in 0 until 4) {
            val candidateY = histogramMedian(histY[s], counts[s])
            val candidateU = histogramMedian(histU[s], counts[s])
            val candidateV = histogramMedian(histV[s], counts[s])
            val base = centers[s]
            out[s][0] = candidateY.coerceIn(
                base[0] - DENSE4_MAX_CENTER_SHIFT_Y,
                base[0] + DENSE4_MAX_CENTER_SHIFT_Y,
            )
            out[s][1] = candidateU.coerceIn(
                base[1] - DENSE4_MAX_CENTER_SHIFT_UV,
                base[1] + DENSE4_MAX_CENTER_SHIFT_UV,
            )
            out[s][2] = candidateV.coerceIn(
                base[2] - DENSE4_MAX_CENTER_SHIFT_UV,
                base[2] + DENSE4_MAX_CENTER_SHIFT_UV,
            )
        }
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
}
