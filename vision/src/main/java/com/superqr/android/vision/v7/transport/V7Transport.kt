package com.superqr.android.vision.v7.transport

import java.nio.ByteBuffer
import java.util.zip.CRC32

/** Profile-aware SuperQR V7 transport. */
object V7Transport {
    const val VERSION = 7
    const val HEADER_SIZE = 16
    const val CRC_SIZE = 4
    const val PACKAGE_HEADER_SIZE = 20
    const val MAX_FILENAME_BYTES = 1024
    const val MAX_MIME_BYTES = 255

    private val baseline = V7OpticalProfiles.all.first()
    val GRID_SIZE: Int get() = baseline.grid
    val CELL_COUNT: Int get() = baseline.cellCount
    val FRAME_SIZE: Int get() = baseline.frameSize
    val PAYLOAD_SIZE: Int get() = baseline.payloadSize

    private val FRAME_MAGIC = byteArrayOf('S'.code.toByte(), 'Q'.code.toByte())
    private val PACKAGE_MAGIC = byteArrayOf('S'.code.toByte(), 'Q'.code.toByte(), 'P'.code.toByte(), '7'.code.toByte())

    fun symbolsToBytes(symbols: ByteArray, profile: V7OpticalProfile): ByteArray? {
        if (symbols.size != profile.cellCount) return null
        val out = ByteArray(profile.frameSize)
        var bitPos = 0
        val maxSymbol = (1 shl profile.bitsPerCell) - 1
        for (symbolByte in symbols) {
            val symbol = symbolByte.toInt()
            if (symbol !in 0..maxSymbol) return null
            for (shiftInSymbol in profile.bitsPerCell - 1 downTo 0) {
                if (((symbol shr shiftInSymbol) and 1) != 0) {
                    val byteIdx = bitPos shr 3
                    val shift = 7 - (bitPos and 7)
                    out[byteIdx] = (out[byteIdx].toInt() or (1 shl shift)).toByte()
                }
                bitPos++
            }
        }
        return out
    }

    fun parseFrame(bytes: ByteArray, expectedProfile: V7OpticalProfile? = null): V7TransportFrame {
        if (bytes.size < HEADER_SIZE + CRC_SIZE) throw V7TransportError("truncated V7 frame")
        if (bytes[0] != FRAME_MAGIC[0] || bytes[1] != FRAME_MAGIC[1]) throw V7TransportError("invalid V7 frame magic")
        if ((bytes[2].toInt() and 0xFF) != VERSION) throw V7TransportError("invalid V7 version")
        val profileId = bytes[3].toInt() and 0xFF
        val profile = V7OpticalProfiles.byId(profileId) ?: throw V7TransportError("unknown V7 profile id $profileId")
        if (expectedProfile != null && expectedProfile.id != profileId) throw V7TransportError("optical profile does not match frame header")
        if (bytes.size != profile.frameSize) throw V7TransportError("frame size does not match ${profile.key}")

        val expectedCrc = ByteBuffer.wrap(bytes, bytes.size - 4, 4).int.toLong() and 0xFFFFFFFFL
        val crc = CRC32().apply { update(bytes, 0, bytes.size - 4) }.value and 0xFFFFFFFFL
        if (crc != expectedCrc) throw V7TransportError("frame CRC32 mismatch")

        val buf = ByteBuffer.wrap(bytes)
        buf.position(4)
        val sessionId = buf.short.toInt() and 0xFFFF
        val frameIdLong = buf.int.toLong() and 0xFFFFFFFFL
        val totalLong = buf.int.toLong() and 0xFFFFFFFFL
        val payloadLen = buf.short.toInt() and 0xFFFF
        if (sessionId == 0) throw V7TransportError("session_id 0 is invalid")
        if (totalLong < 1 || totalLong > Int.MAX_VALUE) throw V7TransportError("unsupported total frame count")
        if (frameIdLong >= totalLong || frameIdLong > Int.MAX_VALUE) throw V7TransportError("invalid frame numbering")
        if (payloadLen > profile.payloadSize) throw V7TransportError("invalid payload length")

        return V7TransportFrame(sessionId, frameIdLong.toInt(), totalLong.toInt(), bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + payloadLen), profileId)
    }

    fun parsePackage(bytes: ByteArray): V7TransferPackage {
        if (bytes.size < PACKAGE_HEADER_SIZE) throw V7TransportError("truncated V7 package")
        for (i in PACKAGE_MAGIC.indices) if (bytes[i] != PACKAGE_MAGIC[i]) throw V7TransportError("invalid V7 package magic")
        val buf = ByteBuffer.wrap(bytes)
        buf.position(4)
        val filenameLen = buf.short.toInt() and 0xFFFF
        val mimeLen = buf.short.toInt() and 0xFFFF
        val fileSizeLong = buf.long
        val expectedCrc = buf.int.toLong() and 0xFFFFFFFFL
        if (filenameLen !in 1..MAX_FILENAME_BYTES) throw V7TransportError("invalid filename length")
        if (mimeLen !in 0..MAX_MIME_BYTES) throw V7TransportError("invalid mime length")
        if (fileSizeLong < 0 || fileSizeLong > Int.MAX_VALUE) throw V7TransportError("file too large for current Android receiver")
        val metaEnd = PACKAGE_HEADER_SIZE + filenameLen + mimeLen
        val expectedTotal = metaEnd.toLong() + fileSizeLong
        if (expectedTotal != bytes.size.toLong()) throw V7TransportError("package length mismatch")
        val filename = String(bytes, PACKAGE_HEADER_SIZE, filenameLen, Charsets.UTF_8)
        val mime = String(bytes, PACKAGE_HEADER_SIZE + filenameLen, mimeLen, Charsets.UTF_8)
        val fileData = bytes.copyOfRange(metaEnd, bytes.size)
        val actualCrc = CRC32().apply { update(fileData) }.value and 0xFFFFFFFFL
        if (actualCrc != expectedCrc) throw V7TransportError("file CRC32 mismatch")
        return V7TransferPackage(filename, if (mime.isBlank()) "application/octet-stream" else mime, fileData.size, expectedCrc, fileData)
    }
}

data class V7TransportFrame(
    val sessionId: Int,
    val frameId: Int,
    val totalFrames: Int,
    val payload: ByteArray,
    val profileId: Int = 0,
)

data class V7TransferPackage(
    val filename: String,
    val mimeType: String,
    val fileSize: Int,
    val fileCrc32: Long,
    val fileData: ByteArray,
)

class V7TransportError(message: String) : Exception(message)
