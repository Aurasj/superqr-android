package com.superqr.android.vision.v7.modem

import java.nio.ByteBuffer
import java.util.zip.CRC32

object V7ModemContract {
    const val PACKET_VERSION = 1
    const val OUTER_CODEC_DENSE_XOR = 1
    const val INNER_CODEC_RS255 = 1
    const val FLAG_SYSTEMATIC = 1
    const val PACKET_HEADER_SIZE = 32
    const val PACKET_OVERHEAD = 36
    const val MAX_SOURCE_SYMBOLS = 256
    const val DEFAULT_PARITY_RATIO = 0.15
    const val DEFAULT_GENERATION_TARGET_BYTES = 128 * 1024
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'Q'.code.toByte(), 'M'.code.toByte(), '7'.code.toByte())

    fun buildPacket(sessionId: Long,generationId: Long,totalGenerations: Long,symbolId: Long,sourceCount: Int,generationPayloadLen: Long,payload: ByteArray): ByteArray {
        require(sessionId in 1..0xFFFF_FFFFL); require(generationId >= 0 && generationId < totalGenerations && totalGenerations <= 0xFFFF_FFFFL); require(symbolId in 0..0xFFFF_FFFFL); require(sourceCount in 1..MAX_SOURCE_SYMBOLS); require(payload.size in 1..0xFFFF); require(generationPayloadLen in 1..sourceCount.toLong() * payload.size)
        val flags = if (symbolId < sourceCount) FLAG_SYSTEMATIC else 0
        val out = ByteArray(PACKET_HEADER_SIZE + payload.size + 4); val buffer = ByteBuffer.wrap(out)
        buffer.put(MAGIC); buffer.put(PACKET_VERSION.toByte()); buffer.put(flags.toByte()); buffer.put(OUTER_CODEC_DENSE_XOR.toByte()); buffer.put(INNER_CODEC_RS255.toByte()); buffer.putInt(sessionId.toInt()); buffer.putInt(generationId.toInt()); buffer.putInt(totalGenerations.toInt()); buffer.putInt(symbolId.toInt()); buffer.putShort(sourceCount.toShort()); buffer.putShort(payload.size.toShort()); buffer.putInt(generationPayloadLen.toInt()); buffer.put(payload)
        val crc = CRC32().apply { update(out, 0, PACKET_HEADER_SIZE + payload.size) }.value; buffer.putInt(crc.toInt()); return out
    }

    fun parsePacket(data: ByteArray): V7ModemPacket {
        if (data.size < PACKET_OVERHEAD) throw V7ModemException("truncated modem packet")
        for (index in MAGIC.indices) if (data[index] != MAGIC[index]) throw V7ModemException("invalid modem magic")
        val buffer = ByteBuffer.wrap(data); buffer.position(4)
        val version=buffer.get().toInt() and 0xFF; val flags=buffer.get().toInt() and 0xFF; val outer=buffer.get().toInt() and 0xFF; val inner=buffer.get().toInt() and 0xFF
        if (version != PACKET_VERSION) throw V7ModemException("unsupported modem packet version"); if (flags and FLAG_SYSTEMATIC.inv() != 0) throw V7ModemException("unknown modem flags"); if (outer != OUTER_CODEC_DENSE_XOR) throw V7ModemException("unsupported outer codec"); if (inner != INNER_CODEC_RS255) throw V7ModemException("unsupported inner FEC")
        val sessionId=buffer.int.toLong() and 0xFFFF_FFFFL; val generationId=buffer.int.toLong() and 0xFFFF_FFFFL; val totalGenerations=buffer.int.toLong() and 0xFFFF_FFFFL; val symbolId=buffer.int.toLong() and 0xFFFF_FFFFL; val sourceCount=buffer.short.toInt() and 0xFFFF; val symbolBytes=buffer.short.toInt() and 0xFFFF; val generationPayloadLen=buffer.int.toLong() and 0xFFFF_FFFFL
        if (sessionId == 0L) throw V7ModemException("session 0 is invalid"); if (totalGenerations < 1 || generationId >= totalGenerations) throw V7ModemException("invalid generation numbering"); if (sourceCount !in 1..MAX_SOURCE_SYMBOLS || symbolBytes < 1) throw V7ModemException("invalid source/symbol size"); if (generationPayloadLen !in 1..sourceCount.toLong() * symbolBytes) throw V7ModemException("invalid generation payload length")
        val packetSize=PACKET_HEADER_SIZE+symbolBytes+4; if (data.size < packetSize) throw V7ModemException("truncated modem symbol")
        val expected=ByteBuffer.wrap(data,PACKET_HEADER_SIZE+symbolBytes,4).int.toLong() and 0xFFFF_FFFFL; val actual=CRC32().apply { update(data,0,PACKET_HEADER_SIZE+symbolBytes) }.value and 0xFFFF_FFFFL; if (expected != actual) throw V7ModemException("modem packet CRC32 mismatch")
        val systematic=flags and FLAG_SYSTEMATIC != 0; if (systematic != (symbolId < sourceCount)) throw V7ModemException("systematic flag/id mismatch")
        return V7ModemPacket(sessionId,generationId,totalGenerations,symbolId,sourceCount,symbolBytes,generationPayloadLen,data.copyOfRange(PACKET_HEADER_SIZE,PACKET_HEADER_SIZE+symbolBytes),systematic)
    }
}

data class V7ModemPacket(val sessionId: Long,val generationId: Long,val totalGenerations: Long,val symbolId: Long,val sourceCount: Int,val symbolBytes: Int,val generationPayloadLen: Long,val payload: ByteArray,val systematic: Boolean)
class V7ModemException(message: String) : Exception(message)
