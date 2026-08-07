package com.superqr.android.vision.v6.transport

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class V6TransportTest {

    private fun readCanonicalVectors(): JSONObject {
        val url = javaClass.classLoader?.getResource("transport-vectors.json")
            ?: throw IllegalStateException("transport-vectors.json not found in resources")
        val content = File(url.toURI()).readText()
        return JSONObject(content)
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4)
                    + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    @Test
    fun testCrc16CcittFalse_fromVector() {
        val vectors = readCanonicalVectors()
        val crcRef = vectors.getJSONObject("crc16_ccitt_false_reference")
        val inputAscii = crcRef.getString("input_ascii")
        val expectedCrc = crcRef.getInt("expected_crc16_int")

        val actualCrc = V6Transport.crc16CcittFalse(inputAscii.toByteArray(Charsets.US_ASCII))
        assertEquals(expectedCrc, actualCrc)
    }

    @Test
    fun testPaletteIndexesToBytes_fromVector() {
        val vectors = readCanonicalVectors()
        val optVector = vectors.getJSONObject("optical_frame_vector")
        
        val expectedIndexesArray = optVector.getJSONArray("expected_400_palette_indexes")
        val indexes = IntArray(400)
        for (i in 0 until 400) {
            indexes[i] = expectedIndexesArray.getInt(i)
        }

        val expectedFrameHex = optVector.getString("frame_hex")
        val expectedFrameBytes = hexStringToByteArray(expectedFrameHex)

        val actualBytes = V6Transport.paletteIndexesToBytes(indexes)
        assertArrayEquals(expectedFrameBytes, actualBytes)
    }

    @Test
    fun testParseFrame_fromVector() {
        val vectors = readCanonicalVectors()
        val optVector = vectors.getJSONObject("optical_frame_vector")
        
        val expectedSessionId = optVector.getInt("session_id")
        val expectedFrameId = optVector.getInt("frame_id")
        val expectedTotalFrames = optVector.getInt("total_frames")
        val expectedPayloadHex = optVector.getString("payload_hex")
        val expectedPayloadBytes = hexStringToByteArray(expectedPayloadHex)
        val expectedCrcHex = optVector.getString("expected_crc16_hex")
        val expectedCrcInt = expectedCrcHex.toInt(16)
        
        val frameHex = optVector.getString("frame_hex")
        val frameBytes = hexStringToByteArray(frameHex)

        val frame = V6Transport.parseFrame(frameBytes)

        assertEquals(expectedSessionId, frame.sessionId)
        assertEquals(expectedFrameId, frame.frameId)
        assertEquals(expectedTotalFrames, frame.totalFrames)
        assertArrayEquals(expectedPayloadBytes, frame.payload)
        assertEquals(expectedCrcInt, frame.crc16)
    }

    @Test
    fun testCorruptedCrcRejected() {
        val vectors = readCanonicalVectors()
        val optVector = vectors.getJSONObject("optical_frame_vector")
        val frameHex = optVector.getString("frame_hex")
        val frameBytes = hexStringToByteArray(frameHex)

        // Corrupt the payload byte
        frameBytes[10] = (frameBytes[10].toInt() xor 0xFF).toByte()

        assertThrows(V6TransportError::class.java) {
            V6Transport.parseFrame(frameBytes)
        }
    }

    @Test
    fun testWrongMagicRejected() {
        val vectors = readCanonicalVectors()
        val optVector = vectors.getJSONObject("optical_frame_vector")
        val frameHex = optVector.getString("frame_hex")
        val frameBytes = hexStringToByteArray(frameHex)

        frameBytes[0] = 0x00.toByte()
        // Must update CRC so it doesn't fail CRC first
        val newCrc = V6Transport.crc16CcittFalse(frameBytes.copyOfRange(0, 98))
        frameBytes[98] = (newCrc ushr 8).toByte()
        frameBytes[99] = newCrc.toByte()

        assertThrows(V6TransportError::class.java) {
            V6Transport.parseFrame(frameBytes)
        }
    }

    @Test
    fun testWrongVersionRejected() {
        val vectors = readCanonicalVectors()
        val optVector = vectors.getJSONObject("optical_frame_vector")
        val frameHex = optVector.getString("frame_hex")
        val frameBytes = hexStringToByteArray(frameHex)

        frameBytes[1] = 0x07.toByte()
        val newCrc = V6Transport.crc16CcittFalse(frameBytes.copyOfRange(0, 98))
        frameBytes[98] = (newCrc ushr 8).toByte()
        frameBytes[99] = newCrc.toByte()

        assertThrows(V6TransportError::class.java) {
            V6Transport.parseFrame(frameBytes)
        }
    }

    @Test
    fun testSessionZeroRejected() {
        val vectors = readCanonicalVectors()
        val optVector = vectors.getJSONObject("optical_frame_vector")
        val frameHex = optVector.getString("frame_hex")
        val frameBytes = hexStringToByteArray(frameHex)

        frameBytes[2] = 0x00.toByte()
        val newCrc = V6Transport.crc16CcittFalse(frameBytes.copyOfRange(0, 98))
        frameBytes[98] = (newCrc ushr 8).toByte()
        frameBytes[99] = newCrc.toByte()

        assertThrows(V6TransportError::class.java) {
            V6Transport.parseFrame(frameBytes)
        }
    }

    @Test
    fun testFrameIdGreaterEqualTotalFramesRejected() {
        val vectors = readCanonicalVectors()
        val optVector = vectors.getJSONObject("optical_frame_vector")
        val frameHex = optVector.getString("frame_hex")
        val frameBytes = hexStringToByteArray(frameHex)

        // Set frame_id = total_frames = 2
        frameBytes[3] = 0x00.toByte()
        frameBytes[4] = 0x02.toByte()
        val newCrc = V6Transport.crc16CcittFalse(frameBytes.copyOfRange(0, 98))
        frameBytes[98] = (newCrc ushr 8).toByte()
        frameBytes[99] = newCrc.toByte()

        assertThrows(V6TransportError::class.java) {
            V6Transport.parseFrame(frameBytes)
        }
    }

    @Test
    fun testTotalFramesLessThanTwoRejected() {
        val vectors = readCanonicalVectors()
        val optVector = vectors.getJSONObject("optical_frame_vector")
        val frameHex = optVector.getString("frame_hex")
        val frameBytes = hexStringToByteArray(frameHex)

        // Set total_frames = 1
        frameBytes[5] = 0x00.toByte()
        frameBytes[6] = 0x01.toByte()
        // frame_id is 0, so frame_id < total_frames is satisfied, but total_frames < 2 should fail
        val newCrc = V6Transport.crc16CcittFalse(frameBytes.copyOfRange(0, 98))
        frameBytes[98] = (newCrc ushr 8).toByte()
        frameBytes[99] = newCrc.toByte()

        assertThrows(V6TransportError::class.java) {
            V6Transport.parseFrame(frameBytes)
        }
    }

    @Test
    fun testExact400CellRequirement() {
        assertThrows(IllegalArgumentException::class.java) {
            V6Transport.paletteIndexesToBytes(IntArray(399))
        }
    }
}
