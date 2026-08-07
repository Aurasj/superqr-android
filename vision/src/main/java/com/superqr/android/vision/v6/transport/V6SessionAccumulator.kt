package com.superqr.android.vision.v6.transport

import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlin.text.Charsets

data class V6TransferPackage(
    val filename: String,
    val fileSize: Int,
    val fileData: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as V6TransferPackage

        if (filename != other.filename) return false
        if (fileSize != other.fileSize) return false
        if (!fileData.contentEquals(other.fileData)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = filename.hashCode()
        result = 31 * result + fileSize
        result = 31 * result + fileData.contentHashCode()
        return result
    }
}

class V6SessionAccumulator {
    private var currentSessionId: Int = -1
    private var totalFrames: Int = -1
    private var packageLength: Int = -1
    private var duplicateCount: Int = 0
    private var conflictCount: Int = 0
    private val frames = mutableMapOf<Int, ByteArray>()

    /**
     * Adds a validated transport frame.
     * Returns a completed V6TransferPackage if the session completes, otherwise null.
     */
    fun addFrame(frame: V6TransportFrame): V6TransferPackage? {
        if (currentSessionId != -1) {
            // While a session is actively incomplete, frames from a different session ID must NOT reset or mix into it.
            if (frame.sessionId != currentSessionId) {
                return null
            }
            // Reject if totalFrames is inconsistent with active session
            if (frame.totalFrames != totalFrames) {
                return null
            }
        } else {
            // Initialize new session
            currentSessionId = frame.sessionId
            totalFrames = frame.totalFrames
        }

        // Handle duplicates / conflicts
        if (frames.containsKey(frame.frameId)) {
            val existingPayload = frames[frame.frameId]!!
            if (frame.payload.contentEquals(existingPayload)) {
                // Identical duplicate: do not increase unique progress, increment duplicateCount
                duplicateCount++
            } else {
                // Conflict: do not overwrite accepted frame, increment conflictCount
                conflictCount++
            }
            return null
        }

        // New unique frame accepted
        frames[frame.frameId] = frame.payload

        if (frame.frameId == 0) {
            val buf = ByteBuffer.wrap(frame.payload)
            packageLength = buf.int
        }

        // Check for completion
        if (frames.size == totalFrames && packageLength != -1) {
            try {
                val pkg = buildAndParsePackage()
                reset() // Ready for next session after completion
                return pkg
            } catch (e: V6TransportError) {
                // If package verification fails, reset to discard the broken session
                reset()
                throw e
            }
        }

        return null
    }

    private fun buildAndParsePackage(): V6TransferPackage {
        // Build the full raw package data
        val rawPackage = ByteArray((totalFrames - 1) * V6Transport.PAYLOAD_SIZE)
        var offset = 0
        for (i in 1 until totalFrames) {
            val payload = frames[i] ?: throw V6TransportError("Missing frame $i")
            System.arraycopy(payload, 0, rawPackage, offset, V6Transport.PAYLOAD_SIZE)
            offset += V6Transport.PAYLOAD_SIZE
        }

        // Trim padding
        if (packageLength > rawPackage.size || packageLength < 9) {
            throw V6TransportError("Invalid package length or truncated")
        }
        val trimmed = rawPackage.copyOfRange(0, packageLength)

        return parseTransferPackage(trimmed)
    }

    private fun parseTransferPackage(packageData: ByteArray): V6TransferPackage {
        if (packageData.size < 9) throw V6TransportError("Truncated package: missing header")

        val buf = ByteBuffer.wrap(packageData)
        val fnLen = buf.get().toInt() and 0xFF
        if (fnLen < 1) throw V6TransportError("Invalid filename length: 0")

        val fileSize = buf.int
        val expectedCrc = buf.int

        if (packageData.size < 9 + fnLen) throw V6TransportError("Truncated package: missing filename bytes")
        val filenameBytes = ByteArray(fnLen)
        buf.get(filenameBytes)

        val filename = String(filenameBytes, Charsets.UTF_8)

        val expectedTotalLen = 9 + fnLen + fileSize
        if (packageData.size < expectedTotalLen) throw V6TransportError("Truncated package: missing file bytes")
        if (packageData.size > expectedTotalLen) throw V6TransportError("Trailing data after declared file bytes")

        val fileData = ByteArray(fileSize)
        buf.get(fileData)

        val crc32 = CRC32()
        crc32.update(fileData)
        val actualCrc = (crc32.value and 0xFFFFFFFFL).toInt()

        if (actualCrc != expectedCrc) {
            throw V6TransportError("File CRC32 mismatch")
        }

        return V6TransferPackage(filename, fileSize, fileData)
    }

    fun getMissingFrames(): List<Int> {
        if (totalFrames == -1) return emptyList()
        val missing = mutableListOf<Int>()
        for (i in 0 until totalFrames) {
            if (!frames.containsKey(i)) {
                missing.add(i)
            }
        }
        return missing
    }

    fun getMissingFramesCount(): Int = getMissingFrames().size

    fun reset() {
        currentSessionId = -1
        totalFrames = -1
        packageLength = -1
        duplicateCount = 0
        conflictCount = 0
        frames.clear()
    }

    fun getCurrentSessionId() = currentSessionId
    fun getFramesCollected() = frames.size
    fun getUniqueFrames() = frames.size
    fun getDuplicateCount() = duplicateCount
    fun getConflictCount() = conflictCount
    fun getTotalFrames() = totalFrames
    fun getFramePayload(frameId: Int): ByteArray? = frames[frameId]
}
