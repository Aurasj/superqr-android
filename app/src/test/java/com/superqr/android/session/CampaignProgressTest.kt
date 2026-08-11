package com.superqr.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CampaignProgressTest {
    @Test
    fun transientMissingEnvelopeKeepsVisibleProgress() {
        val previous = CampaignProgress(
            runToken = 0x1234,
            state = "RUNNING",
            frameIndex = 87,
            frameCount = 256,
        )

        val stabilized = previous.stabilizedWith(null)

        assertEquals(previous, stabilized)
        assertTrue(stabilized.visible)
    }

    @Test
    fun sameRunCannotMoveProgressBackward() {
        val previous = CampaignProgress(
            runToken = 0x1234,
            state = "RUNNING",
            frameIndex = 87,
            frameCount = 256,
        )
        val olderObservation = CampaignProgress(
            runToken = 0x1234,
            state = "RUNNING",
            frameIndex = 84,
            frameCount = 256,
        )

        val stabilized = previous.stabilizedWith(olderObservation)

        assertEquals(87, stabilized.frameIndex)
    }

    @Test
    fun newRunTokenStartsFreshProgress() {
        val previous = CampaignProgress(
            runToken = 0x1234,
            state = "DONE",
            frameIndex = 255,
            frameCount = 256,
        )
        val nextRun = CampaignProgress(
            runToken = 0x5678,
            state = "READY",
            frameIndex = 0,
            frameCount = 256,
        )

        val stabilized = previous.stabilizedWith(nextRun)

        assertEquals(nextRun, stabilized)
    }
}
