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
    fun diagnosticsExportNestedPrimitiveArraysAsRealJson() {
        val recorder = Phase1ObservationRecorder()
        recorder.recordFailure(
            "TEST_DIAGNOSTIC",
            completedNs = 10,
            pipelineMs = 1.0,
            geometry = "SEARCH",
            sync = "SEARCH",
            extra = mapOf(
                "detected_quad" to listOf(
                    doubleArrayOf(1.5, 2.5),
                    doubleArrayOf(3.5, 4.5),
                ),
                "nested" to mapOf("values" to intArrayOf(7, 8, 9)),
            ),
        )

        val json = JSONObject(recorder.jsonLinesForTest().single())
        val quad = json.getJSONArray("detected_quad")
        assertEquals(2, quad.length())
        assertEquals(1.5, quad.getJSONArray(0).getDouble(0), 0.0)
        assertEquals(4.5, quad.getJSONArray(1).getDouble(1), 0.0)
        assertEquals(8, json.getJSONObject("nested").getJSONArray("values").getInt(1))
    }

    @Test
    fun readyRunningDoneFlowFreezesMetricsButKeepsBoundedNextCarrierDiagnostics() {
        val recorder = Phase1ObservationRecorder()
        val profile = Phase1Profile.Grid(
            3, "mono_128x100_qrlike",
            V7Phase1GridProfile("mono_128x100_qrlike", 100, 128, 1, 1600),
            usefulBytes = 1360,
        )
        val ready = com.superqr.android.vision.v7_capacity_lab.V7LabRunEnvelope(
            com.superqr.android.vision.v7_capacity_lab.V7LabRunState.READY, 3, 0xBEEF, 0, 3, 3,
        )
        recorder.observeSender(profile, ready, "LOCKED", "FULL_DETECTION", observedNs = 50)
        assertEquals(0, recorder.snapshot().observations)
        val running = ready.copy(state = com.superqr.android.vision.v7_capacity_lab.V7LabRunState.RUNNING)
        recorder.record(
            profile, 3, 100, frameIndex = 0, observedBits = 12_800,
            bitErrors = 0, erasedBits = 0, frameValid = true, postFecValid = true,
            pipelineMs = 10.0, allocationBytes = 0, gcEvents = 0,
            envelope = running, sync = "LOCKED", geometry = "FULL_DETECTION",
        )
        val done = ready.copy(state = com.superqr.android.vision.v7_capacity_lab.V7LabRunState.DONE, frameIndex = 2)
        recorder.observeSender(profile, done, "LOCKED", "TRACKED_RESAMPLED", observedNs = 200)
        val snapshot = recorder.snapshot(200)
        assertEquals("DONE", snapshot.senderState)
        assertEquals(1, snapshot.observations)
        assertEquals(1, snapshot.uniqueFrames)
        assertEquals(1360, snapshot.innovativeBytes)
        assertEquals(100.0 / 1_000_000_000.0, snapshot.elapsedSeconds, 0.0)
        assertEquals(snapshot.elapsedSeconds, recorder.snapshot(9_000_000_000L).elapsedSeconds, 0.0)

        val linesAtDone = recorder.jsonLinesForTest().size
        recorder.recordFailure(
            "QR_NOT_DECODED", 300, 12.0,
            "QR_NATIVE_FULL_FRAME", "SEARCH_QR",
            extra = mapOf("analysis_path" to "QR", "qr_scan_scope" to "FULL_ANALYSIS_FRAME"),
        )
        val stable = recorder.snapshot(9_000_000_000L)
        assertEquals("DONE", stable.senderState)
        assertEquals("TRACKED_RESAMPLED", stable.geometryState)
        assertEquals("LOCKED", stable.syncStatus)
        assertEquals(snapshot.analyzedFrames, stable.analyzedFrames)
        assertEquals(snapshot.elapsedSeconds, stable.elapsedSeconds, 0.0)
        assertEquals(linesAtDone + 1, recorder.jsonLinesForTest().size)

        val transition = JSONObject(recorder.jsonLinesForTest().last())
        assertTrue(transition.getBoolean("transition_diagnostic"))
        assertEquals("SEARCHING_NEXT", transition.getString("sender_state"))
        assertEquals("QR_NOT_DECODED", transition.getString("failure_reason"))
        assertEquals("FULL_ANALYSIS_FRAME", transition.getString("qr_scan_scope"))
        assertTrue(transition.isNull("run_token"))

        // Once the bounded transition window expires, terminal camera frames do
        // not grow the export forever.
        recorder.recordFailure(
            "QR_NOT_DECODED", 8_000_000_201L, 12.0,
            "QR_NATIVE_FULL_FRAME", "SEARCH_QR",
        )
        assertEquals(linesAtDone + 1, recorder.jsonLinesForTest().size)
    }
}
