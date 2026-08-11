package com.superqr.android.phase1

import com.superqr.android.vision.v7_capacity_lab.V7LabRunEnvelope
import com.superqr.android.vision.v7_capacity_lab.V7LabRunState
import org.junit.Assert.assertEquals
import org.junit.Test

class AdvancedObservationRecorderDynamicTest {
    @Test
    fun `innovation accounting follows envelope frame count instead of fixed 256 stride`() {
        val profile = AdvancedProfile(
            id = 99,
            name = "test_dynamic",
            kind = "multi_qr",
            targetFps = 20.0,
            requiresCarrier = false,
            laneCount = 2,
            usefulBytesPerEpoch = 200,
            theoreticalMbps = 0.032,
            role = "TEST",
            lanes = emptyList(),
        )
        val envelope = V7LabRunEnvelope(
            state = V7LabRunState.RUNNING,
            profileId = 99,
            runToken = 0x1111,
            frameIndex = 2,
            frameCount = 3,
            dwellEpochs = 3,
        )
        val observations = listOf(
            observation(lane = 0, frame = 2),
            observation(lane = 1, frame = 0),
        )
        val recorder = AdvancedObservationRecorder()
        val first = recorder.record(
            "campaign",
            AdvancedPhyResult(profile, envelope, observations, 0, null, 1.0),
            1_000_000_000L,
            30.0,
            1280,
            960,
        )
        assertEquals(2, first.uniqueLaneFrames)
        assertEquals(200L, first.innovativeBytes)

        val duplicate = recorder.record(
            "campaign",
            AdvancedPhyResult(profile, envelope, observations, 0, null, 1.0),
            1_100_000_000L,
            30.0,
            1280,
            960,
        )
        assertEquals(2, duplicate.uniqueLaneFrames)
        assertEquals(200L, duplicate.innovativeBytes)
    }

    private fun observation(lane: Int, frame: Int) = AdvancedLaneObservation(
        laneId = lane,
        kind = "qr",
        frameIndex = frame,
        usefulBytes = 100,
        observedBits = 800,
        bitErrors = 0,
        erasedBits = 0,
        frameValid = true,
        postFecValid = true,
    )
}
