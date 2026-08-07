package com.superqr.android.vision.v6.model

import com.superqr.android.vision.v6.diagnostic.V6FrameDiagnosticPayload

data class V6BitSample(
    val bitName: String,
    val x: Double,
    val y: Double,
    val medianLuma: Int,
    val localBlackRef: Int,
    val localWhiteRef: Int,
    val threshold: Int,
    val decodedBit: Int
)

data class V6CornerMatch(
    val expectedPattern: String,
    val decodedPattern: String,
    val bestMatchId: String,
    val hammingDistance: Int,
    val secondBestMargin: Int
)

data class V6StaticResult(
    val borderFound: Boolean,
    val detectedQuad: List<DoubleArray>?,
    val contourArea: Double,
    val decodedCornerIds: Map<String, String>,
    val decodedCornerBits: Map<String, String>,
    val decodedCornerDistances: Map<String, Int>,
    val decodedCornerMargins: Map<String, Int>,
    val bitSamples: Map<String, List<V6BitSample>>,
    val cornerMatches: Map<String, V6CornerMatch>,
    val orientationResolved: Boolean,
    val reprojectionError: Double,
    val pilotYUVs: Map<String, IntArray>,
    val cellAccuracy: Double, // % correct against known mode
    val uncertainCells: Int,
    val decodedCrc32: Long?,
    val expectedCrc32: Long?,
    val colorCorrect: Int,
    val colorUncertain: Int,
    val colorTotal: Int = 400,
    val confusionMatrix: Map<String, Map<String, Int>>?,
    val processingTimeMs: Long,
    val failureReason: String?,
    val debugImagePath: String?,
    val trackingState: String = "SEARCHING",
    val ransacInliers: Int = 0,
    val missedFrameCount: Int = 0,
    val trackingPoints: List<DoubleArray>? = null,
    val finalInvHomography: DoubleArray? = null,
    val warpMinLuma: Int = 0,
    val warpMaxLuma: Int = 0,
    val warpMeanLuma: Int = 0,
    val warpCoverage: Double = 0.0,
    val diagnosticPayload: V6FrameDiagnosticPayload? = null,
    val warpedLumaBytes: ByteArray? = null,
    val contoursConsidered: Int = 0,
    val quadsConsidered: Int = 0,
    val transportSessionId: Int? = null,
    val transportFrameId: Int? = null,
    val transportTotalFrames: Int? = null,
    val transportPayloadHex: String? = null,
    val transportCrc16Hex: String? = null,
    val transportError: String? = null,
    val transportFrame: com.superqr.android.vision.v6.transport.V6TransportFrame? = null
)
