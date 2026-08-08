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
    fun `two recordings 100ms apart produce approximate 20 fps`() {
        val acc = V7AnalysisRateAccumulator(16)
        val t0 = 1_000_000_000L
        acc.recordCompletion(t0)
        acc.recordCompletion(t0 + 100_000_000L) // 100ms later
        val fps = acc.computeFps(t0 + 100_000_000L)
        // 2 completions in 0.1s = 20 fps
        assertEquals(20.0, fps, 5.0)
    }

    @Test
    fun `steady 10 fps over 500ms`() {
        val acc = V7AnalysisRateAccumulator(32)
        val t0 = 1_000_000_000L
        // 6 completions at 100ms intervals = 5 intervals over 500ms
        val intervalMs = 100_000_000L
        for (i in 0 until 6) {
            acc.recordCompletion(t0 + i * intervalMs)
        }
        val now = t0 + 5 * intervalMs
        val fps = acc.computeFps(now)
        // 6 completions in 500ms = 12 fps (actually 6 points, first at t0, last at t0+500ms)
        // 6 frames / 0.5s = 12 fps
        assertEquals(12.0, fps, 2.0)
    }

    @Test
    fun `old entries expire beyond 2 seconds`() {
        val acc = V7AnalysisRateAccumulator(32)
        val t0 = 1_000_000_000L
        // 3 completions at t0, t0+100ms, t0+200ms
        acc.recordCompletion(t0)
        acc.recordCompletion(t0 + 100_000_000L)
        acc.recordCompletion(t0 + 200_000_000L)
        // Now at t0 + 3 seconds — all should be expired, only recent ones count
        acc.recordCompletion(t0 + 2_900_000_000L)
        acc.recordCompletion(t0 + 3_000_000_000L)
        val fps = acc.computeFps(t0 + 3_000_000_000L)
        // 2 recent frames over 100ms = ~20 fps
        assertEquals(20.0, fps, 2.0)
    }

    @Test
    fun `reset clears all`() {
        val acc = V7AnalysisRateAccumulator(16)
        acc.recordCompletion(1_000_000_000L)
        acc.recordCompletion(1_000_100_000L)
        acc.reset()
        assertEquals(0.0, acc.computeFps(), 1e-9)
    }

    @Test
    fun `ring buffer wraps correctly`() {
        val acc = V7AnalysisRateAccumulator(4)
        val t0 = 1_000_000_000L
        // Fill past the ring buffer size
        for (i in 0 until 6) {
            acc.recordCompletion(t0 + i * 50_000_000L)
        }
        // Should still produce reasonable result
        val fps = acc.computeFps(t0 + 5 * 50_000_000L)
        assertTrue("fps should be positive", fps > 0.0)
    }
}
