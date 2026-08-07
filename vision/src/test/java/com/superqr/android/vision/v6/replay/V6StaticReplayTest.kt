package com.superqr.android.vision.v6.replay

import com.superqr.android.vision.v6.transport.V6Transport
import org.junit.Assert.*
import org.junit.Test

class V6StaticReplayTest {

    @Test
    fun testStaticPatternGridGeneratorBlack() {
        val expected = V6ExpectedGridGenerator.generateForStaticPattern("black")
        assertEquals(400, expected.size)
        assertTrue(expected.all { it == 0 })
    }

    @Test
    fun testStaticPatternGridGeneratorWhite() {
        val expected = V6ExpectedGridGenerator.generateForStaticPattern("white")
        assertEquals(400, expected.size)
        assertTrue(expected.all { it == 1 })
    }

    @Test
    fun testStaticPatternGridGeneratorCheckerboard() {
        val expected = V6ExpectedGridGenerator.generateForStaticPattern("checkerboard")
        assertEquals(400, expected.size)
        assertEquals(0, expected[0 * 20 + 0])
        assertEquals(1, expected[0 * 20 + 1])
        assertEquals(2, expected[0 * 20 + 2])
        assertEquals(3, expected[0 * 20 + 3])
        assertEquals(1, expected[1 * 20 + 0])
    }

    @Test
    fun testHexToBytesAndPaletteIndexesRoundtrip() {
        // Construct a synthetic 100-byte frame
        val originalIndexes = IntArray(400) { it % 4 }
        val packedBytes = V6Transport.paletteIndexesToBytes(originalIndexes)

        val hexStr = packedBytes.joinToString("") { "%02X".format(it) }
        assertEquals(200, hexStr.length)

        val parsedBytes = V6ExpectedGridGenerator.parseHexToBytes(hexStr)
        assertArrayEquals(packedBytes, parsedBytes)

        val unpackedIndexes = V6ExpectedGridGenerator.bytesToPaletteIndexes(parsedBytes)
        assertArrayEquals(originalIndexes, unpackedIndexes)
    }

    @Test
    fun testReplayOnSyntheticFixture() {
        // Create synthetic 100x100 frame
        val frame = V6RawYuvFrame(
            imageWidth = 100,
            imageHeight = 100,
            cropLeft = 0,
            cropTop = 0,
            cropRight = 100,
            cropBottom = 100,
            rotationDegrees = 0,
            yRowStride = 100,
            yPixelStride = 1,
            uRowStride = 50,
            uPixelStride = 1,
            vRowStride = 50,
            vPixelStride = 1,
            yPlaneBytes = ByteArray(10000) { 128.toByte() },
            uPlaneBytes = ByteArray(2500) { 128.toByte() },
            vPlaneBytes = ByteArray(2500) { 128.toByte() },
            patternName = "black"
        )

        val result = V6ReplayEngine.replayFrame(frame, label = "synthetic_black", zipFileName = "synthetic.zip")

        assertEquals("synthetic_black", result.label)
        assertEquals("synthetic.zip", result.zipFileName)
        assertFalse(result.borderFound) // Synthetic blank image won't find outer border
        assertFalse(result.orientationResolved)
        assertFalse(result.transportValid)
        assertFalse(result.packetSuccess)
        assertTrue(result.processingTimeMs >= 0)
    }
}
