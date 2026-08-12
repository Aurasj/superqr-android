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

enum class ReceiverMode(val label: String) {
    QR_ONLY("QR Only"),
    SHAPEGRID_ONLY("ShapeGrid Only"),
    AUTO("Auto"),
}

enum class AppMode(val label: String) {
    RECEIVE("Receive"),
    TEST("Test"),
}

data class CampaignProgress(
    val runToken: Int = -1,
    val state: String = "",
) {
    val visible: Boolean get() = runToken >= 0 && state.isNotEmpty()
    val complete: Boolean get() = state == "DONE"
}

internal fun CampaignProgress.stabilizedWith(observed: CampaignProgress?): CampaignProgress {
    if (observed == null || !observed.visible) return this
    if (!visible || runToken != observed.runToken) return observed
    return observed
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
    val appMode: AppMode = AppMode.TEST,
    val receiverMode: ReceiverMode = ReceiverMode.AUTO,
    val campaignProgress: CampaignProgress = CampaignProgress(),
    val receiverUniqueFrames: Int = 0,
    val receiverExpectedFrames: Int = 256,
    val missedGaps: String = "",
)
