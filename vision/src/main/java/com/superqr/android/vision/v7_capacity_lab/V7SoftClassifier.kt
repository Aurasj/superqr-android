package com.superqr.android.vision.v7_capacity_lab

/**
 * Allocation-light soft YUV classifier shared by the V7 lab and production receiver.
 *
 * CameraX YUV_420 chroma is spatially lower resolution than luma. Production V7 can
 * therefore weight Y more strongly than U/V while keeping the original distance
 * scale (equal weights still produce the original Euclidean squared distance).
 */
class V7SoftClassifier {

    companion object {
        const val ERASURE_MARKER: Byte = -1
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

    fun setChannelWeights(y: Int, u: Int, v: Int) {
        require(y > 0 && u > 0 && v > 0) { "channel weights must be positive" }
        yWeight = y
        uWeight = u
        vWeight = v
    }

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

    fun isCalibrated(symbolIdx: Int): Boolean =
        symbolIdx in 0 until paletteSize && calibrated[symbolIdx]

    fun isFullyCalibrated(): Boolean = calibrated.all { it }

    fun resetCalibration() {
        calibrated.fill(false)
        centers = Array(paletteSize) { intArrayOf(128, 128, 128) }
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

    fun classify(yArr: IntArray, uArr: IntArray, vArr: IntArray, totalCells: Int) {
        setCellCount(totalCells)
        val weightSum = yWeight + uWeight + vWeight

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
                val c = centers[s]
                val dy = y - c[0]
                val du = u - c[1]
                val dv = v - c[2]
                // Normalize back to the old three-channel squared-distance scale.
                val weighted =
                    yWeight.toLong() * dy * dy +
                    uWeight.toLong() * du * du +
                    vWeight.toLong() * dv * dv
                val dist = ((weighted * 3L) / weightSum.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

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

            bestSymbols[i] = when {
                bestDist > maxDistanceThreshold -> ERASURE_MARKER
                secondDist > 0 && secondDist < Int.MAX_VALUE &&
                    bestDist.toDouble() / secondDist.toDouble() > marginThreshold -> ERASURE_MARKER
                else -> bestIdx.toByte()
            }
        }
    }

    fun confidenceMargin(cellIdx: Int): Double {
        val best = bestDistances[cellIdx]
        val second = secondBestDistances[cellIdx]
        if (second <= 0 || second >= Int.MAX_VALUE) return 0.0
        return best.toDouble() / second.toDouble()
    }

    fun isErasure(cellIdx: Int): Boolean = bestSymbols[cellIdx] == ERASURE_MARKER
}
