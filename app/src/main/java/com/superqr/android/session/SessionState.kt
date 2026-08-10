package com.superqr.android.session

import com.superqr.android.vision.v7.transport.V7OpticalProfile
import com.superqr.android.vision.v7.transport.V7OpticalProfiles
import java.io.File

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

enum class SessionGuidance(val label: String) {
    MOVE_CLOSER("Move Closer"),
    MOVE_BACK("Move Back"),
    HOLD_PHONE_PARALLEL("Hold Phone Parallel"),
}

data class CompletedTransfer(
    val filename: String,
    val file: File,
    val sessionId: Int,
    val frameCount: Int,
    val fileSize: Int,
)

data class SessionState(
    val phase: SessionPhase = SessionPhase.IDLE,
    val guidance: Set<SessionGuidance> = emptySet(),
    val profile: V7OpticalProfile = V7OpticalProfiles.default,
    val cameraFps: Double = 0.0,
    val analysisFps: Double = 0.0,
    val pipelineMs: Double = 0.0,
    val calibratedCount: Int = 0,
    val colorCount: Int = 0,
    val acceptedFrames: Int = 0,
    val totalFrames: Int = -1,
    val codedErasures: Int = 0,
    val crcPass: Int = 0,
    val crcFail: Int = 0,
    val error: String? = null,
    val completedTransfer: CompletedTransfer? = null,
)
