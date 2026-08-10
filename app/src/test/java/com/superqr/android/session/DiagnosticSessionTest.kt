package com.superqr.android.session

import com.superqr.android.ui.phase1.Phase1FramingGeometry
import com.superqr.android.ui.phase1.Phase1TrackingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticSessionTest {

    @Test
    fun `session frame gate survives repeated start stop cycles`() {
        val gate = SessionFrameGate()
        assertFalse(gate.acceptsFrames())

        repeat(3) {
            gate.start()
            assertTrue(gate.acceptsFrames())
            gate.stop()
            assertFalse(gate.acceptsFrames())
        }

        gate.start()
        assertTrue(gate.acceptsFrames())
    }

    @Test
    fun `all phases have non-empty labels`() {
        SessionPhase.entries.forEach { phase ->
            assertTrue("Phase ${phase.name} has empty label", phase.label.isNotEmpty())
        }
    }

    @Test
    fun `phase labels are distinct`() {
        val labels = SessionPhase.entries.map { it.label }
        assertEquals(labels.size, labels.distinct().size)
    }

    @Test
    fun `initial state is IDLE with empty framing`() {
        val state = SessionState()
        assertEquals(SessionPhase.IDLE, state.phase)
        assertEquals(Phase1TrackingState.SEARCHING, state.trackingState)
        assertEquals(0.0, state.cameraFps, 0.0)
        assertEquals(0.0, state.analysisFps, 0.0)
        assertEquals(0.0, state.pipelineMs, 0.0)
        assertEquals(0, state.analyzedFrames)
        assertFalse(state.hasObservations)
        assertTrue(state.campaignId.isEmpty())
        assertTrue(state.sourceTransform == null)
    }

    @Test
    fun `SEARCHING state transitions tracked correctly`() {
        val state = SessionState(phase = SessionPhase.SEARCHING)
        assertEquals("Searching", state.phase.label)
        assertEquals(0.0, state.cameraFps, 0.0)
    }

    @Test
    fun `COMPLETE state has correct fields`() {
        val state = SessionState(
            phase = SessionPhase.COMPLETE,
            hasObservations = true,
            campaignId = "test-campaign",
            analyzedFrames = 42,
        )
        assertEquals(SessionPhase.COMPLETE, state.phase)
        assertTrue(state.hasObservations)
        assertEquals(42, state.analyzedFrames)
    }

    @Test
    fun `GRID_LOCKED and RECEIVING have distinct labels`() {
        assertTrue(SessionPhase.GRID_LOCKED.label != SessionPhase.RECEIVING.label)
    }

    @Test
    fun `QR phases are separate from GRID phases`() {
        val qrPhases = setOf(SessionPhase.QR_DETECTED, SessionPhase.QR_LOCKED)
        val gridPhases = setOf(SessionPhase.GRID_DETECTED, SessionPhase.GRID_LOCKED)
        assertTrue(qrPhases.intersect(gridPhases).isEmpty())
    }

    @Test
    fun `LOST and REACQUIRING form a recovery sequence`() {
        assertNotNull(SessionPhase.LOST)
        assertNotNull(SessionPhase.REACQUIRING)
        assertEquals("Lost", SessionPhase.LOST.label)
        assertEquals("Reacquiring", SessionPhase.REACQUIRING.label)
    }

    @Test
    fun `session state copy preserves unrelated fields`() {
        val original = SessionState(
            phase = SessionPhase.SEARCHING,
            cameraFps = 30.0,
            analysisFps = 15.0,
            profileName = "test",
        )
        val updated = original.copy(phase = SessionPhase.GRID_DETECTED)
        assertEquals(SessionPhase.GRID_DETECTED, updated.phase)
        assertEquals(30.0, updated.cameraFps, 0.0)
        assertEquals(15.0, updated.analysisFps, 0.0)
        assertEquals("test", updated.profileName)
    }

    @Test
    fun `hasObservations flag is preserved independently`() {
        val withObs = SessionState(hasObservations = true, analyzedFrames = 10)
        assertTrue(withObs.hasObservations)
        assertEquals(10, withObs.analyzedFrames)

        val withoutObs = SessionState(hasObservations = false)
        assertFalse(withoutObs.hasObservations)
    }

    @Test
    fun `empty framing geometry reports no finders`() {
        val geom = Phase1FramingGeometry.empty()
        assertEquals(0, geom.visibleFinders)
    }

    @Test
    fun `framing geometry carries tracking state from source`() {
        val geom = Phase1FramingGeometry(
            frameWidth = 1280,
            frameHeight = 720,
            status = com.superqr.android.ui.phase1.Phase1FramingStatus.GOOD,
            source = "V7_SYNC_TRACKED",
        )
        assertEquals(Phase1TrackingState.TRACKING, geom.trackingState)
    }

    @Test
    fun `V7_SYNC_TRACKED maps to TRACKING`() {
        assertEquals(Phase1TrackingState.TRACKING, Phase1TrackingState.fromSource("V7_SYNC_TRACKED"))
    }

    @Test
    fun `QR_NATIVE_LOCKED maps to TRACKING`() {
        assertEquals(Phase1TrackingState.TRACKING, Phase1TrackingState.fromSource("QR_NATIVE_LOCKED"))
    }

    @Test
    fun `QR_NATIVE_SEARCH maps to SEARCHING`() {
        assertEquals(Phase1TrackingState.SEARCHING, Phase1TrackingState.fromSource("QR_NATIVE_SEARCH"))
    }

    @Test
    fun `V7_TRACK_HOLD maps to HOLDING`() {
        assertEquals(Phase1TrackingState.HOLDING, Phase1TrackingState.fromSource("V7_TRACK_HOLD"))
    }

    @Test
    fun `ACQUIRED suffix maps correctly`() {
        assertEquals(Phase1TrackingState.ACQUIRED, Phase1TrackingState.fromSource("V7_OTSU_ACQUIRED"))
        assertEquals(Phase1TrackingState.ACQUIRED, Phase1TrackingState.fromSource("V7_FINDERS_ACQUIRED"))
    }

    @Test
    fun `source NONE maps to UNKNOWN`() {
        assertEquals(Phase1TrackingState.UNKNOWN, Phase1TrackingState.fromSource("NONE"))
    }

    @Test
    fun `unknown source defaults to SEARCHING`() {
        assertEquals(Phase1TrackingState.SEARCHING, Phase1TrackingState.fromSource("completely_unknown_string"))
    }
}
