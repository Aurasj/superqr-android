package com.superqr.android.ui.phase1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase1AnalysisPolicyTest {
    @Test
    fun rejectsTheTwelveMegapixelPhysicalRunBeforePacking() {
        assertFalse(Phase1AnalysisPolicy.accepts(3456, 3456))
        assertTrue(Phase1AnalysisPolicy.accepts(1280, 720))
        assertTrue(Phase1AnalysisPolicy.accepts(960, 1280))
    }

    @Test
    fun warmupIsBoundedByTimeAndFramesRatherThanFortyFiveSlowFrames() {
        assertFalse(Phase1AnalysisPolicy.warmupComplete(0, 749_999_999, 20))
        assertFalse(Phase1AnalysisPolicy.warmupComplete(0, 1_000_000_000, 7))
        assertTrue(Phase1AnalysisPolicy.warmupComplete(0, 750_000_000, 8))
    }

    @Test
    fun unlockedSearchAlternatesButGridCandidatesStayOnGrid() {
        val scheduler = Phase1AcquisitionScheduler()
        assertEquals(Phase1AnalysisPath.GRID, scheduler.path)
        scheduler.missed(Phase1AnalysisPath.GRID)
        assertEquals(Phase1AnalysisPath.QR, scheduler.path)
        scheduler.missed(Phase1AnalysisPath.QR)
        assertEquals(Phase1AnalysisPath.GRID, scheduler.path)
        scheduler.missed(Phase1AnalysisPath.GRID, carrierCandidate = true)
        assertEquals(Phase1AnalysisPath.GRID, scheduler.path)
    }

    @Test
    fun lostGridLockProbesQrBeforeGridColdSearchCanRun() {
        val scheduler = Phase1AcquisitionScheduler(unlockAfterMisses = 2, transitionProbeFrames = 3)
        scheduler.locked(Phase1AnalysisPath.GRID)

        scheduler.missed(Phase1AnalysisPath.GRID)
        assertEquals(Phase1AnalysisPath.GRID, scheduler.path)

        // The second miss hands off before V7CarrierAcquirer reaches its third
        // miss, which is where a cold contour search would otherwise begin.
        scheduler.missed(Phase1AnalysisPath.GRID)
        assertEquals(Phase1AnalysisPath.QR, scheduler.path)
        assertEquals("TRANSITION_QR_PROBE", scheduler.state)

        // A changed carrier gets several QR decode opportunities without a
        // dense QR image being sent through the grid contour search first.
        scheduler.missed(Phase1AnalysisPath.QR)
        assertEquals(Phase1AnalysisPath.QR, scheduler.path)
        scheduler.missed(Phase1AnalysisPath.QR)
        assertEquals(Phase1AnalysisPath.QR, scheduler.path)
        scheduler.missed(Phase1AnalysisPath.QR)
        assertEquals(Phase1AnalysisPath.GRID, scheduler.path)
    }

    @Test
    fun successfulTransitionLockClearsProbeState() {
        val scheduler = Phase1AcquisitionScheduler(unlockAfterMisses = 2, transitionProbeFrames = 3)
        scheduler.locked(Phase1AnalysisPath.GRID)
        repeat(2) { scheduler.missed(Phase1AnalysisPath.GRID) }
        assertEquals(Phase1AnalysisPath.QR, scheduler.path)
        scheduler.locked(Phase1AnalysisPath.QR)
        assertEquals(Phase1AnalysisPath.QR, scheduler.path)
        assertEquals("QR_LOCKED", scheduler.state)
    }
}
