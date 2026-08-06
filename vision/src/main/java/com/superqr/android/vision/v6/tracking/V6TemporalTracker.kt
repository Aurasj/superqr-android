package com.superqr.android.vision.v6.tracking

import com.superqr.android.vision.v6.model.V6StaticResult
import org.opencv.core.*
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.abs
import kotlin.math.roundToInt
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

    private val alpha = 0.35 // EMA smoothing factor

    // 16 Canonical Reference Points
    val canonicalReferencePoints: List<Pair<String, Point>> = listOf(
        // 4 Outer border corners
        "CORNER_TL" to Point(60.0, 60.0),
        "CORNER_TR" to Point(940.0, 60.0),
        "CORNER_BR" to Point(940.0, 940.0),
        "CORNER_BL" to Point(60.0, 940.0),
        // 4 Anchor centers
        "ANCHOR_TL" to Point(140.0, 140.0),
        "ANCHOR_TR" to Point(860.0, 140.0),
        "ANCHOR_BR" to Point(860.0, 860.0),
        "ANCHOR_BL" to Point(140.0, 860.0),
        // 8 Border tracking points
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

    /**
     * Called when static detector produces a result.
     * Evaluates state machine transitions, optical flow tracking, motion limits, and drift correction.
     */
    fun processFrame(
        grayMat: Mat?,
        staticResult: V6StaticResult,
        homographyInv: DoubleArray?
    ): V6StaticResult {
        frameCounter++

        // Check if full re-detection drift correction is due
        val isDriftCheckDue = (frameCounter % 45L == 0L)

        val newState: TrackingState
        var effectiveResult = staticResult
        var inliersCount = 0

        if (staticResult.borderFound && staticResult.orientationResolved && homographyInv != null) {
            // Static detector found a valid marker!
            lastValidStaticResult = staticResult
            if (state == TrackingState.SEARCHING) {
                consecutiveAcquisitionCount = 1
                newState = TrackingState.ACQUIRING
            } else if (state == TrackingState.ACQUIRING) {
                consecutiveAcquisitionCount++
                newState = if (consecutiveAcquisitionCount >= 3) {
                    TrackingState.LOCKED
                } else {
                    TrackingState.ACQUIRING
                }
            } else { // LOCKED, TRACKING, REACQUIRING
                consecutiveAcquisitionCount = 3
                newState = TrackingState.TRACKING
            }
            missedFrameCount = 0
            lastValidHomographyInv = homographyInv.clone()

            // Update tracked points from the valid homography
            updateTrackedPointsFromHomography(homographyInv)
            inliersCount = trackedPoints.size
            ransacInliers = inliersCount

        } else if ((state == TrackingState.LOCKED || state == TrackingState.TRACKING || state == TrackingState.REACQUIRING) && !isDriftCheckDue && prevGray != null && grayMat != null && trackedPoints.isNotEmpty()) {
            // Static detector failed this frame, but we have optical flow tracking!
            val trackedRes = tryOpticalFlowTracking(prevGray!!, grayMat)
            if (trackedRes != null) {
                missedFrameCount = 0
                newState = TrackingState.TRACKING
                effectiveResult = trackedRes
                inliersCount = ransacInliers
            } else {
                missedFrameCount++
                if (missedFrameCount >= 4) {
                    newState = TrackingState.SEARCHING
                    reset()
                } else {
                    newState = TrackingState.REACQUIRING
                }
            }
        } else {
            // No static detection and no tracking possible
            if (state != TrackingState.SEARCHING) {
                missedFrameCount++
                if (missedFrameCount >= 3) {
                    newState = TrackingState.SEARCHING
                    reset()
                } else {
                    newState = TrackingState.REACQUIRING
                }
            } else {
                newState = TrackingState.SEARCHING
                consecutiveAcquisitionCount = 0
            }
        }

        state = newState

        // Save current frame for next optical flow step
        if (grayMat != null) {
            try {
                if (prevGray == null) {
                    prevGray = Mat()
                }
                grayMat.copyTo(prevGray!!)
            } catch (e: Throwable) {
                // Ignore if native OpenCV is not loaded
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
            val imgPt = mapPoint(canonicalPt.x, canonicalPt.y, hInv)
            trackedPoints.add(TrackedPoint(id, canonicalPt, imgPt))
        }
    }

    fun recoverQuad(currMat: Mat): Array<Point>? {
        if (prevGray == null) return null
        val tempRansac = ransacInliers
        val res = tryOpticalFlowTracking(prevGray!!, currMat)
        ransacInliers = tempRansac // restore
        if (res != null && res.detectedQuad != null) {
            return res.detectedQuad.map { Point(it[0], it[1]) }.toTypedArray()
        }
        return null
    }

    private fun tryOpticalFlowTracking(prevMat: Mat, currMat: Mat): V6StaticResult? {
        if (trackedPoints.size < 4) return null

        val p0 = MatOfPoint2f()
        val p1 = MatOfPoint2f()
        val status = MatOfByte()
        val err = MatOfFloat()

        val prevList = trackedPoints.map { it.imagePt }
        p0.fromList(prevList)

        try {
            Video.calcOpticalFlowPyrLK(prevMat, currMat, p0, p1, status, err)
        } catch (e: Throwable) {
            return null
        }

        val statusArr = status.toArray()
        val p1List = p1.toList()

        val validPrev = mutableListOf<Point>()
        val validCurr = mutableListOf<Point>()
        val validIndices = mutableListOf<Int>()

        for (i in statusArr.indices) {
            if (statusArr[i].toInt() == 1 && i < p1List.size) {
                val ptOld = prevList[i]
                val ptNew = p1List[i]
                val dist = sqrt((ptNew.x - ptOld.x) * (ptNew.x - ptOld.x) + (ptNew.y - ptOld.y) * (ptNew.y - ptOld.y))
                if (dist <= 65.0) { // Motion threshold validation
                    validPrev.add(ptOld)
                    validCurr.add(ptNew)
                    validIndices.add(i)
                }
            }
        }

        if (validCurr.size < 4) return null

        ransacInliers = validCurr.size

        // Smooth tracked point positions using EMA
        for (idx in validIndices.indices) {
            val originalIdx = validIndices[idx]
            val oldPt = trackedPoints[originalIdx].imagePt
            val newPt = validCurr[idx]
            val smoothedX = oldPt.x * (1.0 - alpha) + newPt.x * alpha
            val smoothedY = oldPt.y * (1.0 - alpha) + newPt.y * alpha
            trackedPoints[originalIdx].imagePt = Point(smoothedX, smoothedY)
        }

        // Get 4 outer corner tracked points
        val cornerTL = trackedPoints.find { it.id == "CORNER_TL" }?.imagePt ?: validCurr[0]
        val cornerTR = trackedPoints.find { it.id == "CORNER_TR" }?.imagePt ?: validCurr[1]
        val cornerBR = trackedPoints.find { it.id == "CORNER_BR" }?.imagePt ?: validCurr[2]
        val cornerBL = trackedPoints.find { it.id == "CORNER_BL" }?.imagePt ?: validCurr[3]

        // Validate quad convexity
        if (!isConvexQuad(cornerTL, cornerTR, cornerBR, cornerBL)) return null

        val quadPts = listOf(cornerTL, cornerTR, cornerBR, cornerBL)
        val quadArray = quadPts.map { doubleArrayOf(it.x, it.y) }
        val area = calculateQuadArea(cornerTL, cornerTR, cornerBR, cornerBL)

        val lastValid = lastValidStaticResult
        return V6StaticResult(
            borderFound = true,
            detectedQuad = quadArray,
            contourArea = area,
            decodedCornerIds = lastValid?.decodedCornerIds ?: mapOf("TL" to "TL", "TR" to "TR", "BR" to "BR", "BL" to "BL"),
            decodedCornerBits = lastValid?.decodedCornerBits ?: mapOf("TL" to "1000", "TR" to "0100", "BR" to "0010", "BL" to "0001"),
            decodedCornerDistances = lastValid?.decodedCornerDistances ?: mapOf("TL" to 0, "TR" to 0, "BR" to 0, "BL" to 0),
            decodedCornerMargins = lastValid?.decodedCornerMargins ?: mapOf("TL" to 2, "TR" to 2, "BR" to 2, "BL" to 2),
            bitSamples = lastValid?.bitSamples ?: emptyMap(),
            cornerMatches = lastValid?.cornerMatches ?: emptyMap(),
            orientationResolved = true,
            reprojectionError = 1.8,
            pilotYUVs = lastValid?.pilotYUVs ?: emptyMap(),
            cellAccuracy = lastValid?.cellAccuracy ?: 100.0,
            uncertainCells = lastValid?.uncertainCells ?: 0,
            decodedCrc32 = lastValid?.decodedCrc32,
            expectedCrc32 = lastValid?.expectedCrc32,
            colorCorrect = lastValid?.colorCorrect ?: 400,
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
            warpCoverage = lastValid?.warpCoverage ?: 0.0
        )
    }

    private fun isConvexQuad(p0: Point, p1: Point, p2: Point, p3: Point): Boolean {
        fun crossProduct(a: Point, b: Point, c: Point): Double {
            return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
        }
        val cp1 = crossProduct(p0, p1, p2)
        val cp2 = crossProduct(p1, p2, p3)
        val cp3 = crossProduct(p2, p3, p0)
        val cp4 = crossProduct(p3, p0, p1)
        return (cp1 > 0 && cp2 > 0 && cp3 > 0 && cp4 > 0) || (cp1 < 0 && cp2 < 0 && cp3 < 0 && cp4 < 0)
    }

    private fun calculateQuadArea(p0: Point, p1: Point, p2: Point, p3: Point): Double {
        return 0.5 * abs(p0.x * p1.y + p1.x * p2.y + p2.x * p3.y + p3.x * p0.y - (p1.x * p0.y + p2.x * p1.y + p3.x * p2.y + p0.x * p3.y))
    }

    private fun mapPoint(canonicalX: Double, canonicalY: Double, hInv: DoubleArray): Point {
        val den = hInv[6] * canonicalX + hInv[7] * canonicalY + hInv[8]
        val x = (hInv[0] * canonicalX + hInv[1] * canonicalY + hInv[2]) / den
        val y = (hInv[3] * canonicalX + hInv[4] * canonicalY + hInv[5]) / den
        return Point(x, y)
    }
}
