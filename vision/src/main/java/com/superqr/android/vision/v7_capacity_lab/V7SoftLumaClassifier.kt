package com.superqr.android.vision.v7_capacity_lab

import kotlin.math.abs

/**
 * Allocation-stable monochrome classifier producing hard bits, erasures, and
 * normalized soft likelihoods from black/white reference luma.
 */
class V7SoftLumaClassifier {
    data class Result(
        val cells: Int,
        val classified: Int,
        val erasures: Int,
        val blackY: Int,
        val whiteY: Int,
        val thresholdY: Float,
        val contrastY: Int,
    )

    private var bits = ByteArray(0)
    private var llr = FloatArray(0)

    fun classify(
        samples: IntArray,
        validMask: ByteArray,
        cells: Int,
        blackReferenceY: Int,
        whiteReferenceY: Int,
        erasureMarginFraction: Float = 0.08f,
        minimumContrast: Int = 32,
    ): Result {
        require(cells >= 0 && cells <= samples.size && cells <= validMask.size)
        require(erasureMarginFraction in 0f..0.5f)
        if (bits.size != cells) {
            bits = ByteArray(cells)
            llr = FloatArray(cells)
        }

        val black = minOf(blackReferenceY, whiteReferenceY)
        val white = maxOf(blackReferenceY, whiteReferenceY)
        val contrast = white - black
        val threshold = (black + white) * 0.5f
        val halfSpan = maxOf(1f, contrast * 0.5f)
        val erasureMargin = maxOf(4f, contrast * erasureMarginFraction)
        var classified = 0
        var erasures = 0

        for (index in 0 until cells) {
            val delta = samples[index] - threshold
            llr[index] = (delta / halfSpan).coerceIn(-4f, 4f)
            if (validMask[index].toInt() == 0 || contrast < minimumContrast || abs(delta) < erasureMargin) {
                bits[index] = ERASURE
                erasures++
            } else {
                bits[index] = if (delta >= 0f) 1 else 0
                classified++
            }
        }
        return Result(cells, classified, erasures, black, white, threshold, contrast)
    }

    fun bits(): ByteArray = bits
    fun llr(): FloatArray = llr

    companion object {
        const val ERASURE: Byte = -1
    }
}
