package com.superqr.android.vision.v7_capacity_lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32

class V7Phase1QrEccTest {
    private val expectations = listOf(
        V7Phase1QrExpectation(profileId = 12, version = 40, eccId = 2, frameBytes = 2331),
        V7Phase1QrExpectation(profileId = 13, version = 40, eccId = 3, frameBytes = 1663),
    )

    @Test
    fun `same QR version resolves exact M and Q profile from run envelope`() {
        val medium = payload(profileId = 12, version = 40, eccId = 2, frameBytes = 2331)
        val quartile = payload(profileId = 13, version = 40, eccId = 3, frameBytes = 1663)

        val mediumResult = V7Phase1QrDecoder.validateProfilePayload(medium, expectations)
        val quartileResult = V7Phase1QrDecoder.validateProfilePayload(quartile, expectations)

        assertTrue(mediumResult.valid)
        assertEquals(12, mediumResult.envelope?.profileId)
        assertTrue(quartileResult.valid)
        assertEquals(13, quartileResult.envelope?.profileId)
    }

    @Test
    fun `profile id cannot silently accept a different ECC`() {
        val wrongEcc = payload(profileId = 12, version = 40, eccId = 3, frameBytes = 2331)

        val result = V7Phase1QrDecoder.validateProfilePayload(wrongEcc, expectations)

        assertFalse(result.valid)
        assertEquals("QR_PROFILE", result.failure)
    }

    @Test
    fun `unknown run profile is rejected before version-only matching`() {
        val unknown = payload(profileId = 99, version = 40, eccId = 2, frameBytes = 2331)

        val result = V7Phase1QrDecoder.validateProfilePayload(unknown, expectations)

        assertFalse(result.valid)
        assertEquals("QR_PROFILE", result.failure)
        assertEquals(99, result.envelope?.profileId)
    }

    private fun payload(profileId: Int, version: Int, eccId: Int, frameBytes: Int): ByteArray {
        val bytes = ByteArray(frameBytes)
        bytes[0] = 'S'.code.toByte()
        bytes[1] = 'Q'.code.toByte()
        bytes[2] = 'P'.code.toByte()
        bytes[3] = '1'.code.toByte()
        bytes[4] = version.toByte()
        bytes[5] = eccId.toByte()
        writeUInt32Le(bytes, 6, 17)
        writeUInt32Le(bytes, 10, 42)
        bytes[14] = (frameBytes and 0xFF).toByte()
        bytes[15] = ((frameBytes ushr 8) and 0xFF).toByte()
        val envelope = V7LabRunEnvelope(
            state = V7LabRunState.RUNNING,
            profileId = profileId,
            runToken = 0x1234,
            frameIndex = 17,
            frameCount = 256,
            dwellEpochs = 3,
        ).encode()
        envelope.copyInto(bytes, destinationOffset = 16)
        val crc = CRC32().apply { update(bytes, 0, bytes.size - 4) }.value
        writeUInt32Le(bytes, bytes.size - 4, crc)
        return bytes
    }

    private fun writeUInt32Le(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
