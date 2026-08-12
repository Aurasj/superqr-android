package com.superqr.android.transfer

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrTransferAccumulatorTest {
    @Test
    fun acceptsOutOfOrderFramesAndReconstructsArbitraryFileBytes() {
        val data = ByteArray(11_000) { ((it * 73 + 19) and 0xFF).toByte() }
        val packageBytes = buildPackage("clip.mp4", "video/mp4", data)
        val frames = buildFrames(32123, packageBytes)
        val dir = Files.createTempDirectory("superqr-transfer-test").toFile()
        val accumulator = QrTransferAccumulator(dir)

        var last: TransferAccumulatorSnapshot? = null
        for (frame in frames.indices.reversed()) last = accumulator.accept(frames[frame])
        assertTrue(last!!.complete)
        assertEquals(frames.size, last!!.uniqueFrames)

        val duplicate = accumulator.accept(frames[0])
        assertFalse(duplicate.accepted)
        assertEquals(1, duplicate.duplicateFrames)

        val completion = accumulator.prepareCompletion()
        accumulator.verifyFileCrc(completion)
        val restored = ByteArray(data.size)
        RandomAccessFile(completion.tempFile, "r").use {
            it.seek(completion.metadata.dataOffset)
            it.readFully(restored)
        }
        assertEquals("clip.mp4", completion.metadata.filename)
        assertEquals("video/mp4", completion.metadata.mimeType)
        assertArrayEquals(data, restored)
        accumulator.discardTemporaryFile()
        dir.deleteRecursively()
    }

    private fun buildPackage(name: String, mime: String, data: ByteArray): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val mimeBytes = mime.toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(data) }.value
        return ByteBuffer.allocate(20 + nameBytes.size + mimeBytes.size + data.size).apply {
            put(byteArrayOf('S'.code.toByte(), 'Q'.code.toByte(), 'P'.code.toByte(), '7'.code.toByte()))
            putShort(nameBytes.size.toShort())
            putShort(mimeBytes.size.toShort())
            putLong(data.size.toLong())
            putInt(crc.toInt())
            put(nameBytes)
            put(mimeBytes)
            put(data)
        }.array()
    }

    private fun buildFrames(sessionId: Int, packageBytes: ByteArray): List<ByteArray> {
        val total = (packageBytes.size + ProductionQrContract.PAYLOAD_BYTES - 1) / ProductionQrContract.PAYLOAD_BYTES
        return (0 until total).map { index ->
            val start = index * ProductionQrContract.PAYLOAD_BYTES
            val end = minOf(packageBytes.size, start + ProductionQrContract.PAYLOAD_BYTES)
            buildFrame(sessionId, index, total, packageBytes.copyOfRange(start, end))
        }
    }

    private fun buildFrame(sessionId: Int, frameId: Int, totalFrames: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(ProductionQrContract.FRAME_BYTES)
        val buf = ByteBuffer.wrap(out)
        buf.put('S'.code.toByte())
        buf.put('Q'.code.toByte())
        buf.put(ProductionQrContract.TRANSPORT_VERSION.toByte())
        buf.put(ProductionQrContract.PROFILE_ID.toByte())
        buf.putShort(sessionId.toShort())
        buf.putInt(frameId)
        buf.putInt(totalFrames)
        buf.putShort(payload.size.toShort())
        buf.put(payload)
        buf.position(ProductionQrContract.FRAME_BYTES - 4)
        val crc = CRC32().apply { update(out, 0, out.size - 4) }.value
        buf.putInt(crc.toInt())
        return out
    }
}
