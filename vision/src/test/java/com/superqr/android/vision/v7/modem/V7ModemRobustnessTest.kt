package com.superqr.android.vision.v7.modem

import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class V7ModemRobustnessTest {
    @Test
    fun shortenedRsRandomizedErrorErasureBudgetMatchesReferenceSemantics() {
        val random = Random(0x53515237L)
        for (parity in intArrayOf(4, 8, 16, 32)) {
            for (dataLength in intArrayOf(10, 50, 100, 180)) {
                if (dataLength + parity > 255) continue
                repeat(16) {
                    val source = ByteArray(dataLength).also(random::nextBytes)
                    val corrupted = V7ReedSolomon.encode(source, parity)
                    val erasureCount = random.nextInt(parity + 1)
                    val errorCount = random.nextInt((parity - erasureCount) / 2 + 1)
                    val positions = uniquePositions(random, corrupted.size, erasureCount + errorCount)
                    val erasures = positions.copyOfRange(0, erasureCount)
                    for (position in positions) {
                        var delta = random.nextInt(256)
                        if (delta == 0) delta = 1
                        corrupted[position] = (corrupted[position].toInt() xor delta).toByte()
                    }
                    assertArrayEquals(source, V7ReedSolomon.decode(corrupted, parity, erasures))
                }
            }
        }
    }

    @Test
    fun interleaverRecoversShortContiguousPhysicalBurstsAcrossChannelSizes() {
        val random = Random(0xB17B17L)
        for (channelBytes in intArrayOf(400, 1600, 2933)) {
            val symbolBytes = V7InnerFec.symbolPayloadCapacity(channelBytes, .15)
            val sources = Array(8) { ByteArray(symbolBytes).also(random::nextBytes) }
            val payload = V7DenseXor.encode(sources, 0x1234ABCDL, 0L, 9L)
            val packet = V7ModemContract.buildPacket(
                sessionId = 0x1234ABCDL,
                generationId = 0L,
                totalGenerations = 1L,
                symbolId = 9L,
                sourceCount = 8,
                generationPayloadLen = 8L * symbolBytes,
                payload = payload,
            )
            val physical = V7InnerFec.encode(packet, channelBytes, .15)
            val start = channelBytes / 3
            repeat(12) { offset ->
                val index = (start + offset) % channelBytes
                physical[index] = (physical[index].toInt() xor (offset + 1)).toByte()
            }
            assertArrayEquals(payload, V7InnerFec.decodePacket(physical, .15).payload)
        }
    }

    private fun uniquePositions(random: Random, limit: Int, count: Int): IntArray {
        val used = BooleanArray(limit)
        val result = IntArray(count)
        var cursor = 0
        while (cursor < count) {
            val candidate = random.nextInt(limit)
            if (!used[candidate]) {
                used[candidate] = true
                result[cursor++] = candidate
            }
        }
        return result
    }
}
