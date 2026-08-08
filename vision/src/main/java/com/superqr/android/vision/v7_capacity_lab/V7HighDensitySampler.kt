package com.superqr.android.vision.v7_capacity_lab

import com.superqr.android.vision.v6.classification.ChromaPixelReader
import kotlin.math.roundToInt

/**
 * Allocation-light high-density sampler for V7 Capacity Lab.
 *
 * Supports grids 40x40 through 96x96 with two probe modes:
 * - CENTER_1: one center sample per cell
 * - CROSS_5: center + four corner probes inside the central region, median reduction
 *
 * No per-cell Kotlin object allocation. All results go into pre-allocated
 * primitive arrays.
 */
class V7HighDensitySampler {

    enum class ProbeMode { CENTER_1, CROSS_5 }

    /** Current grid size. */
    var gridSize: Int = 40
        private set

    /** Total cell count (gridSize * gridSize). */
    var totalCells: Int = 1600
        private set

    // Precomputed canonical (cx, cy) pairs for all cells, row-major.
    private var canonicalX = FloatArray(0)
    private var canonicalY = FloatArray(0)

    // CROSS_5 offsets relative to cell center, in canonical units.
    private var crossOffset = 0f

    // ---- Change grid ----

    fun setGridSize(newGridSize: Int, payloadBbox: DoubleArray) {
        require(newGridSize >= 1) { "Grid size must be positive" }
        if (newGridSize == gridSize && canonicalX.size == newGridSize * newGridSize) return

        gridSize = newGridSize
        totalCells = gridSize * gridSize

        val cellSize = (payloadBbox[2] - payloadBbox[0]) / gridSize
        val gridX0 = payloadBbox[0]
        val gridY0 = payloadBbox[1]

        canonicalX = FloatArray(totalCells)
        canonicalY = FloatArray(totalCells)

        var idx = 0
        for (r in 0 until gridSize) {
            val cy = (gridY0 + (r + 0.5f) * cellSize).toFloat()
            for (c in 0 until gridSize) {
                canonicalX[idx] = (gridX0 + (c + 0.5f) * cellSize).toFloat()
                canonicalY[idx] = cy
                idx++
            }
        }

        // CROSS_5: probe at center ± cellSize * 0.15 / 2
        crossOffset = (cellSize * 0.15f / 2.0f).toFloat()
    }

    // ---- Sample buffers (allocated once per grid change) ----

    // CENTER_1 output arrays
    private var sampleY = IntArray(0)
    private var sampleU = IntArray(0)
    private var sampleV = IntArray(0)

    // CROSS_5 output arrays
    private var sample5Y = IntArray(0)
    private var sample5U = IntArray(0)
    private var sample5V = IntArray(0)

    // Reusable per-cell scratch for CROSS_5 median computation
    private val probeY5 = IntArray(5)
    private val probeU5 = IntArray(5)
    private val probeV5 = IntArray(5)

    // Reusable DoubleArray for homography projection result
    private val tmpXY = DoubleArray(2)

    private fun ensureSampleArrays() {
        if (sampleY.size != totalCells) {
            sampleY = IntArray(totalCells)
            sampleU = IntArray(totalCells)
            sampleV = IntArray(totalCells)
            sample5Y = IntArray(totalCells)
            sample5U = IntArray(totalCells)
            sample5V = IntArray(totalCells)
        }
    }

    // ---- Sampling methods ----

    /**
     * Sample all cells using CENTER_1 mode (single center probe).
     *
     * Writes results into the internal sampleY/sampleU/sampleV arrays.
     * Returns the number of valid samples (samples where the projected
     * coordinate fell within bounds and chroma was readable).
     *
     * @param homographyInv 9-element inverse homography: canonical→camera
     * @param lumaBytes rotation-normalized luma buffer
     * @param lumaWidth width of luma buffer
     * @param lumaHeight height of luma buffer
     * @param chromaReader optional chroma reader (null = Y-only sampling)
     */
    fun sampleCenter1(
        homographyInv: DoubleArray,
        lumaBytes: ByteArray,
        lumaWidth: Int,
        lumaHeight: Int,
        chromaReader: ChromaPixelReader?
    ): Int {
        ensureSampleArrays()
        val h = homographyInv
        val h0 = h[0]; val h1 = h[1]; val h2 = h[2]
        val h3 = h[3]; val h4 = h[4]; val h5 = h[5]
        val h6 = h[6]; val h7 = h[7]; val h8 = h[8]

        var validCount = 0
        val chromaBuf = IntArray(2)

        for (i in 0 until totalCells) {
            val cx = canonicalX[i].toDouble()
            val cy = canonicalY[i].toDouble()

            // Homography projection: canonical → camera
            val den = h6 * cx + h7 * cy + h8
            val ix = (h0 * cx + h1 * cy + h2) / den
            val iy = (h3 * cx + h4 * cy + h5) / den

            val px = ix.roundToInt()
            val py = iy.roundToInt()

            // Read Y
            if (px in 0 until lumaWidth && py in 0 until lumaHeight) {
                sampleY[i] = lumaBytes[py * lumaWidth + px].toInt() and 0xFF
            } else {
                sampleY[i] = 128
            }

            // Read U/V
            if (chromaReader != null && chromaReader.read(ix, iy, chromaBuf)) {
                sampleU[i] = chromaBuf[0]
                sampleV[i] = chromaBuf[1]
                validCount++
            } else {
                sampleU[i] = 128
                sampleV[i] = 128
            }
        }
        return validCount
    }

    /**
     * Sample all cells using CROSS_5 mode (center + 4 corner probes).
     * Uses median5 reduction per channel.
     *
     * Writes results into the internal sample5Y/sample5U/sample5V arrays.
     */
    fun sampleCross5(
        homographyInv: DoubleArray,
        lumaBytes: ByteArray,
        lumaWidth: Int,
        lumaHeight: Int,
        chromaReader: ChromaPixelReader?
    ): Int {
        ensureSampleArrays()
        val h = homographyInv
        val h0 = h[0]; val h1 = h[1]; val h2 = h[2]
        val h3 = h[3]; val h4 = h[4]; val h5 = h[5]
        val h6 = h[6]; val h7 = h[7]; val h8 = h[8]
        val offset = crossOffset.toDouble()

        var validCount = 0
        val chromaBuf = IntArray(2)

        for (i in 0 until totalCells) {
            val cx = canonicalX[i].toDouble()
            val cy = canonicalY[i].toDouble()

            // 5 probe positions
            val probesX = doubleArrayOf(cx, cx - offset, cx + offset, cx - offset, cx + offset)
            val probesY = doubleArrayOf(cy, cy - offset, cy - offset, cy + offset, cy + offset)

            for (p in 0 until 5) {
                val den = h6 * probesX[p] + h7 * probesY[p] + h8
                val ix = (h0 * probesX[p] + h1 * probesY[p] + h2) / den
                val iy = (h3 * probesX[p] + h4 * probesY[p] + h5) / den

                val px = ix.roundToInt()
                val py = iy.roundToInt()

                if (px in 0 until lumaWidth && py in 0 until lumaHeight) {
                    probeY5[p] = lumaBytes[py * lumaWidth + px].toInt() and 0xFF
                } else {
                    probeY5[p] = 128
                }

                if (chromaReader != null && chromaReader.read(ix, iy, chromaBuf)) {
                    probeU5[p] = chromaBuf[0]
                    probeV5[p] = chromaBuf[1]
                } else {
                    probeU5[p] = 128
                    probeV5[p] = 128
                }
            }

            sample5Y[i] = median5(probeY5[0], probeY5[1], probeY5[2], probeY5[3], probeY5[4])
            sample5U[i] = median5(probeU5[0], probeU5[1], probeU5[2], probeU5[3], probeU5[4])
            sample5V[i] = median5(probeV5[0], probeV5[1], probeV5[2], probeV5[3], probeV5[4])
            validCount++
        }
        return validCount
    }

    /**
     * Get the Y values from the last CENTER_1 sample call.
     * The array is owned by the sampler — do not modify.
     */
    fun getYCenters(): IntArray = sampleY
    fun getUCenters(): IntArray = sampleU
    fun getVCenters(): IntArray = sampleV

    /** Get the Y values from the last CROSS_5 sample call. */
    fun getYCross5(): IntArray = sample5Y
    fun getUCross5(): IntArray = sample5U
    fun getVCross5(): IntArray = sample5V

    /** Get precomputed canonical X positions. */
    fun getCanonicalX(): FloatArray = canonicalX
    /** Get precomputed canonical Y positions. */
    fun getCanonicalY(): FloatArray = canonicalY

    companion object {
        /** Allocation-free 5-element median via 3-pass bubble-min. */
        fun median5(v0: Int, v1: Int, v2: Int, v3: Int, v4: Int): Int {
            var a = v0; var b = v1; var c = v2; var d = v3; var e = v4
            if (a > b) { val t = a; a = b; b = t }
            if (a > c) { val t = a; a = c; c = t }
            if (a > d) { val t = a; a = d; d = t }
            if (a > e) { val t = a; a = e; e = t }
            if (b > c) { val t = b; b = c; c = t }
            if (b > d) { val t = b; b = d; d = t }
            if (b > e) { val t = b; b = e; e = t }
            if (c > d) { val t = c; c = d; d = t }
            if (c > e) { val t = c; c = e; e = t }
            return c
        }
    }
}
