package com.superqr.android.vision.v7.modem

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V7GenerationWindowTest {
    @Test
    fun activeWindowPressureDropsLaterGenerationWithoutFailingSession() {
        val receiver = V7GenerationReceiver(maxActiveGenerations = 2)
        fun packet(generation: Int, symbol: Int): V7ModemPacket {
            val payload = MessageDigest.getInstance("SHA-256").digest("$generation:$symbol".toByteArray())
            val sourceCount = V7ModemContract.generationSourceCount(payload.size)
            return V7ModemContract.parsePacket(
                V7ModemContract.buildPacket(
                    sessionId = 0x11223344L,
                    generationId = generation.toLong(),
                    totalGenerations = 8L,
                    symbolId = symbol.toLong(),
                    sourceCount = sourceCount,
                    generationPayloadLen = sourceCount.toLong() * payload.size,
                    payload = payload,
                ),
            )
        }
        assertEquals(V7GenerationReceiver.Status.INNOVATIVE, receiver.offer(packet(0, 0)).status)
        assertEquals(V7GenerationReceiver.Status.INNOVATIVE, receiver.offer(packet(1, 0)).status)
        assertEquals(2, receiver.activeGenerationCount)
        assertEquals(V7GenerationReceiver.Status.WINDOW_FULL, receiver.offer(packet(2, 0)).status)
        assertEquals(2, receiver.activeGenerationCount)
        assertEquals(0, receiver.completedGenerationCount)
        assertEquals(0, receiver.completionBitmapBytes)
    }

    @Test
    fun completionBitmapIsCompactAndCompletedRepairIsIgnored() {
        val receiver = V7GenerationReceiver(maxActiveGenerations = 2)
        val source = Array(2) { index -> MessageDigest.getInstance("SHA-256").digest("source:$index".toByteArray()) }
        var completed: V7GenerationReceiver.Result? = null
        for (symbolId in 0 until 8) {
            val payload = V7DenseXor.encode(source, 0x55667788L, 0L, symbolId.toLong())
            val packet = V7ModemContract.parsePacket(
                V7ModemContract.buildPacket(0x55667788L, 0L, 1L, symbolId.toLong(), 2, 64L, payload),
            )
            val result = receiver.offer(packet)
            if (result.status == V7GenerationReceiver.Status.GENERATION_COMPLETE) {
                completed = result
                break
            }
        }
        assertTrue(completed != null)
        assertEquals(1, receiver.completedGenerationCount)
        assertTrue(receiver.completionBitmapBytes <= 8)
        val repair = V7DenseXor.encode(source, 0x55667788L, 0L, 20L)
        val duplicate = receiver.offer(
            V7ModemContract.parsePacket(V7ModemContract.buildPacket(0x55667788L, 0L, 1L, 20L, 2, 64L, repair)),
        )
        assertEquals(V7GenerationReceiver.Status.DUPLICATE, duplicate.status)
    }
}
