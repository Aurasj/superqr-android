package com.superqr.android.vision.v6

import com.superqr.android.vision.v6.contract.V6Contract
import com.superqr.android.vision.v6.detection.V6StaticDetector
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.io.File

class V6BorderAcquisitionTest {

    init {
        try {
            System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
        } catch (e: Throwable) {
            println("OpenCV native library load warning: ${e.message}")
        }
    }

    private val isOpenCvAvailable: Boolean by lazy {
        try {
            System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
            Core.getVersionString()
            true
        } catch (e: Throwable) {
            false
        }
    }

    private fun setupContract() {
        val contractFile = File("src/main/assets/visual_contract.json")
        assertTrue("Contract file should exist", contractFile.exists())
        V6Contract.loadAndVerifyBytes(contractFile.readBytes())
    }

    @Test
    fun testCleanFrontalMarkerDetected() {
        Assume.assumeTrue("OpenCV native library is required", isOpenCvAvailable)
        setupContract()

        val width = 1000
        val height = 1000
        val luma = ByteArray(width * height) { 255.toByte() }

        // Draw basic frontal border
        for (y in 100..900) {
            for (x in 100..900) {
                if (x < 120 || x > 880 || y < 120 || y > 880) {
                    luma[y * width + x] = 0.toByte()
                }
            }
        }

        val detector = V6StaticDetector()
        val result = detector.detect(luma, width, height, "all-black", null)
        
        assertTrue("Frontal border should be found", result.borderFound)
        assertTrue("Should have considered at least 1 quad", result.quadsConsidered > 0)
    }

    @Test
    fun testPerspectiveSkewedMarkerDetected() {
        Assume.assumeTrue("OpenCV native library is required", isOpenCvAvailable)
        setupContract()

        val width = 1000
        val height = 1000
        val mat = Mat(height, width, CvType.CV_8UC1, Scalar(255.0))

        // Create a heavily skewed quadrilateral (simulating steep perspective)
        val srcPts = listOf(Point(0.0, 0.0), Point(100.0, 0.0), Point(100.0, 100.0), Point(0.0, 100.0))
        val dstPts = listOf(Point(200.0, 300.0), Point(800.0, 100.0), Point(900.0, 900.0), Point(100.0, 700.0))
        
        val innerDstPts = listOf(Point(220.0, 320.0), Point(780.0, 140.0), Point(860.0, 860.0), Point(140.0, 680.0))
        val polyDst = org.opencv.core.MatOfPoint(*dstPts.toTypedArray())
        val polyInnerDst = org.opencv.core.MatOfPoint(*innerDstPts.toTypedArray())
        Imgproc.fillPoly(mat, listOf(polyDst), Scalar(0.0))
        Imgproc.fillPoly(mat, listOf(polyInnerDst), Scalar(255.0))
        polyDst.release()
        polyInnerDst.release()

        val luma = ByteArray(width * height)
        mat.get(0, 0, luma)
        mat.release()

        val detector = V6StaticDetector()
        val result = detector.detect(luma, width, height, "all-black", null)
        
        assertTrue("Perspective-skewed border should be found despite low area/boxArea ratio", result.borderFound)
        assertTrue("Should have considered at least 1 quad", result.quadsConsidered > 0)
    }

    @Test
    fun testModestScaleChangesDetected() {
        Assume.assumeTrue("OpenCV native library is required", isOpenCvAvailable)
        setupContract()

        val width = 1000
        val height = 1000
        val luma = ByteArray(width * height) { 255.toByte() }

        // Draw small border
        for (y in 400..600) {
            for (x in 400..600) {
                if (x < 420 || x > 580 || y < 420 || y > 580) {
                    luma[y * width + x] = 0.toByte()
                }
            }
        }

        val detector = V6StaticDetector()
        val result = detector.detect(luma, width, height, "all-black", null)
        
        assertTrue("Small scaled border should be found", result.borderFound)
        assertTrue("Should have considered at least 1 quad", result.quadsConsidered > 0)
    }

    @Test
    fun testNonMarkerRectangleHandledButFailsOrientation() {
        Assume.assumeTrue("OpenCV native library is required", isOpenCvAvailable)
        setupContract()

        val width = 1000
        val height = 1000
        val luma = ByteArray(width * height) { 255.toByte() }

        // Draw a generic black rectangle that is NOT a SuperQR (no anchors)
        for (y in 100..900) {
            for (x in 100..900) {
                if (x < 150 || x > 850 || y < 150 || y > 850) {
                    luma[y * width + x] = 0.toByte()
                }
            }
        }

        val detector = V6StaticDetector()
        val result = detector.detect(luma, width, height, "all-black", null)
        
        assertTrue("The rectangle should be found as a border candidate", result.borderFound)
        assertTrue("Should have considered at least 1 quad", result.quadsConsidered > 0)
        assertFalse("But orientation must fail because there are no valid anchors", result.orientationResolved)
        assertEquals("Orientation invalid or ambiguous", result.failureReason)
    }
}
