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
    fun searchAlternatesButCandidateAndLockStayOnTheirCarrier() {
        val scheduler = Phase1AcquisitionScheduler(unlockAfterMisses = 2)
        assertEquals(Phase1AnalysisPath.GRID, scheduler.path)
        scheduler.missed(Phase1AnalysisPath.GRID)
        assertEquals(Phase1AnalysisPath.QR, scheduler.path)
        scheduler.missed(Phase1AnalysisPath.QR)
        assertEquals(Phase1AnalysisPath.GRID, scheduler.path)
        scheduler.missed(Phase1AnalysisPath.GRID, carrierCandidate = true)
        assertEquals(Phase1AnalysisPath.GRID, scheduler.path)
        scheduler.locked(Phase1AnalysisPath.GRID)
        scheduler.missed(Phase1AnalysisPath.GRID)
        assertEquals(Phase1AnalysisPath.GRID, scheduler.path)
        scheduler.missed(Phase1AnalysisPath.GRID)
        assertEquals(Phase1AnalysisPath.QR, scheduler.path)
    }
}
