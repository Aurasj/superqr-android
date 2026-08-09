package com.superqr.android.vision.v6.detection

import com.superqr.android.vision.v6.classification.ChromaPixelReader
import com.superqr.android.vision.v6.classification.Xorshift32
import com.superqr.android.vision.v6.contract.V6Contract
import com.superqr.android.vision.v6.diagnostic.*
import com.superqr.android.vision.v6.model.*
import com.superqr.android.vision.v6.tracking.V6TemporalTracker
import com.superqr.android.vision.v6.transport.V6Transport
import com.superqr.android.vision.v6.transport.V6TransportFrame
import com.superqr.android.vision.opencv.OpenCvRuntime
import org.opencv.core.*
import org.opencv.geometry.Geometry
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

private fun median5(v0: Int, v1: Int, v2: Int, v3: Int, v4: Int): Int {
    // 3-pass bubble-min: finds the 3rd smallest = median with zero allocations.
    var a = v0; var b = v1; var c = v2; var d = v3; var e = v4
    // Pass 1: bubble minimum of all 5 to a.
    if (a > b) { val t = a; a = b; b = t }
    if (a > c) { val t = a; a = c; c = t }
    if (a > d) { val t = a; a = d; d = t }
    if (a > e) { val t = a; a = e; e = t }
    // Pass 2: bubble minimum of b,c,d,e to b.
    if (b > c) { val t = b; b = c; c = t }
    if (b > d) { val t = b; b = d; d = t }
    if (b > e) { val t = b; b = e; e = t }
    // Pass 3: bubble minimum of c,d,e to c. c is now the median.
    if (c > d) { val t = c; c = d; d = t }
    if (c > e) { val t = c; c = e; e = t }
    return c
}

private data class SampleReadResult(
    val canonicalX: Double,
    val canonicalY: Double,
    val pt: org.opencv.core.Point,
    val y: Int,
    val u: Int,
    val v: Int,
    val ySuccess: Boolean,
    val uSuccess: Boolean,
    val vSuccess: Boolean
)

class V6StaticDetector : AutoCloseable {
    private data class QuadCandidate(
        val points: Array<Point>,
        val area: Double,
        val contractScore: Int,
    )

    private var openCvInitialized = false
    private val tracker = V6TemporalTracker()
    private lateinit var gray: Mat
    private lateinit var blurred: Mat
    private lateinit var edges: Mat
    private lateinit var hierarchy: Mat
    private lateinit var warped: Mat
    private lateinit var acquisitionKernel: Mat
    private val projectedSampleScratch = IntArray(9)

    private fun ensureOpenCvInitialized() {
        if (openCvInitialized) return
        OpenCvRuntime.ensureLoaded()
        gray = Mat()
        blurred = Mat()
        edges = Mat()
        hierarchy = Mat()
        warped = Mat()
        acquisitionKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        openCvInitialized = true
    }

    override fun close() {
        if (openCvInitialized) {
            if (::gray.isInitialized) gray.release()
            if (::blurred.isInitialized) blurred.release()
            if (::edges.isInitialized) edges.release()
            if (::hierarchy.isInitialized) hierarchy.release()
            if (::warped.isInitialized) warped.release()
            if (::acquisitionKernel.isInitialized) acquisitionKernel.release()
            openCvInitialized = false
        }
    }

    fun getGrayMat(): Mat = gray

    fun detectGeometry(luma: ByteArray, width: Int, height: Int): V6StaticResult =
        detect(
            luma = luma,
            width = width,
            height = height,
            mode = "geometry-only",
            chromaReader = null,
            geometryOnly = true,
            failFast = true,
        )

    fun detect(
        luma: ByteArray,
        width: Int,
        height: Int,
        mode: String,
        chromaReader: ChromaPixelReader? = null,
        exportDebugImage: Boolean = false,
        cacheDir: String? = null,
        geometryOnly: Boolean = false,
        failFast: Boolean = false,
    ): V6StaticResult {
        val startTime = System.currentTimeMillis()
        val detectorStartNs = System.nanoTime()

        try {
            ensureOpenCvInitialized()

            gray.create(height, width, CvType.CV_8UC1)
            gray.put(0, 0, luma)

            var bestPts: Array<Point>? = null
            var maxArea = 0.0
            var classificationSource = "FULL_DETECTION"
            var contoursConsidered = 0
            var quadsConsidered = 0
            var largestContourArea = 0.0

            // ── TRACKED_RESAMPLED proactive gate ──────────────────────
            // If the tracker is locked and periodic re-detection is not
            // due, try optical-flow quad tracking FIRST. A successful quad
            // skips the expensive contour acquisition entirely.
            val trackedQuad = tracker.tryProactiveTracking(gray)
            if (trackedQuad != null) {
                bestPts = trackedQuad
                maxArea = contourAreaOf(bestPts)
                classificationSource = "TRACKED_RESAMPLED"
            }

            // ── FULL_DETECTION contour-based acquisition ──────────────
            if (bestPts == null) {
                classificationSource = "FULL_DETECTION"

                Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
                Imgproc.threshold(
                    blurred,
                    edges,
                    0.0,
                    255.0,
                    Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU,
                )
                Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, acquisitionKernel)

                val contours = ArrayList<MatOfPoint>()
                // A physical monitor bezel encloses the on-screen carrier. RETR_EXTERNAL
                // therefore discards the V6 border completely; retain nested contours and
                // let the four encoded anchors identify the real carrier unambiguously.
                Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

                val candidates = mutableListOf<QuadCandidate>()
                val minimumContourArea = maxOf(1_600.0, width.toDouble() * height.toDouble() * 0.0025)

                val cvContour2f = MatOfPoint2f()
                val approxCurve = MatOfPoint2f()
                val hull = org.opencv.core.MatOfInt()

                for (contour in contours) {
                    val area = Geometry.contourArea(contour)
                    largestContourArea = maxOf(largestContourArea, area)
                    if (area < minimumContourArea) continue
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
                            val contractScore = scoreCarrierContract(pts, luma, width, height)
                            if (contractScore >= 0) candidates.add(QuadCandidate(pts, area, contractScore))
                        }
                    } else {
                        val box = Geometry.minAreaRect(cvContour2f)
                        val boxArea = box.size.width * box.size.height
                        if (boxArea > 0 && area / boxArea > 0.75) {
                            val cvPoints = arrayOfNulls<Point>(4)
                            box.points(cvPoints)
                            quadsConsidered++
                            val points = cvPoints.map { it!! }.toTypedArray()
                            val contractScore = scoreCarrierContract(points, luma, width, height)
                            if (contractScore >= 0) candidates.add(QuadCandidate(points, area, contractScore))
                        }
                    }
                }

                cvContour2f.release()
                approxCurve.release()
                hull.release()

                // Accept only a candidate that satisfies the complete, existing V6 anchor
                // identity contract. Area is only a tie-breaker between the two edges of
                // the same printed border. An arbitrary screen/window quad must not pin the
                // AUTO scheduler to GRID and prevent QR-control acquisition.
                val bestCandidate = candidates
                    .maxWithOrNull(compareBy<QuadCandidate> { it.contractScore }.thenBy { it.area })
                if (bestCandidate != null) {
                    bestPts = bestCandidate.points
                    maxArea = bestCandidate.area
                }

                contours.forEach { it.release() }
                contours.clear()

                // ── Reactive tracking fallback ────────────────────────
                // If contour acquisition failed, try optical-flow recovery.
                // The quad is tracked but classification is fresh — label it
                // TRACKED_RESAMPLED, not TRACKED_HOMOGRAPHY.
                if (bestPts == null) {
                    bestPts = tracker.recoverQuad(gray)
                    if (bestPts != null) {
                        maxArea = contourAreaOf(bestPts)
                        classificationSource = "TRACKED_RESAMPLED"
                    }
                }
            }

            if (bestPts == null) {
                maxArea = maxOf(maxArea, largestContourArea)
                val res = V6StaticResult(false, null, maxArea, emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), false, 0.0, emptyMap(), 0.0, 0, null, null, 0, 0, 400, null, System.currentTimeMillis() - startTime, "Outer border not found: largest contour area was ${largestContourArea.roundToInt()}; quads=$quadsConsidered", null, contoursConsidered = contoursConsidered, quadsConsidered = quadsConsidered)
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

            var finalHArr = solveHomographySimple(
                initialSrcPoints,
                canonicalPoints
            ) ?: run {
                val res = V6StaticResult(true, detectedQuadArray, maxArea, emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), false, 0.0, emptyMap(), 0.0, 0, null, null, 0, 0, 400, null, System.currentTimeMillis() - startTime, "Initial Homography failed", null, contoursConsidered = contoursConsidered, quadsConsidered = quadsConsidered)
                val trackedRes = tracker.processFrame(if (::gray.isInitialized) gray else null, res, null)
                recordFrameTrace(trackedRes, emptyMap(), 0, 0, 0)
                return trackedRes
            }

            // ── final inverse homography (shared) ──────────────────────────
            // TRACKED_RESAMPLED: corners from the tracker are already in
            //   TL/TR/BR/BL order → no identity correction needed.
            // FULL_DETECTION: anchor decoding provides the correction.
            var finalInvHArr: DoubleArray
            var warpMin = 0
            var warpMax = 0
            var warpMean = 0
            var coverage = 1.0
            var orientationResolved: Boolean
            val decodedCornerIds = mutableMapOf<String, String>()
            val decodedCornerBits = mutableMapOf<String, String>()
            val decodedCornerDistances = mutableMapOf<String, Int>()
            val decodedCornerMargins = mutableMapOf<String, Int>()
            val cornerMatchesMap = mutableMapOf<String, V6CornerMatch>()
            val bitSamplesMap = mutableMapOf<String, List<V6BitSample>>()
            val anchorKeys = listOf("TL", "TR", "BR", "BL")

            if (classificationSource == "TRACKED_RESAMPLED") {
                // ── TRACKED_RESAMPLED V2 fast path ──────────────────────
                // Skip warpPerspective, anchor core/ring sampling, orientation
                // decoding, and corrected homography. The tracker already
                // provides corners in TL/TR/BR/BL order.
                finalInvHArr = solveHomographySimple(
                    canonicalPoints,
                    initialSrcPoints
                ) ?: run {
                    val res = V6StaticResult(true, detectedQuadArray, maxArea, emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), false, 0.0, emptyMap(), 0.0, 0, null, null, 0, 0, 400, null, System.currentTimeMillis() - startTime, "TRACKED inv homography failed", null, contoursConsidered = contoursConsidered, quadsConsidered = quadsConsidered)
                    val trackedRes = tracker.processFrame(if (::gray.isInitialized) gray else null, res, null)
                    recordFrameTrace(trackedRes, emptyMap(), 0, 0, 0)
                    return trackedRes
                }

                for (key in anchorKeys) {
                    decodedCornerIds[key] = key
                    decodedCornerBits[key] = V6Contract.getAnchorIdentityPattern(key)
                }
                orientationResolved = true
                // warp stats are not available on the fast path; coverage
                // defaults to 1.0 and warp min/max/mean to 0.
                coverage = 0.0
            } else {
                // ── FULL_DETECTION: warp + anchor + orientation ─────────
                warpWithHomography(finalHArr)

                val minMax = Core.minMaxLoc(warped)
                val mean = Core.mean(warped)
                val nonZero = Core.countNonZero(warped)
                coverage = nonZero.toDouble() / (1000.0 * 1000.0)
                warpMin = minMax.minVal.toInt()
                warpMax = minMax.maxVal.toInt()
                warpMean = mean.`val`[0].toInt()

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

                val anchorEvalMap = mutableMapOf<String, AnchorMatchResult>()

                for (key in anchorKeys) {
                    val core = V6Contract.getAnchorCoreBBox(key)
                    val anchorBBox = V6Contract.getAnchorBBox(key)
                    val x1 = core.x1
                    val y1 = core.y1
                    val w = core.width
                    val h = core.height

                    val ringSamples = listOf(
                        sampleMedianLuma(anchorBBox.x1 + 10.0, anchorBBox.y1 + 10.0),
                        sampleMedianLuma(anchorBBox.x2 - 10.0, anchorBBox.y1 + 10.0),
                        sampleMedianLuma(anchorBBox.x2 - 10.0, anchorBBox.y2 - 10.0),
                        sampleMedianLuma(anchorBBox.x1 + 10.0, anchorBBox.y2 - 10.0)
                    )
                    val ringLuma = ringSamples.sorted()[1]

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

                    val sortedCore = listOf(medTL, medTR, medBR, medBL).sorted()
                    val coreWhiteRef = (sortedCore[1] + sortedCore[2] + sortedCore[3]) / 3
                    val contrast = coreWhiteRef - ringLuma
                    val threshold = (ringLuma + coreWhiteRef) / 2

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

                orientationResolved = V6OrientationEvaluator.isOrientationResolved(anchorEvalMap)

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

                finalHArr = solveHomographySimple(
                    correctedSrcPoints,
                    canonicalPoints
                ) ?: run {
                    val res = V6StaticResult(true, detectedQuadArray, maxArea, decodedCornerIds, decodedCornerBits, decodedCornerDistances, decodedCornerMargins, bitSamplesMap, cornerMatchesMap, false, 0.0, emptyMap(), 0.0, 0, null, null, 0, 0, 400, null, System.currentTimeMillis() - startTime, "Final Homography failed", null, warpMinLuma = warpMin, warpMaxLuma = warpMax, warpMeanLuma = warpMean, warpCoverage = coverage, contoursConsidered = contoursConsidered, quadsConsidered = quadsConsidered)
                    val trackedRes = tracker.processFrame(if (::gray.isInitialized) gray else null, res, null)
                    recordFrameTrace(trackedRes, emptyMap(), 0, 0, 0)
                    return trackedRes
                }

                warpWithHomography(finalHArr)

                finalInvHArr = solveHomographySimple(
                    canonicalPoints,
                    correctedSrcPoints
                ) ?: run {
                    val res = V6StaticResult(true, detectedQuadArray, maxArea, decodedCornerIds, decodedCornerBits, decodedCornerDistances, decodedCornerMargins, bitSamplesMap, cornerMatchesMap, false, 0.0, emptyMap(), 0.0, 0, null, null, 0, 0, 400, null, System.currentTimeMillis() - startTime, "Final Inv Homography failed", null, warpMinLuma = warpMin, warpMaxLuma = warpMax, warpMeanLuma = warpMean, warpCoverage = coverage, contoursConsidered = contoursConsidered, quadsConsidered = quadsConsidered)
                    val trackedRes = tracker.processFrame(if (::gray.isInitialized) gray else null, res, null)
                    recordFrameTrace(trackedRes, emptyMap(), 0, 0, 0)
                    return trackedRes
                }
            }

            if (geometryOnly) {
                val endNs = System.nanoTime()
                val geometryResult = V6StaticResult(
                    borderFound = true,
                    detectedQuad = detectedQuadArray,
                    contourArea = maxArea,
                    decodedCornerIds = decodedCornerIds,
                    decodedCornerBits = decodedCornerBits,
                    decodedCornerDistances = decodedCornerDistances,
                    decodedCornerMargins = decodedCornerMargins,
                    bitSamples = emptyMap(),
                    cornerMatches = emptyMap(),
                    orientationResolved = orientationResolved,
                    reprojectionError = 2.5,
                    pilotYUVs = emptyMap(),
                    cellAccuracy = 0.0,
                    uncertainCells = 0,
                    decodedCrc32 = null,
                    expectedCrc32 = null,
                    colorCorrect = 0,
                    colorUncertain = 0,
                    colorTotal = 0,
                    confusionMatrix = null,
                    processingTimeMs = (endNs - detectorStartNs) / 1_000_000,
                    failureReason = null,
                    debugImagePath = null,
                    finalInvHomography = finalInvHArr,
                    warpMinLuma = warpMin,
                    warpMaxLuma = warpMax,
                    warpMeanLuma = warpMean,
                    warpCoverage = coverage,
                    contoursConsidered = contoursConsidered,
                    quadsConsidered = quadsConsidered,
                    geometrySource = classificationSource,
                    detectorStartNs = detectorStartNs,
                    detectorEndNs = endNs,
                )
                return tracker.processFrame(gray, geometryResult, finalInvHArr)
            }

            // ── reusable arrays for allocation-free sampling ───────────
            val h = finalInvHArr
            val h0 = h[0]; val h1 = h[1]; val h2 = h[2]
            val h3 = h[3]; val h4 = h[4]; val h5 = h[5]
            val h6 = h[6]; val h7 = h[7]; val h8 = h[8]
            val chromaBuf = IntArray(2)  // single reusable chroma buffer

            // Maps one canonical point → two output doubles (image coords).
            fun mapCanonical(cx: Double, cy: Double, out: DoubleArray) {
                val den = h6 * cx + h7 * cy + h8
                out[0] = (h0 * cx + h1 * cy + h2) / den
                out[1] = (h3 * cx + h4 * cy + h5) / den
            }

            // ── allocation-free 5-sample probe ──────────────────────────
            // Saves canonical coords, mapped coords, Y, U, V, and success
            // flags into pre-sized arrays.  index = 0..4.
            val probeCanX  = DoubleArray(5)   // canonical X
            val probeCanY  = DoubleArray(5)   // canonical Y
            val probeMapX  = DoubleArray(5)   // mapped camera X
            val probeMapY  = DoubleArray(5)   // mapped camera Y
            val probeY     = IntArray(5)      // luma sample
            val probeU     = IntArray(5)      // chroma U
            val probeV     = IntArray(5)      // chroma V
            val probeYok   = BooleanArray(5)  // ySuccess
            val probeUok   = BooleanArray(5)  // uSuccess
            val probeVok   = BooleanArray(5)  // vSuccess
            val tmpXY      = DoubleArray(2)   // scratch

            fun sampleProbeFast(idx: Int, canX: Double, canY: Double) {
                probeCanX[idx] = canX
                probeCanY[idx] = canY
                mapCanonical(canX, canY, tmpXY)
                val mx = tmpXY[0]; val my = tmpXY[1]
                probeMapX[idx] = mx
                probeMapY[idx] = my
                val px = mx.roundToInt(); val py = my.roundToInt()
                val ok = px in 0 until width && py in 0 until height
                probeYok[idx] = ok
                probeY[idx] = if (ok) luma[py * width + px].toInt() and 0xFF else 128
                if (chromaReader != null) {
                    val got = chromaReader.read(mx, my, chromaBuf)
                    probeUok[idx] = got
                    probeVok[idx] = got
                    probeU[idx] = if (got) chromaBuf[0] else 128
                    probeV[idx] = if (got) chromaBuf[1] else 128
                } else {
                    probeUok[idx] = false; probeVok[idx] = false
                    probeU[idx] = 128; probeV[idx] = 128
                }
            }

            // ── pilots ──────────────────────────────────────────────────
            val pilots = mutableMapOf<String, IntArray>()
            val pilotDetailsList = mutableListOf<V6PilotDiagnosticDetail>()
            val pilotNames = listOf("BLACK", "WHITE", "RED", "BLUE")

            // Precompute canonical pilot probe coords.
            data class PilotProbe(val canX: Double, val canY: Double)
            for (pilot in pilotNames) {
                val bbox = V6Contract.getPilotCoreBBox(pilot)
                val pcx = bbox.centerX; val pcy = bbox.centerY
                val poff = bbox.width * 0.25
                val probes = arrayOf(
                    PilotProbe(pcx, pcy),
                    PilotProbe(pcx - poff, pcy - poff),
                    PilotProbe(pcx + poff, pcy - poff),
                    PilotProbe(pcx - poff, pcy + poff),
                    PilotProbe(pcx + poff, pcy + poff)
                )
                for (i in 0 until 5) {
                    sampleProbeFast(i, probes[i].canX, probes[i].canY)
                }
                val medY = median5(probeY[0], probeY[1], probeY[2], probeY[3], probeY[4])
                val medU = median5(probeU[0], probeU[1], probeU[2], probeU[3], probeU[4])
                val medV = median5(probeV[0], probeV[1], probeV[2], probeV[3], probeV[4])
                pilots[pilot] = intArrayOf(medY, medU, medV)

                pilotDetailsList.add(V6PilotDiagnosticDetail(
                    pilotName = pilot,
                    canonicalCenterX = pcx, canonicalCenterY = pcy,
                    mappedCameraCenterX = probeMapX[0],
                    mappedCameraCenterY = probeMapY[0],
                    ySuccess = probeYok[0], uSuccess = probeUok[0], vSuccess = probeVok[0],
                    rawSamples = (0..4).map { intArrayOf(probeY[it], probeU[it], probeV[it]) },
                    medianY = medY, medianU = medU, medianV = medV
                ))
            }

            // Precompute pilot array references for fast classification.
            val pBlack = pilots["BLACK"]!!; val pWhite = pilots["WHITE"]!!
            val pRed   = pilots["RED"]!!;   val pBlue  = pilots["BLUE"]!!

            fun sqDistYuv(p1: IntArray, p2: IntArray): Double {
                val dy = p1[0] - p2[0]; val du = p1[1] - p2[1]; val dv = p1[2] - p2[2]
                return (dy * dy + du * du + dv * dv).toDouble()
            }

            val pairwisePilotDistances = mapOf(
                "DIST_BLACK_WHITE" to sqDistYuv(pBlack, pWhite),
                "DIST_BLACK_RED" to sqDistYuv(pBlack, pRed),
                "DIST_BLACK_BLUE" to sqDistYuv(pBlack, pBlue),
                "DIST_WHITE_RED" to sqDistYuv(pWhite, pRed),
                "DIST_WHITE_BLUE" to sqDistYuv(pWhite, pBlue),
                "DIST_RED_BLUE" to sqDistYuv(pRed, pBlue)
            )

            // ── cell classification ─────────────────────────────────────
            val cols = V6Contract.getGridCols()
            val rows = V6Contract.getGridRows()
            val ratio = V6Contract.getCentralRegionRatio()
            var correct = 0; var uncertain = 0

            val gridBBox = V6Contract.getGridBBox()
            val gridX0 = gridBBox.x1; val gridY0 = gridBBox.y1
            val cellSize = V6Contract.getCellSize()
            val probeOffset = cellSize * ratio / 2.0

            val prng = Xorshift32(42)
            val decodedBytes = ByteArray(cols * rows)
            val decodedIndexes = IntArray(cols * rows)
            val expectedBytes = ByteArray(cols * rows)

            val confusionMatrix = mutableMapOf<String, MutableMap<String, Int>>()
            val colorNames = arrayOf("BLACK", "WHITE", "RED", "BLUE")
            for (c in colorNames) {
                confusionMatrix[c] = mutableMapOf("BLACK" to 0, "WHITE" to 0, "RED" to 0, "BLUE" to 0, "UNCERTAIN" to 0)
            }
            val cellDetailsList = mutableListOf<V6CellDiagnosticDetail>()

            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val cx = gridX0 + (c + 0.5) * cellSize
                    val cy = gridY0 + (r + 0.5) * cellSize

                    // 5-probe sampling (allocation-free).
                    sampleProbeFast(0, cx, cy)
                    sampleProbeFast(1, cx - probeOffset, cy - probeOffset)
                    sampleProbeFast(2, cx + probeOffset, cy - probeOffset)
                    sampleProbeFast(3, cx - probeOffset, cy + probeOffset)
                    sampleProbeFast(4, cx + probeOffset, cy + probeOffset)

                    val medY = median5(probeY[0], probeY[1], probeY[2], probeY[3], probeY[4])
                    val medU = median5(probeU[0], probeU[1], probeU[2], probeU[3], probeU[4])
                    val medV = median5(probeV[0], probeV[1], probeV[2], probeV[3], probeV[4])

                    // Nearest-neighbor classification using 4 local vars.
                    val dyB = medY - pBlack[0]; val duB = medU - pBlack[1]; val dvB = medV - pBlack[2]
                    val dB = (dyB * dyB + duB * duB + dvB * dvB).toDouble()
                    val dyW = medY - pWhite[0]; val duW = medU - pWhite[1]; val dvW = medV - pWhite[2]
                    val dW = (dyW * dyW + duW * duW + dvW * dvW).toDouble()
                    val dyR = medY - pRed[0];   val duR = medU - pRed[1];   val dvR = medV - pRed[2]
                    val dR = (dyR * dyR + duR * duR + dvR * dvR).toDouble()
                    val dyL = medY - pBlue[0];  val duL = medU - pBlue[1];  val dvL = medV - pBlue[2]
                    val dL = (dyL * dyL + duL * duL + dvL * dvL).toDouble()

                    var bestDist = dB; var bestIdx = 0
                    if (dW < bestDist) { bestDist = dW; bestIdx = 1 }
                    if (dR < bestDist) { bestDist = dR; bestIdx = 2 }
                    if (dL < bestDist) { bestDist = dL; bestIdx = 3 }

                    var secondBestDist = Double.MAX_VALUE
                    when (bestIdx) {
                        0 -> { if (dW < secondBestDist) secondBestDist = dW; if (dR < secondBestDist) secondBestDist = dR; if (dL < secondBestDist) secondBestDist = dL }
                        1 -> { if (dB < secondBestDist) secondBestDist = dB; if (dR < secondBestDist) secondBestDist = dR; if (dL < secondBestDist) secondBestDist = dL }
                        2 -> { if (dB < secondBestDist) secondBestDist = dB; if (dW < secondBestDist) secondBestDist = dW; if (dL < secondBestDist) secondBestDist = dL }
                        3 -> { if (dB < secondBestDist) secondBestDist = dB; if (dW < secondBestDist) secondBestDist = dW; if (dR < secondBestDist) secondBestDist = dR }
                    }
                    // Coerce to exactly match the map-based best/second-best logic.
                    if (secondBestDist == Double.MAX_VALUE) secondBestDist = 0.0

                    var decodedIdx = bestIdx
                    var uncertainReason = "NONE"
                    if (bestDist > 40000.0) {
                        decodedIdx = -1; uncertainReason = "DIST_EXCEEDS_MAX"
                    } else if (secondBestDist > 0 && bestDist / secondBestDist > 0.9) {
                        decodedIdx = -1; uncertainReason = "MARGIN_TOO_THIN"
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

                    val status = when {
                        decodedIdx == -1 -> { uncertain++; "UNCERTAIN" }
                        decodedIdx == expectedIdx -> { correct++; "CORRECT" }
                        else -> "WRONG"
                    }
                    val margin = if (secondBestDist > 0) bestDist / secondBestDist else 0.0

                    // Diagnostic detail (rebuild from reusable probe arrays).
                    fun mksample(i: Int) = SampleReadResult(
                        probeCanX[i], probeCanY[i],
                        Point(probeMapX[i], probeMapY[i]),
                        probeY[i], probeU[i], probeV[i],
                        probeYok[i], probeUok[i], probeVok[i]
                    )
                    val s0 = mksample(0); val s1 = mksample(1)
                    val s2 = mksample(2); val s3 = mksample(3); val s4 = mksample(4)
                    val sList = listOf(s0, s1, s2, s3, s4)

                    cellDetailsList.add(V6CellDiagnosticDetail(
                        row = r, col = c,
                        expectedIdx = expectedIdx, expectedColor = expectedName,
                        decodedIdx = decodedIdx, decodedColor = decodedName,
                        status = status,
                        classificationSource = classificationSource,
                        canonicalCenterX = cx, canonicalCenterY = cy,
                        canonicalSamples = sList.map { Pair(it.canonicalX, it.canonicalY) },
                        mappedCameraSamples = sList.map { Pair(it.pt.x, it.pt.y) },
                        yReadSuccessCount = sList.count { it.ySuccess },
                        uReadSuccessCount = sList.count { it.uSuccess },
                        vReadSuccessCount = sList.count { it.vSuccess },
                        rawSamples = sList.map { intArrayOf(it.y, it.u, it.v) },
                        medianY = medY, medianU = medU, medianV = medV,
                        normY = medY.toDouble(), normU = medU.toDouble(), normV = medV.toDouble(),
                        distBlack = dB, distWhite = dW, distRed = dR, distBlue = dL,
                        nearestDist = bestDist,
                        secondBestDist = secondBestDist,
                        confidenceMargin = margin,
                        uncertainReason = uncertainReason
                    ))

                    decodedBytes[r * cols + c] = (if (decodedIdx == -1) 0 else decodedIdx).toByte()
                    decodedIndexes[r * cols + c] = decodedIdx
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
                    val decoded = decodedIndexes[i]
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

            var transportSessionId: Int? = null
            var transportFrameId: Int? = null
            var transportTotalFrames: Int? = null
            var transportPayloadHex: String? = null
            var transportCrc16Hex: String? = null
            var transportError: String? = null
            var transportFrameObj: V6TransportFrame? = null

            if (classificationSource == "FULL_DETECTION" || classificationSource == "TRACKED_RESAMPLED") {
                val indexArray = IntArray(400)
                var hasUncertain = false
                for (i in 0 until 400) {
                    val decoded = decodedBytes[i].toInt()
                    if (decoded == -1) {
                        hasUncertain = true
                        break
                    }
                    indexArray[i] = decoded
                }

                if (!hasUncertain) {
                    try {
                        val frameBytes = V6Transport.paletteIndexesToBytes(indexArray)
                        val frame = V6Transport.parseFrame(frameBytes)
                        transportFrameObj = frame
                        transportSessionId = frame.sessionId
                        transportFrameId = frame.frameId
                        transportTotalFrames = frame.totalFrames
                        transportPayloadHex = frame.payload.joinToString("") { "%02X".format(it) }
                        transportCrc16Hex = "%04X".format(frame.crc16)
                    } catch (e: Throwable) {
                        transportError = e.message
                    }
                } else {
                    transportError = "Uncertain cells present"
                }
            }

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
                quadsConsidered = quadsConsidered,
                normalizedAnalysisWidth = width,
                normalizedAnalysisHeight = height,
                transportSessionId = transportSessionId,
                transportFrameId = transportFrameId,
                transportTotalFrames = transportTotalFrames,
                transportPayloadHex = transportPayloadHex,
                transportCrc16Hex = transportCrc16Hex,
                transportError = transportError
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
                quadsConsidered = quadsConsidered,
                transportSessionId = transportSessionId,
                transportFrameId = transportFrameId,
                transportTotalFrames = transportTotalFrames,
                transportPayloadHex = transportPayloadHex,
                transportCrc16Hex = transportCrc16Hex,
                transportError = transportError,
                transportFrame = transportFrameObj,
                geometrySource = classificationSource,
            )
            val timedRes = res.copy(
                detectorStartNs = detectorStartNs,
                detectorEndNs = System.nanoTime()
            )
            val trackedRes = tracker.processFrame(gray, timedRes, finalInvHArr)
            recordFrameTrace(trackedRes, pilots, correct, incorrectCount, uncertain)
            return trackedRes

        } catch (e: Throwable) {
            if (failFast) throw e
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
                debugImagePath = null,
                detectorStartNs = detectorStartNs,
                detectorEndNs = System.nanoTime()
            )
        }
    }

    /**
     * Scores a quadrilateral against the encoded V6 corner-anchor contract before
     * committing to an expensive full warp. This is what distinguishes the carrier
     * from a monitor bezel, screen edge, window, or other nested rectangle.
     *
     * Returns -1 unless all four anchors resolve to distinct, exact identities with
     * the normal production contrast requirement.
     */
    private fun scoreCarrierContract(
        points: Array<Point>,
        luma: ByteArray,
        width: Int,
        height: Int,
    ): Int {
        if (points.size != 4) return -1
        val cx = points.map { it.x }.average()
        val cy = points.map { it.y }.average()
        val ordered = points.sortedBy { Math.atan2(it.y - cy, it.x - cx) }
        val canonical = listOf(
            Point(60.0, 60.0),
            Point(940.0, 60.0),
            Point(940.0, 940.0),
            Point(60.0, 940.0),
        )
        val canonicalToFrame = solveHomographySimple(canonical, ordered) ?: return -1
        val evaluations = mutableMapOf<String, AnchorMatchResult>()
        var score = 0

        for (key in listOf("TL", "TR", "BR", "BL")) {
            val core = V6Contract.getAnchorCoreBBox(key)
            val anchor = V6Contract.getAnchorBBox(key)
            val ring = intArrayOf(
                projectedMedianLuma(canonicalToFrame, anchor.x1 + 10.0, anchor.y1 + 10.0, luma, width, height),
                projectedMedianLuma(canonicalToFrame, anchor.x2 - 10.0, anchor.y1 + 10.0, luma, width, height),
                projectedMedianLuma(canonicalToFrame, anchor.x2 - 10.0, anchor.y2 - 10.0, luma, width, height),
                projectedMedianLuma(canonicalToFrame, anchor.x1 + 10.0, anchor.y2 - 10.0, luma, width, height),
            )
            if (ring.any { it < 0 }) return -1
            ring.sort()
            val ringLuma = ring[1]

            val coreValues = intArrayOf(
                projectedMedianLuma(canonicalToFrame, core.x1 + core.width * 0.25, core.y1 + core.height * 0.25, luma, width, height),
                projectedMedianLuma(canonicalToFrame, core.x1 + core.width * 0.75, core.y1 + core.height * 0.25, luma, width, height),
                projectedMedianLuma(canonicalToFrame, core.x1 + core.width * 0.75, core.y1 + core.height * 0.75, luma, width, height),
                projectedMedianLuma(canonicalToFrame, core.x1 + core.width * 0.25, core.y1 + core.height * 0.75, luma, width, height),
            )
            if (coreValues.any { it < 0 }) return -1
            val sortedCore = coreValues.copyOf().also { it.sort() }
            val coreWhiteRef = (sortedCore[1] + sortedCore[2] + sortedCore[3]) / 3
            val contrast = coreWhiteRef - ringLuma
            val threshold = (ringLuma + coreWhiteRef) / 2
            val decodedBits = buildString(4) {
                coreValues.forEach { append(if (it < threshold) '1' else '0') }
            }
            evaluations[key] = V6OrientationEvaluator.evaluateAnchorBits(key, decodedBits, contrast)
            score += contrast
        }

        return if (V6OrientationEvaluator.isOrientationResolved(evaluations)) score else -1
    }

    private fun projectedMedianLuma(
        homography: DoubleArray,
        canonicalX: Double,
        canonicalY: Double,
        luma: ByteArray,
        width: Int,
        height: Int,
    ): Int {
        val denominator = homography[6] * canonicalX + homography[7] * canonicalY + homography[8]
        if (Math.abs(denominator) < 1e-9) return -1
        val centerX = ((homography[0] * canonicalX + homography[1] * canonicalY + homography[2]) / denominator).roundToInt()
        val centerY = ((homography[3] * canonicalX + homography[4] * canonicalY + homography[5]) / denominator).roundToInt()
        if (centerX !in 1 until width - 1 || centerY !in 1 until height - 1) return -1

        var index = 0
        for (dy in -1..1) {
            val rowOffset = (centerY + dy) * width
            for (dx in -1..1) {
                projectedSampleScratch[index++] = luma[rowOffset + centerX + dx].toInt() and 0xFF
            }
        }
        projectedSampleScratch.sort()
        return projectedSampleScratch[4]
    }

    private fun contourAreaOf(points: Array<Point>): Double {
        val contour = MatOfPoint(*points)
        return try {
            Geometry.contourArea(contour)
        } finally {
            contour.release()
        }
    }

    private fun warpWithHomography(homography: DoubleArray) {
        val matrix = Mat(3, 3, CvType.CV_64F)
        try {
            matrix.put(0, 0, *homography)
            Imgproc.warpPerspective(gray, warped, matrix, Size(1000.0, 1000.0), Imgproc.INTER_NEAREST)
        } finally {
            matrix.release()
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
