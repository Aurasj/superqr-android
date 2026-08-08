package com.superqr.android.vision.v7_capacity_lab

/**
 * Experimental calibration for V7 Capacity Lab.
 *
 * Maintains observed camera-space YUV centers per palette symbol.
 * Supports calibration from:
 * - V6 carrier reference pilots (BLACK/WHITE/RED/BLUE)
 * - Solid calibration frames from the Capacity Lab sequence
 *
 * Does NOT freeze a final V7 calibration algorithm.
 */
class V7Calibrator(paletteSize: Int) {

    /** Number of symbols in the palette. */
    var paletteSize: Int = paletteSize
        private set

    /** Observed YUV centers per symbol index. [symbolIdx][0]=Y, [1]=U, [2]=V */
    val centers: Array<IntArray> = Array(paletteSize) { intArrayOf(128, 128, 128) }

    /** Whether each symbol has been calibrated. */
    val calibrated: BooleanArray = BooleanArray(paletteSize)

    /** Sample counts per symbol (for averaging). */
    private val sampleCounts: IntArray = IntArray(paletteSize)

    // ---- Direct center setting (from V6 pilots) ----

    fun setPilotCenter(symbolIdx: Int, y: Int, u: Int, v: Int) {
        require(symbolIdx in 0 until paletteSize)
        centers[symbolIdx][0] = y
        centers[symbolIdx][1] = u
        centers[symbolIdx][2] = v
        calibrated[symbolIdx] = true
        sampleCounts[symbolIdx] = 1
    }

    // ---- Calibration from solid frames ----

    /**
     * Learn a symbol's center from a solid calibration frame.
     * Takes the median Y/U/V across the entire sampled grid (all cells
     * being the same symbol in a solid calibration frame).
     */
    fun calibrateFromSolidFrame(
        symbolIdx: Int,
        ySamples: IntArray,
        uSamples: IntArray,
        vSamples: IntArray,
        totalCells: Int
    ) {
        require(symbolIdx in 0 until paletteSize)
        require(ySamples.size >= totalCells)

        // Median across all cells for robust center estimation
        val ys = ySamples.copyOf(totalCells)
        val us = uSamples.copyOf(totalCells)
        val vs = vSamples.copyOf(totalCells)
        ys.sort()
        us.sort()
        vs.sort()
        val mid = totalCells / 2
        centers[symbolIdx][0] = ys[mid]
        centers[symbolIdx][1] = us[mid]
        centers[symbolIdx][2] = vs[mid]
        calibrated[symbolIdx] = true
        sampleCounts[symbolIdx] = totalCells
    }

    /**
     * Accumulate a running average for a symbol center.
     * Uses exponential moving average to track slow drift.
     */
    fun updateCenterEMA(symbolIdx: Int, y: Int, u: Int, v: Int, alpha: Double = 0.1) {
        require(symbolIdx in 0 until paletteSize)
        val c = centers[symbolIdx]
        c[0] = (c[0] * (1.0 - alpha) + y * alpha).toInt()
        c[1] = (c[1] * (1.0 - alpha) + u * alpha).toInt()
        c[2] = (c[2] * (1.0 - alpha) + v * alpha).toInt()
        calibrated[symbolIdx] = true
        sampleCounts[symbolIdx]++
    }

    fun isCalibrated(symbolIdx: Int): Boolean =
        symbolIdx in 0 until paletteSize && calibrated[symbolIdx]

    fun isFullyCalibrated(): Boolean = calibrated.all { it }

    fun reset() {
        for (s in 0 until paletteSize) {
            centers[s][0] = 128
            centers[s][1] = 128
            centers[s][2] = 128
            calibrated[s] = false
            sampleCounts[s] = 0
        }
    }

    fun calibratedCount(): Int = calibrated.count { it }

    fun getSampleCount(symbolIdx: Int): Int =
        if (symbolIdx in 0 until paletteSize) sampleCounts[symbolIdx] else 0

    /** Return a snapshot of current centers (defensive copy). */
    fun getCentersSnapshot(): Array<IntArray> = Array(paletteSize) { centers[it].copyOf() }
}
