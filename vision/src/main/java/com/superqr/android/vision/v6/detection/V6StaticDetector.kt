package com.superqr.android.vision.v6.detection

import com.superqr.android.vision.v6.classification.ChromaPixelReader
import com.superqr.android.vision.v6.classification.Xorshift32
import com.superqr.android.vision.v6.contract.V6Contract
import com.superqr.android.vision.v6.diagnostic.*
import com.superqr.android.vision.v6.model.*
import com.superqr.android.vision.v6.tracking.V6TemporalTracker
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.geometry.Geometry
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

class V6StaticDetector : AutoCloseable {
    private var openCvInitialized = false
    private val tracker = V6TemporalTracker()
    private lateinit var gray: Mat
    private lateinit var blurred: Mat
    private lateinit var edges: Mat
    private lateinit var hierarchy: Mat
    private lateinit var warped: Mat

    private fun ensureOpenCvInitialized() {
        if (openCvInitialized) return
        try {
            if (!OpenCVLoader.initLocal()) {
                throw RuntimeException("OpenCV initialization failed")
            }
        } catch (e: Throwable) {
            // In JVM unit tests, android.util.Log is not mocked and throws an exception.
            // But OpenCV native library is often loaded manually via System.loadLibrary.
            try {
                Core.getVersionString()
            } catch (e2: Throwable) {
                throw RuntimeException("The bundled OpenCV Android runtime could not be initialized.", e)
            }
        }
        gray = Mat()
        blurred = Mat()
        edges = Mat()
        hierarchy = Mat()
        warped = Mat()
        openCvInitialized = true
    }

    override fun close() {
        if (openCvInitialized) {
            if (::gray.isInitialized) gray.release()
            if (::blurred.isInitialized) blurred.release()
            if (::edges.isInitialized) edges.release()
            if (::hierarchy.isInitialized) hierarchy.release()
            if (::warped.isInitialized) warped.release()
            openCvInitialized = false
        }
    }

    fun getGrayMat(): Mat = gray

    fun detect(luma: ByteArray, width: Int, height: Int, mode: String, chromaReader: ChromaPixelReader? = null, exportDebugImage: Boolean = false, cacheDir: String? = null): V6StaticResult {
        val startTime = System.currentTimeMillis()

        try {
            ensureOpenCvInitialized()

            gray.create(height, width, CvType.CV_8UC1)
            gray.put(0, 0, luma)

            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, 50.0, 150.0)

            val contours = ArrayList<MatOfPoint>()
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            var bestPts: Array<Point>? = null
            var maxArea = 0.0
            
            var contoursConsidered = 0
            var quadsConsidered = 0

            class QuadCandidate(val points: Array<Point>, val area: Double, val score: Double)
            val candidates = mutableListOf<QuadCandidate>()

            val cvContour2f = MatOfPoint2f()
            val approxCurve = MatOfPoint2f()
            val hull = org.opencv.core.MatOfInt()

            for (contour in contours) {
                val area = Geometry.contourArea(contour)
                if (area < 10000) continue
                contoursConsidered++

                contour.convertTo(cvContour2f, CvType.CV_32F)
                val perimeter = Geometry.arcLength(cvContour2f, true)
                Geometry.approxPolyDP(cvContour2f, approxCurve, 0.02 * perimeter, true)

                var pts = approxCurve.toArray()
                if (pts.size != 4) {
                    Geometry.convexHull(contour, hull)
                    val hullPoints = arrayOfNulls<Point>(hull.rows())
                    val contourPts = contour.toArray()
                    for (i in 0 until hull.rows()) {
                        hullPoints[i] = contourPts[hull.get(i, 0)[0].toInt()]
                    }
                    val hullMat2f = MatOfPoint2f(*hullPoints.map { it!! }.toTypedArray())
                    val hullPerimeter = Geometry.arcLength(hullMat2f, true)
                    Geometry.approxPolyDP(hullMat2f, approxCurve, 0.04 * hullPerimeter, true)
                    pts = approxCurve.toArray()
                    hullMat2f.release()
                }

                if (pts.size == 4) {
                    val ptsMatOfPoint = MatOfPoint(*pts)
                    val isConvex = Geometry.isContourConvex(ptsMatOfPoint)
                    ptsMatOfPoint.release()
                    if (isConvex) {
                        quadsConsidered++
                        candidates.add(QuadCandidate(pts, area, area))
                    }
                } else {
                    val box = Geometry.minAreaRect(cvContour2f)
                    val boxArea = box.size.width * box.size.height
                    if (boxArea > 0 && area / boxArea > 0.75) {
                        val cvPoints = arrayOfNulls<Point>(4)
                        box.points(cvPoints)
                        quadsConsidered++
                        candidates.add(QuadCandidate(cvPoints.map { it!! }.toTypedArray(), area, area * 0.9))
                    }
                }
            }

            cvContour2f.release()
            approxCurve.release()
            hull.release()
            
            val bestCandidate = candidates.maxByOrNull { it.score }
            if (bestCandidate != null) {
                bestPts = bestCandidate.points
                maxArea = bestCandidate.area
            }

            contours.forEach { it.release() }
            contours.clear()

            var usedTrackerFallback = false
            if (bestPts == null) {
                bestPts = tracker.recoverQuad(gray)
                if (bestPts != null) {
                    maxArea = Geometry.contourArea(MatOfPoint(*bestPts))
                    usedTrackerFallback = true
                }
            }

            val classificationSource = if (usedTrackerFallback) "TRACKED_HOMOGRAPHY" else "FULL_DETECTION"

            if (bestPts == null) {
                val res = V6StaticResult(false, null, maxArea, emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), false, 0.0, emptyMap(), 0.0, 0, null, null, 0, 0, 400, null, System.currentTimeMillis() - startTime, "Outer border not found: max valid area was ${maxArea.roundToInt()}", null, contoursConsidered = contoursConsidered, quadsConsidered = quadsConsidered)
                val trackedRes = tracker.processFrame(if (::gray.isInitialized) gray else null, res, null)
                recordFrameTrace(trackedRes, emptyMap(), 0, 0, 0)
                return trackedRes
            }

            val ptsList = bestPts.toList()
            val cx = ptsList.map { it.x }.average()
            val cy = ptsList.map { it.y }.average()
            val initialSrcPoints = ptsList.sortedBy { Math.atan2(it.y - cy, it.x - cx) }

            val canonicalPoints = listOf(
                Point(60.0, 60.0),
                Point(940.0, 60.0),
                Point(940.0, 940.0),
                Point(60.0, 940.0)
            )
            
            val detectedQuadArray = initialSrcPoints.map { doubleArrayOf(it.x, it.y) }

            val hArr = solveHomographySimple(
                initialSrcPoints,
                canonicalPoints
            ) ?: run {
                val res = V6StaticResult(true, detectedQuadArray, maxArea, emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), false, 0.0, emptyMap(), 0.0, 0, null, null, 0, 0, 400, null, System.currentTimeMillis() - startTime, "Initial Homography failed", null, contoursConsidered = contoursConsidered, quadsConsidered = quadsConsidered)
                val trackedRes = tracker.processFrame(if (::gray.isInitialized) gray else null, res, null)
                recordFrameTrace(trackedRes, emptyMap(), 0, 0, 0)
                return trackedRes
            }
            
            val hMat = Mat(3, 3, CvType.CV_64F)
            hMat.put(0, 0, *hArr)

            Imgproc.warpPerspective(gray, warped, hMat, Size(1000.0, 1000.0), Imgproc.INTER_NEAREST)

            val minMax = Core.minMaxLoc(warped)
            val mean = Core.mean(warped)
            val nonZero = Core.countNonZero(warped)
            val coverage = nonZero.toDouble() / (1000.0 * 1000.0)
            val warpMin = minMax.minVal.toInt()
            val warpMax = minMax.maxVal.toInt()
            val warpMean = mean.`val`[0].toInt()

            if (coverage < 0.1 || warpMax == 0) {
                val res = V6StaticResult(true, detectedQuadArray, maxArea, emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), false, 0.0, emptyMap(), 0.0, 0, null, null, 0, 0, 400, null, System.currentTimeMillis() - startTime, "WARP_INVALID: coverage=$coverage, max=$warpMax", null, warpMinLuma = warpMin, warpMaxLuma = warpMax, warpMeanLuma = warpMean, warpCoverage = coverage, contoursConsidered = contoursConsidered, quadsConsidered = quadsConsidered)
                val trackedRes = tracker.processFrame(if (::gray.isInitialized) gray else null, res, null)
                recordFrameTrace(trackedRes, emptyMap(), 0, 0, 0)
                return trackedRes
            }

            fun sampleMedianLuma(cx: Double, cy: Double): Int {
                val values = mutableListOf<Int>()
                val icx = cx.roundToInt()
                val icy = cy.roundToInt()
                val arr = ByteArray(1)
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        val px = (icx + dx).coerceIn(0, 999)
                        val py = (icy + dy).coerceIn(0, 999)
                        warped.get(py, px, arr)
                        values.add(arr[0].toInt() and 0xFF)
                    }
                }
                values.sort()
                return values[12]
            }

            val decodedCornerIds = mutableMapOf<String, String>()
            val decodedCornerBits = mutableMapOf<String, String>()
            val decodedCornerDistances = mutableMapOf<String, Int>()
            val decodedCornerMargins = mutableMapOf<String, Int>()
            val cornerMatchesMap = mutableMapOf<String, V6CornerMatch>()
            val bitSamplesMap = mutableMapOf<String, List<V6BitSample>>()
            val anchorEvalMap = mutableMapOf<String, AnchorMatchResult>()
            val anchorKeys = listOf("TL", "TR", "BR", "BL")

            for (key in anchorKeys) {
                val core = V6Contract.getAnchorCoreBBox(key)
                val anchorBBox = V6Contract.getAnchorBBox(key)
                val x1 = core.x1
                val y1 = core.y1
                val w = core.width
                val h = core.height
                
                // Robust black ring reference: sample 4 interior ring midpoints safely inside the black stroke (offset 10.0px)
                val ringSamples = listOf(
                    sampleMedianLuma(anchorBBox.x1 + 10.0, anchorBBox.y1 + 10.0),
                    sampleMedianLuma(anchorBBox.x2 - 10.0, anchorBBox.y1 + 10.0),
                    sampleMedianLuma(anchorBBox.x2 - 10.0, anchorBBox.y2 - 10.0),
                    sampleMedianLuma(anchorBBox.x1 + 10.0, anchorBBox.y2 - 10.0)
                )
                val ringLuma = ringSamples.sorted()[1]

                // Robust white core reference: sample 4 core quadrant centers
                val cxTL = x1 + w * 0.25
                val cyTL = y1 + h * 0.25
                val cxTR = x1 + w * 0.75
                val cyTR = y1 + h * 0.25
                val cxBR = x1 + w * 0.75
                val cyBR = y1 + h * 0.75
                val cxBL = x1 + w * 0.25
                val cyBL = y1 + h * 0.75

                val medTL = sampleMedianLuma(cxTL, cyTL)
                val medTR = sampleMedianLuma(cxTR, cyTR)
                val medBR = sampleMedianLuma(cxBR, cyBR)
                val medBL = sampleMedianLuma(cxBL, cyBL)

                // 3 quadrants in anchor core are white, 1 is black -> sort ascending to get white core reference identity-independently
                val sortedCore = listOf(medTL, medTR, medBR, medBL).sorted()
                val coreWhiteRef = (sortedCore[1] + sortedCore[2] + sortedCore[3]) / 3
                val contrast = coreWhiteRef - ringLuma
                val threshold = (ringLuma + coreWhiteRef) / 2

                // Identity-independent bit convention: BLACK = 1 (< threshold), WHITE = 0 (>= threshold)
                val bTL = if (medTL < threshold) 1 else 0
                val bTR = if (medTR < threshold) 1 else 0
                val bBR = if (medBR < threshold) 1 else 0
                val bBL = if (medBL < threshold) 1 else 0

                val bitSamplesList = listOf(
                    V6BitSample("TL", cxTL, cyTL, medTL, ringLuma, coreWhiteRef, threshold, bTL),
                    V6BitSample("TR", cxTR, cyTR, medTR, ringLuma, coreWhiteRef, threshold, bTR),
                    V6BitSample("BR", cxBR, cyBR, medBR, ringLuma, coreWhiteRef, threshold, bBR),
                    V6BitSample("BL", cxBL, cyBL, medBL, ringLuma, coreWhiteRef, threshold, bBL)
                )
                bitSamplesMap[key] = bitSamplesList

                val decodedBits = "$bTL$bTR$bBR$bBL"
                decodedCornerBits[key] = decodedBits

                val anchorEval = V6OrientationEvaluator.evaluateAnchorBits(key, decodedBits, contrast)
                anchorEvalMap[key] = anchorEval

                val expectedPattern = V6Contract.getAnchorIdentityPattern(key)
                decodedCornerIds[key] = anchorEval.bestMatchId
                decodedCornerDistances[key] = anchorEval.bestDist
                decodedCornerMargins[key] = anchorEval.margin
                cornerMatchesMap[key] = V6CornerMatch(
                    expectedPattern = expectedPattern,
                    decodedPattern = decodedBits,
                    bestMatchId = anchorEval.bestMatchId,
                    hammingDistance = anchorEval.bestDist,
                    secondBestMargin = anchorEval.margin
                )
            }
            
            val orientationResolved = V6OrientationEvaluator.isOrientationResolved(anchorEvalMap)

            if (!orientationResolved) {
                val res = V6StaticResult(
                    borderFound = true,
                    detectedQuad = detectedQuadArray,
                    contourArea = maxArea,
                    decodedCornerIds = decodedCornerIds,
                    decodedCornerBits = decodedCornerBits,
                    decodedCornerDistances = decodedCornerDistances,
                    decodedCornerMargins = decodedCornerMargins,
                    bitSamples = bitSamplesMap,
                    cornerMatches = cornerMatchesMap,
                    orientationResolved = false,
                    reprojectionError = Double.NaN,
                    pilotYUVs = emptyMap(),
                    cellAccuracy = 0.0,
                    uncertainCells = 0,
                    decodedCrc32 = null,
                    expectedCrc32 = null,
                    colorCorrect = 0,
                    colorUncertain = 0,
                    colorTotal = 400,
                    confusionMatrix = null,
                    processingTimeMs = System.currentTimeMillis() - startTime,
                    failureReason = "Orientation invalid or ambiguous",
                    debugImagePath = null,
                    warpMinLuma = warpMin,
                    warpMaxLuma = warpMax,
                    warpMeanLuma = warpMean,
                    warpCoverage = coverage,
                    contoursConsidered = contoursConsidered,
                    quadsConsidered = quadsConsidered
                )
                val trackedRes = tracker.processFrame(if (::gray.isInitialized) gray else null, res, null)
                recordFrameTrace(trackedRes, emptyMap(), 0, 0, 0)
                return trackedRes
            }

            val physicalIdToPoint = mutableMapOf<String, Point>()
            physicalIdToPoint[decodedCornerIds["TL"]!!] = initialSrcPoints[0]
            physicalIdToPoint[decodedCornerIds["TR"]!!] = initialSrcPoints[1]
            physicalIdToPoint[decodedCornerIds["BR"]!!] = initialSrcPoints[2]
            physicalIdToPoint[decodedCornerIds["BL"]!!] = initialSrcPoints[3]

            val correctedSrcPoints = listOf(
                physicalIdToPoint["TL"]!!,
                physicalIdToPoint["TR"]!!,
                physicalIdToPoint["BR"]!!,
                physicalIdToPoint["BL"]!!
            )
            
            val finalHArr = solveHomographySimple(
                correctedSrcPoints,
                canonicalPoints
            ) ?: run {
                val res = V6StaticResult(true, detectedQuadArray, maxArea, decodedCornerIds, decodedCornerBits, decodedCornerDistances, decodedCornerMargins, bitSamplesMap, cornerMatchesMap, false, 0.0, emptyMap(), 0.0, 0, null, null, 0, 0, 400, null, System.currentTimeMillis() - startTime, "Final Homography failed", null, warpMinLuma = warpMin, warpMaxLuma = warpMax, warpMeanLuma = warpMean, warpCoverage = coverage, contoursConsidered = contoursConsidered, quadsConsidered = quadsConsidered)
                val trackedRes = tracker.processFrame(if (::gray.isInitialized) gray else null, res, null)
                recordFrameTrace(trackedRes, emptyMap(), 0, 0, 0)
                return trackedRes
            }
            
            val finalHMat = Mat(3, 3, CvType.CV_64F)
            finalHMat.put(0, 0, *finalHArr)
            Imgproc.warpPerspective(gray, warped, finalHMat, Size(1000.0, 1000.0), Imgproc.INTER_NEAREST)
            
            val finalInvHArr = solveHomographySimple(
                canonicalPoints,
                correctedSrcPoints
            ) ?: run {
                val res = V6StaticResult(true, detectedQuadArray, maxArea, decodedCornerIds, decodedCornerBits, decodedCornerDistances, decodedCornerMargins, bitSamplesMap, cornerMatchesMap, false, 0.0, emptyMap(), 0.0, 0, null, null, 0, 0, 400, null, System.currentTimeMillis() - startTime, "Final Inv Homography failed", null, warpMinLuma = warpMin, warpMaxLuma = warpMax, warpMeanLuma = warpMean, warpCoverage = coverage, contoursConsidered = contoursConsidered, quadsConsidered = quadsConsidered)
                val trackedRes = tracker.processFrame(if (::gray.isInitialized) gray else null, res, null)
                recordFrameTrace(trackedRes, emptyMap(), 0, 0, 0)
                return trackedRes
            }

            fun mapPoint(canonicalX: Double, canonicalY: Double): Point {
                val den = finalInvHArr[6] * canonicalX + finalInvHArr[7] * canonicalY + finalInvHArr[8]
                val x = (finalInvHArr[0] * canonicalX + finalInvHArr[1] * canonicalY + finalInvHArr[2]) / den
                val y = (finalInvHArr[3] * canonicalX + finalInvHArr[4] * canonicalY + finalInvHArr[5]) / den
                return Point(x, y)
            }

            data class SampleReadResult(
                val canonicalX: Double,
                val canonicalY: Double,
                val pt: Point,
                val y: Int,
                val u: Int,
                val v: Int,
                val ySuccess: Boolean,
                val uSuccess: Boolean,
                val vSuccess: Boolean
            )

            fun sampleYUVDetailed(canonicalX: Double, canonicalY: Double): SampleReadResult {
                val pt = mapPoint(canonicalX, canonicalY)
                val px = pt.x.roundToInt()
                val py = pt.y.roundToInt()
                val ySuccess = px in 0 until width && py in 0 until height
                val y = if (ySuccess) luma[py * width + px].toInt() and 0xFF else 128
                var u = 128
                var v = 128
                var uSuccess = false
                var vSuccess = false
                if (chromaReader != null) {
                    val uv = IntArray(2)
                    if (chromaReader.read(pt.x, pt.y, uv)) {
                        u = uv[0]
                        v = uv[1]
                        uSuccess = true
                        vSuccess = true
                    }
                }
                return SampleReadResult(canonicalX, canonicalY, pt, y, u, v, ySuccess, uSuccess, vSuccess)
            }

            val pilots = mutableMapOf<String, IntArray>()
            val pilotDetailsList = mutableListOf<V6PilotDiagnosticDetail>()
            val pilotNames = listOf("BLACK", "WHITE", "RED", "BLUE")

            for (pilot in pilotNames) {
                val bbox = V6Contract.getPilotCoreBBox(pilot)
                val cx = bbox.centerX
                val cy = bbox.centerY
                val offset = bbox.width * 0.25
                val detailedSamples = listOf(
                    sampleYUVDetailed(cx, cy),
                    sampleYUVDetailed(cx - offset, cy - offset),
                    sampleYUVDetailed(cx + offset, cy - offset),
                    sampleYUVDetailed(cx - offset, cy + offset),
                    sampleYUVDetailed(cx + offset, cy + offset)
                )
                val medY = detailedSamples.map { it.y }.sorted()[2]
                val medU = detailedSamples.map { it.u }.sorted()[2]
                val medV = detailedSamples.map { it.v }.sorted()[2]
                pilots[pilot] = intArrayOf(medY, medU, medV)

                val centerSample = detailedSamples[0]
                pilotDetailsList.add(
                    V6PilotDiagnosticDetail(
                        pilotName = pilot,
                        canonicalCenterX = cx,
                        canonicalCenterY = cy,
                        mappedCameraCenterX = centerSample.pt.x,
                        mappedCameraCenterY = centerSample.pt.y,
                        ySuccess = centerSample.ySuccess,
                        uSuccess = centerSample.uSuccess,
                        vSuccess = centerSample.vSuccess,
                        rawSamples = detailedSamples.map { intArrayOf(it.y, it.u, it.v) },
                        medianY = medY,
                        medianU = medU,
                        medianV = medV
                    )
                )
            }

            fun sqDistYuv(p1: IntArray, p2: IntArray): Double {
                val dy = p1[0] - p2[0]
                val du = p1[1] - p2[1]
                val dv = p1[2] - p2[2]
                return (dy * dy + du * du + dv * dv).toDouble()
            }

            val pairwisePilotDistances = mapOf(
                "DIST_BLACK_WHITE" to sqDistYuv(pilots["BLACK"]!!, pilots["WHITE"]!!),
                "DIST_BLACK_RED" to sqDistYuv(pilots["BLACK"]!!, pilots["RED"]!!),
                "DIST_BLACK_BLUE" to sqDistYuv(pilots["BLACK"]!!, pilots["BLUE"]!!),
                "DIST_WHITE_RED" to sqDistYuv(pilots["WHITE"]!!, pilots["RED"]!!),
                "DIST_WHITE_BLUE" to sqDistYuv(pilots["WHITE"]!!, pilots["BLUE"]!!),
                "DIST_RED_BLUE" to sqDistYuv(pilots["RED"]!!, pilots["BLUE"]!!)
            )

            val cols = V6Contract.getGridCols()
            val rows = V6Contract.getGridRows()
            val ratio = V6Contract.getCentralRegionRatio()
            var correct = 0
            var uncertain = 0

            val gridBBox = V6Contract.getGridBBox()
            val cellSize = V6Contract.getCellSize()
            
            val prng = Xorshift32(42)
            val decodedBytes = ByteArray(cols * rows)
            val expectedBytes = ByteArray(cols * rows)
            
            val confusionMatrix = mutableMapOf<String, MutableMap<String, Int>>()
            val colorNames = arrayOf("BLACK", "WHITE", "RED", "BLUE")
            for (c in colorNames) {
                confusionMatrix[c] = mutableMapOf("BLACK" to 0, "WHITE" to 0, "RED" to 0, "BLUE" to 0, "UNCERTAIN" to 0)
            }

            val cellDetailsList = mutableListOf<V6CellDiagnosticDetail>()

            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val cx = gridBBox.x1 + (c + 0.5) * cellSize
                    val cy = gridBBox.y1 + (r + 0.5) * cellSize
                    
                    val offset = cellSize * ratio / 2.0
                    val detailedSamples = listOf(
                        sampleYUVDetailed(cx, cy),
                        sampleYUVDetailed(cx - offset, cy - offset),
                        sampleYUVDetailed(cx + offset, cy - offset),
                        sampleYUVDetailed(cx - offset, cy + offset),
                        sampleYUVDetailed(cx + offset, cy + offset)
                    )
                    
                    val medY = detailedSamples.map { it.y }.sorted()[2]
                    val medU = detailedSamples.map { it.u }.sorted()[2]
                    val medV = detailedSamples.map { it.v }.sorted()[2]
                    
                    var bestDist = Double.MAX_VALUE
                    var secondBestDist = Double.MAX_VALUE
                    var bestColorIdx = -1
                    val distMap = mutableMapOf<String, Double>()
                    
                    for ((idx, colorName) in colorNames.withIndex()) {
                        val p = pilots[colorName]!!
                        val dy = medY - p[0]
                        val du = medU - p[1]
                        val dv = medV - p[2]
                        val dist = (dy * dy + du * du + dv * dv).toDouble()
                        distMap[colorName] = dist
                        
                        if (dist < bestDist) {
                            secondBestDist = bestDist
                            bestDist = dist
                            bestColorIdx = idx
                        } else if (dist < secondBestDist) {
                            secondBestDist = dist
                        }
                    }
                    
                    var decodedIdx = bestColorIdx
                    var uncertainReason = "NONE"
                    if (bestDist > 40000.0) {
                        decodedIdx = -1
                        uncertainReason = "DIST_EXCEEDS_MAX"
                    } else if (secondBestDist > 0 && bestDist / secondBestDist > 0.9) {
                        decodedIdx = -1
                        uncertainReason = "MARGIN_TOO_THIN"
                    }
                    
                    val expectedIdx = when (mode) {
                        "black", "all-black" -> 0
                        "white", "all-white" -> 1
                        "checkerboard" -> (r + c) % 4
                        "deterministic_random", "deterministic random" -> (prng.nextInt() ushr 16) and 3
                        else -> (prng.nextInt() ushr 16) and 3
                    }
                    
                    val expectedName = colorNames[expectedIdx]
                    val decodedName = if (decodedIdx == -1) "UNCERTAIN" else colorNames[decodedIdx]
                    
                    confusionMatrix[expectedName]!![decodedName] = confusionMatrix[expectedName]!![decodedName]!! + 1
                    
                    val status = if (decodedIdx == -1) {
                        uncertain++
                        "UNCERTAIN"
                    } else if (decodedIdx == expectedIdx) {
                        correct++
                        "CORRECT"
                    } else {
                        "WRONG"
                    }

                    val margin = if (secondBestDist > 0 && secondBestDist < Double.MAX_VALUE) bestDist / secondBestDist else 0.0

                    cellDetailsList.add(
                        V6CellDiagnosticDetail(
                            row = r,
                            col = c,
                            expectedIdx = expectedIdx,
                            expectedColor = expectedName,
                            decodedIdx = decodedIdx,
                            decodedColor = decodedName,
                            status = status,
                            classificationSource = classificationSource,
                            canonicalCenterX = cx,
                            canonicalCenterY = cy,
                            canonicalSamples = detailedSamples.map { Pair(it.canonicalX, it.canonicalY) },
                            mappedCameraSamples = detailedSamples.map { Pair(it.pt.x, it.pt.y) },
                            yReadSuccessCount = detailedSamples.count { it.ySuccess },
                            uReadSuccessCount = detailedSamples.count { it.uSuccess },
                            vReadSuccessCount = detailedSamples.count { it.vSuccess },
                            rawSamples = detailedSamples.map { intArrayOf(it.y, it.u, it.v) },
                            medianY = medY,
                            medianU = medU,
                            medianV = medV,
                            normY = medY.toDouble(),
                            normU = medU.toDouble(),
                            normV = medV.toDouble(),
                            distBlack = distMap["BLACK"] ?: 0.0,
                            distWhite = distMap["WHITE"] ?: 0.0,
                            distRed = distMap["RED"] ?: 0.0,
                            distBlue = distMap["BLUE"] ?: 0.0,
                            nearestDist = bestDist,
                            secondBestDist = if (secondBestDist == Double.MAX_VALUE) 0.0 else secondBestDist,
                            confidenceMargin = margin,
                            uncertainReason = uncertainReason
                        )
                    )
                    
                    decodedBytes[r * cols + c] = (if (decodedIdx == -1) 0 else decodedIdx).toByte()
                    expectedBytes[r * cols + c] = expectedIdx.toByte()
                }
            }
            
            val accuracy = correct * 100.0 / (rows * cols)
            val incorrectCount = rows * cols - correct - uncertain
            
            val crc32Decoded = java.util.zip.CRC32().apply { update(decodedBytes) }.value
            val crc32Expected = java.util.zip.CRC32().apply { update(expectedBytes) }.value

            var savedImagePath: String? = null
            if (exportDebugImage && cacheDir != null) {
                val debugMat = Mat()
                Imgproc.cvtColor(warped, debugMat, Imgproc.COLOR_GRAY2BGR)
                for (key in anchorKeys) {
                    val bbox = V6Contract.getAnchorCoreBBox(key)
                    Imgproc.rectangle(debugMat, Point(bbox.x1, bbox.y1), Point(bbox.x2, bbox.y2), Scalar(0.0, 255.0, 0.0), 2)
                    bitSamplesMap[key]?.forEach {
                        Imgproc.circle(debugMat, Point(it.x, it.y), 3, Scalar(0.0, 0.0, 255.0), -1)
                    }
                }
                
                for (pilot in listOf("BLACK", "WHITE", "RED", "BLUE")) {
                    val bbox = V6Contract.getPilotCoreBBox(pilot)
                    Imgproc.rectangle(debugMat, Point(bbox.x1, bbox.y1), Point(bbox.x2, bbox.y2), Scalar(255.0, 0.0, 255.0), 2)
                }
                
                for (i in 0 until 20) {
                    val r = i / cols
                    val c = i % cols
                    val expected = expectedBytes[i].toInt()
                    val decoded = decodedBytes[i].toInt()
                    val cx = gridBBox.x1 + c * cellSize
                    val cy = gridBBox.y1 + r * cellSize
                    Imgproc.putText(debugMat, "E:$expected", Point(cx, cy + 10), Imgproc.FONT_HERSHEY_SIMPLEX, 0.3, Scalar(0.0, 0.0, 255.0), 1)
                    Imgproc.putText(debugMat, "D:$decoded", Point(cx, cy + 22), Imgproc.FONT_HERSHEY_SIMPLEX, 0.3, Scalar(0.0, 255.0, 0.0), 1)
                }

                val path = java.io.File(cacheDir, "v6_debug_snapshot_${System.currentTimeMillis()}.png").absolutePath
                Imgcodecs.imwrite(path, debugMat)
                savedImagePath = path
                debugMat.release()
            }

            val warpedLumaBytes = ByteArray(1000 * 1000)
            warped.get(0, 0, warpedLumaBytes)

            val frameDiagnosticPayload = V6FrameDiagnosticPayload(
                timestamp = System.currentTimeMillis(),
                classificationSource = classificationSource,
                imageToCanonicalHomography = finalHArr,
                canonicalToImageHomography = finalInvHArr,
                detectedQuad = detectedQuadArray,
                trackedQuad = detectedQuadArray,
                contractHash = V6Contract.canonicalHash,
                patternName = mode,
                seed = 42,
                first20Expected = expectedBytes.take(20).map { it.toInt() },
                first20Decoded = decodedBytes.take(20).map { it.toInt() },
                expectedCrc32 = crc32Expected,
                decodedCrc32 = crc32Decoded,
                cellDetails = cellDetailsList,
                pilotDetails = pilotDetailsList,
                pairwisePilotDistances = pairwisePilotDistances,
                contoursConsidered = contoursConsidered,
                quadsConsidered = quadsConsidered
            )

            val res = V6StaticResult(
                borderFound = true,
                detectedQuad = detectedQuadArray,
                contourArea = maxArea,
                decodedCornerIds = decodedCornerIds,
                decodedCornerBits = decodedCornerBits,
                decodedCornerDistances = decodedCornerDistances,
                decodedCornerMargins = decodedCornerMargins,
                bitSamples = bitSamplesMap,
                cornerMatches = cornerMatchesMap,
                orientationResolved = orientationResolved,
                reprojectionError = 2.5,
                pilotYUVs = pilots,
                cellAccuracy = accuracy,
                uncertainCells = uncertain,
                decodedCrc32 = crc32Decoded,
                expectedCrc32 = crc32Expected,
                colorCorrect = correct,
                colorUncertain = uncertain,
                colorTotal = 400,
                confusionMatrix = confusionMatrix,
                processingTimeMs = System.currentTimeMillis() - startTime,
                failureReason = null,
                debugImagePath = savedImagePath,
                finalInvHomography = finalInvHArr,
                warpMinLuma = warpMin,
                warpMaxLuma = warpMax,
                warpMeanLuma = warpMean,
                warpCoverage = coverage,
                diagnosticPayload = frameDiagnosticPayload,
                warpedLumaBytes = warpedLumaBytes,
                contoursConsidered = contoursConsidered,
                quadsConsidered = quadsConsidered
            )
            val trackedRes = tracker.processFrame(gray, res, finalInvHArr)
            recordFrameTrace(trackedRes, pilots, correct, incorrectCount, uncertain)
            return trackedRes

        } catch (e: Throwable) {
            return V6StaticResult(
                borderFound = false,
                detectedQuad = null,
                contourArea = 0.0,
                decodedCornerIds = emptyMap(),
                decodedCornerBits = emptyMap(),
                decodedCornerDistances = emptyMap(),
                decodedCornerMargins = emptyMap(),
                bitSamples = emptyMap(),
                cornerMatches = emptyMap(),
                orientationResolved = false,
                reprojectionError = Double.NaN,
                pilotYUVs = emptyMap(),
                cellAccuracy = 0.0,
                uncertainCells = 0,
                decodedCrc32 = null,
                expectedCrc32 = null,
                colorCorrect = 0,
                colorUncertain = 0,
                colorTotal = 400,
                confusionMatrix = null,
                processingTimeMs = System.currentTimeMillis() - startTime,
                failureReason = "OpenCV initialization/detection failed: ${e.message}",
                debugImagePath = null
            )
        }
    }

    private val frameTraceRingBuffer = java.util.concurrent.ConcurrentLinkedQueue<V6FrameTraceEntry>()

    fun getFrameTraceSnapshot(): List<V6FrameTraceEntry> {
        return frameTraceRingBuffer.toList()
    }

    private fun recordFrameTrace(res: V6StaticResult, pilots: Map<String, IntArray>, correct: Int, incorrect: Int, uncertain: Int) {
        val entry = V6FrameTraceEntry(
            timestamp = System.currentTimeMillis(),
            state = res.trackingState,
            quad = res.detectedQuad?.map { it.toList() },
            ransacInliers = res.ransacInliers,
            correctCount = correct,
            incorrectCount = incorrect,
            uncertainCount = uncertain,
            decodedCrc = res.decodedCrc32?.let { String.format("%08X", it) } ?: "N/A",
            pilotMedians = pilots.mapValues { it.value.toList() },
            yuvReadSuccessCounts = mapOf(
                "Y" to (res.diagnosticPayload?.cellDetails?.sumOf { it.yReadSuccessCount } ?: 0),
                "U" to (res.diagnosticPayload?.cellDetails?.sumOf { it.uReadSuccessCount } ?: 0),
                "V" to (res.diagnosticPayload?.cellDetails?.sumOf { it.vReadSuccessCount } ?: 0)
            )
        )
        frameTraceRingBuffer.add(entry)
        while (frameTraceRingBuffer.size > 30) {
            frameTraceRingBuffer.poll()
        }
    }

    private fun solveHomographySimple(src: List<Point>, dst: List<Point>): DoubleArray? {
        if (src.size != 4 || dst.size != 4) return null
        val augmented = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val sx = src[i].x
            val sy = src[i].y
            val dx = dst[i].x
            val dy = dst[i].y
            val r1 = augmented[i * 2]
            r1[0] = sx; r1[1] = sy; r1[2] = 1.0
            r1[6] = -dx * sx; r1[7] = -dx * sy; r1[8] = dx
            val r2 = augmented[i * 2 + 1]
            r2[3] = sx; r2[4] = sy; r2[5] = 1.0
            r2[6] = -dy * sx; r2[7] = -dy * sy; r2[8] = dy
        }
        for (col in 0 until 8) {
            var pivotRow = col
            for (row in col + 1 until 8) {
                if (Math.abs(augmented[row][col]) > Math.abs(augmented[pivotRow][col])) {
                    pivotRow = row
                }
            }
            val pivot = augmented[pivotRow][col]
            if (Math.abs(pivot) < 1e-14) return null
            val temp = augmented[col]
            augmented[col] = augmented[pivotRow]
            augmented[pivotRow] = temp
            val divisor = augmented[col][col]
            for (entry in col until 9) augmented[col][entry] /= divisor
            for (row in 0 until 8) {
                if (row == col) continue
                val factor = augmented[row][col]
                for (entry in col until 9) augmented[row][entry] -= factor * augmented[col][entry]
            }
        }
        return doubleArrayOf(
            augmented[0][8], augmented[1][8], augmented[2][8],
            augmented[3][8], augmented[4][8], augmented[5][8],
            augmented[6][8], augmented[7][8], 1.0
        )
    }
}
