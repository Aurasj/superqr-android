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
        assertTrue(lines.all { it.has("allocation_bytes") && it.has("post_fec_valid") && it.getBoolean("scored") })
    }

    @Test
    fun synchronizedRunResetsPerRunCountersAndExportsFailureReasons() {
        val recorder = Phase1ObservationRecorder()
        val profile = Phase1Profile.Grid(
            2, "mono_test", V7Phase1GridProfile("mono_test", 8, 8, 1, 8), usefulBytes = 6,
        )
        val envelope = com.superqr.android.vision.v7_capacity_lab.V7LabRunEnvelope(
            com.superqr.android.vision.v7_capacity_lab.V7LabRunState.READY, 2, 0xBEEF, 0, 32, 2,
        )
        recorder.observeSender(profile, envelope, "LOCKED", "FULL_DETECTION")
        recorder.recordFailure("SYNC_TRANSITION_TOP_BOTTOM_MISMATCH", 10, 1.0, "TRACKED", "MISMATCH")
        val snapshot = recorder.snapshot(20)
        assertEquals("BEEF", snapshot.runId)
        assertEquals(32, snapshot.expectedFrames)
        assertEquals(1, snapshot.analyzedFrames)
        assertTrue(snapshot.failureSummary.contains("SYNC_TRANSITION"))
        val json = JSONObject(recorder.jsonLinesForTest().single())
        assertEquals(false, json.getBoolean("scored"))
        assertEquals(0xBEEF, json.getInt("run_token"))
    }

    @Test
    fun readyRunningDoneFlowScoresOnlyRunningPayload() {
        val recorder = Phase1ObservationRecorder()
        val profile = Phase1Profile.Grid(
            3, "mono_128x100_qrlike",
            V7Phase1GridProfile("mono_128x100_qrlike", 100, 128, 1, 1600),
            usefulBytes = 1360,
        )
        val ready = com.superqr.android.vision.v7_capacity_lab.V7LabRunEnvelope(
            com.superqr.android.vision.v7_capacity_lab.V7LabRunState.READY, 3, 0xBEEF, 0, 3, 3,
        )
        recorder.observeSender(profile, ready, "LOCKED", "FULL_DETECTION")
        assertEquals(0, recorder.snapshot().observations)
        val running = ready.copy(state = com.superqr.android.vision.v7_capacity_lab.V7LabRunState.RUNNING)
        recorder.record(
            profile, 3, 100, frameIndex = 0, observedBits = 12_800,
            bitErrors = 0, erasedBits = 0, frameValid = true, postFecValid = true,
            pipelineMs = 10.0, allocationBytes = 0, gcEvents = 0,
            envelope = running, sync = "LOCKED", geometry = "FULL_DETECTION",
        )
        val done = ready.copy(state = com.superqr.android.vision.v7_capacity_lab.V7LabRunState.DONE, frameIndex = 2)
        recorder.observeSender(profile, done, "LOCKED", "TRACKED_RESAMPLED")
        val snapshot = recorder.snapshot(200)
        assertEquals("DONE", snapshot.senderState)
        assertEquals(1, snapshot.observations)
        assertEquals(1, snapshot.uniqueFrames)
        assertEquals(1360, snapshot.innovativeBytes)

        val linesAtDone = recorder.jsonLinesForTest().size
        recorder.recordFailure(
            "V7_NO_COMPLETE_CARRIER_GEOMETRY", 300, 12.0,
            "V7_SYNC_NOT_VALIDATED", "V7_NO_COMPLETE_CARRIER_GEOMETRY",
        )
        val stable = recorder.snapshot(400)
        assertEquals("DONE", stable.senderState)
        assertEquals("TRACKED_RESAMPLED", stable.geometryState)
        assertEquals("LOCKED", stable.syncStatus)
        assertEquals(snapshot.analyzedFrames, stable.analyzedFrames)
        assertEquals(linesAtDone, recorder.jsonLinesForTest().size)
    }
}
