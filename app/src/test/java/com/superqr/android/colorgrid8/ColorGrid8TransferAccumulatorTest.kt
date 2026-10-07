package com.superqr.android.colorgrid8

import com.superqr.android.vision.lab.colorgrid8.ColorGrid8Profile
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8TransferCodec as Codec
import com.superqr.android.transfer.ReceiverWorkQueue
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ColorGrid8TransferAccumulatorTest {
    private val profile = ColorGrid8Profile(240, 216, 30, version = 2)
    private val capacity = Codec.logicalChunkCapacity(profile)

    private fun packageBytes(data: ByteArray): ByteArray {
        val name = "received.bin".toByteArray()
        val mime = "application/octet-stream".toByteArray()
        return ByteBuffer.allocate(20 + name.size + mime.size + data.size)
            .put("SQP7".toByteArray()).putShort(name.size.toShort()).putShort(mime.size.toShort())
            .putLong(data.size.toLong()).putInt(CRC32().apply { update(data) }.value.toInt())
            .put(name).put(mime).put(data).array()
    }

    private fun frames(data: ByteArray, session: Int = 123): List<Codec.Frame> {
        val bytes = packageBytes(data)
        val total = (bytes.size + capacity - 1) / capacity
        val result = (0 until total).map { id ->
            val payload = bytes.copyOfRange(id * capacity, minOf(bytes.size, (id + 1) * capacity))
            Codec.Frame(Codec.KIND_DATA, profile.profileId, session, id, total, capacity, payload,
                BooleanArray((payload.size + 511) / 512) { true })
        }.toMutableList()
        for (start in 0 until total step Codec.XOR_GROUP_SIZE) {
            val parity = ByteArray(capacity)
            for (id in start until minOf(total, start + Codec.XOR_GROUP_SIZE)) {
                result[id].payload.forEachIndexed { i, b -> parity[i] = (parity[i].toInt() xor b.toInt()).toByte() }
            }
            result += Codec.Frame(Codec.KIND_XOR_PARITY, profile.profileId, session, start, total,
                capacity, parity, BooleanArray((capacity + 511) / 512) { true })
        }
        return result
    }

    private fun damaged(frame: Codec.Frame, vararg blocks: Int): Codec.Frame {
        val payload = frame.payload.copyOf()
        val valid = frame.validBlocks.copyOf()
        blocks.forEach { block ->
            if (block in valid.indices) {
                valid[block] = false
                payload[block * 512] = (payload[block * 512].toInt() xor 1).toByte()
            }
        }
        return frame.copy(payload = payload, validBlocks = valid)
    }

    private fun withReceiver(test: (ColorGrid8TransferAccumulator, File) -> Unit) {
        val dir = Files.createTempDirectory("grid8-receive-test").toFile()
        val receiver = ColorGrid8TransferAccumulator(dir)
        try { test(receiver, dir) } finally { receiver.reset(); dir.deleteRecursively() }
    }

    private fun assertFile(receiver: ColorGrid8TransferAccumulator, expected: ByteArray) {
        val completion = receiver.prepareCompletion()
        assertEquals(sha256(expected), receiver.verify(completion))
        RandomAccessFile(completion.tempFile, "r").use { source ->
            source.seek(completion.metadata.dataOffset)
            val restored = ByteArray(expected.size)
            source.readFully(restored)
            assertArrayEquals(expected, restored)
        }
        assertEquals(packageBytes(expected).size.toLong(), completion.tempFile.length())
    }

    @Test fun smallAndEmptyFilesVerifyIncludingParityOnlyRecovery() {
        for (data in listOf(ByteArray(0), "A small complete ColorGrid transfer".toByteArray())) {
            for (parityOnly in listOf(false, true)) withReceiver { receiver, _ ->
                val all = frames(data)
                val frame = if (parityOnly) all.last() else all.first()
                assertTrue(receiver.accept(profile, frame).complete)
                assertFile(receiver, data)
            }
        }
    }

    @Test fun shortParityOnlyTransferDoesNotNeedValidUnusedPaddingBlocks() = withReceiver { receiver, _ ->
        val data = ByteArray(1000) { (it * 13).toByte() }
        val parity = damaged(frames(data).last(), 10, 20, 30)
        val snapshot = receiver.accept(profile, parity)
        assertTrue("Unused parity tail must not block a small complete file", snapshot.complete)
        assertEquals(packageBytes(data).size.toLong(), snapshot.receivedBytes)
        assertFile(receiver, data)
    }

    @Test fun crcVerifiedMetadataDoesNotWaitForAllFileDataInFirstFrame() = withReceiver { receiver, _ ->
        val data = ByteArray(capacity * 2)
        val snapshot = receiver.accept(profile, damaged(frames(data)[0], 10))
        assertFalse(snapshot.complete)
        assertEquals("received.bin", snapshot.metadata?.filename)
        assertEquals(data.size.toLong(), snapshot.metadata?.fileSize)
    }

    @Test fun outOfOrderDuplicatesAndOneDroppedFramePerGroupVerify() = withReceiver { receiver, _ ->
        val data = ByteArray(capacity * 18 + 17) { (it * 71 + 5).toByte() }
        var last: Grid8AccumulatorSnapshot? = null
        for (frame in frames(data).reversed()) {
            if (frame.kind == Codec.KIND_DATA && frame.frameId % 8 == 0) continue
            receiver.accept(profile, frame)
            last = receiver.accept(profile, frame)
        }
        assertTrue(last!!.complete)
        assertEquals(3, last!!.recoveredFrames)
        assertTrue(last!!.duplicates > 0)
        assertFile(receiver, data)
    }

    @Test fun complementaryCarouselPassesMergeOnlyCrcValidBlocks() = withReceiver { receiver, _ ->
        val data = ByteArray(capacity + 3500) { (it * 29).toByte() }
        val all = frames(data).filter { it.kind == Codec.KIND_DATA }
        all.forEach { assertFalse(receiver.accept(profile, damaged(it, 0)).complete) }
        var last: Grid8AccumulatorSnapshot? = null
        all.reversed().forEach { last = receiver.accept(profile, damaged(it, 1)) }
        assertTrue(last!!.complete)
        assertFile(receiver, data)
    }

    @Test fun xorRecoversDifferentMissingBlocksInSeveralIncompleteFrames() = withReceiver { receiver, _ ->
        val data = ByteArray(capacity * 3 + 2100) { (it * 43).toByte() }
        var last: Grid8AccumulatorSnapshot? = null
        for (frame in frames(data)) {
            last = receiver.accept(profile, if (frame.kind == Codec.KIND_DATA) damaged(frame, frame.frameId) else frame)
        }
        assertTrue("CRC-valid parity can repair each column independently", last!!.complete)
        assertEquals(4, last!!.recoveredFrames)
        assertFile(receiver, data)
    }

    @Test fun validParityBlocksAreUsefulBeforeTheWholeParityFrameArrives() = withReceiver { receiver, _ ->
        val data = ByteArray(capacity * 2 + 1700) { (it * 17).toByte() }
        val all = frames(data)
        all.filter { it.kind == Codec.KIND_DATA }.forEach { receiver.accept(profile, damaged(it, it.frameId)) }
        val snapshot = receiver.accept(profile, damaged(all.last(), 10))
        assertTrue(snapshot.complete)
        assertEquals(0, snapshot.parityFrames) // One bad parity block is irrelevant to this recovery.
        assertFile(receiver, data)
    }

    @Test fun twoMissingBlocksInTheSameColumnWaitForAnotherPass() = withReceiver { receiver, _ ->
        val data = ByteArray(capacity * 2 + 1700) { (it * 11).toByte() }
        val all = frames(data)
        all.forEach { receiver.accept(profile, if (it.kind == Codec.KIND_DATA) damaged(it, 1) else it) }
        assertFalse(receiver.accept(profile, all.last()).complete)
        assertFalse(receiver.accept(profile, all[0]).complete)
        assertTrue(receiver.accept(profile, all[1]).complete)
        assertFile(receiver, data)
    }

    @Test fun lastGroupCanRecoverBeforeMetadataWithoutCountingPaddingAsFileBytes() = withReceiver { receiver, _ ->
        val data = ByteArray(capacity * 8 + 13) { (it * 59).toByte() }
        val all = frames(data)
        // A one-frame final group arrives as parity only, then the first group.
        val early = receiver.accept(profile, all.last())
        assertFalse(early.complete)
        assertEquals(0, early.receivedBytes)
        var last: Grid8AccumulatorSnapshot? = null
        all.filter { it.kind == Codec.KIND_DATA && it.frameId < 8 }.forEach {
            last = receiver.accept(profile, it)
        }
        assertTrue(last!!.complete)
        assertEquals(packageBytes(data).size.toLong(), last!!.receivedBytes)
        assertFile(receiver, data)
    }

    @Test fun malformedFinalLengthCannotBeSilentlyPaddedWithZeros() = withReceiver { receiver, _ ->
        val data = ByteArray(capacity + 2000)
        val all = frames(data)
        receiver.accept(profile, all[0])
        val last = all[1].copy(payload = all[1].payload.copyOf(all[1].payload.size - 1))
        val failure = runCatching { receiver.accept(profile, last) }.exceptionOrNull()
        assertEquals("final ColorGrid8 payload length does not match package", failure?.message)
        assertTrue(receiver.accept(profile, all[1]).complete)
        assertFile(receiver, data)
    }

    @Test fun closingDuringVerificationKeepsFileUntilVerificationFinishesThenCleansIt() = withReceiver { receiver, dir ->
        val data = "verification owns the file until it finishes".toByteArray()
        assertTrue(receiver.accept(profile, frames(data)[0]).complete)
        val queue = ReceiverWorkQueue()
        val verifying = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        queue.execute {
            try {
                val completion = receiver.prepareCompletion()
                verifying.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                assertTrue(completion.tempFile.exists())
                assertEquals(sha256(data), receiver.verify(completion))
            } catch (error: Throwable) { failure.set(error) }
        }
        try {
            assertTrue(verifying.await(5, TimeUnit.SECONDS))
            queue.closeAfterPendingWork { receiver.reset(); cleaned.countDown() }
            assertEquals(1, File(dir, "superqr-transfer").listFiles()!!.size)
            assertFalse(queue.execute { fail("closed queue accepted more work") })
        } finally {
            release.countDown()
            queue.closeAfterPendingWork { receiver.reset(); cleaned.countDown() }
            assertTrue(cleaned.await(5, TimeUnit.SECONDS))
        }
        assertNull(failure.get())
        assertTrue(File(dir, "superqr-transfer").listFiles()!!.isEmpty())
    }

    @Test fun resetCleansPartialFilesAndSessionReplacementIsIsolated() = withReceiver { receiver, dir ->
        val first = frames(ByteArray(capacity * 2))
        receiver.accept(profile, first[0])
        val oldFiles = File(dir, "superqr-transfer").listFiles()!!.toList()
        assertEquals(2, oldFiles.size)
        val data = "different session".toByteArray()
        assertTrue(receiver.accept(profile, frames(data, session = 456)[0]).complete)
        assertTrue(oldFiles.none { it.exists() })
        assertFile(receiver, data)
        receiver.reset()
        assertTrue(File(dir, "superqr-transfer").listFiles()!!.isEmpty())
    }

    @Test fun damagedFileIsNeverAcceptedJustBecauseAllFramesArrived() = withReceiver { receiver, _ ->
        val frame = frames("check file CRC too".toByteArray()).first()
        frame.payload[frame.payload.lastIndex] = (frame.payload.last().toInt() xor 1).toByte()
        assertTrue(receiver.accept(profile, frame).complete)
        val completion = receiver.prepareCompletion()
        val failure = runCatching { receiver.verify(completion) }.exceptionOrNull()
        assertEquals("file CRC32 mismatch", failure?.message)
    }

    /** Optional cross-repository gate: Desktop creates these using its real sender and native decoder. */
    @Test fun desktopSenderFixturesVerifyThroughAndroidReceiver() {
        val path = System.getenv("SUPERQR_COLORGRID_FIXTURE_DIR")
        assumeTrue("Run Desktop test_colorgrid8_android_transfer.py to generate fixtures", path != null)
        val dir = File(requireNotNull(path))
        val cases = JSONObject(File(dir, "manifest.json").readText()).getJSONArray("cases")
        for (index in 0 until cases.length()) withReceiver { receiver, _ ->
            val case = cases.getJSONObject(index)
            val fixtureProfile = ColorGrid8Profile(case.getInt("cols"), case.getInt("rows"), 30, version = 2)
            val frameFiles = case.getJSONArray("frames")
            var last: Grid8AccumulatorSnapshot? = null
            for (i in 0 until frameFiles.length()) {
                val symbols = File(dir, frameFiles.getString(i)).readBytes()
                val frame = requireNotNull(Codec.parse(fixtureProfile, symbols)) { "invalid Desktop frame" }
                last = receiver.accept(fixtureProfile, frame)
            }
            assertTrue(case.getString("name"), last!!.complete)
            val completion = receiver.prepareCompletion()
            assertEquals(case.getString("sha256"), receiver.verify(completion))
            assertEquals(case.getLong("size"), completion.metadata.fileSize)
            assertEquals(case.getString("filename"), completion.metadata.filename)
            val expected = File(dir, case.getString("source")).readBytes()
            val actual = ByteArray(expected.size)
            RandomAccessFile(completion.tempFile, "r").use {
                it.seek(completion.metadata.dataOffset)
                it.readFully(actual)
            }
            assertArrayEquals(expected, actual)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
