package com.superqr.android.vision.v7.transport

import com.superqr.android.vision.v7_capacity_lab.V7HighDensitySampler
import com.superqr.android.vision.v7_capacity_lab.V7SoftClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V7ProductionColorTuningTest {

    @Test
    fun `luma weighted metric can reject chroma alias that fools equal weighting`() {
        val centers = arrayOf(
            intArrayOf(50, 128, 132),   // black
            intArrayOf(237, 127, 128),  // white
            intArrayOf(127, 100, 205),  // red
            intArrayOf(94, 192, 126),   // blue
        )
        val sampleY = intArrayOf(76)
        val sampleU = intArrayOf(126)
        val sampleV = intArrayOf(138)

        val equal = V7SoftClassifier().also {
            it.setCenters(centers)
            it.setCellCount(1)
            it.marginThreshold = 0.99
        }
        equal.classify(sampleY, sampleU, sampleV, 1)

        val production = V7SoftClassifier().also {
            it.setCenters(centers)
            it.setCellCount(1)
            it.setChannelWeights(10, 1, 1)
            it.marginThreshold = 0.99
        }
        production.classify(sampleY, sampleU, sampleV, 1)

        assertEquals(0, production.bestSymbols[0].toInt())
        assertTrue(production.bestDistances[0] < production.secondBestDistances[0])
    }

    @Test
    fun `cross probes occupy meaningful central area of cell`() {
        val sampler = V7HighDensitySampler()
        sampler.setGridSize(56, doubleArrayOf(100.0, 190.0, 900.0, 810.0))

        assertEquals(sampler.getCellWidth() * V7HighDensitySampler.CROSS_OFFSET_FRACTION, sampler.getCrossOffsetX(), 1e-5f)
        assertEquals(sampler.getCellHeight() * V7HighDensitySampler.CROSS_OFFSET_FRACTION, sampler.getCrossOffsetY(), 1e-5f)
        assertTrue(V7HighDensitySampler.CROSS_OFFSET_FRACTION >= 0.20f)
        assertTrue(V7HighDensitySampler.CROSS_OFFSET_FRACTION < 0.50f)
    }
}
