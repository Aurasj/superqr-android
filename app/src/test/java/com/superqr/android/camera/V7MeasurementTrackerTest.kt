package com.superqr.android.camera

import org.junit.Assert.*
import org.junit.Test

class V7MeasurementTrackerTest {
    @Test
    fun `analysis rate and stage timings are recorded`() {
        val t = V7MeasurementTracker()
        val start = 1_000_000_000L
        t.reset(start)
        t.recordAnalysis(start + 100_000_000L, 12.0, 5.0, 6.0, 0.2, 2.0, 1.0, 2.8)
        t.recordAnalysis(start + 200_000_000L, 11.0, 4.5, 5.5, 0.2, 1.8, 0.9, 2.6)
        val s = t.snapshot(start + 200_000_000L)
        assertEquals(1, s.schemaVersion)
        assertEquals(10.0, s.analysisFps, 0.01)
        assertEquals(11.0, s.pipelineMs, 0.01)
        assertEquals(4.5, s.detectorMs, 0.01)
        assertEquals(5.5, s.v7TotalMs, 0.01)
        assertEquals(2, s.analysisCompleted)
    }

    @Test
    fun `accepted rate counts unique logical frames only`() {
        val t = V7MeasurementTracker()
        val start = 1_000_000_000L
        t.reset(start)
        assertTrue(t.recordAccepted(7, 0, 380))
        assertFalse(t.recordAccepted(7, 0, 380))
        assertTrue(t.recordAccepted(7, 1, 200))
        val s = t.snapshot(start + 1_000_000_000L)
        assertEquals(2.0, s.usefulUniqueFps, 0.001)
        assertEquals(580L, s.acceptedPayloadBytes)
        assertEquals(580.0 / 1024.0, s.decodedPayloadKiBs, 0.001)
    }

    @Test
    fun `new accepted session resets unique payload accounting`() {
        val t = V7MeasurementTracker()
        t.reset(1_000_000_000L)
        t.recordAccepted(1, 0, 300)
        t.recordAccepted(2, 0, 100)
        val s = t.snapshot(2_000_000_000L)
        assertEquals(100L, s.acceptedPayloadBytes)
        assertEquals(1.0, s.usefulUniqueFps, 0.001)
    }

    @Test
    fun `unexpected exceptions are visible`() {
        val t = V7MeasurementTracker()
        t.reset(1_000_000_000L)
        t.recordException(IllegalStateException("boom"))
        val s = t.snapshot(1_100_000_000L)
        assertEquals(1, s.analysisExceptionCount)
        assertTrue(s.lastAnalysisException!!.contains("IllegalStateException"))
        assertTrue(s.lastAnalysisException!!.contains("boom"))
    }
}
