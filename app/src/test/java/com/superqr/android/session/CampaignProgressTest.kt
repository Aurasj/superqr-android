package com.superqr.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CampaignProgressTest {
    @Test
    fun transientMissingEnvelopeKeepsVisibleProgress() {
        val previous = CampaignProgress(
            runToken = 0x1234,
            state = "RUNNING",
        )

        val stabilized = previous.stabilizedWith(null)

        assertEquals(previous, stabilized)
        assertTrue(stabilized.visible)
    }

    @Test
    fun sameRunTokenPreservesProgress() {
        val previous = CampaignProgress(
            runToken = 0x1234,
            state = "RUNNING",
        )
        val sameToken = CampaignProgress(
            runToken = 0x1234,
            state = "DONE",
        )

        val stabilized = previous.stabilizedWith(sameToken)

        assertEquals("DONE", stabilized.state)
        assertTrue(stabilized.visible)
    }

    @Test
    fun newRunTokenStartsFreshProgress() {
        val previous = CampaignProgress(
            runToken = 0x1234,
            state = "DONE",
        )
        val nextRun = CampaignProgress(
            runToken = 0x5678,
            state = "READY",
        )

        val stabilized = previous.stabilizedWith(nextRun)

        assertEquals(nextRun, stabilized)
    }

    @Test
    fun completeFlagWorks() {
        assertTrue(CampaignProgress(1, "DONE").complete)
        assertFalse(CampaignProgress(1, "RUNNING").complete)
    }
}
