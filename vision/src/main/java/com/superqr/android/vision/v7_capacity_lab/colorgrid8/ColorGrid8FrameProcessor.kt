package com.superqr.android.vision.v7_capacity_lab.colorgrid8

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
    val analysis: ColorGrid8AnalysisResult,
    val quad: List<DoubleArray>,
    val acquisitionMode: String,
    val finderCandidates: Int,
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

    private val analyzer = ColorGrid8Analyzer()
    private var initialized = false
    private var frameCounter = 0L
    private var lastQuad: Array<Point>? = null

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
        frameCounter = 0L
        if (initialized) {
            previousGray.release()
            previousGray = Mat()
        }
    }

    fun process(profile: ColorGrid8Profile, frame: ColorGrid8YuvFrame): ColorGrid8ProcessResult? {
        ensureInitialized()
        val pipelineStart = System.nanoTime()
        gray.create(frame.height, frame.width, CvType.CV_8UC1)
        gray.put(0, 0, frame.y)
        chromaU.create(frame.chromaHeight, frame.chromaWidth, CvType.CV_8UC1)
        chromaU.put(0, 0, frame.u)
        chromaV.create(frame.chromaHeight, frame.chromaWidth, CvType.CV_8UC1)
        chromaV.put(0, 0, frame.v)

        val geometryStart = System.nanoTime()
        val acquisition = acquireQuad(gray, frame.width, frame.height)
        val geometryMs = (System.nanoTime() - geometryStart) / 1_000_000.0
        if (acquisition == null) {
            rememberGray()
            return null
        }

        val warpStart = System.nanoTime()
        val yMeans = warpPlaneToCellMeans(
            source = gray,
            sourceQuad = acquisition.quad,
            profile = profile,
            samplesPerCell = 4,
            warped = warpedY,
            resized = meansY,
        ) ?: run {
            rememberGray(); return null
        }
        val sx = frame.chromaWidth.toDouble() / frame.width.toDouble()
        val sy = frame.chromaHeight.toDouble() / frame.height.toDouble()
        val chromaQuad = Array(4) { index ->
            Point(acquisition.quad[index].x * sx, acquisition.quad[index].y * sy)
        }
        val uMeans = warpPlaneToCellMeans(
            source = chromaU,
            sourceQuad = chromaQuad,
            profile = profile,
            samplesPerCell = 2,
            warped = warpedU,
            resized = meansU,
        ) ?: run {
            rememberGray(); return null
        }
        val vMeans = warpPlaneToCellMeans(
            source = chromaV,
            sourceQuad = chromaQuad,
            profile = profile,
            samplesPerCell = 2,
            warped = warpedV,
            resized = meansV,
        ) ?: run {
            rememberGray(); return null
        }
        val warpMs = (System.nanoTime() - warpStart) / 1_000_000.0

        val analysis = analyzer.analyze(profile, ColorGrid8CellMeans(yMeans, uMeans, vMeans))
        rememberGray()
        if (analysis == null) return null
        val totalMs = (System.nanoTime() - pipelineStart) / 1_000_000.0
        return ColorGrid8ProcessResult(
            analysis = analysis,
            quad = acquisition.quad.map { doubleArrayOf(it.x, it.y) },
            acquisitionMode = acquisition.mode,
            finderCandidates = acquisition.candidateCount,
            geometryMs = geometryMs,
            warpAndMeanMs = warpMs,
            totalPipelineMs = totalMs,
        )
    }

    private fun acquireQuad(currentGray: Mat, width: Int, height: Int): Acquisition? {
        frameCounter++
        val cached = lastQuad
        val shouldRedetect = cached == null || previousGray.empty() || frameCounter % redetectEveryFrames == 0L
        if (!shouldRedetect && cached != null) {
            val tracked = trackQuad(cached, currentGray, width, height)
            if (tracked != null) {
                lastQuad = tracked
                return Acquisition(tracked, "TRACKED_PYRLK", 4)
            }
        }

        val detected = detectFiducials(currentGray, width, height) ?: return null
        lastQuad = detected.quad
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

    private fun detectFiducials(currentGray: Mat, width: Int, height: Int): Acquisition? {
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
        if (contours.isEmpty() || hierarchy.empty()) return null

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
        if (deduped.size < 4) return null

        val tl = deduped.minByOrNull { it.center.x + it.center.y } ?: return null
        val br = deduped.maxByOrNull { it.center.x + it.center.y } ?: return null
        val tr = deduped.maxByOrNull { it.center.x - it.center.y } ?: return null
        val bl = deduped.minByOrNull { it.center.x - it.center.y } ?: return null
        if (setOf(tl, tr, br, bl).size != 4) return null
        val quad = arrayOf(tl.center, tr.center, br.center, bl.center)
        if (!validQuad(quad, width, height)) return null
        return Acquisition(quad, "FULL_FIDUCIAL_DETECT", deduped.size)
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
