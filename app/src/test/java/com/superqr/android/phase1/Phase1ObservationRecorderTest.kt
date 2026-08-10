package com.superqr.android.phase1

import com.superqr.android.vision.v7_capacity_lab.V7Phase1GridProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase1ObservationRecorderTest {
    @Test
    fun hasLinesIsConstantTimeStateAndResettable() {
        val recorder = Phase1ObservationRecorder()
        assertFalse(recorder.hasLines)
        assertEquals(0, recorder.lineCount)
        recorder.recordFailure("TEST", 10, 1.0, "SEARCH", "SEARCH")
        assertTrue(recorder.hasLines)
        assertEquals(1, recorder.lineCount)
        recorder.reset()
        assertFalse(recorder.hasLines)
        assertEquals(0, recorder.lineCount)
    }

    @Test
    fun duplicateOpticalFrameOnlyContributesInnovativeBytesOnce() {
        val recorder = Phase1ObservationRecorder()
        val profile = Phase1Profile.Grid(
            0, "mono_test", V7Phase1GridProfile("mono_test", 8, 8, 1, 8), usefulBytes = 6,
        )
        val now = System.nanoTime()
        repeat(2) {
            recorder.record(
                profile, 3, now + it, frameIndex = 7, observedBits = 64,
                bitErrors = 0, erasedBits = 0, frameValid = true, postFecValid = true,
                pipelineMs = 2.5, allocationBytes = 12, gcEvents = 0,
            )
        }
        val snapshot = recorder.snapshot(now + 1_000_000_000)
        assertEquals(2, snapshot.observations)
        assertEquals(1, snapshot.uniqueFrames)
        assertEquals(6, snapshot.innovativeBytes)
        val lines = recorder.jsonLinesForTest().map(::JSONObject)
        assertEquals(6, lines[0].getInt("innovative_bytes"))
        assertEquals(0, lines[1].getInt("innovative_bytes"))
    }

    @Test
    fun nestedPrimitiveArraysRemainRealJson() {
        val recorder = Phase1ObservationRecorder()
        recorder.recordFailure(
            "TEST_DIAGNOSTIC", 10, 1.0, "SEARCH", "SEARCH",
            extra = mapOf(
                "detected_quad" to listOf(doubleArrayOf(1.5, 2.5), doubleArrayOf(3.5, 4.5)),
                "nested" to mapOf("values" to intArrayOf(7, 8, 9)),
            ),
        )
        val json = JSONObject(recorder.jsonLinesForTest().single())
        assertEquals(4.5, json.getJSONArray("detected_quad").getJSONArray(1).getDouble(1), 0.0)
        assertEquals(8, json.getJSONObject("nested").getJSONArray("values").getInt(1))
    }
}
