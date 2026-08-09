package com.superqr.android.vision.v7_capacity_lab

import com.superqr.android.vision.opencv.OpenCvRuntime
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Result of acquisition of the lab-only V7 carrier.
 *
 * A quadrilateral is deliberately not a lock. [canonicalToImageHomography] is
 * returned only when both independently rendered sync bands decode to the same
 * CRC-protected run envelope. This prevents monitor bezels and application
 * windows from becoming false locks without carrying V6's anchor contract into
 * the V7 architecture.
 */
data class V7CarrierAcquisitionResult(
    val canonicalToImageHomography: DoubleArray?,
    val sync: V7LabSyncResult,
    val source: String,
    val contourCount: Int,
    val candidateCount: Int,
    val syncAttempts: Int,
    val bestSyncStatus: String,
    val detectedQuad: List<DoubleArray>? = null,
    val carrierLike: Boolean = false,
    val finderHypothesisCount: Int = 0,
    val finderCenters: List<DoubleArray> = emptyList(),
    val visibleFinderCount: Int = finderCenters.size.coerceAtMost(4),
    val candidateSummary: String? = null,
) {
    val acquired: Boolean
        get() = canonicalToImageHomography != null && sync.envelope != null
}

/**
 * Dedicated V7 acquisition/tracking foundation for the physical PHY lab.
 *
 * Contours provide cheap homography hypotheses; duplicated protected optical
 * sync validates them. A validated homography is sampled first on subsequent
 * frames, making steady-state tracking allocation-free and much cheaper than
 * repeated contour acquisition. Short sync misses are held on the tracked path;
 * bounded repeated misses fall back to full acquisition.
 */
class V7CarrierAcquirer(
    private val spec: V7CarrierSpec = V7CarrierSpec(),
) : AutoCloseable {
    private data class QuadCandidate(
        val points: Array<Point>,
        val area: Double,
        val source: String,
    )

    private data class SyncHypothesis(
        val homography: DoubleArray,
        val sync: V7LabSyncResult,
        val quad: Array<Point>,
        val source: String,
    )

    private data class FinderCenter(val point: Point, val strength: Int, val outerArea: Double)
    private data class FinderEvidence(val centers: List<Point>, val quads: List<Array<Point>>)

    private var initialized = false
    private lateinit var gray: Mat
    private lateinit var blurred: Mat
    private lateinit var binary: Mat
    private lateinit var edges: Mat
    private lateinit var hierarchy: Mat
    private lateinit var closeKernel: Mat
    private lateinit var previousGray: Mat
    private lateinit var flowPrevious: MatOfPoint2f
    private lateinit var flowCurrent: MatOfPoint2f
    private lateinit var flowStatus: MatOfByte
    private lateinit var flowError: MatOfFloat
    private lateinit var temporalBrightest: Mat
    private var temporalLuma = ByteArray(0)
    private var temporalFrames = 0
    private val syncDecoder = V7Phase1SyncDecoder(spec)
    private var trackedHomography: DoubleArray? = null
    private var trackedQuad: Array<Point>? = null
    private var trackedCanonicalQuad: Array<Point>? = null
    private var trackingReferenceValid = false
    private var framesSinceFlow = 0
    private var consecutiveTrackMisses = 0
    private var consecutiveColdFailures = 0
    private var trackedFinderCount = 0

    @Synchronized
    fun analyze(
        luma: ByteArray,
        width: Int,
        height: Int,
        diagnostics: Boolean = true,
    ): V7CarrierAcquisitionResult {
        require(width > 0 && height > 0 && luma.size >= width * height)
        ensureInitialized()
        gray.create(height, width, CvType.CV_8UC1)
        gray.put(0, 0, luma)

        trackedHomography?.let { homography ->
            var flowAttempted = false
            if (trackingReferenceValid && framesSinceFlow >= FLOW_INTERVAL_FRAMES) {
                flowAttempted = true
                val flowed = tryOpticalFlow(luma, width, height)
                if (flowed != null) return flowed
            }
            val sync = syncDecoder.analyze(homography, luma, width, height)
            if (sync.envelope != null) {
                consecutiveTrackMisses = 0
                framesSinceFlow++
                return V7CarrierAcquisitionResult(
                    canonicalToImageHomography = homography,
                    sync = sync,
                    source = "V7_SYNC_TRACKED",
                    contourCount = 0,
                    candidateCount = 0,
                    syncAttempts = 1,
                    bestSyncStatus = sync.status,
                    detectedQuad = null,
                    carrierLike = true,
                    finderHypothesisCount = 1,
                    visibleFinderCount = trackedFinderCount,
                )
            }
            if (trackingReferenceValid && !flowAttempted) {
                flowAttempted = true
                val flowed = tryOpticalFlow(luma, width, height)
                if (flowed != null) return flowed
            }

            // A single rolling-shutter/mixed-frame sync miss is not evidence that
            // geometry was lost. The previous implementation immediately launched
            // the 70-100 ms contour search on every isolated CRC miss, even though
            // the next camera frame almost always revalidated the tracked lock.
            // Hold the last proven geometry for a bounded number of misses and do
            // no contour work on those rejected frames.
            consecutiveTrackMisses++
            if (consecutiveTrackMisses < MAX_TRACK_MISSES) {
                return V7CarrierAcquisitionResult(
                    canonicalToImageHomography = null,
                    sync = sync,
                    source = "V7_TRACK_HOLD",
                    contourCount = 0,
                    candidateCount = 0,
                    syncAttempts = if (flowAttempted) 2 else 1,
                    bestSyncStatus = sync.status,
                    detectedQuad = null,
                    carrierLike = true,
                    finderHypothesisCount = 1,
                    visibleFinderCount = trackedFinderCount,
                )
            }

            trackedHomography = null
            trackedQuad = null
            trackedCanonicalQuad = null
            trackingReferenceValid = false
            trackedFinderCount = 0
        }

        if (temporalFrames == 0 || temporalBrightest.rows() != height || temporalBrightest.cols() != width || temporalFrames >= TEMPORAL_WINDOW_FRAMES) {
            gray.copyTo(temporalBrightest)
            temporalFrames = 1
        } else {
            Core.max(temporalBrightest, gray, temporalBrightest)
            temporalFrames++
        }
        if (temporalLuma.size != width * height) temporalLuma = ByteArray(width * height)
        temporalBrightest.get(0, 0, temporalLuma)

        // Temporal brightest-value compositing removes moving dark monitor scan
        // bands without weakening edges. It is used only to propose geometry;
        // protected sync is always decoded from the current camera frame.
        Imgproc.GaussianBlur(temporalBrightest, blurred, Size(5.0, 5.0), 0.0)

        val candidates = ArrayList<QuadCandidate>(MAX_CANDIDATES)
        var contourCount = 0
        Imgproc.threshold(blurred, binary, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, closeKernel)
        contourCount += collectCandidates(binary, width, height, "OTSU", candidates)

        // Edges preserve the border when exposure or a bright monitor surround
        // makes the binary segmentation merge neighboring regions.
        Imgproc.Canny(blurred, edges, 36.0, 108.0)
        Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, closeKernel)
        contourCount += collectCandidates(edges, width, height, "CANNY", candidates)

        candidates.sortByDescending { it.area }
        var attempts = 0
        var best: SyncHypothesis? = null
        var bestRank = Int.MIN_VALUE

        // A monitor/camera scan band can fragment a long continuous border while
        // leaving the four compact nested finders intact. Recover a projective
        // hypothesis from their centers before trying arbitrary border quads.
        val finderEvidence = finderEvidence(candidates, temporalLuma, width, height)
        val finderQuads = finderEvidence.quads
        for (finderQuad in finderQuads) {
            val ordered = orderAroundCenter(finderQuad)
            val windings = arrayOf(ordered, reverseWinding(ordered))
            for (winding in windings) {
                for (rotation in 0 until 4) {
                    val imageQuad = Array(4) { winding[(it + rotation) and 3] }
                    val homography = solveHomography(canonicalQuad(FINDER_CENTER_EDGE), imageQuad) ?: continue
                    attempts++
                    val sync = syncDecoder.analyze(homography, luma, width, height)
                    val hypothesis = SyncHypothesis(homography, sync, imageQuad, "FINDERS")
                    if (sync.envelope != null) {
                        val trackingCanonical = canonicalQuad(FINDER_TRACKING_EDGE)
                        val trackingImage = Array(4) { index -> mapPoint(homography, trackingCanonical[index]) }
                        trackedHomography = homography.copyOf()
                        trackedQuad = trackingImage
                        trackedCanonicalQuad = trackingCanonical
                        trackedFinderCount = finderEvidence.centers.size.coerceAtMost(4)
                        updateTrackingReference()
                        temporalFrames = 0
                        consecutiveTrackMisses = 0
                        consecutiveColdFailures = 0
                        return V7CarrierAcquisitionResult(
                            canonicalToImageHomography = homography,
                            sync = sync,
                            source = "V7_FINDERS_ACQUIRED",
                            contourCount = contourCount,
                            candidateCount = candidates.size,
                            syncAttempts = attempts,
                            bestSyncStatus = sync.status,
                            detectedQuad = if (diagnostics && sync.envelope.state != V7LabRunState.RUNNING) imageQuad.asDiagnosticQuad() else null,
                            carrierLike = true,
                            finderHypothesisCount = finderQuads.size,
                            visibleFinderCount = trackedFinderCount,
                        )
                    }
                    val rank = syncRank(sync)
                    if (rank > bestRank) {
                        best = hypothesis
                        bestRank = rank
                    }
                }
            }
        }

        for (candidate in candidates.take(MAX_CANDIDATES)) {
            val ordered = orderAroundCenter(candidate.points)
            val windings = arrayOf(ordered, reverseWinding(ordered))
            for (winding in windings) {
                for (rotation in 0 until 4) {
                    val imageQuad = Array(4) { winding[(it + rotation) and 3] }
                    for (edge in spec.candidateContourEdges) {
                        val canonicalQuad = canonicalQuad(edge)
                        val homography = solveHomography(canonicalQuad, imageQuad) ?: continue
                        attempts++
                        val sync = syncDecoder.analyze(homography, luma, width, height)
                        val hypothesis = SyncHypothesis(homography, sync, imageQuad, candidate.source)
                        if (sync.envelope != null) {
                            trackedHomography = homography.copyOf()
                            trackedQuad = imageQuad.copyPoints()
                            trackedCanonicalQuad = canonicalQuad.copyPoints()
                            trackedFinderCount = finderEvidence.centers.size.coerceAtMost(4)
                            updateTrackingReference()
                            temporalFrames = 0
                            consecutiveTrackMisses = 0
                            consecutiveColdFailures = 0
                            return V7CarrierAcquisitionResult(
                                canonicalToImageHomography = homography,
                                sync = sync,
                                source = "V7_${candidate.source}_ACQUIRED",
                                contourCount = contourCount,
                                candidateCount = candidates.size,
                                syncAttempts = attempts,
                                bestSyncStatus = sync.status,
                                detectedQuad = if (diagnostics && sync.envelope.state != V7LabRunState.RUNNING) imageQuad.asDiagnosticQuad() else null,
                                carrierLike = true,
                                finderHypothesisCount = finderQuads.size,
                                visibleFinderCount = trackedFinderCount,
                            )
                        }
                        val rank = syncRank(sync)
                        if (rank > bestRank) {
                            best = hypothesis
                            bestRank = rank
                        }
                    }
                }
            }
        }

        consecutiveColdFailures++
        val rawBestSync = best?.sync ?: V7LabSyncResult(null, "V7_NO_QUADRILATERAL", -1, -1)
        val incompleteGeometry = finderQuads.isEmpty() && bestRank >= SYNC_EVIDENCE_RANK &&
            consecutiveColdFailures >= INCOMPLETE_GEOMETRY_FRAMES
        val bestSync = if (incompleteGeometry) {
            rawBestSync.copy(status = "V7_NO_COMPLETE_CARRIER_GEOMETRY")
        } else {
            rawBestSync
        }
        return V7CarrierAcquisitionResult(
            canonicalToImageHomography = null,
            sync = bestSync,
            source = if (candidates.isEmpty()) "V7_NO_CANDIDATE" else "V7_SYNC_NOT_VALIDATED",
            contourCount = contourCount,
            candidateCount = candidates.size,
            syncAttempts = attempts,
            bestSyncStatus = bestSync.status,
            detectedQuad = if (diagnostics) best?.quad?.asDiagnosticQuad() else null,
            carrierLike = bestRank >= SYNC_EVIDENCE_RANK,
            finderHypothesisCount = finderQuads.size,
            finderCenters = if (diagnostics) finderEvidence.centers.asDiagnosticCenters() else emptyList(),
            visibleFinderCount = finderEvidence.centers.size.coerceAtMost(4),
            candidateSummary = if (diagnostics) candidates.take(32).joinToString(";") {
                val center = centerOf(it.points)
                "${it.source}:${it.area.toInt()}@${center.x.toInt()},${center.y.toInt()}"
            } else null,
        )
    }

    private fun ensureInitialized() {
        if (initialized) return
        OpenCvRuntime.ensureLoaded()
        gray = Mat()
        blurred = Mat()
        binary = Mat()
        edges = Mat()
        hierarchy = Mat()
        previousGray = Mat()
        flowPrevious = MatOfPoint2f()
        flowCurrent = MatOfPoint2f()
        flowStatus = MatOfByte()
        flowError = MatOfFloat()
        temporalBrightest = Mat()
        closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        initialized = true
    }

    private fun tryOpticalFlow(
        luma: ByteArray,
        width: Int,
        height: Int,
    ): V7CarrierAcquisitionResult? {
        val previousQuad = trackedQuad ?: return null
        val canonicalQuad = trackedCanonicalQuad ?: return null
        if (!trackingReferenceValid || previousGray.empty()) return null
        flowPrevious.fromArray(*previousQuad)
        return try {
            Video.calcOpticalFlowPyrLK(previousGray, gray, flowPrevious, flowCurrent, flowStatus, flowError)
            val status = flowStatus.toArray()
            val current = flowCurrent.toArray()
            if (status.size < 4 || current.size < 4 || status.take(4).any { it.toInt() != 1 }) return null
            for (index in 0 until 4) {
                val movement = hypot(current[index].x - previousQuad[index].x, current[index].y - previousQuad[index].y)
                if (!movement.isFinite() || movement > MAX_FLOW_DISPLACEMENT_PIXELS) return null
            }
            if (!isUsableQuad(current, width, height)) return null
            val homography = solveHomography(canonicalQuad, current) ?: return null
            val sync = syncDecoder.analyze(homography, luma, width, height)
            if (sync.envelope == null) return null
            trackedHomography = homography.copyOf()
            trackedQuad = current.copyPoints()
            updateTrackingReference()
            consecutiveTrackMisses = 0
            V7CarrierAcquisitionResult(
                canonicalToImageHomography = homography,
                sync = sync,
                source = "V7_OPTICAL_FLOW_TRACKED",
                contourCount = 0,
                candidateCount = 0,
                syncAttempts = 1,
                bestSyncStatus = sync.status,
                detectedQuad = null,
                carrierLike = true,
                finderHypothesisCount = 1,
                visibleFinderCount = trackedFinderCount,
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun updateTrackingReference() {
        gray.copyTo(previousGray)
        trackingReferenceValid = true
        framesSinceFlow = 0
    }

    /** Returns the number of contours inspected, not just accepted quads. */
    private fun collectCandidates(
        mask: Mat,
        width: Int,
        height: Int,
        source: String,
        output: MutableList<QuadCandidate>,
    ): Int {
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        val minimumArea = maxOf(MIN_ABSOLUTE_AREA, width.toDouble() * height * MIN_FRAME_AREA_FRACTION)
        val maximumArea = width.toDouble() * height * MAX_FRAME_AREA_FRACTION
        val contour2f = MatOfPoint2f()
        val approx = MatOfPoint2f()
        val hullIndices = MatOfInt()
        try {
            for (contour in contours) {
                val area = abs(Geometry.contourArea(contour))
                if (area !in minimumArea..maximumArea) continue
                contour.convertTo(contour2f, CvType.CV_32F)
                val perimeter = Geometry.arcLength(contour2f, true)
                if (perimeter <= 0.0) continue

                var accepted: Array<Point>? = null
                for (epsilon in APPROXIMATION_EPSILONS) {
                    Geometry.approxPolyDP(contour2f, approx, epsilon * perimeter, true)
                    val points = approx.toArray()
                    if (points.size == 4 && isUsableQuad(points, width, height)) {
                        accepted = points
                        break
                    }
                }
                if (accepted == null) {
                    Geometry.convexHull(contour, hullIndices)
                    if (hullIndices.rows() >= 4) {
                        val sourcePoints = contour.toArray()
                        val hullPoints = Array(hullIndices.rows()) { index ->
                            sourcePoints[hullIndices.get(index, 0)[0].toInt()]
                        }
                        val hull2f = MatOfPoint2f(*hullPoints)
                        try {
                            val hullPerimeter = Geometry.arcLength(hull2f, true)
                            for (epsilon in APPROXIMATION_EPSILONS) {
                                Geometry.approxPolyDP(hull2f, approx, epsilon * hullPerimeter, true)
                                val points = approx.toArray()
                                if (points.size == 4 && isUsableQuad(points, width, height)) {
                                    accepted = points
                                    break
                                }
                            }
                        } finally {
                            hull2f.release()
                        }
                    }
                }
                if (accepted == null) {
                    val rectangle = Geometry.minAreaRect(contour2f)
                    val rectangleArea = rectangle.size.width * rectangle.size.height
                    if (rectangleArea > 0.0 && area / rectangleArea >= MIN_RECTANGULARITY) {
                        val points = arrayOfNulls<Point>(4)
                        rectangle.points(points)
                        val nonNull = points.map { it!! }.toTypedArray()
                        if (isUsableQuad(nonNull, width, height)) accepted = nonNull
                    }
                }
                val points = accepted ?: continue
                val ordered = orderAroundCenter(points)
                if (output.none { equivalent(it.points, ordered, width, height) }) {
                    output += QuadCandidate(ordered.copyPoints(), area, source)
                }
            }
        } finally {
            contour2f.release()
            approx.release()
            hullIndices.release()
            contours.forEach { it.release() }
        }
        return contours.size
    }

    private fun isUsableQuad(points: Array<Point>, width: Int, height: Int): Boolean {
        val mat = MatOfPoint(*points)
        val convex = try { Geometry.isContourConvex(mat) } finally { mat.release() }
        if (!convex) return false
        val ordered = orderAroundCenter(points)
        val lengths = DoubleArray(4) { index ->
            val a = ordered[index]
            val b = ordered[(index + 1) and 3]
            hypot(a.x - b.x, a.y - b.y)
        }
        if ((lengths.minOrNull() ?: 0.0) < MIN_EDGE_PIXELS) return false
        val frameDiagonal = hypot(width.toDouble(), height.toDouble())
        return (lengths.maxOrNull() ?: frameDiagonal) < frameDiagonal * 0.99
    }

    private fun equivalent(a: Array<Point>, b: Array<Point>, width: Int, height: Int): Boolean {
        val orderedA = orderAroundCenter(a)
        val orderedB = orderAroundCenter(b)
        val threshold = hypot(width.toDouble(), height.toDouble()) * DEDUPE_DIAGONAL_FRACTION
        for (rotation in 0 until 4) {
            var maximum = 0.0
            for (index in 0 until 4) {
                val p = orderedA[index]
                val q = orderedB[(index + rotation) and 3]
                maximum = maxOf(maximum, hypot(p.x - q.x, p.y - q.y))
            }
            if (maximum <= threshold) return true
        }
        return false
    }

    /** Finds four groups of concentric square contours and returns center quads. */
    private fun finderEvidence(
        candidates: List<QuadCandidate>,
        luma: ByteArray,
        width: Int,
        height: Int,
    ): FinderEvidence {
        data class ScoredQuad(val points: Array<Point>, val score: Double)

        val frameArea = width.toDouble() * height
        val squares = candidates.filter { candidate ->
            val areaFraction = candidate.area / frameArea
            areaFraction in MIN_FINDER_AREA_FRACTION..MAX_FINDER_AREA_FRACTION && isSquareLike(candidate.points)
        }
        val centers = ArrayList<FinderCenter>()
        for (candidate in squares) {
            // Depending on threshold polarity, moire, and whether the narrow
            // quiet gap merged with the carrier border, OpenCV may return the
            // 7/7 outer boundary, 5/7 white-ring boundary, or 3/7 black core.
            // Validate each interpretation photometrically instead of requiring
            // a fragile contour hierarchy that rolling display scan can split.
            for (outerScale in FINDER_OUTER_SCALES) {
                val outerPoints = scaleAboutCenter(candidate.points, outerScale)
                val strength = finderPatternContrast(outerPoints, luma, width, height)
                if (strength < MIN_FINDER_CONTRAST) continue
                val outerCenter = centerOf(outerPoints)
                val outerArea = quadArea(outerPoints)
                val outerSize = kotlin.math.sqrt(outerArea)
                val existing = centers.indexOfFirst {
                    hypot(it.point.x - outerCenter.x, it.point.y - outerCenter.y) <= maxOf(6.0, outerSize * 0.15)
                }
                val finder = FinderCenter(outerCenter, strength, outerArea)
                if (existing < 0) centers += finder
                else if (strength > centers[existing].strength ||
                    (strength == centers[existing].strength && outerArea > centers[existing].outerArea)
                ) {
                    centers[existing] = finder
                }
            }
        }
        val bounded = centers.sortedWith(
            compareByDescending<FinderCenter> { it.strength }.thenByDescending { it.outerArea },
        ).take(MAX_FINDER_CENTERS)
        if (bounded.size < 4) return FinderEvidence(bounded.map { it.point }, emptyList())
        val scored = ArrayList<ScoredQuad>()
        for (a in 0 until bounded.size - 3) for (b in a + 1 until bounded.size - 2) {
            for (c in b + 1 until bounded.size - 1) for (d in c + 1 until bounded.size) {
                val points = orderAroundCenter(arrayOf(bounded[a].point, bounded[b].point, bounded[c].point, bounded[d].point))
                val area = quadArea(points)
                if (area < frameArea * MIN_FINDER_QUAD_AREA_FRACTION) continue
                val sides = DoubleArray(4) { index ->
                    val p = points[index]; val q = points[(index + 1) and 3]
                    hypot(p.x - q.x, p.y - q.y)
                }
                val horizontalBalance = minOf(sides[0], sides[2]) / maxOf(sides[0], sides[2])
                val verticalBalance = minOf(sides[1], sides[3]) / maxOf(sides[1], sides[3])
                if (horizontalBalance < MIN_OPPOSITE_SIDE_BALANCE || verticalBalance < MIN_OPPOSITE_SIDE_BALANCE) continue
                val diagonalA = hypot(points[0].x - points[2].x, points[0].y - points[2].y)
                val diagonalB = hypot(points[1].x - points[3].x, points[1].y - points[3].y)
                val diagonalBalance = minOf(diagonalA, diagonalB) / maxOf(diagonalA, diagonalB)
                if (diagonalBalance < MIN_DIAGONAL_BALANCE) continue
                val strength = bounded[a].strength + bounded[b].strength + bounded[c].strength + bounded[d].strength
                scored += ScoredQuad(points, area * horizontalBalance * verticalBalance * diagonalBalance * (1.0 + strength * 0.08))
            }
        }
        return FinderEvidence(
            centers = bounded.map { it.point },
            quads = scored.sortedByDescending { it.score }.take(MAX_FINDER_QUADS).map { it.points },
        )
    }

    private fun List<Point>.asDiagnosticCenters(): List<DoubleArray> =
        take(4).map { doubleArrayOf(it.x, it.y) }

    /** Samples the black/white/black nested finder identity inside a quad. */
    private fun finderPatternContrast(
        points: Array<Point>,
        luma: ByteArray,
        width: Int,
        height: Int,
    ): Int {
        val homography = solveHomography(
            arrayOf(Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0), Point(0.0, 1.0)),
            orderAroundCenter(points),
        ) ?: return 0
        // Sample near the centers of the 1:1:3:1:1 QR-style finder rings.
        // The previous 0.28/0.72 white samples belonged to the old non-standard
        // marker and landed close to the enlarged 3/7 core after correction.
        val outer = doubleArrayOf(0.07, 0.50, 0.93, 0.50, 0.50, 0.07, 0.50, 0.93)
        val white = doubleArrayOf(0.21, 0.50, 0.79, 0.50, 0.50, 0.21, 0.50, 0.79)
        val blackSamples = IntArray(5)
        val whiteSamples = IntArray(4)
        for (index in 0 until 4) {
            blackSamples[index] = projectedMedian(homography, outer[index * 2], outer[index * 2 + 1], luma, width, height)
            whiteSamples[index] = projectedMedian(homography, white[index * 2], white[index * 2 + 1], luma, width, height)
        }
        blackSamples[4] = projectedMedian(homography, 0.5, 0.5, luma, width, height)
        if (blackSamples.any { it < 0 } || whiteSamples.any { it < 0 }) return 0
        blackSamples.sort(); whiteSamples.sort()
        return whiteSamples[whiteSamples.size / 2] - blackSamples[blackSamples.size / 2]
    }

    private fun projectedMedian(
        homography: DoubleArray,
        x: Double,
        y: Double,
        luma: ByteArray,
        width: Int,
        height: Int,
    ): Int {
        val point = mapPoint(homography, Point(x, y))
        val centerX = point.x.toInt(); val centerY = point.y.toInt()
        if (centerX !in 1 until width - 1 || centerY !in 1 until height - 1) return -1
        val values = IntArray(9)
        var index = 0
        for (dy in -1..1) for (dx in -1..1) {
            values[index++] = luma[(centerY + dy) * width + centerX + dx].toInt() and 0xFF
        }
        values.sort()
        return values[4]
    }

    private fun isSquareLike(points: Array<Point>): Boolean {
        val ordered = orderAroundCenter(points)
        val sides = DoubleArray(4) { index ->
            val p = ordered[index]; val q = ordered[(index + 1) and 3]
            hypot(p.x - q.x, p.y - q.y)
        }
        val minimum = sides.minOrNull() ?: return false
        val maximum = sides.maxOrNull() ?: return false
        return maximum > 0.0 && minimum / maximum >= MIN_FINDER_SIDE_RATIO
    }

    private fun centerOf(points: Array<Point>): Point =
        Point(points.sumOf { it.x } / points.size, points.sumOf { it.y } / points.size)

    private fun scaleAboutCenter(points: Array<Point>, scale: Double): Array<Point> {
        val center = centerOf(points)
        return Array(points.size) { index ->
            Point(
                center.x + (points[index].x - center.x) * scale,
                center.y + (points[index].y - center.y) * scale,
            )
        }
    }

    private fun quadArea(points: Array<Point>): Double {
        var sum = 0.0
        for (index in points.indices) {
            val next = points[(index + 1) % points.size]
            sum += points[index].x * next.y - next.x * points[index].y
        }
        return abs(sum) * 0.5
    }

    private fun orderAroundCenter(points: Array<Point>): Array<Point> {
        val centerX = points.sumOf { it.x } / points.size
        val centerY = points.sumOf { it.y } / points.size
        return points.sortedBy { atan2(it.y - centerY, it.x - centerX) }.toTypedArray()
    }

    private fun reverseWinding(points: Array<Point>): Array<Point> =
        arrayOf(points[0], points[3], points[2], points[1])

    private fun canonicalQuad(edge: Double): Array<Point> = arrayOf(
        Point(edge, edge),
        Point(spec.canvasSize - edge, edge),
        Point(spec.canvasSize - edge, spec.canvasSize - edge),
        Point(edge, spec.canvasSize - edge),
    )

    private fun mapPoint(homography: DoubleArray, point: Point): Point {
        val denominator = homography[6] * point.x + homography[7] * point.y + homography[8]
        return Point(
            (homography[0] * point.x + homography[1] * point.y + homography[2]) / denominator,
            (homography[3] * point.x + homography[4] * point.y + homography[5]) / denominator,
        )
    }

    private fun syncRank(sync: V7LabSyncResult): Int = when {
        sync.envelope != null -> 100
        sync.status.contains("MISMATCH") -> 80
        sync.status.contains("CRC_OR_HEADER") -> 60
        sync.status.contains("LOW_CONTRAST") -> 20 + minOf(sync.topContrast, sync.bottomContrast)
        sync.status.contains("UNREADABLE") -> 5
        else -> 0
    }

    private fun solveHomography(src: Array<Point>, dst: Array<Point>): DoubleArray? {
        val augmented = Array(8) { DoubleArray(9) }
        for (index in 0 until 4) {
            val sx = src[index].x; val sy = src[index].y
            val dx = dst[index].x; val dy = dst[index].y
            val rowX = augmented[index * 2]
            rowX[0] = sx; rowX[1] = sy; rowX[2] = 1.0
            rowX[6] = -dx * sx; rowX[7] = -dx * sy; rowX[8] = dx
            val rowY = augmented[index * 2 + 1]
            rowY[3] = sx; rowY[4] = sy; rowY[5] = 1.0
            rowY[6] = -dy * sx; rowY[7] = -dy * sy; rowY[8] = dy
        }
        for (column in 0 until 8) {
            var pivot = column
            for (row in column + 1 until 8) {
                if (abs(augmented[row][column]) > abs(augmented[pivot][column])) pivot = row
            }
            if (abs(augmented[pivot][column]) < 1e-12) return null
            val temporary = augmented[column]; augmented[column] = augmented[pivot]; augmented[pivot] = temporary
            val divisor = augmented[column][column]
            for (entry in column until 9) augmented[column][entry] /= divisor
            for (row in 0 until 8) {
                if (row == column) continue
                val factor = augmented[row][column]
                for (entry in column until 9) augmented[row][entry] -= factor * augmented[column][entry]
            }
        }
        return doubleArrayOf(
            augmented[0][8], augmented[1][8], augmented[2][8],
            augmented[3][8], augmented[4][8], augmented[5][8],
            augmented[6][8], augmented[7][8], 1.0,
        ).takeIf { values -> values.all { it.isFinite() } }
    }

    @Synchronized
    override fun close() {
        if (!initialized) return
        gray.release(); blurred.release(); binary.release(); edges.release(); hierarchy.release(); closeKernel.release()
        previousGray.release(); flowPrevious.release(); flowCurrent.release(); flowStatus.release(); flowError.release()
        temporalBrightest.release()
        initialized = false
        trackedHomography = null
        trackedQuad = null
        trackedCanonicalQuad = null
        trackingReferenceValid = false
        trackedFinderCount = 0
        temporalFrames = 0
        consecutiveTrackMisses = 0
        consecutiveColdFailures = 0
    }

    private fun Array<Point>.copyPoints(): Array<Point> = Array(size) { Point(this[it].x, this[it].y) }
    private fun Array<Point>.asDiagnosticQuad(): List<DoubleArray> = map { doubleArrayOf(it.x, it.y) }

    companion object {
        private const val MIN_ABSOLUTE_AREA = 1_200.0
        private const val MIN_FRAME_AREA_FRACTION = 0.0015
        private const val MAX_FRAME_AREA_FRACTION = 0.97
        private const val MIN_RECTANGULARITY = 0.62
        private const val MIN_EDGE_PIXELS = 24.0
        // Keep the concentric finder rings distinct (typically 8-15 px apart at
        // 720p) while still merging duplicate Otsu/Canny contours of one edge.
        private const val DEDUPE_DIAGONAL_FRACTION = 0.0035
        private const val MAX_CANDIDATES = 24
        private const val MAX_TRACK_MISSES = 3
        private const val TEMPORAL_WINDOW_FRAMES = 8
        private const val FLOW_INTERVAL_FRAMES = 3
        private const val MAX_FLOW_DISPLACEMENT_PIXELS = 90.0
        private const val SYNC_EVIDENCE_RANK = 60
        private const val INCOMPLETE_GEOMETRY_FRAMES = 6
        private const val FINDER_CENTER_EDGE = 130.0
        private const val FINDER_TRACKING_EDGE = 80.0
        private const val MIN_FINDER_AREA_FRACTION = 0.00045
        private const val MAX_FINDER_AREA_FRACTION = 0.04
        private const val MIN_FINDER_CONTRAST = 30
        private const val MIN_FINDER_QUAD_AREA_FRACTION = 0.035
        private const val MIN_OPPOSITE_SIDE_BALANCE = 0.45
        private const val MIN_DIAGONAL_BALANCE = 0.55
        private const val MIN_FINDER_SIDE_RATIO = 0.58
        private const val MAX_FINDER_CENTERS = 16
        private const val MAX_FINDER_QUADS = 8
        private val FINDER_OUTER_SCALES = doubleArrayOf(1.0, 7.0 / 5.0, 7.0 / 3.0)
        private val APPROXIMATION_EPSILONS = doubleArrayOf(0.012, 0.02, 0.035, 0.055)
    }
}
