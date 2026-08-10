package com.superqr.android.session

import com.superqr.android.ui.phase1.Phase1FramingGeometry
import com.superqr.android.ui.phase1.Phase1TrackingState

enum class SessionPhase(val label: String) {
    IDLE("Ready"),
    SEARCHING("Searching"),
    QR_DETECTED("QR Detected"),
    QR_LOCKED("QR Locked"),
    GRID_DETECTED("Grid Detected"),
    GRID_LOCKED("Grid Locked"),
    RECEIVING("Receiving"),
    LOST("Lost"),
    REACQUIRING("Reacquiring"),
    COMPLETE("Complete"),
}

data class SessionState(
    val phase: SessionPhase = SessionPhase.IDLE,
    val trackingState: Phase1TrackingState = Phase1TrackingState.SEARCHING,
    val framing: Phase1FramingGeometry = Phase1FramingGeometry.empty(),
    val profileName: String = "",
    val cameraFps: Double = 0.0,
    val analysisFps: Double = 0.0,
    val pipelineMs: Double = 0.0,
    val analyzedFrames: Int = 0,
    val hasObservations: Boolean = false,
    val campaignId: String = "",
    val error: String? = null,
)
