package com.superqr.android.transfer

import com.superqr.android.vision.v7.transport.V7Transport
import com.superqr.android.vision.v7.transport.V7TransportError
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.CRC32

/** Metadata embedded at the beginning of the SQP7 package stream. */
data class IncomingPackageMetadata(
    val filename: String,
    val mimeType: String,
    val fileSize: Long,
    val fileCrc32: Long,
    val dataOffset: Long,
    val packageBytes: Long,
)

data class TransferAccumulatorSnapshot(
    val accepted: Boolean,
    val sessionId: Int,
    val uniqueFrames: Int,
    val totalFrames: Int,
    val duplicateFrames: Int,
    val receivedPayloadBytes: Long,
    val metadata: IncomingPackageMetadata?,
    val complete: Boolean,
    val profileId: Int,
    val profileLabel: String,
)

data class TransferCompletion(
    val sessionId: Int,
    val metadata: IncomingPackageMetadata,
    val tempFile: File,
)

/**
 * Disk-backed out-of-order accumulator for production V40 QR frames.
 *
 * Only a BooleanArray + payload lengths live in RAM. File/photo/audio/video bytes
 * are written directly into a sparse temporary package file, so large transfers
 * do not require loading the whole payload into memory.
 */
class QrTransferAccumulator(private val cacheDir: File) {
    companion object {
        private val PACKAGE_MAGIC = byteArrayOf('S'.code.toByte(), 'Q'.code.toByte(), 'P'.code.toByte(), '7'.code.toByte())
        private const val MAX_TOTAL_FRAMES = 2_000_000
    }

    private var sessionId = -1
    private var totalFrames = 0
    private var activeProfile: ProductionQrProfile? = null
    private var seen = BooleanArray(0)
    private var payloadLengths = IntArray(0)
    private var uniqueFrames = 0
    private var duplicates = 0
    private var receivedPayloadBytes = 0L
    private var metadata: IncomingPackageMetadata? = null
    private var tempFile: File? = null
    private var raf: RandomAccessFile? = null

    @Synchronized
    fun accept(raw: ByteArray): TransferAccumulatorSnapshot {
        val profile = ProductionQrContract.profileForFrame(raw)
            ?: throw V7TransportError("not a supported production V40 frame")
        val frame = V7Transport.parseQrFrame(raw)
        if (frame.profileId != profile.id) throw V7TransportError("production QR profile mismatch")
        if (frame.totalFrames !in 1..MAX_TOTAL_FRAMES) throw V7TransportError("unsupported total frame count")
        if (frame.payload.size > profile.payloadBytes) throw V7TransportError("payload exceeds ${profile.label} capacity")

        if (sessionId != frame.sessionId) {
            startSession(frame.sessionId, frame.totalFrames, profile)
        } else if (activeProfile?.id != profile.id) {
            throw V7TransportError("QR profile changed inside session")
        }
        if (frame.totalFrames != totalFrames) throw V7TransportError("frame count changed inside session")

        if (seen[frame.frameId]) {
            duplicates++
            return snapshot(accepted = false)
        }

        val parsedMetadata = if (frame.frameId == 0) parseMetadata(frame.payload, frame.totalFrames, profile.payloadBytes) else null
        if (parsedMetadata != null) metadata = parsedMetadata

        val file = raf ?: throw IllegalStateException("temporary package file is closed")
        file.seek(frame.frameId.toLong() * profile.payloadBytes)
        file.write(frame.payload)
        seen[frame.frameId] = true
        payloadLengths[frame.frameId] = frame.payload.size
        uniqueFrames++
        receivedPayloadBytes += frame.payload.size.toLong()

        val complete = uniqueFrames == totalFrames
        if (complete) validateCompletePackage()
        return snapshot(accepted = true)
    }

    @Synchronized
    fun prepareCompletion(): TransferCompletion {
        if (uniqueFrames != totalFrames || totalFrames == 0) throw IllegalStateException("transfer is not complete")
        val meta = metadata ?: throw V7TransportError("package metadata was not received")
        validateCompletePackage()
        raf?.fd?.sync()
        raf?.close()
        raf = null
        return TransferCompletion(sessionId, meta, tempFile ?: throw IllegalStateException("missing package file"))
    }

    fun verifyFileCrc(completion: TransferCompletion): String {
        val crc = CRC32()
        val sha256 = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(completion.tempFile, "r").use { source ->
            source.seek(completion.metadata.dataOffset)
            var remaining = completion.metadata.fileSize
            val buffer = ByteArray(1024 * 1024)
            while (remaining > 0) {
                val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count <= 0) throw V7TransportError("temporary package ended early")
                crc.update(buffer, 0, count)
                sha256.update(buffer, 0, count)
                remaining -= count
            }
        }
        if ((crc.value and 0xFFFFFFFFL) != completion.metadata.fileCrc32) {
            throw V7TransportError("file CRC32 mismatch")
        }
        return sha256.digest().joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun reset() {
        closeAndDelete()
        sessionId = -1
        totalFrames = 0
        activeProfile = null
        seen = BooleanArray(0)
        payloadLengths = IntArray(0)
        uniqueFrames = 0
        duplicates = 0
        receivedPayloadBytes = 0L
        metadata = null
    }

    @Synchronized
    fun discardTemporaryFile() {
        raf?.close()
        raf = null
        tempFile?.delete()
        tempFile = null
    }

    @Synchronized
    fun detachTemporaryFile(): File {
        raf?.close()
        raf = null
        val file = tempFile ?: throw IllegalStateException("missing temporary package file")
        tempFile = null
        return file
    }

    private fun startSession(newSessionId: Int, newTotalFrames: Int, profile: ProductionQrProfile) {
        reset()
        sessionId = newSessionId
        totalFrames = newTotalFrames
        activeProfile = profile
        seen = BooleanArray(newTotalFrames)
        payloadLengths = IntArray(newTotalFrames)
        val dir = File(cacheDir, "superqr-transfer").apply { mkdirs() }
        tempFile = File(dir, "session_${newSessionId}.part")
        raf = RandomAccessFile(tempFile, "rw")
    }

    private fun parseMetadata(payload: ByteArray, declaredFrames: Int, payloadBytes: Int): IncomingPackageMetadata {
        if (payload.size < V7Transport.PACKAGE_HEADER_SIZE) throw V7TransportError("truncated SQP7 metadata")
        for (i in PACKAGE_MAGIC.indices) if (payload[i] != PACKAGE_MAGIC[i]) throw V7TransportError("invalid SQP7 package magic")
        val buf = ByteBuffer.wrap(payload)
        buf.position(4)
        val filenameLen = buf.short.toInt() and 0xFFFF
        val mimeLen = buf.short.toInt() and 0xFFFF
        val fileSize = buf.long
        val fileCrc32 = buf.int.toLong() and 0xFFFFFFFFL
        if (filenameLen !in 1..V7Transport.MAX_FILENAME_BYTES) throw V7TransportError("invalid filename length")
        if (mimeLen !in 0..V7Transport.MAX_MIME_BYTES) throw V7TransportError("invalid MIME length")
        if (fileSize < 0) throw V7TransportError("negative file size")
        val dataOffset = V7Transport.PACKAGE_HEADER_SIZE.toLong() + filenameLen + mimeLen
        if (dataOffset > payload.size) throw V7TransportError("metadata does not fit first V40 frame")
        if (fileSize > Long.MAX_VALUE - dataOffset) throw V7TransportError("declared file is too large")
        val packageBytes = dataOffset + fileSize
        val expectedFrames = ((packageBytes + payloadBytes - 1) / payloadBytes).coerceAtLeast(1)
        if (expectedFrames != declaredFrames.toLong()) throw V7TransportError("package size does not match frame count")
        val filename = sanitizeReceivedFilename(
            String(payload, V7Transport.PACKAGE_HEADER_SIZE, filenameLen, Charsets.UTF_8)
        )
        val mime = String(payload, V7Transport.PACKAGE_HEADER_SIZE + filenameLen, mimeLen, Charsets.UTF_8)
        return IncomingPackageMetadata(
            filename = filename,
            mimeType = mime.ifBlank { "application/octet-stream" },
            fileSize = fileSize,
            fileCrc32 = fileCrc32,
            dataOffset = dataOffset,
            packageBytes = packageBytes,
        )
    }

    private fun validateCompletePackage() {
        val meta = metadata ?: throw V7TransportError("missing first frame / package metadata")
        val profile = activeProfile ?: throw V7TransportError("missing production QR profile")
        val lastLength = payloadLengths.last()
        if (lastLength <= 0) throw V7TransportError("missing final payload length")
        val actualPackageBytes = (totalFrames - 1).toLong() * profile.payloadBytes + lastLength
        if (actualPackageBytes != meta.packageBytes) throw V7TransportError("assembled package length mismatch")
    }

    private fun snapshot(accepted: Boolean): TransferAccumulatorSnapshot {
        val profile = activeProfile
        return TransferAccumulatorSnapshot(
            accepted = accepted,
            sessionId = sessionId,
            uniqueFrames = uniqueFrames,
            totalFrames = totalFrames,
            duplicateFrames = duplicates,
            receivedPayloadBytes = receivedPayloadBytes,
            metadata = metadata,
            complete = totalFrames > 0 && uniqueFrames == totalFrames,
            profileId = profile?.id ?: -1,
            profileLabel = profile?.label.orEmpty(),
        )
    }

    private fun closeAndDelete() {
        try { raf?.close() } catch (_: Throwable) {}
        raf = null
        tempFile?.delete()
        tempFile = null
    }
}
