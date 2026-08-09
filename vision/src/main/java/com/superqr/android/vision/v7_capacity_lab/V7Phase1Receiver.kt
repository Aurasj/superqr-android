package com.superqr.android.vision.v7_capacity_lab

import com.superqr.android.vision.v6.classification.ChromaPixelReader
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Laboratory-only rectangular PHY profile. It deliberately is not a V7 wire profile. */
data class V7Phase1GridProfile(
    val name: String,
    val rows: Int,
    val cols: Int,
    val bitsPerCell: Int,
    val rawBytesPerFrame: Int,
    val payloadBbox: DoubleArray = doubleArrayOf(100.0, 190.0, 900.0, 810.0),
)

data class V7Phase1GridObservation(
    val frameIndex: Int?,
    val observedBits: Int,
    val bitErrors: Int,
    val erasedBits: Int,
    val byteErrors: Int,
    val byteErasures: Int,
    val frameValid: Boolean,
    val postFecValid: Boolean,
    val validSamples: Int,
    val blackY: Int,
    val whiteY: Int,
    val errorCellIndexes: IntArray,
    val errorCellCount: Int,
    val erasureCellIndexes: IntArray,
    val erasureCellCount: Int,
)

/**
 * Truth-aware Phase 1 receiver for the five grid candidates.
 *
 * Expected data is generated once on profile selection. Per-camera-frame work is
 * geometry projection, sampling, classification and integer error accounting.
 */
class V7Phase1Receiver(profile: V7Phase1GridProfile, seed: Int = 42) {
    private val sampler = V7HighDensitySampler()
    private val lumaClassifier = V7SoftLumaClassifier()
    private val colorClassifier = V7SoftClassifier()
    private val expectedFrames: Array<ByteArray>
    private val pilotY = IntArray(4)
    private val pilotU = IntArray(4)
    private val pilotV = IntArray(4)
    private val projected = DoubleArray(2)
    private val chromaOut = IntArray(2)
    private val patchValues = IntArray(9)
    private val pilotCenters = arrayOf(
        doubleArrayOf(300.0, 120.0), doubleArrayOf(380.0, 120.0),
        doubleArrayOf(620.0, 120.0), doubleArrayOf(700.0, 120.0),
    )
    private val rsBlockCount = (profile.rawBytesPerFrame + 254) / 255
    private val byteToRsBlock = IntArray(profile.rawBytesPerFrame)
    private val rsBlockErrors = IntArray(rsBlockCount)
    private val rsBlockErasures = IntArray(rsBlockCount)

    var profile: V7Phase1GridProfile = profile
        private set
    val errorHeatmap = IntArray(profile.rows * profile.cols)
    val erasureHeatmap = IntArray(profile.rows * profile.cols)
    private val frameErrorCells = IntArray(profile.rows * profile.cols)
    private val frameErasureCells = IntArray(profile.rows * profile.cols)

    init {
        require(profile.bitsPerCell == 1 || profile.bitsPerCell == 2)
        require(profile.rows * profile.cols * profile.bitsPerCell == profile.rawBytesPerFrame * 8)
        sampler.setGridShape(profile.rows, profile.cols, profile.payloadBbox)
        colorClassifier.setCenters(Array(4) { intArrayOf(128, 128, 128) })
        expectedFrames = generateExpectedFrames(profile, seed)
        var byte = 0
        val baseLength = profile.rawBytesPerFrame / rsBlockCount
        val extraLength = profile.rawBytesPerFrame % rsBlockCount
        for (block in 0 until rsBlockCount) {
            repeat(baseLength + if (block < extraLength) 1 else 0) { byteToRsBlock[byte++] = block }
        }
    }

    fun analyze(
        homographyInv: DoubleArray,
        luma: ByteArray,
        width: Int,
        height: Int,
        chromaReader: ChromaPixelReader? = null,
        fecParityRatio: Double = 0.15,
        synchronizedFrameIndex: Int? = null,
    ): V7Phase1GridObservation {
        require(homographyInv.size >= 9)
        samplePilots(homographyInv, luma, width, height, chromaReader)
        val blackY = pilotY[0]
        val whiteY = pilotY[1]
        val frameIndex = synchronizedFrameIndex
        val cells = profile.rows * profile.cols

        val decoded: ByteArray
        val validSamples: Int
        if (profile.bitsPerCell == 1) {
            validSamples = sampler.sampleLumaPatch9(homographyInv, luma, width, height)
            lumaClassifier.classify(
                sampler.getYLumaPatch9(), sampler.getValidMask(), cells, blackY, whiteY,
            )
            decoded = lumaClassifier.bits()
        } else {
            validSamples = sampler.sampleCross5(homographyInv, luma, width, height, chromaReader)
            for (symbol in 0 until 4) {
                colorClassifier.setCenter(symbol, pilotY[symbol], pilotU[symbol], pilotV[symbol])
            }
            colorClassifier.classify(
                sampler.getYCross5(), sampler.getUCross5(), sampler.getVCross5(), cells,
            )
            decoded = colorClassifier.bestSymbols
        }

        if (frameIndex == null) {
            return V7Phase1GridObservation(
                null, cells * profile.bitsPerCell, 0, cells * profile.bitsPerCell,
                0, profile.rawBytesPerFrame, false, false, validSamples, blackY, whiteY,
                frameErrorCells, 0, frameErasureCells, 0,
            )
        }

        val expected = expectedFrames[frameIndex]
        var bitErrors = 0
        var erasedBits = 0
        var byteErrors = 0
        var byteErasures = 0
        var errorCellCount = 0
        var erasureCellCount = 0
        rsBlockErrors.fill(0)
        rsBlockErasures.fill(0)
        val cellsPerByte = 8 / profile.bitsPerCell
        for (byteIndex in 0 until profile.rawBytesPerFrame) {
            var byteWrong = false
            var byteErased = false
            val firstCell = byteIndex * cellsPerByte
            for (offset in 0 until cellsPerByte) {
                val cell = firstCell + offset
                val actual = decoded[cell].toInt()
                if (actual < 0) {
                    erasedBits += profile.bitsPerCell
                    erasureHeatmap[cell]++
                    frameErasureCells[erasureCellCount++] = cell
                    byteErased = true
                } else if (actual != expected[cell].toInt()) {
                    bitErrors += Integer.bitCount(actual xor expected[cell].toInt())
                    errorHeatmap[cell]++
                    frameErrorCells[errorCellCount++] = cell
                    byteWrong = true
                }
            }
            val block = byteToRsBlock[byteIndex]
            if (byteErased) {
                byteErasures++
                rsBlockErasures[block]++
            } else if (byteWrong) {
                byteErrors++
                rsBlockErrors[block]++
            }
        }
        val parityBytes = ceil(profile.rawBytesPerFrame * fecParityRatio).toInt()
        val parityBase = parityBytes / rsBlockCount
        val parityExtra = parityBytes % rsBlockCount
        val frameValid = bitErrors == 0 && erasedBits == 0
        val postFecValid = (0 until rsBlockCount).all { block ->
            val blockParity = parityBase + if (block < parityExtra) 1 else 0
            2 * rsBlockErrors[block] + rsBlockErasures[block] <= blockParity
        }
        return V7Phase1GridObservation(
            frameIndex, cells * profile.bitsPerCell, bitErrors, erasedBits,
            byteErrors, byteErasures, frameValid, postFecValid, validSamples, blackY, whiteY,
            frameErrorCells, errorCellCount, frameErasureCells, erasureCellCount,
        )
    }

    internal fun expectedSymbols(frameIndex: Int): ByteArray = expectedFrames[frameIndex]

    private fun samplePilots(
        h: DoubleArray,
        luma: ByteArray,
        width: Int,
        height: Int,
        chromaReader: ChromaPixelReader?,
    ) {
        for (symbol in pilotCenters.indices) {
            val c = pilotCenters[symbol]
            pilotY[symbol] = samplePatchMedian(h, c[0], c[1], luma, width, height)
            if (chromaReader != null && project(h, c[0], c[1])) {
                if (chromaReader.read(projected[0], projected[1], chromaOut)) {
                    pilotU[symbol] = chromaOut[0]
                    pilotV[symbol] = chromaOut[1]
                } else {
                    pilotU[symbol] = 128; pilotV[symbol] = 128
                }
            } else {
                pilotU[symbol] = 128; pilotV[symbol] = 128
            }
        }
    }

    private fun samplePatchMedian(
        h: DoubleArray, cx: Double, cy: Double, luma: ByteArray, width: Int, height: Int,
    ): Int {
        var count = 0
        for (dy in -1..1) for (dx in -1..1) {
            val value = sampleLuma(h, cx + dx * 4.0, cy + dy * 4.0, luma, width, height)
            if (value >= 0) patchValues[count++] = value
        }
        if (count == 0) return 128
        for (i in 1 until count) {
            val value = patchValues[i]
            var j = i - 1
            while (j >= 0 && patchValues[j] > value) { patchValues[j + 1] = patchValues[j]; j-- }
            patchValues[j + 1] = value
        }
        return patchValues[count / 2]
    }

    private fun sampleLuma(
        h: DoubleArray, x: Double, y: Double, luma: ByteArray, width: Int, height: Int,
    ): Int {
        if (!project(h, x, y)) return -1
        val px = projected[0].roundToInt()
        val py = projected[1].roundToInt()
        if (px !in 0 until width || py !in 0 until height) return -1
        return luma[py * width + px].toInt() and 0xFF
    }

    private fun project(h: DoubleArray, x: Double, y: Double): Boolean {
        val den = h[6] * x + h[7] * y + h[8]
        if (!den.isFinite() || kotlin.math.abs(den) < 1e-9) return false
        val px = (h[0] * x + h[1] * y + h[2]) / den
        val py = (h[3] * x + h[4] * y + h[5]) / den
        if (!px.isFinite() || !py.isFinite()) return false
        projected[0] = px; projected[1] = py
        return true
    }

    companion object {
        private fun generateExpectedFrames(profile: V7Phase1GridProfile, seed: Int): Array<ByteArray> {
            val prng = V7LabPrng(seed)
            return Array(256) {
                ByteArray(profile.rows * profile.cols) { prng.nextSymbol(profile.bitsPerCell).toByte() }
            }
        }
    }
}
