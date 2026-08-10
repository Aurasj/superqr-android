package com.superqr.android.ui.phase1

/**
 * Cheap, always-updated snapshot of the most recently analyzed frame.
 *
 * Populated on the analyzer thread every frame (scalars and small arrays only,
 * no bitmap) so a "Save frame" / "Export" action on the UI thread can describe
 * exactly what the decoder last saw without re-deriving it or racing the camera
 * analyzer for the mutable luma buffer.
 */
data class Phase1LiveDiagnostics(
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val cropLeft: Int = 0,
    val cropTop: Int = 0,
    val cropRight: Int = 0,
    val cropBottom: Int = 0,
    val rotationDegrees: Int = 0,
    val sensorTimestampNs: Long = 0,
    val arrivalNs: Long = 0,
    val mode: Phase1FramingMode = Phase1FramingMode.GRID,
    val activeProfileName: String = "AUTO • searching",
    val acquisitionSource: String = "NONE",
    val trackingState: Phase1TrackingState = Phase1TrackingState.UNKNOWN,
    val homography: DoubleArray? = null,
    val detectedQuad: List<DoubleArray>? = null,
    val runToken: Int? = null,
    val frameIndex: Long? = null,
    val acquisitionMs: Double? = null,
    val syncMs: Double? = null,
    val payloadMs: Double? = null,
    val pipelineMs: Double = 0.0,
)
