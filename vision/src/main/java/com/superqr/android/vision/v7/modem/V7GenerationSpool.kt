package com.superqr.android.vision.v7.modem

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.BitSet

/**
 * Disk-backed random-access generation sink. It lets generations complete out of
 * order without retaining a whole package in RAM. The fixed DENSE_XOR_V1
 * generation target makes every generation's byte offset deterministic from
 * (generationId, symbolBytes); the final generation may simply be shorter.
 */
class V7GenerationSpool(
    private val packageFile: File,
    val totalGenerations: Long,
    val symbolBytes: Int,
) : Closeable {
    private val file = RandomAccessFile(packageFile, "rw")
    private val written = BitSet()
    private var writtenCount = 0
    private var finalPackageLength: Long? = null

    init {
        require(totalGenerations in 1..MAX_TRACKED_GENERATIONS)
        require(symbolBytes > 0)
        file.setLength(0)
    }

    @Synchronized
    fun writeGeneration(generationId: Long, bytes: ByteArray): Boolean {
        if (generationId !in 0 until totalGenerations) throw V7ModemException("generation outside spool session")
        if (bytes.isEmpty() || bytes.size > V7ModemContract.generationCapacity(symbolBytes)) {
            throw V7ModemException("invalid completed generation length")
        }
        val index = generationId.toInt()
        if (written[index]) return false
        val isFinal = generationId == totalGenerations - 1
        if (!isFinal && bytes.size.toLong() != V7ModemContract.generationCapacity(symbolBytes)) {
            throw V7ModemException("non-final completed generation has wrong length")
        }
        val offset = V7ModemContract.generationStreamOffset(generationId, symbolBytes)
        file.seek(offset)
        file.write(bytes)
        written.set(index)
        writtenCount++
        if (isFinal) finalPackageLength = Math.addExact(offset, bytes.size.toLong())
        return true
    }

    @Synchronized
    fun isComplete(): Boolean = writtenCount.toLong() == totalGenerations && finalPackageLength != null

    @Synchronized
    fun packageLength(): Long {
        if (!isComplete()) throw V7ModemException("package spool is incomplete")
        return finalPackageLength!!
    }

    /**
     * Decode and verify the assembled S7PK stream into a caller-owned output.
     * Production UI should pass a temporary file output and atomically expose it
     * only after this call succeeds.
     */
    @Synchronized
    fun verifyAndDecodeTo(output: OutputStream): V7PackageStream.Metadata {
        val length = packageLength()
        file.fd.sync()
        file.setLength(length)
        FileInputStream(packageFile).use { input ->
            return V7PackageStream.decodeTo(input, output)
        }
    }

    @Synchronized
    override fun close() {
        file.close()
    }

    val completedGenerations: Int get() = writtenCount
    val completionBitmapBytes: Int get() = written.toLongArray().size * Long.SIZE_BYTES

    private companion object {
        const val MAX_TRACKED_GENERATIONS = 16_000_000L
    }
}
