package com.superqr.android.vision.v7.modem

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class V7ModemPhase2Test {
    @Test
    fun crossPlatformGoldenPacketAndPhysicalFrameMatchProtocol() {
        val words = V7DenseXor.coefficientWords(305419896L, 2, 74, 70)
        assertEquals(listOf("F54AC24042A844A0", "0000000000000023"), words.map { "%016X".format(it) })
        val digest = MessageDigest.getInstance("SHA-256")
        val sources = Array(4) { digest.digest("source-$it".toByteArray()) }
        val payload = V7DenseXor.encode(sources, 270544960L, 2, 8)
        assertEquals("C06C87352CC05F2CE7613F30776B874185E581ABF140A9E9C399F63961307071", payload.hex())
        val packet = V7ModemContract.buildPacket(270544960L, 2, 3, 8, 4, 113, payload)
        assertEquals("53514D3701000101102030400000000200000003000000080004002000000071C06C87352CC05F2CE7613F30776B874185E581ABF140A9E9C399F639613070711B31CCF5", packet.hex())
        val physical = V7InnerFec.encode(packet, 400, .15)
        assertEquals("1E307B4924511D1D6C2543668A1375D105BD1B0314C37B200937947C1045073D", digest.digest(physical).hex())
        val parsed = V7InnerFec.decodePacket(physical, .15)
        assertArrayEquals(payload, parsed.payload)
        assertFalse(parsed.systematic)
    }

    @Test
    fun shortenedRsRepairsErrorsErasuresAndInterleavedBurst() {
        for (parity in intArrayOf(8, 16, 32)) {
            val source = ByteArray(120) { ((it * 73 + parity * 11) and 0xFF).toByte() }
            val codeword = V7ReedSolomon.encode(source, parity)
            val erasures = intArrayOf(3, 17, 41, 79).filter { it < codeword.size }.take(minOf(4, parity)).toIntArray()
            for ((index, position) in erasures.withIndex()) codeword[position] = (codeword[position].toInt() xor (0x51 + index)).toByte()
            val errors = minOf(4, (parity - erasures.size) / 2)
            repeat(errors) { index -> val position = (101 + index * 7) % codeword.size; if (position !in erasures) codeword[position] = (codeword[position].toInt() xor (0x21 + index)).toByte() }
            assertArrayEquals(source, V7ReedSolomon.decode(codeword, parity, erasures))
        }
        val sourceSymbols = Array(8) { source -> ByteArray(V7InnerFec.symbolPayloadCapacity(400, .15)) { offset -> (source * 31 + offset * 17).toByte() } }
        val payload = V7DenseXor.encode(sourceSymbols, 0x1234ABCDL, 0, 9)
        val packet = V7ModemContract.buildPacket(0x1234ABCDL, 0, 1, 9, 8, 8L * payload.size, payload)
        val physical = V7InnerFec.encode(packet, 400, .15)
        repeat(12) { physical[133 + it] = (physical[133 + it].toInt() xor (it + 1)).toByte() }
        assertArrayEquals(payload, V7InnerFec.decodePacket(physical, .15).payload)
    }

    @Test
    fun fountainCompletesWithoutExactMissingSourceIdsAndIgnoresDuplicates() {
        for (sourceCount in intArrayOf(16, 64, 128, 256)) {
            val sources = Array(sourceCount) { index -> MessageDigest.getInstance("SHA-256").digest("$sourceCount:$index".toByteArray()) }
            val decoder = V7DenseXorDecoder(0xCAFEBABEL, 7, sourceCount, 32)
            var duplicateChecked = false
            for (symbolId in 0 until 3 * sourceCount + 64) {
                if (symbolId % 3 == 1) continue
                val payload = V7DenseXor.encode(sources, 0xCAFEBABEL, 7, symbolId.toLong())
                val innovative = decoder.add(symbolId.toLong(), payload)
                if (!duplicateChecked && innovative) {
                    assertFalse(decoder.add(symbolId.toLong(), payload))
                    duplicateChecked = true
                }
                if (decoder.complete) break
            }
            assertTrue("rank ${decoder.rank}/$sourceCount", decoder.complete)
            assertArrayEquals(sources.fold(ByteArray(0)) { acc, bytes -> acc + bytes }, decoder.decode(sourceCount * 32))
        }
    }

    @Test
    fun packageDecoderStreamsDeflateAndVerifiesSha256() {
        val packageBytes = "5337504B01010000000A000A00000000000006400000000000000025400CFC472F2F2EA3A86C5CB7F8C0331B6DE80C0FD2CD013F2C01A5DD0C94A2C88DEECD507068617365322E747874746578742F706C61696E0B2E2D482D0A0C5208C8482C4E5530E20A1EE58F86C7687A18CD0FA3E5C1687948447D0000".hexBytes()
        val output = ByteArrayOutputStream()
        val metadata = V7PackageStream.decodeTo(ByteArrayInputStream(packageBytes), output)
        assertEquals("phase2.txt", metadata.filename)
        assertEquals("text/plain", metadata.mimeType)
        assertEquals(V7PackageStream.COMPRESSION_DEFLATE_RAW, metadata.compressionId)
        assertArrayEquals("SuperQR Phase 2\n".repeat(100).toByteArray(), output.toByteArray())
    }

    @Test
    fun packageMetadataRejectsMalformedUtf8EvenWithValidMetadataCrc() {
        val packageBytes = "5337504B01010000000A000A00000000000006400000000000000025400CFC472F2F2EA3A86C5CB7F8C0331B6DE80C0FD2CD013F2C01A5DD0C94A2C88DEECD507068617365322E747874746578742F706C61696E0B2E2D482D0A0C5208C8482C4E5530E20A1EE58F86C7687A18CD0FA3E5C1687948447D0000".hexBytes()
        packageBytes[64] = 0xC3.toByte()
        packageBytes[65] = 0x28
        val crc = CRC32().apply {
            update(packageBytes, 0, 60)
            update(packageBytes, 64, 20)
        }.value.toInt()
        packageBytes[60] = (crc ushr 24).toByte()
        packageBytes[61] = (crc ushr 16).toByte()
        packageBytes[62] = (crc ushr 8).toByte()
        packageBytes[63] = crc.toByte()
        try {
            V7PackageStream.inspect(packageBytes.copyOfRange(0, 84))
            fail("malformed UTF-8 should be rejected")
        } catch (expected: V7ModemException) {
            assertTrue(expected.message!!.contains("UTF-8"))
        }
    }

    @Test
    fun generationReceiverEmitsCompletedBytesAndBoundsActiveState() {
        val count = 32
        val sources = Array(count) { index -> MessageDigest.getInstance("SHA-256").digest("generation:$index".toByteArray()) }
        val receiver = V7GenerationReceiver(maxActiveGenerations = 2)
        var completed: ByteArray? = null
        for (symbolId in 0 until 128) {
            if (symbolId < count && symbolId % 4 == 1) continue
            val payload = V7DenseXor.encode(sources, 0x10203040L, 0, symbolId.toLong())
            val packetBytes = V7ModemContract.buildPacket(0x10203040L, 0, 1, symbolId.toLong(), count, (count * 32).toLong(), payload)
            val result = receiver.offer(V7ModemContract.parsePacket(packetBytes))
            if (result.completedBytes != null) { completed = result.completedBytes; break }
        }
        assertArrayEquals(sources.fold(ByteArray(0)) { acc, bytes -> acc + bytes }, completed)
        assertEquals(0, receiver.activeGenerationCount)
        assertEquals(1, receiver.completedGenerationCount)
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
