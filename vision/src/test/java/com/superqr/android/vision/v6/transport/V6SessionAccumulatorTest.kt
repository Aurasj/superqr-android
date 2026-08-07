package com.superqr.android.vision.v6.transport

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlin.text.Charsets

class V6SessionAccumulatorTest {

    private lateinit var accumulator: V6SessionAccumulator

    @Before
    fun setup() {
        accumulator = V6SessionAccumulator()
    }

    private fun buildPackageData(filename: String, fileData: ByteArray): ByteArray {
        val fnBytes = filename.toByteArray(Charsets.UTF_8)
        val crc32 = CRC32()
        crc32.update(fileData)
        val crcValue = (crc32.value and 0xFFFFFFFFL).toInt()

        val buf = ByteBuffer.allocate(9 + fnBytes.size + fileData.size)
        buf.put(fnBytes.size.toByte())
        buf.putInt(fileData.size)
        buf.putInt(crcValue)
        buf.put(fnBytes)
        buf.put(fileData)
        return buf.array()
    }

    private fun buildFrame0(sessionId: Int, totalFrames: Int, pkgLength: Int): V6TransportFrame {
        val payload = ByteArray(V6Transport.PAYLOAD_SIZE)
        val buf = ByteBuffer.wrap(payload)
        buf.putInt(pkgLength)
        return V6TransportFrame(sessionId, 0, totalFrames, payload, 0x1234)
    }

    private fun buildDataFrame(sessionId: Int, frameId: Int, totalFrames: Int, chunk: ByteArray): V6TransportFrame {
        val payload = ByteArray(V6Transport.PAYLOAD_SIZE)
        System.arraycopy(chunk, 0, payload, 0, Math.min(chunk.size, payload.size))
        return V6TransportFrame(sessionId, frameId, totalFrames, payload, 0x5678)
    }

    private fun splitPackageToFrames(sessionId: Int, pkg: ByteArray): List<V6TransportFrame> {
        val dataFramesCount = (pkg.size + V6Transport.PAYLOAD_SIZE - 1) / V6Transport.PAYLOAD_SIZE
        val totalFrames = dataFramesCount + 1
        val frames = mutableListOf<V6TransportFrame>()

        frames.add(buildFrame0(sessionId, totalFrames, pkg.size))

        for (i in 0 until dataFramesCount) {
            val start = i * V6Transport.PAYLOAD_SIZE
            val end = Math.min(start + V6Transport.PAYLOAD_SIZE, pkg.size)
            val chunk = pkg.copyOfRange(start, end)
            frames.add(buildDataFrame(sessionId, i + 1, totalFrames, chunk))
        }

        return frames
    }

    @Test
    fun `test 1 frame 0 then frame 1 completes hello_txt`() {
        val pkg = buildPackageData("hello.txt", "Hello World!\n".toByteArray())
        val frames = splitPackageToFrames(3, pkg)
        assertEquals(2, frames.size)

        var result = accumulator.addFrame(frames[0])
        assertNull(result)
        assertEquals(1, accumulator.getUniqueFrames())
        assertEquals(1, accumulator.getMissingFramesCount())

        result = accumulator.addFrame(frames[1])

        assertNotNull(result)
        assertEquals("hello.txt", result!!.filename)
        assertEquals(13, result.fileSize)
        assertArrayEquals("Hello World!\n".toByteArray(), result.fileData)
    }

    @Test
    fun `test 2 frame 1 then frame 0 completes`() {
        val pkg = buildPackageData("hello.txt", "Hello World!\n".toByteArray())
        val frames = splitPackageToFrames(3, pkg)

        var result = accumulator.addFrame(frames[1])
        assertNull(result)
        assertEquals(1, accumulator.getUniqueFrames())

        result = accumulator.addFrame(frames[0])

        assertNotNull(result)
        assertEquals("hello.txt", result!!.filename)
    }

    @Test
    fun `test 3 duplicate frame no progress increase`() {
        val pkg = buildPackageData("test.txt", "abc".toByteArray())
        val frames = splitPackageToFrames(1, pkg)

        accumulator.addFrame(frames[0])
        assertEquals(1, accumulator.getFramesCollected())
        assertEquals(1, accumulator.getUniqueFrames())
        assertEquals(0, accumulator.getDuplicateCount())

        val result1 = accumulator.addFrame(frames[0])
        assertNull(result1)
        assertEquals(1, accumulator.getFramesCollected())
        assertEquals(1, accumulator.getUniqueFrames())
        assertEquals(1, accumulator.getDuplicateCount())

        val result2 = accumulator.addFrame(frames[1])
        assertNotNull(result2)
    }

    @Test
    fun `test 5 conflicting same-ID valid frame rejected`() {
        val pkg = buildPackageData("test.txt", "abc".toByteArray())
        val frames = splitPackageToFrames(1, pkg)

        accumulator.addFrame(frames[0])
        assertEquals(0, accumulator.getConflictCount())
        assertEquals(1, accumulator.getUniqueFrames())

        val conflictingPayload = ByteArray(V6Transport.PAYLOAD_SIZE) { 0xFF.toByte() }
        val conflictFrame = V6TransportFrame(1, 0, 2, conflictingPayload, 0x1111)

        val result = accumulator.addFrame(conflictFrame)
        assertNull(result)
        assertEquals(1, accumulator.getConflictCount())
        assertEquals(1, accumulator.getUniqueFrames())
        assertArrayEquals(frames[0].payload, accumulator.getFramePayload(0))

        val finalResult = accumulator.addFrame(frames[1])
        assertNotNull(finalResult)
    }

    @Test
    fun `test 6 incomplete session reports missing frames`() {
        val pkg = ByteArray(200) // Needs 3 data frames + 1 header = 4 total frames
        val frames = splitPackageToFrames(1, pkg)
        assertEquals(4, frames.size)

        accumulator.addFrame(frames[0])
        accumulator.addFrame(frames[2])

        val missing = accumulator.getMissingFrames()
        assertEquals(listOf(1, 3), missing.sorted())
        assertEquals(2, accumulator.getMissingFramesCount())
    }

    @Test
    fun `test 7 inconsistent totalFrames rejected`() {
        val frameA = buildFrame0(1, 2, 30)
        val frameB = buildDataFrame(1, 1, 3, ByteArray(V6Transport.PAYLOAD_SIZE))

        accumulator.addFrame(frameA)
        accumulator.addFrame(frameB) // Should be rejected/ignored due to totalFrames=3 != 2

        assertEquals(1, accumulator.getFramesCollected())
        assertEquals(listOf(1), accumulator.getMissingFrames())
    }

    @Test
    fun `test 8 package trim removes final zero padding`() {
        val pkg = buildPackageData("a", byteArrayOf(1))
        val frames = splitPackageToFrames(5, pkg)

        accumulator.addFrame(frames[0])
        val result = accumulator.addFrame(frames[1])

        assertNotNull(result)
        assertEquals("a", result!!.filename)
        assertEquals(1, result.fileSize)
    }

    @Test
    fun `test 9 exact filename size file bytes recovered`() {
        val data = ByteArray(500) { it.toByte() }
        val pkg = buildPackageData("large_file.bin", data)
        val frames = splitPackageToFrames(7, pkg)

        var res: V6TransferPackage? = null
        for (f in frames) {
            res = accumulator.addFrame(f)
        }

        assertNotNull(res)
        assertEquals("large_file.bin", res!!.filename)
        assertEquals(500, res.fileSize)
        assertArrayEquals(data, res.fileData)
    }

    @Test
    fun `test 10 final CRC32 mismatch rejected`() {
        val pkg = buildPackageData("crc.txt", "data".toByteArray())
        pkg[5] = (pkg[5].toInt() xor 0xFF).toByte()

        val frames = splitPackageToFrames(2, pkg)
        accumulator.addFrame(frames[0])

        assertThrows(V6TransportError::class.java) {
            accumulator.addFrame(frames[1])
        }
    }

    @Test
    fun `test 11 malformed truncated package rejected`() {
        val pkg = buildPackageData("file", "data".toByteArray())
        val frames = splitPackageToFrames(2, pkg)

        val badFrame0 = buildFrame0(2, 2, 2000)

        accumulator.addFrame(badFrame0)
        assertThrows(V6TransportError::class.java) {
            accumulator.addFrame(frames[1])
        }
    }

    @Test
    fun `test 12 completion emitted only once`() {
        val pkg = buildPackageData("hello.txt", "Hello World!\n".toByteArray())
        val frames = splitPackageToFrames(3, pkg)

        accumulator.addFrame(frames[0])
        val res1 = accumulator.addFrame(frames[1])
        assertNotNull(res1)

        val res2 = accumulator.addFrame(frames[1])
        assertNull(res2)
        assertEquals(1, accumulator.getFramesCollected())
    }

    @Test
    fun `test 13 new session can start after completed session`() {
        val pkg1 = buildPackageData("first.txt", "1".toByteArray())
        val frames1 = splitPackageToFrames(10, pkg1)

        accumulator.addFrame(frames1[0])
        val res1 = accumulator.addFrame(frames1[1])
        assertEquals("first.txt", res1!!.filename)

        val pkg2 = buildPackageData("second.txt", "2".toByteArray())
        val frames2 = splitPackageToFrames(11, pkg2)

        accumulator.addFrame(frames2[0])
        val res2 = accumulator.addFrame(frames2[1])
        assertEquals("second.txt", res2!!.filename)
    }

    @Test
    fun `test 14 foreign session during incomplete session does not replace or mix active session`() {
        val pkg1 = buildPackageData("active.txt", "active".toByteArray())
        val frames1 = splitPackageToFrames(10, pkg1) // sessionId = 10

        val pkg2 = buildPackageData("foreign.txt", "foreign".toByteArray())
        val frames2 = splitPackageToFrames(99, pkg2) // sessionId = 99

        accumulator.addFrame(frames1[0]) // Starts session 10
        assertEquals(10, accumulator.getCurrentSessionId())
        assertEquals(1, accumulator.getUniqueFrames())

        // Foreign session frame arrives
        val foreignResult = accumulator.addFrame(frames2[0])
        assertNull(foreignResult)
        assertEquals(10, accumulator.getCurrentSessionId()) // Active session remains 10
        assertEquals(1, accumulator.getUniqueFrames()) // Active session progress preserved

        // Active session can complete normally
        val finalResult = accumulator.addFrame(frames1[1])
        assertNotNull(finalResult)
        assertEquals("active.txt", finalResult!!.filename)
    }
}
