package com.superqr.android.vision.lab.colorgrid8

import com.superqr.android.vision.opencv.OpenCvRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc

class ColorGrid8Phase2Test {
    companion object {
        @BeforeClass
        @JvmStatic
        fun setup() {
            OpenCvRuntime.ensureLoaded()
        }
    }

    private val profile = ColorGrid8Profile(
        cols = 336,
        rows = 288,
        fps = 30,
        version = ColorGrid8Spec.TRANSFER_HEADER_VERSION,
    )

    /** Helper to generate a synthetic YUV frame containing a ColorGrid8 carrier. */
    private fun createSyntheticFrame(
        width: Int = 1500,
        height: Int = 1308,
        corruptHeader: Boolean = false,
        addOrientationCue: Boolean = true,
        quad: Array<Point>? = null,
    ): ColorGrid8YuvFrame {
        val yMat = Mat(height, width, CvType.CV_8UC1, Scalar(200.0))

        // Draw 4 finder boxes (nested black/white/black squares)
        val offset = ColorGrid8Spec.FIDUCIAL_OFFSET_CELLS
        val outerCols = profile.cols + offset * 2
        val outerRows = profile.rows + offset * 2
        val scale = 4.0
        val pad = 48.0
        val outerW = (outerCols - 1) * scale
        val outerH = (outerRows - 1) * scale
        val targetQuad = quad ?: arrayOf(
            Point(pad, pad),
            Point(pad + outerW, pad),
            Point(pad + outerW, pad + outerH),
            Point(pad, pad + outerH),
        )
        val canvasW = (outerW + pad * 2.0).toInt()
        val canvasH = (outerH + pad * 2.0).toInt()
        val gridMat = Mat(canvasH, canvasW, CvType.CV_8UC1, Scalar(240.0))

        // Draw 4 corner finders directly inside gridMat
        val finderCenters = arrayOf(
            Point(pad, pad),
            Point(pad + (outerCols - 1) * scale, pad),
            Point(pad + (outerCols - 1) * scale, pad + (outerRows - 1) * scale),
            Point(pad, pad + (outerRows - 1) * scale),
        )
        val finderSize = 24.0
        val half = finderSize / 2.0
        for (pt in finderCenters) {
            Imgproc.rectangle(
                gridMat,
                Point(pt.x - half, pt.y - half),
                Point(pt.x + half, pt.y + half),
                Scalar(20.0),
                -1,
            )
            Imgproc.rectangle(
                gridMat,
                Point(pt.x - half * 0.6, pt.y - half * 0.6),
                Point(pt.x + half * 0.6, pt.y + half * 0.6),
                Scalar(240.0),
                -1,
            )
            Imgproc.rectangle(
                gridMat,
                Point(pt.x - half * 0.28, pt.y - half * 0.28),
                Point(pt.x + half * 0.28, pt.y + half * 0.28),
                Scalar(20.0),
                -1,
            )
        }

        // Draw Top-Left orientation marker in outer margin
        if (addOrientationCue) {
            val omX = pad + 0.015 * (outerCols - 1) * scale
            val omY = pad
            Imgproc.rectangle(
                gridMat,
                Point(omX - 3, omY - 3),
                Point(omX + 3, omY + 3),
                Scalar(10.0),
                -1,
            )
        }

        // Draw data grid area
        val symbols = ColorGrid8Codec.buildSymbols(profile, 42)
        if (corruptHeader) {
            for (i in 0 until profile.cols * 2) {
                symbols[i] = (symbols[i].toInt() xor 7).toByte()
            }
        }

        // Populate header and pilots
        val yCentres = byteArrayOf(40, 50, 60, 70, 180.toByte(), 190.toByte(), 200.toByte(), 210.toByte())
        val pixelBuf = ByteArray(1)
        val startX = (pad + (offset - 0.5) * scale).toInt()
        val startY = (pad + (offset - 0.5) * scale).toInt()
        val s = scale.toInt()
        for (r in 0 until profile.rows) {
            for (c in 0 until profile.cols) {
                val sym = symbols[r * profile.cols + c].toInt() and 7
                pixelBuf[0] = yCentres[sym]
                val gr = startY + r * s
                val gc = startX + c * s
                for (dy in 0 until s) {
                    for (dx in 0 until s) {
                        gridMat.put(gr + dy, gc + dx, pixelBuf)
                    }
                }
            }
        }

        val src = org.opencv.core.MatOfPoint2f(*finderCenters)
        val dst = org.opencv.core.MatOfPoint2f(*targetQuad)
        val transform = org.opencv.geometry.Geometry.getPerspectiveTransform(src, dst)
        Imgproc.warpPerspective(
            gridMat,
            yMat,
            transform,
            org.opencv.core.Size(width.toDouble(), height.toDouble()),
            Imgproc.INTER_LINEAR,
            org.opencv.core.Core.BORDER_TRANSPARENT,
            Scalar(0.0),
        )
        src.release(); dst.release(); transform.release(); gridMat.release()

        val yBytes = ByteArray(width * height)
        yMat.get(0, 0, yBytes)
        yMat.release()

        val chromaW = width / 2
        val chromaH = height / 2
        val uBytes = ByteArray(chromaW * chromaH) { 128.toByte() }
        val vBytes = ByteArray(chromaW * chromaH) { 128.toByte() }

        return ColorGrid8YuvFrame(
            width = width,
            height = height,
            y = yBytes,
            chromaWidth = chromaW,
            chromaHeight = chromaH,
            u = uBytes,
            v = vBytes,
        )
    }

    @Test
    fun testFiducialHierarchyInspection() {
        val frame = createSyntheticFrame(addOrientationCue = true)
        val mat = Mat(frame.height, frame.width, CvType.CV_8UC1)
        mat.put(0, 0, frame.y)
        val blurred = Mat()
        val binary = Mat()
        val hierarchy = Mat()
        Imgproc.GaussianBlur(mat, blurred, org.opencv.core.Size(3.0, 3.0), 0.0)
        Imgproc.threshold(blurred, binary, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE)
        println("total contours: ${contours.size}")
        for (i in contours.indices) {
            val h = hierarchy.get(0, i)
            val area = org.opencv.geometry.Geometry.contourArea(contours[i])
            val rect = org.opencv.geometry.Geometry.boundingRect(contours[i])
            val child = if (h != null && h.size >= 3) h[2].toInt() else -1
            val grandChild = if (child in contours.indices) {
                val ch = hierarchy.get(0, child)
                if (ch != null && ch.size >= 3) ch[2].toInt() else -1
            } else -1
            if (area > 40.0) {
                println("contour $i: area=$area rect=$rect h=${h?.toList()} child=$child grandChild=$grandChild")
            }
        }
        mat.release(); blurred.release(); binary.release(); hierarchy.release()
        contours.forEach { it.release() }
    }

    @Test
    fun testDirectHeaderExtraction() {
        val frame = createSyntheticFrame(addOrientationCue = false, corruptHeader = false)
        val analyzer = ColorGrid8Analyzer()

        val gray = Mat(frame.height, frame.width, CvType.CV_8UC1)
        gray.put(0, 0, frame.y)

        val offset = ColorGrid8Spec.FIDUCIAL_OFFSET_CELLS
        val outerCols = profile.cols + offset * 2
        val outerRows = profile.rows + offset * 2
        val scale = 4.0
        val pad = 48.0
        val outerW = (outerCols - 1) * scale
        val outerH = (outerRows - 1) * scale
        val knownQuad = arrayOf(
            Point(pad, pad),
            Point(pad + outerW, pad),
            Point(pad + outerW, pad + outerH),
            Point(pad, pad + outerH),
        )
        val samplesPerCell = 4
        val outerWidth = outerCols * samplesPerCell
        val headerRows = ColorGrid8Spec.HEADER_ROWS
        val headerOuterHeight = (offset + headerRows + 2) * samplesPerCell

        val src = MatOfPoint2f(*knownQuad)
        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(((outerCols - 1) * samplesPerCell).toDouble(), 0.0),
            Point(((outerCols - 1) * samplesPerCell).toDouble(), ((outerRows - 1) * samplesPerCell).toDouble()),
            Point(0.0, ((outerRows - 1) * samplesPerCell).toDouble()),
        )
        val transform = Geometry.getPerspectiveTransform(src, dst)
        val warped = Mat()
        Imgproc.warpPerspective(gray, warped, transform, Size(outerWidth.toDouble(), headerOuterHeight.toDouble()))
        val cropX = ((offset - 0.5) * samplesPerCell).toInt()
        val cropY = ((offset - 0.5) * samplesPerCell).toInt()
        val crop = Rect(cropX, cropY, profile.cols * samplesPerCell, headerRows * samplesPerCell)
        val grid = warped.submat(crop)
        val resized = Mat()
        Imgproc.resize(grid, resized, Size(profile.cols.toDouble(), headerRows.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)

        val means = ByteArray(profile.cols * headerRows)
        resized.get(0, 0, means)

        val probe = analyzer.probeHeader(means, profile.cols, headerRows)
        assertNotNull("Direct header probe failed", probe.header)

        src.release(); dst.release(); transform.release(); warped.release(); grid.release(); resized.release(); gray.release()
    }

    @Test
    fun testOrientationCueDetection() {
        val frame = createSyntheticFrame(addOrientationCue = true)
        ColorGrid8FrameProcessor().use { processor ->
            val result = processor.process(profile, frame)
            println("ORIENTATION RESULT: stage=${result.stage} status=${result.headerStatus} failure=${result.failure} mode=${result.acquisitionMode}")
            assertTrue(result.failure ?: "", result.geometryLocked)
            assertEquals("VALID", result.headerStatus)
            assertTrue(result.acquisitionMode.contains("R0"))
        }
    }

    @Test
    fun testInvalidHeaderRejectsBeforePayloadStage() {
        val frame = createSyntheticFrame(corruptHeader = true)
        ColorGrid8FrameProcessor().use { processor ->
            val result = processor.process(profile, frame)
            assertEquals(ColorGrid8Stage.HEADER, result.stage)
            assertEquals("INVALID", result.headerStatus)
            assertNull(result.analysis)
            assertEquals(0.0, result.pilotMs, 0.0)
            assertEquals(0.0, result.payloadMs, 0.0)
            assertTrue(result.headerMs > 0.0)
        }
    }

    @Test
    fun testValidHeaderAdvancesToPayloadStage() {
        val frame = createSyntheticFrame(corruptHeader = false)
        ColorGrid8FrameProcessor().use { processor ->
            val result = processor.process(profile, frame)
            assertEquals("VALID", result.headerStatus)
            assertEquals(ColorGrid8Stage.PAYLOAD, result.stage)
            assertNotNull(result.analysis)
            assertTrue(result.totalPipelineMs > 0.0)
        }
    }

    @Test
    fun testRoiFinderPathUsesBoundedRegionsAfterLock() {
        val frame1 = createSyntheticFrame()
        val frame2 = createSyntheticFrame()
        ColorGrid8FrameProcessor().use { processor ->
            val res1 = processor.process(profile, frame1)
            assertEquals("VALID", res1.headerStatus)
            assertEquals(0L, processor.roiFrames)

            val res2 = processor.process(profile, frame2)
            assertEquals("VALID", res2.headerStatus)
            assertTrue(processor.roiFrames > 0L)
            assertTrue(res2.acquisitionMode.startsWith("LOCKED_ROI"))
        }
    }

    @Test
    fun testTrackingFailureTriggersFullReacquisition() {
        val frame1 = createSyntheticFrame()
        // Blank frame causes tracking to fail
        val blankFrame = ColorGrid8YuvFrame(
            width = 960,
            height = 720,
            y = ByteArray(960 * 720) { 128.toByte() },
            chromaWidth = 480,
            chromaHeight = 360,
            u = ByteArray(480 * 360) { 128.toByte() },
            v = ByteArray(480 * 360) { 128.toByte() },
        )
        ColorGrid8FrameProcessor().use { processor ->
            processor.process(profile, frame1)
            val resBlank = processor.process(profile, blankFrame)
            assertFalse(resBlank.geometryLocked)
            assertTrue(processor.trackingFailures > 0L)
        }
    }

    @Test
    fun testRepeatedHeaderFailureTriggersReacquisition() {
        val validFrame = createSyntheticFrame(corruptHeader = false)
        val corruptFrame = createSyntheticFrame(corruptHeader = true)
        ColorGrid8FrameProcessor().use { processor ->
            processor.process(profile, validFrame)
            assertEquals(1L, processor.fullRedetections)

            // 3 consecutive corrupted headers
            processor.process(profile, corruptFrame)
            processor.process(profile, corruptFrame)
            processor.process(profile, corruptFrame)

            // Next frame triggers full redetection
            processor.process(profile, validFrame)
            assertTrue(processor.fullRedetections >= 2L)
        }
    }

    @Test
    fun testNoFourFullPayloadOrientationAttemptsAfterLock() {
        val frame = createSyntheticFrame()
        ColorGrid8FrameProcessor().use { processor ->
            val res1 = processor.process(profile, frame)
            assertTrue(res1.geometryLocked)

            // On frame 2, orientation is locked, header check is instantaneous
            val res2 = processor.process(profile, frame)
            assertTrue(res2.headerMs < 5.0)
        }
    }
}
