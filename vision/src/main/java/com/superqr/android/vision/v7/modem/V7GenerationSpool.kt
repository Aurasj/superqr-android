package com.superqr.android.vision.v7.modem

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.BitSet

/**
 * Disk-backed random-access generation sink. It lets generations complete out of
 * order without retaining a whole package in RAM. The caller must provide
 * explicit package/output limits so a CRC-valid but hostile optical session
 * cannot create an enormous sparse file or decompression output.
 */
class V7GenerationSpool(
    private val packageFile: File,
    val totalGenerations: Long,
    val symbolBytes: Int,
    private val maxPackageBytes: Long,
    private val maxOriginalBytes: Long,
) : Closeable {
    private val file = RandomAccessFile(packageFile, "rw")
    private val written = BitSet()
    private var writtenCount = 0
    private var finalPackageLength: Long? = null
    private val generationCapacity = V7ModemContract.generationCapacity(symbolBytes)

    init {
        require(totalGenerations in 1L..MAX_TRACKED_GENERATIONS)
        require(symbolBytes > 0)
        require(maxPackageBytes > 0)
        require(maxOriginalBytes >= 0)
        val minimumPackageBytes = Math.addExact(
            Math.multiplyExact(totalGenerations - 1L, generationCapacity),
            1L,
        )
        if (minimumPackageBytes > maxPackageBytes) {
            file.close()
            throw V7ModemException("declared generation count exceeds package storage limit")
        }
        file.setLength(0)
    }

    @Synchronized
    fun writeGeneration(generationId: Long, bytes: ByteArray): Boolean {
        if (generationId !in 0L until totalGenerations) throw V7ModemException("generation outside spool session")
        if (bytes.isEmpty() || bytes.size.toLong() > generationCapacity) {
            throw V7ModemException("invalid completed generation length")
        }
        val index = generationId.toInt()
        if (written[index]) return false
        val isFinal = generationId == totalGenerations - 1L
        if (!isFinal && bytes.size.toLong() != generationCapacity) {
            throw V7ModemException("non-final completed generation has wrong length")
        }
        val offset = V7ModemContract.generationStreamOffset(generationId, symbolBytes)
        val end = Math.addExact(offset, bytes.size.toLong())
        if (end > maxPackageBytes) throw V7ModemException("completed generation exceeds package storage limit")
        file.seek(offset)
        file.write(bytes)
        written.set(index)
        writtenCount++
        if (isFinal) finalPackageLength = end
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
     * Decode and verify the assembled S7PK stream into a caller-owned temporary
     * output. Only expose/rename that output after this call succeeds.
     */
    @Synchronized
    fun verifyAndDecodeTo(output: OutputStream): V7PackageStream.Metadata {
        val length = packageLength()
        file.fd.sync()
        file.setLength(length)
        FileInputStream(packageFile).use { input ->
            return V7PackageStream.decodeTo(input, output, maxOriginalBytes)
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
