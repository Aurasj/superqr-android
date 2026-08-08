package com.superqr.android.vision.v7.transport

/**
 * Out-of-order V7 frame accumulator.
 *
 * The sender continuously loops the finite frame set. Unique CRC-valid frames
 * are retained until the package is complete. Identical duplicates are ignored;
 * conflicting duplicates are rejected without overwriting accepted data.
 */
class V7SessionAccumulator {
    private var currentSessionId = -1
    private var totalFrames = -1
    private val frames = HashMap<Int, ByteArray>()
    private var duplicateCount = 0
    private var conflictCount = 0

    fun addFrame(frame: V7TransportFrame): V7TransferPackage? {
        if (currentSessionId == -1) {
            currentSessionId = frame.sessionId
            totalFrames = frame.totalFrames
        } else {
            if (frame.sessionId != currentSessionId) return null
            if (frame.totalFrames != totalFrames) return null
        }

        val existing = frames[frame.frameId]
        if (existing != null) {
            if (existing.contentEquals(frame.payload)) duplicateCount++ else conflictCount++
            return null
        }

        frames[frame.frameId] = frame.payload
        if (frames.size != totalFrames) return null

        val packageSize = (0 until totalFrames).sumOf { id ->
            frames[id]?.size ?: throw V7TransportError("missing frame $id")
        }
        val packageBytes = ByteArray(packageSize)
        var offset = 0
        for (id in 0 until totalFrames) {
            val payload = frames[id] ?: throw V7TransportError("missing frame $id")
            System.arraycopy(payload, 0, packageBytes, offset, payload.size)
            offset += payload.size
        }

        return try {
            V7Transport.parsePackage(packageBytes).also { reset() }
        } catch (e: V7TransportError) {
            reset()
            throw e
        }
    }

    fun reset() {
        currentSessionId = -1
        totalFrames = -1
        duplicateCount = 0
        conflictCount = 0
        frames.clear()
    }

    fun getCurrentSessionId(): Int = currentSessionId
    fun getTotalFrames(): Int = totalFrames
    fun getUniqueFrames(): Int = frames.size
    fun getDuplicateCount(): Int = duplicateCount
    fun getConflictCount(): Int = conflictCount
    fun getMissingFramesCount(): Int = if (totalFrames < 0) 0 else totalFrames - frames.size
    fun getProgress(): Double = if (totalFrames <= 0) 0.0 else frames.size.toDouble() / totalFrames.toDouble()
}
