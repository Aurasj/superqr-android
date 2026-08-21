package com.superqr.android.vision.v7.transport

import java.nio.ByteBuffer
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class V7TransportTest {
    @Test
    fun rejectsOversizedDeclaredPayloadWithValidRecomputedCrc() {
        val frame = validFrame(byteArrayOf(1, 2, 3))
        ByteBuffer.wrap(frame).putShort(14, (frame.size - V7Transport.HEADER_SIZE - V7Transport.CRC_SIZE + 1).toShort())
        writeCrc(frame)

        try {
            V7Transport.parseQrFrame(frame)
            fail("malformed payload length was accepted")
        } catch (error: V7TransportError) {
            assertEquals("invalid payload length", error.message)
        }
    }

    private fun validFrame(payload: ByteArray): ByteArray {
        val frame = ByteArray(48)
        ByteBuffer.wrap(frame).apply {
            put('S'.code.toByte())
            put('Q'.code.toByte())
            put(V7Transport.VERSION.toByte())
            put(0)
            putShort(1234)
            putInt(0)
            putInt(1)
            putShort(payload.size.toShort())
            put(payload)
        }
        writeCrc(frame)
        return frame
    }

    private fun writeCrc(frame: ByteArray) {
        val crc = CRC32().apply { update(frame, 0, frame.size - V7Transport.CRC_SIZE) }.value
        ByteBuffer.wrap(frame).putInt(frame.size - V7Transport.CRC_SIZE, crc.toInt())
    }
}
