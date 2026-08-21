package com.superqr.android.vision.v7.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.CRC32

class V7TransportTest {
    @Test
    fun goldenFrameMatchesProtocolContract() {
        val frame = V7Transport.buildFrame(1, 0, 1, "abc".toByteArray())
        assertEquals(400, frame.size)
        val crc = ByteBuffer.wrap(frame, 396, 4).int.toLong() and 0xFFFFFFFFL
        assertEquals(0x9BAFCBABL, crc)
        val sha = MessageDigest.getInstance("SHA-256").digest(frame).joinToString("") { "%02x".format(it) }
        assertEquals("8048032cd8f495c68903fbe05c9ae032e0965a7d7faab3dac279ff2f112d4025", sha)
    }

    @Test
    fun symbolRoundTripIsExact() {
        val frame = V7Transport.buildFrame(77, 3, 9, ByteArray(380) { (it * 17).toByte() })
        val symbols = V7Transport.bytesToSymbols(frame)
        assertEquals(1600, symbols.size)
        assertArrayEquals(frame, V7Transport.symbolsToBytes(symbols))
    }

    @Test
    fun erasureRejectsSymbolPacking() {
        val symbols = ByteArray(1600)
        symbols[123] = -1
        assertNull(V7Transport.symbolsToBytes(symbols))
    }

    @Test
    fun frameParserRejectsCorruption() {
        val frame = V7Transport.buildFrame(5, 0, 1, byteArrayOf(1, 2, 3)).copyOf()
        frame[120] = (frame[120].toInt() xor 1).toByte()
        var failed = false
        try {
            V7Transport.parseFrame(frame)
        } catch (_: V7TransportError) {
            failed = true
        }
        assertEquals(true, failed)
    }

    @Test
    fun qrParserRejectsCrcValidPayloadLengthPastFrameBoundary() {
        val frame = V7Transport.buildFrame(5, 0, 1, byteArrayOf(1, 2, 3)).copyOf()
        // Declare a payload far larger than the bytes available before the CRC.
        // Recompute CRC so this specifically exercises structural length checking.
        frame[14] = 0x7F
        frame[15] = 0xFF.toByte()
        val crcOffset = frame.size - V7Transport.CRC_SIZE
        val crc = CRC32().apply { update(frame, 0, crcOffset) }.value
        ByteBuffer.wrap(frame, crcOffset, V7Transport.CRC_SIZE).putInt(crc.toInt())

        var failed = false
        try {
            V7Transport.parseQrFrame(frame)
        } catch (_: V7TransportError) {
            failed = true
        }
        assertEquals(true, failed)
    }

    @Test
    fun accumulatorCompletesOutOfOrderPackage() {
        val filename = "hello.txt".toByteArray()
        val mime = "text/plain".toByteArray()
        val data = "SuperQR V7 works".toByteArray()
        val crc = CRC32().apply { update(data) }.value
        val packageBytes = ByteBuffer.allocate(20 + filename.size + mime.size + data.size)
            .put(byteArrayOf('S'.code.toByte(), 'Q'.code.toByte(), 'P'.code.toByte(), '7'.code.toByte()))
            .putShort(filename.size.toShort())
            .putShort(mime.size.toShort())
            .putLong(data.size.toLong())
            .putInt(crc.toInt())
            .put(filename)
            .put(mime)
            .put(data)
            .array()

        val chunks = packageBytes.toList().chunked(V7Transport.PAYLOAD_SIZE).map { it.toByteArray() }
        val acc = V7SessionAccumulator()
        var complete: V7TransferPackage? = null
        for (idx in chunks.indices.reversed()) {
            complete = acc.addFrame(V7TransportFrame(9, idx, chunks.size, chunks[idx])) ?: complete
        }
        assertNotNull(complete)
        assertEquals("hello.txt", complete!!.filename)
        assertEquals("text/plain", complete.mimeType)
        assertArrayEquals(data, complete.fileData)
    }
}
