package com.superqr.android.colorgrid8

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorGrid8RunEstimatorTest {
    @Test
    fun droppedFramesConsumeTheSameRedundancyBudget() {
        val estimate = ColorGrid8RunEstimator.estimate(
            postFecBudgetKibS = 202.7,
            channelFecLoad = 0.05,
            frameDeliveryRatio = 0.90,
            redundancyFraction = 0.20,
        )
        // 10% full-frame erasures + 90% * 5% received-frame load = 14.5%.
        assertEquals(0.145, estimate.totalFecLoad, 1e-12)
        assertEquals(202.7, estimate.estimatedPostFecKibS, 1e-12)
    }

    @Test
    fun excessiveFrameLossReducesEstimatedGoodput() {
        val estimate = ColorGrid8RunEstimator.estimate(
            postFecBudgetKibS = 202.7,
            channelFecLoad = 0.05,
            frameDeliveryRatio = 0.70,
            redundancyFraction = 0.20,
        )
        assertEquals(0.335, estimate.totalFecLoad, 1e-12)
        assertTrue(estimate.estimatedPostFecKibS < 130.0)
    }

    @Test
    fun continuityIgnoresDuplicatesAndCountsSkippedLogicalFrames() {
        val continuity = ColorGrid8FrameContinuity()
        assertTrue(continuity.observe(10))
        assertEquals(false, continuity.observe(10))
        assertTrue(continuity.observe(11))
        assertTrue(continuity.observe(13))
        assertEquals(2L, continuity.deliveredTransitions)
        assertEquals(3L, continuity.sentTransitions)
        assertEquals(2.0 / 3.0, continuity.deliveryRatio, 1e-12)
    }

    @Test
    fun smallBackwardJumpStartsANewRunInsteadOfHugeLoss() {
        val continuity = ColorGrid8FrameContinuity()
        continuity.observe(250)
        continuity.observe(251)
        continuity.observe(0)
        assertEquals(0L, continuity.sentTransitions)
        assertEquals(1.0, continuity.deliveryRatio, 0.0)
    }
}
