package com.superqr.android.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisRateAccumulatorTest {
    @Test
    fun emptyAccumulatorReturnsZero() {
        assertEquals(0.0, AnalysisRateAccumulator(16).computeFps(), 1e-9)
    }

    @Test
    fun twoRecordingsOneHundredMsApartProduceTenFps() {
        val accumulator = AnalysisRateAccumulator(16)
        val t0 = 1_000_000_000L
        accumulator.recordCompletion(t0)
        accumulator.recordCompletion(t0 + 100_000_000L)
        assertEquals(10.0, accumulator.computeFps(t0 + 100_000_000L), 0.01)
    }

    @Test
    fun resetClearsMeasurements() {
        val accumulator = AnalysisRateAccumulator(16)
        accumulator.recordCompletion(1_000_000_000L)
        accumulator.recordCompletion(1_100_000_000L)
        accumulator.reset()
        assertEquals(0.0, accumulator.computeFps(), 1e-9)
    }
}
