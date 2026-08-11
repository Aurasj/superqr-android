package com.superqr.android.session

import androidx.camera.view.transform.OutputTransform
import com.superqr.android.phase1.Phase1FramingGeometry
import com.superqr.android.phase1.Phase1TrackingState

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

data class CampaignProgress(
    val runToken: Int = -1,
    val state: String = "",
    val frameIndex: Int = 0,
    val frameCount: Int = 0,
) {
    val fraction: Float get() = if (frameCount > 0) (frameIndex.toFloat() / frameCount).coerceIn(0f, 1f) else 0f
    val visible: Boolean get() = runToken >= 0 && state.isNotEmpty()
}

internal fun CampaignProgress.stabilizedWith(observed: CampaignProgress?): CampaignProgress {
    if (observed == null || !observed.visible) return this
    if (!visible || runToken != observed.runToken) return observed
    return observed.copy(frameIndex = maxOf(frameIndex, observed.frameIndex))
}

data class SessionState(
    val phase: SessionPhase = SessionPhase.IDLE,
    val trackingState: Phase1TrackingState = Phase1TrackingState.SEARCHING,
    val framing: Phase1FramingGeometry = Phase1FramingGeometry.empty(),
    val sourceTransform: OutputTransform? = null,
    val profileName: String = "",
    val cameraFps: Double = 0.0,
    val analysisFps: Double = 0.0,
    val pipelineMs: Double = 0.0,
    val analyzedFrames: Int = 0,
    val hasObservations: Boolean = false,
    val campaignId: String = "",
    val error: String? = null,
    val campaignProgress: CampaignProgress = CampaignProgress(),
)
