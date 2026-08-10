package com.superqr.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DiagnosticSessionTest {

    @Test
    fun `initial state is IDLE`() {
        val session = DiagnosticSession(File("."))
        val state = session.sessionState.value
        assertEquals(SessionPhase.IDLE, state.phase)
        assertEquals(0.0, state.cameraFps, 0.0)
        assertEquals(0.0, state.analysisFps, 0.0)
        session.close()
    }

    @Test
    fun `start transitions to SEARCHING`() {
        val session = DiagnosticSession(File("."))
        session.start()
        assertEquals(SessionPhase.SEARCHING, session.sessionState.value.phase)
        session.close()
    }

    @Test
    fun `stop from SEARCHING returns to IDLE`() {
        val session = DiagnosticSession(File("."))
        session.start()
        session.stop()
        assertEquals(SessionPhase.IDLE, session.sessionState.value.phase)
        session.close()
    }

    @Test
    fun `reset clears state`() {
        val session = DiagnosticSession(File("."))
        session.start()
        session.reset()
        val state = session.sessionState.value
        assertEquals(SessionPhase.IDLE, state.phase)
        assertEquals(0.0, state.cameraFps, 0.0)
        session.close()
    }

    // ---- computePhase tests ----

    @Test
    fun `no border no history is SEARCHING`() {
        val phase = DiagnosticSession.computePhase(
            borderFound = false,
            orientationResolved = false,
            calibratedCount = 0,
            colorCount = 4,
            hasSession = false,
            wasTracking = false,
            wasLost = false,
        )
        assertEquals(SessionPhase.SEARCHING, phase)
    }

    @Test
    fun `no border after tracking is LOST`() {
        val phase = DiagnosticSession.computePhase(
            borderFound = false,
            orientationResolved = false,
            calibratedCount = 0,
            colorCount = 4,
            hasSession = false,
            wasTracking = true,
            wasLost = false,
        )
        assertEquals(SessionPhase.LOST, phase)
    }

    @Test
    fun `no border after lost stays LOST`() {
        val phase = DiagnosticSession.computePhase(
            borderFound = false,
            orientationResolved = false,
            calibratedCount = 0,
            colorCount = 4,
            hasSession = false,
            wasTracking = false,
            wasLost = true,
        )
        assertEquals(SessionPhase.LOST, phase)
    }

    @Test
    fun `border found without orientation is QR_DETECTED`() {
        val phase = DiagnosticSession.computePhase(
            borderFound = true,
            orientationResolved = false,
            calibratedCount = 0,
            colorCount = 4,
            hasSession = false,
            wasTracking = false,
            wasLost = false,
        )
        assertEquals(SessionPhase.QR_DETECTED, phase)
    }

    @Test
    fun `border after lost without orientation is REACQUIRING`() {
        val phase = DiagnosticSession.computePhase(
            borderFound = true,
            orientationResolved = false,
            calibratedCount = 0,
            colorCount = 4,
            hasSession = false,
            wasTracking = false,
            wasLost = true,
        )
        assertEquals(SessionPhase.REACQUIRING, phase)
    }

    @Test
    fun `orientation resolved no calibration is QR_LOCKED`() {
        val phase = DiagnosticSession.computePhase(
            borderFound = true,
            orientationResolved = true,
            calibratedCount = 0,
            colorCount = 4,
            hasSession = false,
            wasTracking = true,
            wasLost = false,
        )
        assertEquals(SessionPhase.QR_LOCKED, phase)
    }

    @Test
    fun `partial calibration is GRID_DETECTED`() {
        val phase = DiagnosticSession.computePhase(
            borderFound = true,
            orientationResolved = true,
            calibratedCount = 2,
            colorCount = 4,
            hasSession = false,
            wasTracking = true,
            wasLost = false,
        )
        assertEquals(SessionPhase.GRID_DETECTED, phase)
    }

    @Test
    fun `full calibration is GRID_LOCKED`() {
        val phase = DiagnosticSession.computePhase(
            borderFound = true,
            orientationResolved = true,
            calibratedCount = 4,
            colorCount = 4,
            hasSession = false,
            wasTracking = true,
            wasLost = false,
        )
        assertEquals(SessionPhase.GRID_LOCKED, phase)
    }

    @Test
    fun `full calibration with session is RECEIVING`() {
        val phase = DiagnosticSession.computePhase(
            borderFound = true,
            orientationResolved = true,
            calibratedCount = 4,
            colorCount = 4,
            hasSession = true,
            wasTracking = true,
            wasLost = false,
        )
        assertEquals(SessionPhase.RECEIVING, phase)
    }

    @Test
    fun `full calibration with 8 colors is GRID_LOCKED`() {
        val phase = DiagnosticSession.computePhase(
            borderFound = true,
            orientationResolved = true,
            calibratedCount = 8,
            colorCount = 8,
            hasSession = false,
            wasTracking = true,
            wasLost = false,
        )
        assertEquals(SessionPhase.GRID_LOCKED, phase)
    }

    // ---- SessionPhase labels ----

    @Test
    fun `all phases have non-empty labels`() {
        SessionPhase.entries.forEach { phase ->
            assertTrue("Phase ${phase.name} has empty label", phase.label.isNotEmpty())
        }
    }

    // ---- Guidance derivation ----

    @Test
    fun `guidance is empty set initially`() {
        val session = DiagnosticSession(File("."))
        assertTrue(session.sessionState.value.guidance.isEmpty())
        session.close()
    }
}
