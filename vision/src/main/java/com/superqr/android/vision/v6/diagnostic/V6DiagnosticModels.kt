package com.superqr.android.vision.v6.diagnostic

import android.graphics.Rect

data class V6CellDiagnosticDetail(
    val row: Int,
    val col: Int,
    val expectedIdx: Int,
    val expectedColor: String,
    val decodedIdx: Int,
    val decodedColor: String,
    val status: String, // CORRECT, WRONG, UNCERTAIN
    val classificationSource: String, // FULL_DETECTION, TRACKED_HOMOGRAPHY
    val canonicalCenterX: Double,
    val canonicalCenterY: Double,
    val canonicalSamples: List<Pair<Double, Double>>,
    val mappedCameraSamples: List<Pair<Double, Double>>,
    val yReadSuccessCount: Int,
    val uReadSuccessCount: Int,
    val vReadSuccessCount: Int,
    val rawSamples: List<IntArray>, // List of 5 [Y, U, V]
    val medianY: Int,
    val medianU: Int,
    val medianV: Int,
    val normY: Double,
    val normU: Double,
    val normV: Double,
    val distBlack: Double,
    val distWhite: Double,
    val distRed: Double,
    val distBlue: Double,
    val nearestDist: Double,
    val secondBestDist: Double,
    val confidenceMargin: Double,
    val uncertainReason: String
)

data class V6PilotDiagnosticDetail(
    val pilotName: String,
    val canonicalCenterX: Double,
    val canonicalCenterY: Double,
    val mappedCameraCenterX: Double,
    val mappedCameraCenterY: Double,
    val ySuccess: Boolean,
    val uSuccess: Boolean,
    val vSuccess: Boolean,
    val rawSamples: List<IntArray>, // List of 5 [Y, U, V]
    val medianY: Int,
    val medianU: Int,
    val medianV: Int
)

data class V6FrameTraceEntry(
    val timestamp: Long,
    val state: String,
    val quad: List<List<Double>>?,
    val ransacInliers: Int,
    val correctCount: Int,
    val incorrectCount: Int,
    val uncertainCount: Int,
    val decodedCrc: String,
    val pilotMedians: Map<String, List<Int>>,
    val yuvReadSuccessCounts: Map<String, Int>
)

data class V6FrameDiagnosticPayload(
    val timestamp: Long,
    val classificationSource: String,
    val imageToCanonicalHomography: DoubleArray?,
    val canonicalToImageHomography: DoubleArray?,
    val detectedQuad: List<DoubleArray>?,
    val trackedQuad: List<DoubleArray>?,
    val contractHash: String,
    val patternName: String,
    val seed: Int,
    val first20Expected: List<Int>,
    val first20Decoded: List<Int>,
    val expectedCrc32: Long,
    val decodedCrc32: Long?,
    val cellDetails: List<V6CellDiagnosticDetail>,
    val pilotDetails: List<V6PilotDiagnosticDetail>,
    val pairwisePilotDistances: Map<String, Double>
)

data class V6CapturedFrameBundle(
    val timestamp: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val rotationDegrees: Int,
    val cropRect: Rect,
    val yRowStride: Int,
    val yPixelStride: Int,
    val uRowStride: Int,
    val uPixelStride: Int,
    val vRowStride: Int,
    val vPixelStride: Int,
    val yPlaneBytes: ByteArray,
    val uPlaneBytes: ByteArray,
    val vPlaneBytes: ByteArray,
    val trackingState: String,
    val ransacInliers: Int,
    val correctCount: Int,
    val incorrectCount: Int,
    val uncertainCount: Int,
    val payload: V6FrameDiagnosticPayload,
    val warpedLumaBytes: ByteArray?,
    val frameTrace: List<V6FrameTraceEntry>
)
