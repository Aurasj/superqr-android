package com.superqr.android.vision.v7_capacity_lab

import com.superqr.android.vision.v6.classification.ChromaPixelReader
import kotlin.math.roundToInt

/**
 * Allocation-light high-density sampler used by production V7 and Capacity Lab.
 *
 * Payloads may be rectangular. X and Y cell pitch are computed independently.
 * CROSS_5 deliberately spreads probes across the central part of each cell so
 * they reach distinct YUV_420 chroma samples without approaching cell borders.
 *
 * Hot-path rule: no per-cell/per-probe object allocation. At 40x40 CROSS_5 the
 * old Pair-returning projection + median scratch code created thousands of small
 * objects every camera frame and produced avoidable GC pressure.
 */
class V7HighDensitySampler {

    enum class ProbeMode { CENTER_1, CROSS_5 }

    var gridSize: Int = 40
        private set
    var totalCells: Int = 1600
        private set

    private var canonicalX = FloatArray(0)
    private var canonicalY = FloatArray(0)
    private var cellWidth = 0f
    private var cellHeight = 0f
    private var crossOffsetX = 0f
    private var crossOffsetY = 0f

    private var sampleY = IntArray(0)
    private var sampleU = IntArray(0)
    private var sampleV = IntArray(0)
    private var sample5Y = IntArray(0)
    private var sample5U = IntArray(0)
    private var sample5V = IntArray(0)
    private var validMask = ByteArray(0)
    // CENTER_1 uses bit 0. CROSS_5 uses bits 0..4 for center/TL/TR/BL/BR.
    private var probeValidityMask = ByteArray(0)

    private val probeY5 = IntArray(5)
    private val probeU5 = IntArray(5)
    private val probeV5 = IntArray(5)
    private val probeValid5 = BooleanArray(5)
    private val chromaBuf = IntArray(2)
    private val projected = DoubleArray(2)
    private val medianScratch = IntArray(5)

    fun setGridSize(newGridSize: Int, payloadBbox: DoubleArray) {
        require(newGridSize >= 1) { "Grid size must be positive" }
        require(payloadBbox.size >= 4) { "payload bbox must have four values" }
        require(payloadBbox[2] > payloadBbox[0] && payloadBbox[3] > payloadBbox[1]) { "invalid payload bbox" }

        gridSize = newGridSize
        totalCells = gridSize * gridSize
        cellWidth = ((payloadBbox[2] - payloadBbox[0]) / gridSize).toFloat()
        cellHeight = ((payloadBbox[3] - payloadBbox[1]) / gridSize).toFloat()
        crossOffsetX = cellWidth * CROSS_OFFSET_FRACTION
        crossOffsetY = cellHeight * CROSS_OFFSET_FRACTION

        canonicalX = FloatArray(totalCells)
        canonicalY = FloatArray(totalCells)
        var idx = 0
        for (r in 0 until gridSize) {
            val cy = (payloadBbox[1] + (r + 0.5) * cellHeight).toFloat()
            for (c in 0 until gridSize) {
                canonicalX[idx] = (payloadBbox[0] + (c + 0.5) * cellWidth).toFloat()
                canonicalY[idx] = cy
                idx++
            }
        }
        ensureSampleArrays()
    }

    private fun ensureSampleArrays() {
        if (sampleY.size == totalCells) return
        sampleY = IntArray(totalCells)
        sampleU = IntArray(totalCells)
        sampleV = IntArray(totalCells)
        sample5Y = IntArray(totalCells)
        sample5U = IntArray(totalCells)
        sample5V = IntArray(totalCells)
        validMask = ByteArray(totalCells)
        probeValidityMask = ByteArray(totalCells)
    }

    fun sampleCenter1(
        homographyInv: DoubleArray,
        lumaBytes: ByteArray,
        lumaWidth: Int,
        lumaHeight: Int,
        chromaReader: ChromaPixelReader?
    ): Int {
        ensureSampleArrays()
        validMask.fill(0)
        probeValidityMask.fill(0)
        val h = homographyInv
        var validCount = 0

        for (i in 0 until totalCells) {
            if (!projectInto(h, canonicalX[i].toDouble(), canonicalY[i].toDouble(), projected)) {
                sampleY[i] = 128; sampleU[i] = 128; sampleV[i] = 128
                continue
            }
            val ix = projected[0]
            val iy = projected[1]
            val px = ix.roundToInt()
            val py = iy.roundToInt()
            if (px !in 0 until lumaWidth || py !in 0 until lumaHeight) {
                sampleY[i] = 128; sampleU[i] = 128; sampleV[i] = 128
                continue
            }

            sampleY[i] = lumaBytes[py * lumaWidth + px].toInt() and 0xFF
            if (chromaReader == null) {
                sampleU[i] = 128; sampleV[i] = 128
                validMask[i] = 1; probeValidityMask[i] = 1; validCount++
            } else if (chromaReader.read(ix, iy, chromaBuf)) {
                sampleU[i] = chromaBuf[0]
                sampleV[i] = chromaBuf[1]
                validMask[i] = 1
                probeValidityMask[i] = 1
                validCount++
            } else {
                sampleU[i] = 128
                sampleV[i] = 128
            }
        }
        return validCount
    }

    fun sampleCross5(
        homographyInv: DoubleArray,
        lumaBytes: ByteArray,
        lumaWidth: Int,
        lumaHeight: Int,
        chromaReader: ChromaPixelReader?
    ): Int {
        ensureSampleArrays()
        validMask.fill(0)
        probeValidityMask.fill(0)
        val h = homographyInv
        val ox = crossOffsetX.toDouble()
        val oy = crossOffsetY.toDouble()
        var validCount = 0

        for (i in 0 until totalCells) {
            val cx = canonicalX[i].toDouble()
            val cy = canonicalY[i].toDouble()
            var validProbes = 0
            var probeBits = 0

            for (p in 0 until 5) {
                val dx = when (p) { 1, 3 -> -ox; 2, 4 -> ox; else -> 0.0 }
                val dy = when (p) { 1, 2 -> -oy; 3, 4 -> oy; else -> 0.0 }
                if (!projectInto(h, cx + dx, cy + dy, projected)) {
                    probeY5[p] = 128; probeU5[p] = 128; probeV5[p] = 128; probeValid5[p] = false
                    continue
                }
                val ix = projected[0]
                val iy = projected[1]
                val px = ix.roundToInt()
                val py = iy.roundToInt()
                val inBounds = px in 0 until lumaWidth && py in 0 until lumaHeight
                probeY5[p] = if (inBounds) lumaBytes[py * lumaWidth + px].toInt() and 0xFF else 128

                when {
                    !inBounds -> {
                        probeU5[p] = 128; probeV5[p] = 128; probeValid5[p] = false
                    }
                    chromaReader == null -> {
                        probeU5[p] = 128; probeV5[p] = 128; probeValid5[p] = true
                        validProbes++; probeBits = probeBits or (1 shl p)
                    }
                    chromaReader.read(ix, iy, chromaBuf) -> {
                        probeU5[p] = chromaBuf[0]
                        probeV5[p] = chromaBuf[1]
                        probeValid5[p] = true
                        validProbes++
                        probeBits = probeBits or (1 shl p)
                    }
                    else -> {
                        probeU5[p] = 128; probeV5[p] = 128; probeValid5[p] = false
                    }
                }
            }
            probeValidityMask[i] = probeBits.toByte()

            if (validProbes >= 3) {
                sample5Y[i] = medianValid5(probeY5, probeValid5, medianScratch)
                sample5U[i] = medianValid5(probeU5, probeValid5, medianScratch)
                sample5V[i] = medianValid5(probeV5, probeValid5, medianScratch)
                validMask[i] = 1
                validCount++
            } else {
                sample5Y[i] = 128; sample5U[i] = 128; sample5V[i] = 128
            }
        }
        return validCount
    }

    /** Project one canonical coordinate into camera space without allocating Pair. */
    private fun projectInto(h: DoubleArray, x: Double, y: Double, out: DoubleArray): Boolean {
        val den = h[6] * x + h[7] * y + h[8]
        if (!den.isFinite() || kotlin.math.abs(den) < 1e-9) return false
        val ix = (h[0] * x + h[1] * y + h[2]) / den
        val iy = (h[3] * x + h[4] * y + h[5]) / den
        if (!ix.isFinite() || !iy.isFinite()) return false
        out[0] = ix
        out[1] = iy
        return true
    }

    fun getYCenters(): IntArray = sampleY
    fun getUCenters(): IntArray = sampleU
    fun getVCenters(): IntArray = sampleV
    fun getYCross5(): IntArray = sample5Y
    fun getUCross5(): IntArray = sample5U
    fun getVCross5(): IntArray = sample5V
    fun getValidMask(): ByteArray = validMask
    fun getProbeValidityMask(): ByteArray = probeValidityMask
    fun getCanonicalX(): FloatArray = canonicalX
    fun getCanonicalY(): FloatArray = canonicalY
    fun getCellWidth(): Float = cellWidth
    fun getCellHeight(): Float = cellHeight
    fun getCrossOffsetX(): Float = crossOffsetX
    fun getCrossOffsetY(): Float = crossOffsetY

    companion object {
        /**
         * ±14% from cell center. Measured against captured phone footage: this was
         * far enough to hit distinct 4:2:0 chroma samples while remaining safely
         * inside dense optical cells.
         */
        const val CROSS_OFFSET_FRACTION: Float = 0.14f

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

        private fun medianValid5(values: IntArray, valid: BooleanArray, scratch: IntArray): Int {
            var n = 0
            for (i in 0 until 5) if (valid[i]) scratch[n++] = values[i]
            for (i in 1 until n) {
                val value = scratch[i]
                var j = i - 1
                while (j >= 0 && scratch[j] > value) { scratch[j + 1] = scratch[j]; j-- }
                scratch[j + 1] = value
            }
            return scratch[n / 2]
        }
    }
}
