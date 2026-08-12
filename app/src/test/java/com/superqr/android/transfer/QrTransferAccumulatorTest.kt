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
    fun acceptsOutOfOrderV40LFramesAndReconstructsArbitraryFileBytes() {
        roundTripProfile(requireNotNull(ProductionQrContract.profileForId(0)))
    }

    @Test
    fun acceptsOutOfOrderV40MFramesAndReconstructsArbitraryFileBytes() {
        roundTripProfile(requireNotNull(ProductionQrContract.profileForId(1)))
    }

    @Test
    fun recognizesAllSixProductionProfiles() {
        val expected = mapOf(
            0 to Triple("L", 15.0, 2953),
            1 to Triple("M", 15.0, 2331),
            2 to Triple("L", 20.0, 2953),
            3 to Triple("M", 20.0, 2331),
            4 to Triple("L", 30.0, 2953),
            5 to Triple("M", 30.0, 2331),
        )
        expected.forEach { (id, values) ->
            val profile = requireNotNull(ProductionQrContract.profileForId(id))
            assertEquals(values.first, profile.ecc)
            assertEquals(values.second, profile.senderFps, 0.0)
            assertEquals(values.third, profile.frameBytes)
            val raw = buildFrame(profile, 555, 0, 1, byteArrayOf(1, 2, 3))
            assertEquals(profile, ProductionQrContract.profileForFrame(raw))
        }
    }

    private fun roundTripProfile(profile: ProductionQrProfile) {
        val data = ByteArray(11_000) { ((it * 73 + 19) and 0xFF).toByte() }
        val packageBytes = buildPackage("clip.mp4", "video/mp4", data)
        val frames = buildFrames(profile, 32123, packageBytes)
        val dir = Files.createTempDirectory("superqr-transfer-test").toFile()
        val accumulator = QrTransferAccumulator(dir)

        var last: TransferAccumulatorSnapshot? = null
        for (frame in frames.indices.reversed()) last = accumulator.accept(frames[frame])
        assertTrue(last!!.complete)
        assertEquals(frames.size, last!!.uniqueFrames)
        assertEquals(profile.id, last!!.profileId)
        assertEquals(profile.label, last!!.profileLabel)

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

    private fun buildFrames(profile: ProductionQrProfile, sessionId: Int, packageBytes: ByteArray): List<ByteArray> {
        val total = (packageBytes.size + profile.payloadBytes - 1) / profile.payloadBytes
        return (0 until total).map { index ->
            val start = index * profile.payloadBytes
            val end = minOf(packageBytes.size, start + profile.payloadBytes)
            buildFrame(profile, sessionId, index, total, packageBytes.copyOfRange(start, end))
        }
    }

    private fun buildFrame(
        profile: ProductionQrProfile,
        sessionId: Int,
        frameId: Int,
        totalFrames: Int,
        payload: ByteArray,
    ): ByteArray {
        val out = ByteArray(profile.frameBytes)
        val buf = ByteBuffer.wrap(out)
        buf.put('S'.code.toByte())
        buf.put('Q'.code.toByte())
        buf.put(ProductionQrContract.TRANSPORT_VERSION.toByte())
        buf.put(profile.id.toByte())
        buf.putShort(sessionId.toShort())
        buf.putInt(frameId)
        buf.putInt(totalFrames)
        buf.putShort(payload.size.toShort())
        buf.put(payload)
        buf.position(profile.frameBytes - 4)
        val crc = CRC32().apply { update(out, 0, out.size - 4) }.value
        buf.putInt(crc.toInt())
        return out
    }
}
