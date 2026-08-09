package com.superqr.android.vision.v7.modem

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V7ModemReceiverCoreTest {
    @Test
    fun physicalReceiveCoreRejectsCorruptFrameAndCompletesFountainGeneration() {
        val channelBytes = 400
        val symbolBytes = V7InnerFec.symbolPayloadCapacity(channelBytes, .15)
        val sourceCount = 4
        val payloadLen = sourceCount * symbolBytes - 17
        val source = Array(sourceCount) { sourceIndex ->
            ByteArray(symbolBytes) { offset -> (sourceIndex * 41 + offset * 13).toByte() }
        }
        val expected = source.fold(ByteArray(0)) { acc, bytes -> acc + bytes }.copyOf(payloadLen)
        val receiver = V7ModemReceiverCore(parityRatio = .15, maxActiveGenerations = 2)

        val corruptPayload = V7DenseXor.encode(source, 0x10203040L, 0L, 0L)
        val corruptPacket = V7ModemContract.buildPacket(0x10203040L, 0L, 1L, 0L, sourceCount, payloadLen.toLong(), corruptPayload)
        val corruptPhysical = V7InnerFec.encode(corruptPacket, channelBytes, .15)
        repeat(50) { corruptPhysical[it] = (corruptPhysical[it].toInt() xor 0x7F).toByte() }
        assertEquals(V7ModemReceiverCore.Status.INNER_REJECTED, receiver.offerPhysicalFrame(corruptPhysical).status)

        var completed: ByteArray? = null
        for (symbolId in 0 until 32) {
            if (symbolId == 1) continue
            val payload = V7DenseXor.encode(source, 0x10203040L, 0L, symbolId.toLong())
            val packet = V7ModemContract.buildPacket(0x10203040L, 0L, 1L, symbolId.toLong(), sourceCount, payloadLen.toLong(), payload)
            val result = receiver.offerPhysicalFrame(V7InnerFec.encode(packet, channelBytes, .15))
            if (result.completedGeneration != null) {
                completed = result.completedGeneration
                break
            }
        }
        assertArrayEquals(expected, completed)
        assertEquals(1, receiver.innerRejectedFrames)
        assertTrue(receiver.innovativeFrames >= sourceCount.toLong())
        assertEquals(1, receiver.completedGenerationCount)
    }

    @Test
    fun generationSpoolWritesOutOfOrderAtDeterministicOffsets() {
        val temp = File.createTempFile("superqr-v7-spool-", ".bin")
        try {
            val symbolBytes = 32
            val capacity = V7ModemContract.generationCapacity(symbolBytes).toInt()
            V7GenerationSpool(temp, totalGenerations = 2L, symbolBytes = symbolBytes).use { spool ->
                val final = ByteArray(111) { 0x6A }
                val first = ByteArray(capacity) { (it * 7).toByte() }
                assertTrue(spool.writeGeneration(1L, final))
                assertFalse(spool.isComplete())
                assertTrue(spool.writeGeneration(0L, first))
                assertTrue(spool.isComplete())
                assertEquals(capacity.toLong() + final.size, spool.packageLength())
                val assembled = temp.readBytes()
                assertArrayEquals(first, assembled.copyOfRange(0, capacity))
                assertArrayEquals(final, assembled.copyOfRange(capacity, assembled.size))
                assertFalse(spool.writeGeneration(0L, first))
            }
        } finally {
            temp.delete()
        }
    }

    @Test
    fun generationSpoolCanVerifyAndDecodeCompletedS7pk() {
        val packageBytes = "5337504B01010000000A000A00000000000006400000000000000025400CFC472F2F2EA3A86C5CB7F8C0331B6DE80C0FD2CD013F2C01A5DD0C94A2C88DEECD507068617365322E747874746578742F706C61696E0B2E2D482D0A0C5208C8482C4E5530E20A1EE58F86C7687A18CD0FA3E5C1687948447D0000".hexBytes()
        val temp = File.createTempFile("superqr-v7-package-", ".bin")
        try {
            V7GenerationSpool(temp, totalGenerations = 1L, symbolBytes = 304).use { spool ->
                assertTrue(spool.writeGeneration(0L, packageBytes))
                val output = ByteArrayOutputStream()
                val metadata = spool.verifyAndDecodeTo(output)
                assertEquals("phase2.txt", metadata.filename)
                assertArrayEquals("SuperQR Phase 2\n".repeat(100).toByteArray(), output.toByteArray())
            }
        } finally {
            temp.delete()
        }
    }

    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
