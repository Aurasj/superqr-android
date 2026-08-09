package com.superqr.android.ui.phase1

import com.superqr.android.vision.v7_capacity_lab.V7Phase1GridProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase1ObservationRecorderTest {
    @Test
    fun duplicateOpticalFrameOnlyContributesInnovativeBytesOnce() {
        val recorder = Phase1ObservationRecorder()
        val profile = Phase1Profile.Grid(
            "mono_test", V7Phase1GridProfile("mono_test", 8, 8, 1, 8), usefulBytes = 6,
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
        assertTrue(lines.all { it.has("allocation_bytes") && it.has("post_fec_valid") })
    }
}
