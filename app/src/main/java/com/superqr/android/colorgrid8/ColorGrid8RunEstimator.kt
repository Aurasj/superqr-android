package com.superqr.android.colorgrid8

import kotlin.math.max

/** Run-level LAB estimate that combines cell corruption with whole-frame loss. */
data class ColorGrid8RunEstimate(
    val frameDeliveryRatio: Double,
    val frameLossRate: Double,
    val channelFecLoad: Double,
    val totalFecLoad: Double,
    val estimatedPostFecKibS: Double,
)

object ColorGrid8RunEstimator {
    /**
     * Treat a completely missed logical frame as 100% erasure load for that frame.
     * Received frames contribute their measured `2*errors + erasures` load.
     *
     * total load = missed fraction + received fraction * received-frame load
     */
    fun estimate(
        postFecBudgetKibS: Double,
        channelFecLoad: Double,
        frameDeliveryRatio: Double,
        redundancyFraction: Double = 0.20,
    ): ColorGrid8RunEstimate {
        require(postFecBudgetKibS >= 0.0)
        require(redundancyFraction > 0.0 && redundancyFraction < 1.0)
        val delivery = frameDeliveryRatio.coerceIn(0.0, 1.0)
        val channelLoad = max(0.0, channelFecLoad)
        val loss = 1.0 - delivery
        val totalLoad = loss + delivery * channelLoad
        val estimated = if (totalLoad <= redundancyFraction) {
            postFecBudgetKibS
        } else {
            postFecBudgetKibS * (redundancyFraction / totalLoad).coerceIn(0.0, 1.0)
        }
        return ColorGrid8RunEstimate(
            frameDeliveryRatio = delivery,
            frameLossRate = loss,
            channelFecLoad = channelLoad,
            totalFecLoad = totalLoad,
            estimatedPostFecKibS = estimated,
        )
    }
}

/**
 * Infer logical-frame delivery directly from the sender's 16-bit frame index.
 * Duplicate camera observations have delta 0 and do not improve delivery.
 */
class ColorGrid8FrameContinuity {
    private var lastFrame: Int? = null
    var deliveredTransitions: Long = 0
        private set
    var sentTransitions: Long = 0
        private set

    val deliveryRatio: Double
        get() = if (sentTransitions > 0) {
            (deliveredTransitions.toDouble() / sentTransitions.toDouble()).coerceIn(0.0, 1.0)
        } else {
            1.0
        }

    fun reset() {
        lastFrame = null
        deliveredTransitions = 0
        sentTransitions = 0
    }

    /** Returns true when this observation advances to a new logical frame. */
    fun observe(frameIndex: Int): Boolean {
        val current = frameIndex and 0xFFFF
        val previous = lastFrame
        if (previous == null) {
            lastFrame = current
            return true
        }
        if (current == previous) return false

        // A small backwards jump is a sender/test restart, not a 65k-frame loss.
        if (current < previous && previous - current < 0x8000) {
            lastFrame = current
            deliveredTransitions = 0
            sentTransitions = 0
            return true
        }

        val delta = (current - previous) and 0xFFFF
        if (delta <= 0) return false
        sentTransitions += delta.toLong()
        deliveredTransitions += 1L
        lastFrame = current
        return true
    }
}
