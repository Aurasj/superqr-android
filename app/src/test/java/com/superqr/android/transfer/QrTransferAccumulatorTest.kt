package com.superqr.android.transfer

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.CRC32
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.superqr.android.ui.carouselFrameId

class QrTransferAccumulatorTest {
    @Test
    fun sanitizesUnsafeReceivedFilenames() {
        assertEquals("superqr_file", sanitizeReceivedFilename(""))
        assertEquals("superqr_file", sanitizeReceivedFilename("."))
        assertEquals("superqr_file", sanitizeReceivedFilename(".."))
        assertEquals("safe.txt", sanitizeReceivedFilename("../../safe.txt"))
        assertEquals("safe.txt", sanitizeReceivedFilename("..\\..\\safe.txt"))
        assertEquals("bad_name.txt", sanitizeReceivedFilename("bad\u0000name.txt"))
        assertEquals(180, sanitizeReceivedFilename("a".repeat(400)).length)
    }

    @Test
    fun androidSenderRoundTripsEveryProductionProfileThroughReceiver() {
        val source = Files.createTempFile("superqr-send", ".bin").toFile()
        val data = ByteArray(19_321) { ((it * 73 + 19) and 0xFF).toByte() }
        source.writeBytes(data)
        try {
            ProductionQrContract.profiles.forEach { profile ->
                PreparedQrSendSession.fromFile(source, "camera_clip.bin", "application/octet-stream", profile).use { sender ->
                    val dir = Files.createTempDirectory("superqr-receive").toFile()
                    val accumulator = QrTransferAccumulator(dir)
                    try {
                        for (frameId in sender.totalFrames - 1 downTo 0) accumulator.accept(sender.frameBytes(frameId))
                        val completion = accumulator.prepareCompletion()
                        val sha = accumulator.verifyFileCrc(completion)
                        assertEquals(sender.metadata.sha256Hex, sha)
                        assertEquals(profile.id, requireNotNull(ProductionQrContract.profileForFrame(sender.frameBytes(0))).id)
                    } finally {
                        accumulator.discardTemporaryFile()
                        dir.deleteRecursively()
                    }
                }
            }
        } finally {
            source.delete()
        }
    }

    @Test
    fun laterAndroidCarouselPassesRemainBijective() {
        val total = 256
        val first = (0 until total).map { carouselFrameId(it, 0, total, 32123) }
        val second = (0 until total).map { carouselFrameId(it, 1, total, 32123) }
        assertEquals((0 until total).toList(), first)
        assertEquals((0 until total).toList(), second.sorted())
        assertTrue(first != second)
    }

    @Test
    fun productionProfilesMatchCanonicalContract() {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream("v7_production_qr_contract.json"))
            .bufferedReader().use { it.readText() }
        val canonical = JSONObject(text).getJSONArray("profiles")
        assertEquals(ProductionQrContract.profiles.size, canonical.length())
        ProductionQrContract.profiles.forEachIndexed { index, profile ->
            val item = canonical.getJSONObject(index)
            assertEquals(profile.id, item.getInt("id"))
            assertEquals(profile.ecc, item.getString("qr_ecc"))
            assertEquals(profile.senderFps, item.getDouble("sender_fps"), 0.0)
            assertEquals(profile.frameBytes, item.getInt("frame_bytes"))
            assertEquals(profile.payloadBytes, item.getInt("payload_bytes"))
        }
    }

    @Test
    fun productionFrameMatchesCanonicalGoldenVector() {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream("v7_production_qr_vectors.json"))
            .bufferedReader().use { it.readText() }
        val vector = JSONObject(text).getJSONObject("frame_vector")
        val profile = requireNotNull(ProductionQrContract.profileForId(vector.getInt("profile_id")))
        val frame = buildFrame(
            profile,
            vector.getInt("session_id"),
            vector.getInt("frame_id"),
            vector.getInt("total_frames"),
            vector.getString("payload_hex").chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
        )
        val sha = MessageDigest.getInstance("SHA-256").digest(frame).joinToString("") { "%02X".format(it) }
        assertEquals(vector.getString("frame_sha256"), sha)
    }

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
