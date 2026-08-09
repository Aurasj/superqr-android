package com.superqr.android.camera

import org.junit.Assert.*
import org.junit.Test

class V7AnalysisRateAccumulatorTest {

    @Test
    fun `empty accumulator returns zero`() {
        val acc = V7AnalysisRateAccumulator(16)
        assertEquals(0.0, acc.computeFps(), 1e-9)
    }

    @Test
    fun `single recording returns zero`() {
        val acc = V7AnalysisRateAccumulator(16)
        acc.recordCompletion(1_000_000_000L)
        assertEquals(0.0, acc.computeFps(1_100_000_000L), 1e-9)
    }

    @Test
    fun `two recordings 100ms apart produce 10 fps`() {
        val acc = V7AnalysisRateAccumulator(16)
        val t0 = 1_000_000_000L
        acc.recordCompletion(t0)
        acc.recordCompletion(t0 + 100_000_000L)
        assertEquals(10.0, acc.computeFps(t0 + 100_000_000L), 0.01)
    }

    @Test
    fun `steady 10 fps over 500ms remains 10 fps`() {
        val acc = V7AnalysisRateAccumulator(32)
        val t0 = 1_000_000_000L
        for (i in 0 until 6) {
            acc.recordCompletion(t0 + i * 100_000_000L)
        }
        assertEquals(10.0, acc.computeFps(t0 + 500_000_000L), 0.01)
    }

    @Test
    fun `old entries expire beyond 2 seconds`() {
        val acc = V7AnalysisRateAccumulator(32)
        val t0 = 1_000_000_000L
        acc.recordCompletion(t0)
        acc.recordCompletion(t0 + 100_000_000L)
        acc.recordCompletion(t0 + 200_000_000L)
        acc.recordCompletion(t0 + 2_900_000_000L)
        acc.recordCompletion(t0 + 3_000_000_000L)
        assertEquals(10.0, acc.computeFps(t0 + 3_000_000_000L), 0.01)
    }

    @Test
    fun `stale window returns zero`() {
        val acc = V7AnalysisRateAccumulator(16)
        acc.recordCompletion(1_000_000_000L)
        acc.recordCompletion(1_100_000_000L)
        assertEquals(0.0, acc.computeFps(4_000_000_000L), 0.0)
    }

    @Test
    fun `reset clears all`() {
        val acc = V7AnalysisRateAccumulator(16)
        acc.recordCompletion(1_000_000_000L)
        acc.recordCompletion(1_100_000_000L)
        acc.reset()
        assertEquals(0.0, acc.computeFps(), 1e-9)
    }

    @Test
    fun `ring buffer wraps correctly`() {
        val acc = V7AnalysisRateAccumulator(4)
        val t0 = 1_000_000_000L
        for (i in 0 until 6) {
            acc.recordCompletion(t0 + i * 50_000_000L)
        }
        assertEquals(20.0, acc.computeFps(t0 + 5 * 50_000_000L), 0.01)
    }
}
