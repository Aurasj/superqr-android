package com.superqr.android.vision.lab.colorgrid8

import com.superqr.android.vision.opencv.OpenCvRuntime
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** Dense, rotation-normalized YUV420 planes owned by the caller for one frame. */
data class ColorGrid8YuvFrame(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val chromaWidth: Int,
    val chromaHeight: Int,
    val u: ByteArray,
    val v: ByteArray,
) {
    init {
        require(width > 0 && height > 0)
        require(chromaWidth > 0 && chromaHeight > 0)
        require(y.size >= width * height)
        require(u.size >= chromaWidth * chromaHeight)
        require(v.size >= chromaWidth * chromaHeight)
    }
}


data class ColorGrid8ProcessResult(
    val analysis: ColorGrid8AnalysisResult?,
    val stage: ColorGrid8Stage,
    val failure: String?,
    val quad: List<DoubleArray>,
    val acquisitionMode: String,
    val finderCandidates: Int,
    val geometryLocked: Boolean,
    val headerStatus: String,
    val expectedProfile: String,
    val detectedProfile: String?,
    val geometryMs: Double,
    val warpAndMeanMs: Double,
    val totalPipelineMs: Double,
)

/**
 * LAB-only optical front-end:
 * 1. acquire four nested-square fiducials in Y,
 * 2. track their centers with PyrLK between periodic re-detections,
 * 3. perspective-warp Y/U/V once per plane,
 * 4. downsample each rectified cell with INTER_AREA into one Y/U/V mean,
 * 5. feed the pilot-calibrated soft ColorGrid8 analyzer.
 */
class ColorGrid8FrameProcessor(
    private val redetectEveryFrames: Int = 10,
) : AutoCloseable {
    private data class FinderCandidate(val center: Point, val area: Double, val diameter: Double)
    private data class Acquisition(val quad: Array<Point>, val mode: String, val candidateCount: Int)
    private data class AcquisitionAttempt(
        val acquisition: Acquisition?,
        val candidateCount: Int,
        val failure: String?,
    )
    private data class OrientationAttempt(
        val offset: Int,
        val quad: Array<Point>,
        val analysis: ColorGrid8AnalysisAttempt,
        val warpMs: Double,
    )

    private val analyzer = ColorGrid8Analyzer()
    private var initialized = false
    private var frameCounter = 0L
    private var lastQuad: Array<Point>? = null
    private var orientationOffset: Int? = null

    private lateinit var gray: Mat
    private lateinit var blurred: Mat
    private lateinit var binary: Mat
    private lateinit var hierarchy: Mat
    private lateinit var chromaU: Mat
    private lateinit var chromaV: Mat
    private lateinit var previousGray: Mat
    private lateinit var warpedY: Mat
    private lateinit var warpedU: Mat
    private lateinit var warpedV: Mat
    private lateinit var meansY: Mat
    private lateinit var meansU: Mat
    private lateinit var meansV: Mat

    init {
        require(redetectEveryFrames > 0)
    }

    private fun ensureInitialized() {
        if (initialized) return
        OpenCvRuntime.ensureLoaded()
        gray = Mat()
        blurred = Mat()
        binary = Mat()
        hierarchy = Mat()
        chromaU = Mat()
        chromaV = Mat()
        previousGray = Mat()
        warpedY = Mat()
        warpedU = Mat()
        warpedV = Mat()
        meansY = Mat()
        meansU = Mat()
        meansV = Mat()
        initialized = true
    }

    fun resetTracking() {
        lastQuad = null
        orientationOffset = null
        analyzer.reset()
        frameCounter = 0L
        if (initialized) {
            previousGray.release()
            previousGray = Mat()
        }
    }

    fun process(profile: ColorGrid8Profile, frame: ColorGrid8YuvFrame): ColorGrid8ProcessResult {
        ensureInitialized()
        val pipelineStart = System.nanoTime()
        gray.create(frame.height, frame.width, CvType.CV_8UC1)
        gray.put(0, 0, frame.y)
        chromaU.create(frame.chromaHeight, frame.chromaWidth, CvType.CV_8UC1)
        chromaU.put(0, 0, frame.u)
        chromaV.create(frame.chromaHeight, frame.chromaWidth, CvType.CV_8UC1)
        chromaV.put(0, 0, frame.v)

        val geometryStart = System.nanoTime()
        val acquisitionAttempt = acquireQuad(gray, frame.width, frame.height)
        val geometryMs = (System.nanoTime() - geometryStart) / 1_000_000.0
        val acquisition = acquisitionAttempt.acquisition
        if (acquisition == null) {
            rememberGray()
            val stage = if (acquisitionAttempt.candidateCount < 4) ColorGrid8Stage.FINDERS else ColorGrid8Stage.GEOMETRY
            return failureResult(
                profile = profile,
                stage = stage,
                failure = acquisitionAttempt.failure ?: "finder geometry unavailable",
                candidateCount = acquisitionAttempt.candidateCount,
                geometryMs = geometryMs,
                pipelineStart = pipelineStart,
            )
        }

        val sx = frame.chromaWidth.toDouble() / frame.width.toDouble()
        val sy = frame.chromaHeight.toDouble() / frame.height.toDouble()
        val preferred = orientationOffset ?: 0
        val offsets = listOf(preferred) + (0..3).filter { it != preferred }
        var selected: OrientationAttempt? = null
        var accumulatedWarpMs = 0.0
        for (offset in offsets) {
            val orientedQuad = Array(4) { index -> acquisition.quad[(index + offset) and 3] }
            val candidate = analyzeOrientation(profile, orientedQuad, sx, sy, offset)
            if (candidate == null) continue
            accumulatedWarpMs += candidate.warpMs
            if (selected == null || candidate.analysis.headerScore < selected.analysis.headerScore) {
                selected = candidate
            }
            if (candidate.analysis.detectedHeader != null) {
                selected = candidate
                orientationOffset = offset
                break
            }
            // Once an orientation has decoded a header, a later transient header
            // miss must not make every camera frame pay for four full warps.
            if (orientationOffset != null) break
        }
        rememberGray()
        val orientation = selected ?: return failureResult(
            profile,
            ColorGrid8Stage.WARP,
            "perspective warp failed for every carrier orientation",
            acquisition,
            geometryMs,
            pipelineStart,
        )
        val attempt = orientation.analysis
        val totalMs = (System.nanoTime() - pipelineStart) / 1_000_000.0
        val detected = attempt.detectedHeader?.let { "${it.profileId}@${it.fps}fps seed=%04X".format(it.seed) }
        return ColorGrid8ProcessResult(
            analysis = attempt.result,
            stage = attempt.stage,
            failure = attempt.failure,
            quad = orientation.quad.map { doubleArrayOf(it.x, it.y) },
            acquisitionMode = "${acquisition.mode}_R${orientation.offset * 90}",
            finderCandidates = acquisition.candidateCount,
            geometryLocked = true,
            headerStatus = if (attempt.detectedHeader != null) "VALID" else "INVALID",
            expectedProfile = "${profile.profileId}@${profile.fps}fps ${profile.cols}x${profile.rows}",
            detectedProfile = detected,
            geometryMs = geometryMs,
            warpAndMeanMs = accumulatedWarpMs,
            totalPipelineMs = totalMs,
        )
    }

    private fun analyzeOrientation(
        profile: ColorGrid8Profile,
        sourceQuad: Array<Point>,
        sx: Double,
        sy: Double,
        offset: Int,
    ): OrientationAttempt? {
        val warpStart = System.nanoTime()
        val transferMode = profile.version == ColorGrid8Spec.TRANSFER_HEADER_VERSION
        val yMeans = warpPlaneToCellMeans(
            source = gray,
            sourceQuad = sourceQuad,
            profile = profile,
            samplesPerCell = if (transferMode) 2 else 4,
            warped = warpedY,
            resized = meansY,
        ) ?: return null
        val chromaQuad = Array(4) { index -> Point(sourceQuad[index].x * sx, sourceQuad[index].y * sy) }
        val uMeans = warpPlaneToCellMeans(
            source = chromaU,
            sourceQuad = chromaQuad,
            profile = profile,
            samplesPerCell = if (transferMode) 1 else 2,
            warped = warpedU,
            resized = meansU,
        ) ?: return null
        val vMeans = warpPlaneToCellMeans(
            source = chromaV,
            sourceQuad = chromaQuad,
            profile = profile,
            samplesPerCell = if (transferMode) 1 else 2,
            warped = warpedV,
            resized = meansV,
        ) ?: return null
        val analysis = analyzer.analyzeDetailed(profile, ColorGrid8CellMeans(yMeans, uMeans, vMeans))
        return OrientationAttempt(
            offset,
            sourceQuad,
            analysis,
            (System.nanoTime() - warpStart) / 1_000_000.0,
        )
    }

    private fun failureResult(
        profile: ColorGrid8Profile,
        stage: ColorGrid8Stage,
        failure: String,
        acquisition: Acquisition? = null,
        geometryMs: Double,
        pipelineStart: Long,
        candidateCount: Int = acquisition?.candidateCount ?: 0,
    ): ColorGrid8ProcessResult = ColorGrid8ProcessResult(
        analysis = null,
        stage = stage,
        failure = failure,
        quad = acquisition?.quad?.map { doubleArrayOf(it.x, it.y) } ?: emptyList(),
        acquisitionMode = acquisition?.mode ?: if (candidateCount > 0) "DETECTED_INVALID" else "SEARCHING",
        finderCandidates = candidateCount,
        geometryLocked = acquisition != null,
        headerStatus = "NOT_REACHED",
        expectedProfile = "${profile.profileId}@${profile.fps}fps ${profile.cols}x${profile.rows}",
        detectedProfile = null,
        geometryMs = geometryMs,
        warpAndMeanMs = 0.0,
        totalPipelineMs = (System.nanoTime() - pipelineStart) / 1_000_000.0,
    )

    private fun acquireQuad(currentGray: Mat, width: Int, height: Int): AcquisitionAttempt {
        frameCounter++
        val cached = lastQuad
        if (cached != null && !previousGray.empty() && frameCounter % redetectEveryFrames != 0L) {
            val tracked = trackQuad(cached, currentGray, width, height)
            if (tracked != null) {
                lastQuad = tracked
                return AcquisitionAttempt(Acquisition(tracked, "TRACKED_PYRLK", 4), 4, null)
            }
        }

        val detected = detectFiducials(currentGray, width, height)
        detected.acquisition?.let { lastQuad = it.quad }
        return detected
    }

    private fun trackQuad(
        previousQuad: Array<Point>,
        currentGray: Mat,
        width: Int,
        height: Int,
    ): Array<Point>? {
        if (previousGray.empty()) return null
        val p0 = MatOfPoint2f(*previousQuad)
        val p1 = MatOfPoint2f()
        val status = MatOfByte()
        val error = MatOfFloat()
        return try {
            Video.calcOpticalFlowPyrLK(previousGray, currentGray, p0, p1, status, error)
            val statuses = status.toArray()
            val points = p1.toArray()
            if (statuses.size < 4 || points.size < 4) return null
            if ((0 until 4).any { statuses[it].toInt() != 1 }) return null
            for (index in 0 until 4) {
                val moved = hypot(points[index].x - previousQuad[index].x, points[index].y - previousQuad[index].y)
                if (moved > max(width, height) * 0.08) return null
            }
            val quad = arrayOf(points[0], points[1], points[2], points[3])
            if (!validQuad(quad, width, height)) null else quad
        } catch (_: Throwable) {
            null
        } finally {
            p0.release(); p1.release(); status.release(); error.release()
        }
    }

    private fun detectFiducials(currentGray: Mat, width: Int, height: Int): AcquisitionAttempt {
        Imgproc.GaussianBlur(currentGray, blurred, Size(3.0, 3.0), 0.0)
        Imgproc.threshold(
            blurred,
            binary,
            0.0,
            255.0,
            Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU,
        )
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE)
        if (contours.isEmpty() || hierarchy.empty()) {
            contours.forEach { it.release() }
            return AcquisitionAttempt(null, 0, "no nested-square contours")
        }

        val minArea = max(80.0, width.toDouble() * height.toDouble() * 0.00008)
        val maxArea = width.toDouble() * height.toDouble() * 0.05
        val raw = ArrayList<FinderCandidate>()

        fun childOf(index: Int): Int {
            if (index !in contours.indices) return -1
            val h = hierarchy.get(0, index) ?: return -1
            return if (h.size >= 3) h[2].toInt() else -1
        }

        for (index in contours.indices) {
            val child = childOf(index)
            if (child < 0) continue
            val grandChild = childOf(child)
            if (grandChild < 0) continue
            val contour = contours[index]
            val area = Geometry.contourArea(contour)
            if (area !in minArea..maxArea) continue
            val rect = Geometry.boundingRect(contour)
            if (rect.width <= 0 || rect.height <= 0) continue
            val ratio = rect.width.toDouble() / rect.height.toDouble()
            if (ratio !in 0.45..2.20) continue
            val fill = area / (rect.width.toDouble() * rect.height.toDouble())
            if (fill < 0.35) continue
            raw.add(
                FinderCandidate(
                    center = Point(rect.x + rect.width * 0.5, rect.y + rect.height * 0.5),
                    area = area,
                    diameter = max(rect.width, rect.height).toDouble(),
                )
            )
        }

        val deduped = ArrayList<FinderCandidate>()
        for (candidate in raw.sortedByDescending { it.area }) {
            val duplicate = deduped.any {
                hypot(candidate.center.x - it.center.x, candidate.center.y - it.center.y) <
                    max(candidate.diameter, it.diameter) * 0.55
            }
            if (!duplicate) deduped.add(candidate)
            if (deduped.size >= 16) break
        }
        contours.forEach { it.release() }
        contours.clear()
        if (deduped.size < 4) return AcquisitionAttempt(null, deduped.size, "only ${deduped.size}/4 finder candidates")

        val tl = deduped.minByOrNull { it.center.x + it.center.y }
            ?: return AcquisitionAttempt(null, deduped.size, "top-left finder not resolved")
        val br = deduped.maxByOrNull { it.center.x + it.center.y }
            ?: return AcquisitionAttempt(null, deduped.size, "bottom-right finder not resolved")
        val tr = deduped.maxByOrNull { it.center.x - it.center.y }
            ?: return AcquisitionAttempt(null, deduped.size, "top-right finder not resolved")
        val bl = deduped.minByOrNull { it.center.x - it.center.y }
            ?: return AcquisitionAttempt(null, deduped.size, "bottom-left finder not resolved")
        if (setOf(tl, tr, br, bl).size != 4) {
            return AcquisitionAttempt(null, deduped.size, "finder candidates do not form four distinct corners")
        }
        val quad = arrayOf(tl.center, tr.center, br.center, bl.center)
        if (!validQuad(quad, width, height)) {
            return AcquisitionAttempt(null, deduped.size, "four finders failed convexity/size geometry checks")
        }
        return AcquisitionAttempt(Acquisition(quad, "FULL_FIDUCIAL_DETECT", deduped.size), deduped.size, null)
    }

    private fun validQuad(quad: Array<Point>, width: Int, height: Int): Boolean {
        if (quad.size != 4) return false
        if (quad.any { !it.x.isFinite() || !it.y.isFinite() }) return false
        val minEdge = minOf(width, height) * 0.12
        for (index in 0 until 4) {
            val a = quad[index]
            val b = quad[(index + 1) and 3]
            if (hypot(a.x - b.x, a.y - b.y) < minEdge) return false
        }
        var signedCross = 0.0
        for (index in 0 until 4) {
            val a = quad[index]
            val b = quad[(index + 1) and 3]
            val c = quad[(index + 2) and 3]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < 1e-6) return false
            if (index == 0) signedCross = cross else if (cross * signedCross <= 0.0) return false
        }
        var area2 = 0.0
        for (index in 0 until 4) {
            val a = quad[index]
            val b = quad[(index + 1) and 3]
            area2 += a.x * b.y - b.x * a.y
        }
        val area = abs(area2) * 0.5
        return area >= width.toDouble() * height.toDouble() * 0.06
    }

    private fun warpPlaneToCellMeans(
        source: Mat,
        sourceQuad: Array<Point>,
        profile: ColorGrid8Profile,
        samplesPerCell: Int,
        warped: Mat,
        resized: Mat,
    ): ByteArray? {
        val offset = ColorGrid8Spec.FIDUCIAL_OFFSET_CELLS
        val outerCols = profile.cols + offset * 2
        val outerRows = profile.rows + offset * 2
        val outerWidth = outerCols * samplesPerCell
        val outerHeight = outerRows * samplesPerCell
        if (outerWidth <= 0 || outerHeight <= 0) return null

        val src = MatOfPoint2f(*sourceQuad)
        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point((outerWidth - 1).toDouble(), 0.0),
            Point((outerWidth - 1).toDouble(), (outerHeight - 1).toDouble()),
            Point(0.0, (outerHeight - 1).toDouble()),
        )
        val transform = Geometry.getPerspectiveTransform(src, dst)
        return try {
            Imgproc.warpPerspective(source, warped, transform, Size(outerWidth.toDouble(), outerHeight.toDouble()))
            val crop = Rect(
                offset * samplesPerCell,
                offset * samplesPerCell,
                profile.cols * samplesPerCell,
                profile.rows * samplesPerCell,
            )
            val grid = warped.submat(crop)
            try {
                Imgproc.resize(
                    grid,
                    resized,
                    Size(profile.cols.toDouble(), profile.rows.toDouble()),
                    0.0,
                    0.0,
                    Imgproc.INTER_AREA,
                )
                val out = ByteArray(profile.totalCells)
                val read = resized.get(0, 0, out)
                if (read <= 0) null else out
            } finally {
                grid.release()
            }
        } catch (_: Throwable) {
            null
        } finally {
            src.release(); dst.release(); transform.release()
        }
    }

    private fun rememberGray() {
        try {
            gray.copyTo(previousGray)
        } catch (_: Throwable) {
            previousGray.release()
            previousGray = Mat()
        }
    }

    override fun close() {
        if (!initialized) return
        listOf(
            gray, blurred, binary, hierarchy, chromaU, chromaV, previousGray,
            warpedY, warpedU, warpedV, meansY, meansU, meansV,
        ).forEach { runCatching { it.release() } }
        initialized = false
        lastQuad = null
    }
}
