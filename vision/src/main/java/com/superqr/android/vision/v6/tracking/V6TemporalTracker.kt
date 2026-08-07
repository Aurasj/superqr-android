package com.superqr.android.vision.v6.tracking

import com.superqr.android.vision.v6.model.V6StaticResult
import org.opencv.core.*
import org.opencv.geometry.Geometry
import org.opencv.video.Video
import kotlin.math.abs
import kotlin.math.sqrt

enum class TrackingState {
    SEARCHING,
    ACQUIRING,
    LOCKED,
    TRACKING,
    REACQUIRING
}

data class TrackedPoint(
    val id: String,
    val canonicalPt: Point,
    var imagePt: Point
)

class V6TemporalTracker {
    var state: TrackingState = TrackingState.SEARCHING
        private set

    var consecutiveAcquisitionCount: Int = 0
        private set

    var missedFrameCount: Int = 0
        private set

    var frameCounter: Long = 0
        private set

    var ransacInliers: Int = 0
        private set

    private var prevGray: Mat? = null
    private var lastValidHomographyInv: DoubleArray? = null
    private var lastValidStaticResult: V6StaticResult? = null
    private var trackedPoints: MutableList<TrackedPoint> = mutableListOf()

    val canonicalReferencePoints: List<Pair<String, Point>> = listOf(
        "CORNER_TL" to Point(60.0, 60.0),
        "CORNER_TR" to Point(940.0, 60.0),
        "CORNER_BR" to Point(940.0, 940.0),
        "CORNER_BL" to Point(60.0, 940.0),
        "ANCHOR_TL" to Point(140.0, 140.0),
        "ANCHOR_TR" to Point(860.0, 140.0),
        "ANCHOR_BR" to Point(860.0, 860.0),
        "ANCHOR_BL" to Point(140.0, 860.0),
        "TOP_1" to Point(220.0, 70.0),
        "TOP_2" to Point(780.0, 70.0),
        "BOTTOM_1" to Point(220.0, 930.0),
        "BOTTOM_2" to Point(780.0, 930.0),
        "LEFT_1" to Point(70.0, 220.0),
        "LEFT_2" to Point(70.0, 780.0),
        "RIGHT_1" to Point(930.0, 220.0),
        "RIGHT_2" to Point(930.0, 780.0)
    )

    fun reset() {
        state = TrackingState.SEARCHING
        consecutiveAcquisitionCount = 0
        missedFrameCount = 0
        ransacInliers = 0
        lastValidHomographyInv = null
        lastValidStaticResult = null
        trackedPoints.clear()
        prevGray?.release()
        prevGray = null
    }

    fun processFrame(
        grayMat: Mat?,
        staticResult: V6StaticResult,
        homographyInv: DoubleArray?
    ): V6StaticResult {
        frameCounter++
        val isDriftCheckDue = frameCounter % 45L == 0L

        val newState: TrackingState
        var effectiveResult = staticResult

        if (staticResult.borderFound && staticResult.orientationResolved && homographyInv != null) {
            lastValidStaticResult = staticResult
            newState = when (state) {
                TrackingState.SEARCHING -> {
                    consecutiveAcquisitionCount = 1
                    TrackingState.ACQUIRING
                }
                TrackingState.ACQUIRING -> {
                    consecutiveAcquisitionCount++
                    if (consecutiveAcquisitionCount >= 3) TrackingState.LOCKED else TrackingState.ACQUIRING
                }
                else -> {
                    consecutiveAcquisitionCount = 3
                    TrackingState.TRACKING
                }
            }
            missedFrameCount = 0
            lastValidHomographyInv = homographyInv.clone()
            updateTrackedPointsFromHomography(homographyInv)
            ransacInliers = trackedPoints.size
        } else if (
            (state == TrackingState.LOCKED || state == TrackingState.TRACKING || state == TrackingState.REACQUIRING) &&
            !isDriftCheckDue && prevGray != null && grayMat != null && trackedPoints.isNotEmpty()
        ) {
            val trackedRes = tryOpticalFlowTracking(grayMat)
            if (trackedRes != null) {
                missedFrameCount = 0
                newState = TrackingState.TRACKING
                effectiveResult = trackedRes
            } else {
                missedFrameCount++
                newState = if (missedFrameCount >= 4) {
                    reset()
                    TrackingState.SEARCHING
                } else {
                    TrackingState.REACQUIRING
                }
            }
        } else {
            if (state != TrackingState.SEARCHING) {
                missedFrameCount++
                newState = if (missedFrameCount >= 3) {
                    reset()
                    TrackingState.SEARCHING
                } else {
                    TrackingState.REACQUIRING
                }
            } else {
                consecutiveAcquisitionCount = 0
                newState = TrackingState.SEARCHING
            }
        }

        state = newState

        if (grayMat != null) {
            try {
                if (prevGray == null) prevGray = Mat()
                grayMat.copyTo(prevGray!!)
            } catch (_: Throwable) {
            }
        }

        val trackingPointsOutput = trackedPoints.map { doubleArrayOf(it.imagePt.x, it.imagePt.y) }

        var resultToReturn = effectiveResult
        val lastValid = lastValidStaticResult
        if (lastValid != null && (state == TrackingState.LOCKED || state == TrackingState.TRACKING || state == TrackingState.REACQUIRING)) {
            if (resultToReturn.warpCoverage == 0.0 && lastValid.warpCoverage > 0.0) {
                resultToReturn = resultToReturn.copy(
                    warpMinLuma = lastValid.warpMinLuma,
                    warpMaxLuma = lastValid.warpMaxLuma,
                    warpMeanLuma = lastValid.warpMeanLuma,
                    warpCoverage = lastValid.warpCoverage,
                    bitSamples = if (resultToReturn.bitSamples.isEmpty()) lastValid.bitSamples else resultToReturn.bitSamples,
                    cornerMatches = if (resultToReturn.cornerMatches.isEmpty()) lastValid.cornerMatches else resultToReturn.cornerMatches,
                    pilotYUVs = if (resultToReturn.pilotYUVs.isEmpty()) lastValid.pilotYUVs else resultToReturn.pilotYUVs
                )
            }
        }

        return resultToReturn.copy(
            trackingState = state.name,
            ransacInliers = ransacInliers,
            missedFrameCount = missedFrameCount,
            trackingPoints = trackingPointsOutput
        )
    }

    private fun updateTrackedPointsFromHomography(hInv: DoubleArray) {
        trackedPoints.clear()
        for ((id, canonicalPt) in canonicalReferencePoints) {
            trackedPoints.add(TrackedPoint(id, canonicalPt, mapPoint(canonicalPt.x, canonicalPt.y, hInv)))
        }
    }

    // ── shared 16-point optical-flow geometry primitive ─────────────────
    // Runs optical flow on all 16 canonical tracking points from prevGray
    // into currMat, stores raw current-frame positions in trackedPoints,
    // and returns the 4 raw current-frame corner positions.
    //
    // Returns null when fewer than 4 points track, any corner point fails
    // the validity check, the resulting quad is non-convex, or prevGray is
    // unavailable.
    private fun trackCurrentQuad(currMat: Mat): Array<Point>? {
        val prev = prevGray ?: return null
        if (trackedPoints.size < 4) return null

        val p0 = MatOfPoint2f()
        val p1 = MatOfPoint2f()
        val status = MatOfByte()
        val err = MatOfFloat()
        val prevList = trackedPoints.map { it.imagePt }
        p0.fromList(prevList)

        try {
            Video.calcOpticalFlowPyrLK(prev, currMat, p0, p1, status, err)
        } catch (_: Throwable) {
            p0.release(); p1.release(); status.release(); err.release()
            return null
        }

        val statusArr = status.toArray()
        val p1List = p1.toList()
        val validCurr = mutableListOf<Point>()
        val validIndices = mutableListOf<Int>()

        for (i in statusArr.indices) {
            if (statusArr[i].toInt() == 1 && i < p1List.size) {
                val oldPt = prevList[i]
                val newPt = p1List[i]
                val dist = sqrt((newPt.x - oldPt.x) * (newPt.x - oldPt.x) + (newPt.y - oldPt.y) * (newPt.y - oldPt.y))
                if (dist <= 65.0) {
                    validCurr.add(newPt)
                    validIndices.add(i)
                }
            }
        }

        p0.release(); p1.release(); status.release(); err.release()
        if (validCurr.size < 4) return null

        // Require that all four corner points tracked individually.
        // CORNER_TL=0, CORNER_TR=1, CORNER_BR=2, CORNER_BL=3 per
        // canonicalReferencePoints ordering used by updateTrackedPointsFromHomography.
        if (0 !in validIndices || 1 !in validIndices || 2 !in validIndices || 3 !in validIndices) {
            return null
        }
        val cornerTL = p1List[0]
        val cornerTR = p1List[1]
        val cornerBR = p1List[2]
        val cornerBL = p1List[3]

        if (!isConvexQuad(cornerTL, cornerTR, cornerBR, cornerBL)) return null

        // Store raw current-frame OF positions for every valid tracked point.
        // No EMA / temporal smoothing — these are the actual feature positions
        // in the current frame and the seeds for the next optical-flow step.
        ransacInliers = validCurr.size
        for (idx in validIndices.indices) {
            val originalIdx = validIndices[idx]
            trackedPoints[originalIdx].imagePt = validCurr[idx]
        }

        return arrayOf(cornerTL, cornerTR, cornerBR, cornerBL)
    }

    // ── public proactive-tracking entry point ──────────────────────────
    // Called by the detector BEFORE contour acquisition when the tracker
    // is LOCKED/TRACKING and periodic re-detection is not due.
    // Returns the current corner quad from optical flow, or null.
    fun tryProactiveTracking(currMat: Mat): Array<Point>? {
        if (state != TrackingState.LOCKED && state != TrackingState.TRACKING) return null
        // Sync with processFrame's drift-check cadence: every 45th frame
        // forces contour-based FULL_DETECTION to prevent slow drift.
        if ((frameCounter + 1) % 45L == 0L) return null
        return trackCurrentQuad(currMat)
    }

    fun recoverQuad(currMat: Mat): Array<Point>? {
        val tempRansac = ransacInliers
        val quad = trackCurrentQuad(currMat)
        ransacInliers = tempRansac
        return quad
    }

    // ── held/stale tracking result (geometry current, classification stale) ─
    private fun tryOpticalFlowTracking(currMat: Mat): V6StaticResult? {
        val quad = trackCurrentQuad(currMat) ?: return null
        val quadArray = listOf(quad[0], quad[1], quad[2], quad[3]).map { doubleArrayOf(it.x, it.y) }
        val area = calculateQuadArea(quad[0], quad[1], quad[2], quad[3])
        val lastValid = lastValidStaticResult

        // A tracked result provides current geometry only. Classification and transport
        // metadata belong to the previous full detection and must never masquerade as
        // current-frame data.
        val trackedPayload = lastValid?.diagnosticPayload?.copy(
            classificationSource = "TRACKED_HOMOGRAPHY",
            transportSessionId = null,
            transportFrameId = null,
            transportTotalFrames = null,
            transportPayloadHex = null,
            transportCrc16Hex = null,
            transportError = "HELD_TRACKING_NOT_FRESH"
        )

        return V6StaticResult(
            borderFound = true,
            detectedQuad = quadArray,
            contourArea = area,
            decodedCornerIds = lastValid?.decodedCornerIds ?: emptyMap(),
            decodedCornerBits = lastValid?.decodedCornerBits ?: emptyMap(),
            decodedCornerDistances = lastValid?.decodedCornerDistances ?: emptyMap(),
            decodedCornerMargins = lastValid?.decodedCornerMargins ?: emptyMap(),
            bitSamples = lastValid?.bitSamples ?: emptyMap(),
            cornerMatches = lastValid?.cornerMatches ?: emptyMap(),
            orientationResolved = true,
            reprojectionError = Double.NaN,
            pilotYUVs = lastValid?.pilotYUVs ?: emptyMap(),
            cellAccuracy = lastValid?.cellAccuracy ?: 0.0,
            uncertainCells = lastValid?.uncertainCells ?: 0,
            decodedCrc32 = lastValid?.decodedCrc32,
            expectedCrc32 = lastValid?.expectedCrc32,
            colorCorrect = lastValid?.colorCorrect ?: 0,
            colorUncertain = lastValid?.colorUncertain ?: 0,
            colorTotal = lastValid?.colorTotal ?: 400,
            confusionMatrix = lastValid?.confusionMatrix,
            processingTimeMs = 4,
            failureReason = null,
            debugImagePath = null,
            finalInvHomography = lastValidHomographyInv,
            warpMinLuma = lastValid?.warpMinLuma ?: 0,
            warpMaxLuma = lastValid?.warpMaxLuma ?: 0,
            warpMeanLuma = lastValid?.warpMeanLuma ?: 0,
            warpCoverage = lastValid?.warpCoverage ?: 0.0,
            diagnosticPayload = trackedPayload,
            contoursConsidered = lastValid?.contoursConsidered ?: 0,
            quadsConsidered = lastValid?.quadsConsidered ?: 0,
            transportSessionId = null,
            transportFrameId = null,
            transportTotalFrames = null,
            transportPayloadHex = null,
            transportCrc16Hex = null,
            transportError = "HELD_TRACKING_NOT_FRESH",
            transportFrame = null
        )
    }

    private fun isConvexQuad(p0: Point, p1: Point, p2: Point, p3: Point): Boolean {
        fun crossProduct(a: Point, b: Point, c: Point): Double =
            (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

        val cp1 = crossProduct(p0, p1, p2)
        val cp2 = crossProduct(p1, p2, p3)
        val cp3 = crossProduct(p2, p3, p0)
        val cp4 = crossProduct(p3, p0, p1)
        return (cp1 > 0 && cp2 > 0 && cp3 > 0 && cp4 > 0) ||
            (cp1 < 0 && cp2 < 0 && cp3 < 0 && cp4 < 0)
    }

    private fun calculateQuadArea(p0: Point, p1: Point, p2: Point, p3: Point): Double =
        0.5 * abs(
            p0.x * p1.y + p1.x * p2.y + p2.x * p3.y + p3.x * p0.y -
                (p1.x * p0.y + p2.x * p1.y + p3.x * p2.y + p0.x * p3.y)
        )

    private fun mapPoint(canonicalX: Double, canonicalY: Double, hInv: DoubleArray): Point {
        val den = hInv[6] * canonicalX + hInv[7] * canonicalY + hInv[8]
        return Point(
            (hInv[0] * canonicalX + hInv[1] * canonicalY + hInv[2]) / den,
            (hInv[3] * canonicalX + hInv[4] * canonicalY + hInv[5]) / den
        )
    }
}
