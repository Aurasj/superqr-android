package com.superqr.android.vision.v6.replay

data class V6ReplayResult(
    val label: String,
    val zipFileName: String,
    val borderFound: Boolean,
    val orientationResolved: Boolean,
    val classificationSource: String,
    val correctSymbols: Int?,
    val symbolErrors: Int?,
    val symbolErrorRate: Double?,
    val uncertainCount: Int,
    val uncertainRate: Double,
    val parseAttempted: Boolean,
    val transportValid: Boolean,
    val sessionId: Int?,
    val frameId: Int?,
    val totalFrames: Int?,
    val expectedSessionId: Int?,
    val expectedFrameId: Int?,
    val expectedTotalFrames: Int?,
    val packetSuccess: Boolean,
    val processingTimeMs: Long,
    val failureReason: String?
)
