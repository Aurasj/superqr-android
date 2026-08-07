package com.superqr.android.vision.v6.replay

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class V6DiagnosticZipLoaderTest {

    private fun createSyntheticZip(
        metadataJson: String = createDefaultMetadataJson(),
        includeY: Boolean = true,
        includeU: Boolean = true,
        includeV: Boolean = true,
        ySize: Int = 100 * 50,
        uSize: Int = 50 * 25,
        vSize: Int = 50 * 25
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("frame_metadata.json"))
            zos.write(metadataJson.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            if (includeY) {
                zos.putNextEntry(ZipEntry("y_plane.bin"))
                zos.write(ByteArray(ySize) { 128.toByte() })
                zos.closeEntry()
            }
            if (includeU) {
                zos.putNextEntry(ZipEntry("u_plane.bin"))
                zos.write(ByteArray(uSize) { 128.toByte() })
                zos.closeEntry()
            }
            if (includeV) {
                zos.putNextEntry(ZipEntry("v_plane.bin"))
                zos.write(ByteArray(vSize) { 128.toByte() })
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun createDefaultMetadataJson(
        imageWidth: Int = 100,
        imageHeight: Int = 50,
        cropLeft: Int = 0,
        cropTop: Int = 0,
        cropRight: Int = 100,
        cropBottom: Int = 50,
        rotationDegrees: Int = 0,
        yRowStride: Int = 100,
        yPixelStride: Int = 1,
        uRowStride: Int = 50,
        uPixelStride: Int = 1,
        vRowStride: Int = 50,
        vPixelStride: Int = 1
    ): String {
        val json = JSONObject()
        json.put("imageWidth", imageWidth)
        json.put("imageHeight", imageHeight)

        val crop = JSONObject()
        crop.put("left", cropLeft)
        crop.put("top", cropTop)
        crop.put("right", cropRight)
        crop.put("bottom", cropBottom)
        json.put("cropRect", crop)

        json.put("rotationDegrees", rotationDegrees)
        json.put("yRowStride", yRowStride)
        json.put("yPixelStride", yPixelStride)
        json.put("uRowStride", uRowStride)
        json.put("uPixelStride", uPixelStride)
        json.put("vRowStride", vRowStride)
        json.put("vPixelStride", vPixelStride)
        return json.toString()
    }

    @Test
    fun testValidSyntheticZipLoading() {
        val zipBytes = createSyntheticZip()
        val frame = V6DiagnosticZipLoader.loadFromZipStream(ByteArrayInputStream(zipBytes))

        assertEquals(100, frame.imageWidth)
        assertEquals(50, frame.imageHeight)
        assertEquals(0, frame.cropLeft)
        assertEquals(0, frame.cropTop)
        assertEquals(100, frame.cropRight)
        assertEquals(50, frame.cropBottom)
        assertEquals(0, frame.rotationDegrees)
        assertEquals(100 * 50, frame.yPlaneBytes.size)
    }

    @Test
    fun testMissingYPlaneRejection() {
        val zipBytes = createSyntheticZip(includeY = false)
        try {
            V6DiagnosticZipLoader.loadFromZipStream(ByteArrayInputStream(zipBytes))
            fail("Expected V6ReplayLoadException for missing Y plane")
        } catch (e: V6ReplayLoadException) {
            assertTrue(e.message!!.contains("Missing y_plane.bin"))
        }
    }

    @Test
    fun testMissingUPlaneRejection() {
        val zipBytes = createSyntheticZip(includeU = false)
        try {
            V6DiagnosticZipLoader.loadFromZipStream(ByteArrayInputStream(zipBytes))
            fail("Expected V6ReplayLoadException for missing U plane")
        } catch (e: V6ReplayLoadException) {
            assertTrue(e.message!!.contains("Missing u_plane.bin"))
        }
    }

    @Test
    fun testMissingVPlaneRejection() {
        val zipBytes = createSyntheticZip(includeV = false)
        try {
            V6DiagnosticZipLoader.loadFromZipStream(ByteArrayInputStream(zipBytes))
            fail("Expected V6ReplayLoadException for missing V plane")
        } catch (e: V6ReplayLoadException) {
            assertTrue(e.message!!.contains("Missing v_plane.bin"))
        }
    }

    @Test
    fun testInvalidDimensionsRejection() {
        val meta = createDefaultMetadataJson(imageWidth = -1, imageHeight = 50)
        val zipBytes = createSyntheticZip(metadataJson = meta)
        try {
            V6DiagnosticZipLoader.loadFromZipStream(ByteArrayInputStream(zipBytes))
            fail("Expected V6ReplayLoadException for invalid dimensions")
        } catch (e: V6ReplayLoadException) {
            assertTrue(e.message!!.contains("Invalid dimensions"))
        }
    }

    @Test
    fun testInvalidCropRectRejection() {
        val meta = createDefaultMetadataJson(cropLeft = 80, cropRight = 50) // right < left
        val zipBytes = createSyntheticZip(metadataJson = meta)
        try {
            V6DiagnosticZipLoader.loadFromZipStream(ByteArrayInputStream(zipBytes))
            fail("Expected V6ReplayLoadException for invalid cropRect")
        } catch (e: V6ReplayLoadException) {
            assertTrue(e.message!!.contains("Invalid cropRect"))
        }
    }

    @Test
    fun testInvalidRotationRejection() {
        val meta = createDefaultMetadataJson(rotationDegrees = 45)
        val zipBytes = createSyntheticZip(metadataJson = meta)
        try {
            V6DiagnosticZipLoader.loadFromZipStream(ByteArrayInputStream(zipBytes))
            fail("Expected V6ReplayLoadException for invalid rotationDegrees")
        } catch (e: V6ReplayLoadException) {
            assertTrue(e.message!!.contains("Invalid rotationDegrees"))
        }
    }

    @Test
    fun testTruncatedYPlaneDataRejection() {
        val zipBytes = createSyntheticZip(ySize = 10) // Expected 100 * 50 = 5000
        try {
            V6DiagnosticZipLoader.loadFromZipStream(ByteArrayInputStream(zipBytes))
            fail("Expected V6ReplayLoadException for truncated Y plane")
        } catch (e: V6ReplayLoadException) {
            assertTrue(e.message!!.contains("y_plane.bin size"))
        }
    }
}
