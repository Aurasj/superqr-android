package com.superqr.android.vision.v7_capacity_lab

/**
 * Experimental soft color classifier for the V7 Capacity Lab.
 *
 * For each cell, computes distances to observed (calibrated) YUV centers
 * for every palette symbol. Produces best/2nd symbol, distances, margin,
 * and an explicit erasure decision.
 *
 * Thresholds are experiment-configurable. No per-cell object allocation
 * in the FAST path — results go into parallel primitive arrays.
 *
 * ERASURE_MARKER = -1. Uncertain cells are NEVER coerced to symbol 0.
 */
class V7SoftClassifier {

    companion object {
        const val ERASURE_MARKER: Byte = -1
    }

    /** Palette size (4 or 8). */
    var paletteSize: Int = 4
        private set

    /** Calibrated YUV centers: centers[symbolIdx] = IntArray(3) { Y, U, V } */
    private var centers: Array<IntArray> = emptyArray()

    /** Whether each symbol has been calibrated. */
    private var calibrated: BooleanArray = BooleanArray(0)

    // ---- Output arrays (allocated once) ----
    var bestSymbols: ByteArray = ByteArray(0)
        private set
    var secondBestSymbols: ByteArray = ByteArray(0)
        private set
    var bestDistances: IntArray = IntArray(0)
        private set
    var secondBestDistances: IntArray = IntArray(0)
        private set

    /**
     * Distance and margin thresholds for erasure decisions.
     *
     * maxDistanceThreshold: if best_distance > this, cell is erased.
     * marginThreshold: if bestDist/secondBestDist > this (when secondBestDist > 0),
     *   cell is erased (too ambiguous).
     */
    var maxDistanceThreshold: Int = 40000
    var marginThreshold: Double = 0.9

    // ---- Calibration ----

    fun setCenters(newCenters: Array<IntArray>) {
        paletteSize = newCenters.size
        centers = newCenters
        calibrated = BooleanArray(paletteSize) { true }
        ensureOutputArrays()
    }

    fun setCenter(symbolIdx: Int, y: Int, u: Int, v: Int) {
        require(symbolIdx in 0 until paletteSize)
        centers[symbolIdx] = intArrayOf(y, u, v)
        calibrated[symbolIdx] = true
    }

    fun isCalibrated(symbolIdx: Int): Boolean {
        return symbolIdx in 0 until paletteSize && calibrated[symbolIdx]
    }

    fun isFullyCalibrated(): Boolean {
        return calibrated.all { it }
    }

    fun resetCalibration() {
        calibrated.fill(false)
        centers = Array(paletteSize) { intArrayOf(128, 128, 128) }
    }

    // ---- Classification ----

    private fun ensureOutputArrays() {
        // Output arrays are sized by the sampler's totalCells; we set them
        // from the receiver when grid size is known.
    }

    fun setCellCount(count: Int) {
        if (bestSymbols.size != count) {
            bestSymbols = ByteArray(count)
            secondBestSymbols = ByteArray(count)
            bestDistances = IntArray(count)
            secondBestDistances = IntArray(count)
        }
    }

    /**
     * Classify all cells given sampled Y/U/V arrays.
     *
     * Writes best/2nd symbols, distances, and erasures into the output arrays.
     * Erased cells have bestSymbols[i] = ERASURE_MARKER.
     *
     * @param yArr sampled Y values (size = totalCells)
     * @param uArr sampled U values
     * @param vArr sampled V values
     * @param totalCells number of cells
     */
    fun classify(yArr: IntArray, uArr: IntArray, vArr: IntArray, totalCells: Int) {
        setCellCount(totalCells)

        for (i in 0 until totalCells) {
            val y = yArr[i]
            val u = uArr[i]
            val v = vArr[i]

            // Compute distances to all calibrated centers
            var bestIdx = 0
            var bestDist = Int.MAX_VALUE
            var secondIdx = 0
            var secondDist = Int.MAX_VALUE

            for (s in 0 until paletteSize) {
                if (!calibrated[s]) continue
                val c = centers[s]
                val dy = y - c[0]
                val du = u - c[1]
                val dv = v - c[2]
                val dist = dy * dy + du * du + dv * dv

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

            // Erasure decision
            if (bestDist > maxDistanceThreshold) {
                bestSymbols[i] = ERASURE_MARKER
            } else if (secondDist > 0 && secondDist < Int.MAX_VALUE &&
                bestDist.toDouble() / secondDist.toDouble() > marginThreshold) {
                bestSymbols[i] = ERASURE_MARKER
            } else {
                bestSymbols[i] = bestIdx.toByte()
            }
        }
    }

    /**
     * Compute confidence margin for a cell.
     * Returns 0.0 if margin cannot be computed (single calibrated symbol, etc.).
     */
    fun confidenceMargin(cellIdx: Int): Double {
        val best = bestDistances[cellIdx]
        val second = secondBestDistances[cellIdx]
        if (second <= 0 || second >= Int.MAX_VALUE) return 0.0
        return best.toDouble() / second.toDouble()
    }

    /** Alias for ERASURE_MARKER for consistency with protocol naming. */
    fun isErasure(cellIdx: Int): Boolean = bestSymbols[cellIdx] == ERASURE_MARKER
}
