package com.superqr.android.phase1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase1AnalysisPolicyTest {
    @Test
    fun usesFourByThreePhysicalTargetForPreviewParity() {
        assertEquals(1280, Phase1AnalysisPolicy.TARGET_WIDTH)
        assertEquals(960, Phase1AnalysisPolicy.TARGET_HEIGHT)
        assertTrue(Phase1AnalysisPolicy.accepts(1280, 960))
        assertTrue(Phase1AnalysisPolicy.accepts(960, 1280))
    }

    @Test
    fun rejectsOversizedPhysicalFramesBeforePacking() {
        assertFalse(Phase1AnalysisPolicy.accepts(3456, 3456))
        assertTrue(Phase1AnalysisPolicy.accepts(1280, 720))
        assertTrue(Phase1AnalysisPolicy.accepts(960, 1280))
    }

    @Test
    fun warmupIsBoundedByTimeAndFrames() {
        assertFalse(Phase1AnalysisPolicy.warmupComplete(0, 749_999_999, 20))
        assertFalse(Phase1AnalysisPolicy.warmupComplete(0, 1_000_000_000, 7))
        assertTrue(Phase1AnalysisPolicy.warmupComplete(0, 750_000_000, 8))
    }

    @Test
    fun unlockedSearchProbesQrFirstThenAlternatesAndKeepsGridCandidates() {
        val scheduler = Phase1AcquisitionScheduler()
        assertEquals(Phase1AnalysisPath.QR, scheduler.path)
        scheduler.missed(Phase1AnalysisPath.QR)
        assertEquals(Phase1AnalysisPath.GRID, scheduler.path)
        scheduler.missed(Phase1AnalysisPath.GRID)
        assertEquals(Phase1AnalysisPath.QR, scheduler.path)
        scheduler.missed(Phase1AnalysisPath.QR)
        assertEquals(Phase1AnalysisPath.GRID, scheduler.path)
        scheduler.missed(Phase1AnalysisPath.GRID, carrierCandidate = true)
        assertEquals(Phase1AnalysisPath.GRID, scheduler.path)
    }

    @Test
    fun unlockedQrCandidateKeepsConsecutiveQrFramesUntilGeometryDisappears() {
        val scheduler = Phase1AcquisitionScheduler()
        assertEquals(Phase1AnalysisPath.QR, scheduler.path)

        repeat(5) {
            scheduler.missed(Phase1AnalysisPath.QR, qrCandidate = true)
            assertEquals(Phase1AnalysisPath.QR, scheduler.path)
            assertEquals("SEARCH_QR", scheduler.state)
        }

        scheduler.missed(Phase1AnalysisPath.QR, qrCandidate = false)
        assertEquals(Phase1AnalysisPath.GRID, scheduler.path)
    }

    @Test
    fun lockedGridKeepsConsecutiveFramesDuringCarrierLikeMotion() {
        val scheduler = Phase1AcquisitionScheduler(unlockAfterMisses = 2, transitionProbeFrames = 3)
        scheduler.locked(Phase1AnalysisPath.GRID)

        repeat(5) {
            scheduler.missed(Phase1AnalysisPath.GRID, carrierCandidate = true)
            assertEquals(Phase1AnalysisPath.GRID, scheduler.path)
            assertEquals("GRID_LOCKED", scheduler.state)
        }

        // Once carrier evidence actually disappears, the normal lost-lock policy resumes.
        scheduler.missed(Phase1AnalysisPath.GRID, carrierCandidate = false)
        assertEquals(Phase1AnalysisPath.GRID, scheduler.path)
        scheduler.missed(Phase1AnalysisPath.GRID, carrierCandidate = false)
        assertEquals(Phase1AnalysisPath.QR, scheduler.path)
        assertEquals("TRANSITION_QR_PROBE", scheduler.state)
    }

    @Test
    fun lostGridLockProbesQrBeforeColdGridSearch() {
        val scheduler = Phase1AcquisitionScheduler(unlockAfterMisses = 2, transitionProbeFrames = 3)
        scheduler.locked(Phase1AnalysisPath.GRID)
        scheduler.missed(Phase1AnalysisPath.GRID)
        assertEquals(Phase1AnalysisPath.GRID, scheduler.path)
        scheduler.missed(Phase1AnalysisPath.GRID)
        assertEquals(Phase1AnalysisPath.QR, scheduler.path)
        assertEquals("TRANSITION_QR_PROBE", scheduler.state)
        repeat(3) { scheduler.missed(Phase1AnalysisPath.QR) }
        assertEquals(Phase1AnalysisPath.GRID, scheduler.path)
    }
}
