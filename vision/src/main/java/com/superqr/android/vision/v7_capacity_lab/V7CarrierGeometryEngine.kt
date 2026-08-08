package com.superqr.android.vision.v7_capacity_lab

import com.superqr.android.vision.v6.classification.ChromaPixelReader
import com.superqr.android.vision.v6.contract.V6Contract
import com.superqr.android.vision.v6.detection.AnchorMatchResult
import com.superqr.android.vision.v6.detection.V6OrientationEvaluator
import com.superqr.android.vision.v6.tracking.V6TemporalTracker
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Lightweight V6_REFERENCE_LAB_CARRIER geometry engine for V7 Capacity Lab.
 *
 * Performs only what V7 needs: quad detection, anchor/orientation validation,
 * homography acquisition, and tracking. Does NOT run V6 20x20 sampling,
 * V6 4-color classification, V6 diagnostics, V6 transport, or V6 warped images.
 *
 * V6_REFERENCE_LAB_CARRIER is temporary laboratory scaffolding.
 * It does NOT define final V7 geometry.
 *
 * Reuses grid-independent V6 utilities: V6Contract, V6TemporalTracker,
 * V6OrientationEvaluator. Does NOT modify V6 files.
 */
class V7CarrierGeometryEngine : AutoCloseable {

    /** Result from carrier geometry acquisition. */
    data class CarrierResult(
        val quadFound: Boolean,
        val orientationResolved: Boolean,
        /** 4 image-space corner points of the carrier border */
        val detectedQuad: List<DoubleArray>?,
        /** 9-element forward homography: image → canonical */
        val imageToCanonicalH: DoubleArray?,
        /** 9-element inverse homography: canonical → image */
        val canonicalToImageH: DoubleArray?,
        /** Per-pilot median YUV values (BLACK, WHITE, RED, BLUE) */
        val pilotYUVs: Map<String, IntArray>,
        /** Whether V6 carrier pilots were readable */
        val pilotsRead: Boolean,
        /** Tracking state from V6TemporalTracker */
        val trackingState: String
    )

    private var openCvInitialized = false
    val tracker = V6TemporalTracker()

    // Reusable OpenCV Mats
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
            if (!tryLoadDesktopNative()) {
                try { System.loadLibrary(Core.NATIVE_LIBRARY_NAME) } catch (_: Throwable) {}
                try { Core.getVersionString() } catch (e2: Throwable) {
                    throw RuntimeException("OpenCV runtime could not be initialized.", e)
                }
            }
        }
        gray = Mat()
        blurred = Mat()
        edges = Mat()
        hierarchy = Mat()
        warped = Mat()
        openCvInitialized = true
    }

    private fun tryLoadDesktopNative(): Boolean {
        val envDir = System.getenv("SUPERQR_OPENCV_NATIVE")
        if (envDir != null) {
            val libName = System.mapLibraryName(Core.NATIVE_LIBRARY_NAME)
            val libFile = java.io.File(envDir, libName)
            if (libFile.exists()) {
                try { System.load(libFile.absolutePath); return true } catch (_: Throwable) {}
            }
        }
        val cacheRoot = java.io.File(System.getProperty("user.home"), ".gradle/opencv-windows")
        if (cacheRoot.isDirectory) {
            cacheRoot.listFiles { f -> f.isDirectory && f.name.startsWith("dlls-") }
                ?.sortedByDescending { it.name }
                ?.forEach {
                    val libName = System.mapLibraryName(Core.NATIVE_LIBRARY_NAME)
                    val libFile = java.io.File(it, libName)
                    if (libFile.exists()) {
                        try { System.load(libFile.absolutePath); return true } catch (_: Throwable) {}
                    }
                }
        }
        return false
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

    /**
     * Acquire carrier geometry from a camera frame.
     *
     * Steps:
     * 1. Try proactive tracking (optical flow from V6TemporalTracker)
     * 2. Fall back to contour-based quad detection
     * 3. Solve initial homography
     * 4. Decode anchor identity patterns for orientation
     * 5. Solve corrected homography
     * 6. Sample V6 carrier pilots
     * 7. Compute inverse homography for V7 payload sampling
     *
     * Does NOT: classify V6 cells, parse V6 transport, create V6 diagnostics.
     *
     * @param lumaBytes rotation-normalized luma buffer
     * @param width luma buffer width
     * @param height luma buffer height
     * @param chromaReader optional chroma reader for pilot color sampling
     */
    fun acquire(
        lumaBytes: ByteArray,
        width: Int,
        height: Int,
        chromaReader: ChromaPixelReader?
    ): CarrierResult {
        ensureOpenCvInitialized()

        gray.create(height, width, CvType.CV_8UC1)
        gray.put(0, 0, lumaBytes)

        var bestPts: Array<Point>? = null
        var classificationSource = "FULL_DETECTION"

        // 1. Proactive tracking gate
        val trackedQuad = tracker.tryProactiveTracking(gray)
        if (trackedQuad != null) {
            bestPts = trackedQuad
            classificationSource = "TRACKED_RESAMPLED"
        }

        // 2. Contour-based quad detection
        if (bestPts == null) {
            classificationSource = "FULL_DETECTION"

            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, 50.0, 150.0)

            val contours = ArrayList<MatOfPoint>()
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            data class QuadCandidate(val points: Array<Point>, val area: Double, val score: Double)
            val candidates = mutableListOf<QuadCandidate>()

            val cvContour2f = MatOfPoint2f()
            val approxCurve = MatOfPoint2f()
            val hull = org.opencv.core.MatOfInt()

            for (contour in contours) {
                val area = Geometry.contourArea(contour)
                if (area < 10000) continue

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
                    if (isConvex) candidates.add(QuadCandidate(pts, area, area))
                } else {
                    val box = Geometry.minAreaRect(cvContour2f)
                    val boxArea = box.size.width * box.size.height
                    if (boxArea > 0 && area / boxArea > 0.75) {
                        val cvPoints = arrayOfNulls<Point>(4)
                        box.points(cvPoints)
                        candidates.add(QuadCandidate(cvPoints.map { it!! }.toTypedArray(), area, area * 0.9))
                    }
                }
            }

            cvContour2f.release(); approxCurve.release(); hull.release()
            contours.forEach { it.release() }

            bestPts = candidates.maxByOrNull { it.score }?.points

            if (bestPts == null) {
                bestPts = tracker.recoverQuad(gray)
                if (bestPts != null) classificationSource = "TRACKED_RESAMPLED"
            }
        }

        if (bestPts == null) {
            val emptyRes = CarrierResult(false, false, null, null, null, emptyMap(), false, tracker.state.name)
            tracker.processFrame(gray, com.superqr.android.vision.v6.model.V6StaticResult(
                borderFound = false, detectedQuad = null, contourArea = 0.0,
                decodedCornerIds = emptyMap(), decodedCornerBits = emptyMap(),
                decodedCornerDistances = emptyMap(), decodedCornerMargins = emptyMap(),
                bitSamples = emptyMap(), cornerMatches = emptyMap(),
                orientationResolved = false, reprojectionError = Double.NaN,
                pilotYUVs = emptyMap(), cellAccuracy = 0.0, uncertainCells = 0,
                decodedCrc32 = null, expectedCrc32 = null, colorCorrect = 0,
                colorUncertain = 0, colorTotal = 400, confusionMatrix = null,
                processingTimeMs = 0, failureReason = null, debugImagePath = null
            ), null)
            return emptyRes
        }

        // 3. Order corners by angle around centroid
        val ptsList = bestPts.toList()
        val cx = ptsList.map { it.x }.average()
        val cy = ptsList.map { it.y }.average()
        val orderedPts = ptsList.sortedBy { atan2(it.y - cy, it.x - cx) }

        val canonicalCorners = listOf(
            Point(60.0, 60.0), Point(940.0, 60.0),
            Point(940.0, 940.0), Point(60.0, 940.0)
        )
        val detectedQuad = orderedPts.map { doubleArrayOf(it.x, it.y) }

        // 4. Initial homography
        val hArr = solveHomographySimple(orderedPts, canonicalCorners) ?: run {
            val emptyRes = CarrierResult(true, false, detectedQuad, null, null, emptyMap(), false, tracker.state.name)
            tracker.processFrame(gray, com.superqr.android.vision.v6.model.V6StaticResult(
                borderFound = true, detectedQuad = detectedQuad, contourArea = 0.0,
                decodedCornerIds = emptyMap(), decodedCornerBits = emptyMap(),
                decodedCornerDistances = emptyMap(), decodedCornerMargins = emptyMap(),
                bitSamples = emptyMap(), cornerMatches = emptyMap(),
                orientationResolved = false, reprojectionError = Double.NaN,
                pilotYUVs = emptyMap(), cellAccuracy = 0.0, uncertainCells = 0,
                decodedCrc32 = null, expectedCrc32 = null, colorCorrect = 0,
                colorUncertain = 0, colorTotal = 400, confusionMatrix = null,
                processingTimeMs = 0, failureReason = null, debugImagePath = null
            ), null)
            return emptyRes
        }

        var finalHArr = hArr
        var finalInvHArr: DoubleArray? = null
        var orientationResolved: Boolean
        var anchorEvalMap: Map<String, AnchorMatchResult> = emptyMap()

        if (classificationSource == "TRACKED_RESAMPLED") {
            // Tracker already provides TL/TR/BR/BL order
            finalInvHArr = solveHomographySimple(canonicalCorners, orderedPts)
            orientationResolved = true
        } else {
            // 5. Warp and decode anchors for orientation
            val hMat = Mat(3, 3, CvType.CV_64F)
            hMat.put(0, 0, *hArr)
            Imgproc.warpPerspective(gray, warped, hMat, Size(1000.0, 1000.0), Imgproc.INTER_NEAREST)

            val coverage = Core.countNonZero(warped).toDouble() / (1000.0 * 1000.0)
            val minMax = Core.minMaxLoc(warped)
            if (coverage < 0.1 || minMax.maxVal == 0.0) {
                val emptyRes = CarrierResult(true, false, detectedQuad, hArr, null, emptyMap(), false, tracker.state.name)
                tracker.processFrame(gray, com.superqr.android.vision.v6.model.V6StaticResult(
                    borderFound = true, detectedQuad = detectedQuad, contourArea = 0.0,
                    decodedCornerIds = emptyMap(), decodedCornerBits = emptyMap(),
                    decodedCornerDistances = emptyMap(), decodedCornerMargins = emptyMap(),
                    bitSamples = emptyMap(), cornerMatches = emptyMap(),
                    orientationResolved = false, reprojectionError = Double.NaN,
                    pilotYUVs = emptyMap(), cellAccuracy = 0.0, uncertainCells = 0,
                    decodedCrc32 = null, expectedCrc32 = null, colorCorrect = 0,
                    colorUncertain = 0, colorTotal = 400, confusionMatrix = null,
                    processingTimeMs = 0, failureReason = "WARP_INVALID", debugImagePath = null,
                    warpMinLuma = minMax.minVal.toInt(), warpMaxLuma = minMax.maxVal.toInt(),
                    warpMeanLuma = Core.mean(warped).`val`[0].toInt(), warpCoverage = coverage
                ), null)
                return emptyRes
            }

            // Anchor decoding
            val anchorEvalMutable = mutableMapOf<String, AnchorMatchResult>()
            for (key in V6OrientationEvaluator.anchorKeys) {
                val core = V6Contract.getAnchorCoreBBox(key)
                val anchorBBox = V6Contract.getAnchorBBox(key)
                val ringSamples = listOf(
                    sampleMedianLuma(anchorBBox.x1 + 10.0, anchorBBox.y1 + 10.0),
                    sampleMedianLuma(anchorBBox.x2 - 10.0, anchorBBox.y1 + 10.0),
                    sampleMedianLuma(anchorBBox.x2 - 10.0, anchorBBox.y2 - 10.0),
                    sampleMedianLuma(anchorBBox.x1 + 10.0, anchorBBox.y2 - 10.0)
                )
                val ringLuma = ringSamples.sorted()[1]

                val cxTL = core.x1 + core.width * 0.25; val cyTL = core.y1 + core.height * 0.25
                val cxTR = core.x1 + core.width * 0.75; val cyTR = core.y1 + core.height * 0.25
                val cxBR = core.x1 + core.width * 0.75; val cyBR = core.y1 + core.height * 0.75
                val cxBL = core.x1 + core.width * 0.25; val cyBL = core.y1 + core.height * 0.75

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
                val decodedBits = "$bTL$bTR$bBR$bBL"

                anchorEvalMutable[key] = V6OrientationEvaluator.evaluateAnchorBits(key, decodedBits, contrast)
            }

            orientationResolved = V6OrientationEvaluator.isOrientationResolved(anchorEvalMutable)
            anchorEvalMap = anchorEvalMutable

            if (!orientationResolved) {
                val emptyRes = CarrierResult(true, false, detectedQuad, hArr, null, emptyMap(), false, tracker.state.name)
                tracker.processFrame(gray, com.superqr.android.vision.v6.model.V6StaticResult(
                    borderFound = true, detectedQuad = detectedQuad, contourArea = 0.0,
                    decodedCornerIds = anchorEvalMutable.mapValues { it.value.bestMatchId },
                    decodedCornerBits = anchorEvalMutable.mapValues { it.value.decodedBits },
                    decodedCornerDistances = anchorEvalMutable.mapValues { it.value.bestDist },
                    decodedCornerMargins = anchorEvalMutable.mapValues { it.value.margin },
                    bitSamples = emptyMap(), cornerMatches = emptyMap(),
                    orientationResolved = false, reprojectionError = Double.NaN,
                    pilotYUVs = emptyMap(), cellAccuracy = 0.0, uncertainCells = 0,
                    decodedCrc32 = null, expectedCrc32 = null, colorCorrect = 0,
                    colorUncertain = 0, colorTotal = 400, confusionMatrix = null,
                    processingTimeMs = 0, failureReason = null, debugImagePath = null
                ), null)
                return emptyRes
            }

            // 6. Corrected homography with orientation-aware corner ordering
            val decodedIds = anchorEvalMutable.mapValues { it.value.bestMatchId }
            val physicalIdToPoint = mutableMapOf<String, Point>()
            physicalIdToPoint[decodedIds["TL"]!!] = orderedPts[0]
            physicalIdToPoint[decodedIds["TR"]!!] = orderedPts[1]
            physicalIdToPoint[decodedIds["BR"]!!] = orderedPts[2]
            physicalIdToPoint[decodedIds["BL"]!!] = orderedPts[3]

            val correctedSrcPts = listOf(
                physicalIdToPoint["TL"]!!, physicalIdToPoint["TR"]!!,
                physicalIdToPoint["BR"]!!, physicalIdToPoint["BL"]!!
            )

            finalHArr = solveHomographySimple(correctedSrcPts, canonicalCorners) ?: run {
                val emptyRes = CarrierResult(true, false, detectedQuad, hArr, null, emptyMap(), false, tracker.state.name)
                tracker.processFrame(gray, com.superqr.android.vision.v6.model.V6StaticResult(
                    borderFound = true, detectedQuad = detectedQuad, contourArea = 0.0,
                    decodedCornerIds = emptyMap(), decodedCornerBits = emptyMap(),
                    decodedCornerDistances = emptyMap(), decodedCornerMargins = emptyMap(),
                    bitSamples = emptyMap(), cornerMatches = emptyMap(),
                    orientationResolved = false, reprojectionError = Double.NaN,
                    pilotYUVs = emptyMap(), cellAccuracy = 0.0, uncertainCells = 0,
                    decodedCrc32 = null, expectedCrc32 = null, colorCorrect = 0,
                    colorUncertain = 0, colorTotal = 400, confusionMatrix = null,
                    processingTimeMs = 0, failureReason = null, debugImagePath = null
                ), null)
                return emptyRes
            }

            finalInvHArr = solveHomographySimple(canonicalCorners, correctedSrcPts)
        }

        // Allow null inv homography (tracking-only frames)
        if (finalInvHArr == null) {
            finalInvHArr = solveHomographySimple(canonicalCorners, orderedPts)
        }

        // 7. Sample V6 carrier pilots
        val pilotYUVs = mutableMapOf<String, IntArray>()
        var pilotsRead = false
        if (finalInvHArr != null && chromaReader != null) {
            pilotsRead = samplePilots(finalInvHArr, lumaBytes, width, height, chromaReader, pilotYUVs)
        }

        // 8. Update tracker
        val v6Result = com.superqr.android.vision.v6.model.V6StaticResult(
            borderFound = true, detectedQuad = detectedQuad, contourArea = 0.0,
            decodedCornerIds = if (classificationSource != "TRACKED_RESAMPLED")
                anchorEvalMap.mapValues { it.value.bestMatchId } else
                mapOf("TL" to "TL", "TR" to "TR", "BR" to "BR", "BL" to "BL"),
            decodedCornerBits = emptyMap(),
            decodedCornerDistances = emptyMap(), decodedCornerMargins = emptyMap(),
            bitSamples = emptyMap(), cornerMatches = emptyMap(),
            orientationResolved = orientationResolved, reprojectionError = Double.NaN,
            pilotYUVs = pilotYUVs, cellAccuracy = 0.0, uncertainCells = 0,
            decodedCrc32 = null, expectedCrc32 = null, colorCorrect = 0,
            colorUncertain = 0, colorTotal = 400, confusionMatrix = null,
            processingTimeMs = 0, failureReason = null, debugImagePath = null
        )
        tracker.processFrame(gray, v6Result, finalInvHArr)

        return CarrierResult(
            quadFound = true,
            orientationResolved = orientationResolved,
            detectedQuad = detectedQuad,
            imageToCanonicalH = finalHArr,
            canonicalToImageH = finalInvHArr,
            pilotYUVs = pilotYUVs,
            pilotsRead = pilotsRead,
            trackingState = tracker.state.name
        )
    }

    // ---- Pilot sampling ----

    private fun samplePilots(
        hInv: DoubleArray,
        lumaBytes: ByteArray, lumaWidth: Int, lumaHeight: Int,
        chromaReader: ChromaPixelReader,
        result: MutableMap<String, IntArray>
    ): Boolean {
        val h0 = hInv[0]; val h1 = hInv[1]; val h2 = hInv[2]
        val h3 = hInv[3]; val h4 = hInv[4]; val h5 = hInv[5]
        val h6 = hInv[6]; val h7 = hInv[7]; val h8 = hInv[8]
        val chromaBuf = IntArray(2)
        var anyRead = false

        val pilotNames = listOf("BLACK", "WHITE", "RED", "BLUE")
        for (pilot in pilotNames) {
            val bbox = V6Contract.getPilotCoreBBox(pilot)
            val pcx = bbox.centerX; val pcy = bbox.centerY
            val poff = bbox.width * 0.25
            val probesX = doubleArrayOf(pcx, pcx - poff, pcx + poff, pcx - poff, pcx + poff)
            val probesY = doubleArrayOf(pcy, pcy - poff, pcy - poff, pcy + poff, pcy + poff)

            val ys = IntArray(5); val us = IntArray(5); val vs = IntArray(5)
            for (p in 0 until 5) {
                val den = h6 * probesX[p] + h7 * probesY[p] + h8
                val ix = (h0 * probesX[p] + h1 * probesY[p] + h2) / den
                val iy = (h3 * probesX[p] + h4 * probesY[p] + h5) / den
                val px = ix.roundToInt(); val py = iy.roundToInt()
                if (px in 0 until lumaWidth && py in 0 until lumaHeight) {
                    ys[p] = lumaBytes[py * lumaWidth + px].toInt() and 0xFF
                } else {
                    ys[p] = 128
                }
                if (chromaReader.read(ix, iy, chromaBuf)) {
                    us[p] = chromaBuf[0]; vs[p] = chromaBuf[1]
                } else {
                    us[p] = 128; vs[p] = 128
                }
            }
            val medY = V7HighDensitySampler.median5(ys[0], ys[1], ys[2], ys[3], ys[4])
            val medU = V7HighDensitySampler.median5(us[0], us[1], us[2], us[3], us[4])
            val medV = V7HighDensitySampler.median5(vs[0], vs[1], vs[2], vs[3], vs[4])
            result[pilot] = intArrayOf(medY, medU, medV)
            anyRead = true
        }
        return anyRead
    }

    // ---- Warped-image luma sampling for anchor decoding ----

    private fun sampleMedianLuma(cx: Double, cy: Double): Int {
        val values = mutableListOf<Int>()
        val icx = cx.roundToInt(); val icy = cy.roundToInt()
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

    // ---- Homography solver (same algorithm as V6, not duplicating via V6 reference) ----

    companion object {
        fun solveHomographySimple(src: List<Point>, dst: List<Point>): DoubleArray? {
            if (src.size != 4 || dst.size != 4) return null
            val augmented = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val sx = src[i].x; val sy = src[i].y
                val dx = dst[i].x; val dy = dst[i].y
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
}
